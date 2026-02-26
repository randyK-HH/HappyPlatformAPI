package com.happyhealth.bleplatform.internal.connection

import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.bleplatform.api.HpyConnectionState
import com.happyhealth.bleplatform.api.HpyErrorCode
import com.happyhealth.bleplatform.api.HpyEvent
import com.happyhealth.bleplatform.internal.command.CommandBuilder
import com.happyhealth.bleplatform.internal.command.CommandId
import com.happyhealth.bleplatform.internal.command.ResponseParser
import com.happyhealth.bleplatform.internal.command.toHex
import com.happyhealth.bleplatform.internal.download.DownloadAction
import com.happyhealth.bleplatform.internal.download.DownloadController
import com.happyhealth.bleplatform.internal.memfault.MemfaultBuffer
import com.happyhealth.bleplatform.internal.model.DeviceInfoData
import com.happyhealth.bleplatform.internal.model.DeviceStatusData
import com.happyhealth.bleplatform.internal.model.FirmwareTier
import com.happyhealth.bleplatform.internal.model.HpyCharId
import com.happyhealth.bleplatform.internal.shim.PlatformBleShim
import com.happyhealth.bleplatform.internal.shim.PlatformTimeSource
import com.happyhealth.bleplatform.internal.shim.WriteType
import com.happyhealth.bleplatform.internal.util.CRC_INIT
import com.happyhealth.bleplatform.internal.util.finalizeCrc
import com.happyhealth.bleplatform.internal.util.updateCrc
import kotlinx.coroutines.CoroutineScope

class ConnectionSlot(
    val connId: ConnectionId,
    private val shim: PlatformBleShim,
    private val timeSource: PlatformTimeSource,
    private val config: ConnectionConfig,
    private val scope: CoroutineScope,
    private val emitEvent: (HpyEvent) -> Unit,
) {
    var state: HpyConnectionState = HpyConnectionState.IDLE
        private set

    var deviceInfo: DeviceInfoData = DeviceInfoData()
        private set
    var lastStatus: DeviceStatusData? = null
        private set
    var firmwareTier: FirmwareTier = FirmwareTier.TIER_0
        private set

    private var deviceHandle: Any? = null
    private var availableChars: Set<HpyCharId> = emptySet()
    private var handshakeRunner: HandshakeRunner? = null
    private var downloadController: DownloadController? = null
    private var downloadEnabled: Boolean = false
    private var downloadPendingStatusPoll: Boolean = false

    // Persists across reconnections — NOT cleared on disconnect
    private val memfaultBuffer = MemfaultBuffer()

    // Transient per-drain accumulator for memfault STREAM_TX data
    private var memfaultAccumulator: ByteArray? = null
    private var memfaultAccumulatorPos: Int = 0

    // Serialized GATT operation queues (Android allows only one outstanding op at a time)
    private val pendingNotifSubscriptions = mutableListOf<HpyCharId>()
    private val pendingDisReads = mutableListOf<HpyCharId>()

    // Track which DIS chars we've read
    private var disSerialNumber: String = ""
    private var disFwVersion: String = ""
    private var disSwVersion: String = ""
    private var disManufacturerName: String = ""
    private var disModelNumber: String = ""

    private val commandQueue = CommandQueue(
        onSend = { cmd -> sendCommand(cmd) },
        onTimeout = { cmd ->
            log("Command timeout: ${cmd.tag}")
            emitEvent(HpyEvent.Error(connId, HpyErrorCode.COMMAND_TIMEOUT, "Command timeout: ${cmd.tag}"))
        },
    )

    // ---- Public actions ----

    fun connect(handle: Any) {
        if (state != HpyConnectionState.IDLE && state != HpyConnectionState.DISCONNECTED) return
        deviceHandle = handle
        transition(HpyConnectionState.CONNECTING)
        shim.connect(connId, handle)
    }

    fun disconnect() {
        commandQueue.flush()
        handshakeRunner = null
        if (downloadController != null) {
            shim.l2capClose(connId)
            downloadController = null
        }
        downloadEnabled = false
        downloadPendingStatusPoll = false
        memfaultAccumulator = null
        memfaultAccumulatorPos = 0
        shim.disconnect(connId)
        transition(HpyConnectionState.DISCONNECTED)
    }

    fun enqueueCommand(command: QueuedCommand): Boolean {
        return commandQueue.enqueue(command)
    }

    fun startDownload() {
        if (state != HpyConnectionState.READY) return
        downloadEnabled = true
        downloadPendingStatusPoll = true
        commandQueue.enqueue(QueuedCommand(
            tag = "DL_GET_DEV_STATUS",
            charId = HpyCharId.CMD_RX,
            data = CommandBuilder.buildGetDeviceStatus(),
            timeoutMs = config.commandTimeoutMs,
            completionType = CompletionType.ON_NOTIFICATION,
        ))
    }

    fun stopDownload() {
        downloadEnabled = false
        downloadPendingStatusPoll = false
        if (state == HpyConnectionState.DOWNLOADING) {
            downloadController?.let { shim.l2capClose(connId) }
            downloadController = null
            commandQueue.flush()
            log("Download stopped by user (was DOWNLOADING)")
            transition(HpyConnectionState.READY)
        } else if (state == HpyConnectionState.WAITING) {
            log("Download stopped by user (was WAITING)")
            transition(HpyConnectionState.READY)
        }
    }

    // ---- Shim callbacks ----

    fun onConnected() {
        log("Connected")
        shim.requestMtu(connId, config.requestedMtu)
    }

    fun onMtuChanged(mtu: Int) {
        log("MTU negotiated: $mtu")
        shim.discoverServices(connId)
    }

    fun onDisconnected(status: Int) {
        log("Disconnected (status=$status)")
        commandQueue.flush()
        handshakeRunner = null
        if (downloadController != null) {
            shim.l2capClose(connId)
            downloadController = null
        }
        downloadEnabled = false
        downloadPendingStatusPoll = false
        memfaultAccumulator = null
        memfaultAccumulatorPos = 0
        if (state != HpyConnectionState.DISCONNECTED) {
            // Unexpected disconnect
            transition(HpyConnectionState.DISCONNECTED)
        }
    }

    fun onServicesDiscovered(chars: Set<HpyCharId>) {
        availableChars = chars
        log("Services discovered: ${chars.size} characteristics")

        // Validate required services
        if (HpyCharId.CMD_RX !in chars || HpyCharId.CMD_TX !in chars) {
            log("ERROR: Required HCS characteristics missing")
            emitEvent(HpyEvent.Error(connId, HpyErrorCode.CONNECT_FAIL, "HCS service missing"))
            shim.disconnect(connId)
            transition(HpyConnectionState.DISCONNECTED)
            return
        }

        // Subscribe to notifications
        subscribeNotifications()
    }

    fun onDescriptorWritten(charId: HpyCharId, status: Int) {
        // Process next pending notification subscription, or move on to DIS reads
        if (pendingNotifSubscriptions.isNotEmpty()) {
            val next = pendingNotifSubscriptions.removeFirst()
            shim.subscribeNotifications(connId, next, true)
        } else {
            startDisReads()
        }
    }

    fun onCharacteristicRead(charId: HpyCharId, value: ByteArray) {
        val str = value.decodeToString().trimEnd('\u0000')
        when (charId) {
            HpyCharId.DIS_SERIAL_NUMBER -> disSerialNumber = str
            HpyCharId.DIS_FW_VERSION -> disFwVersion = str
            HpyCharId.DIS_SW_VERSION -> disSwVersion = str
            HpyCharId.DIS_MANUFACTURER_NAME -> disManufacturerName = str
            HpyCharId.DIS_MODEL_NUMBER -> disModelNumber = str
            else -> {}
        }

        // Read next DIS char, or finish when all done
        if (pendingDisReads.isNotEmpty()) {
            val next = pendingDisReads.removeFirst()
            shim.readCharacteristic(connId, next)
        } else {
            onAllDisReadsComplete()
        }
    }

    fun onCharacteristicChanged(charId: HpyCharId, value: ByteArray) {
        when (charId) {
            HpyCharId.CMD_TX -> handleCommandResponse(value)
            HpyCharId.DEBUG_TX -> emitEvent(HpyEvent.DebugMessage(connId, value.copyOf()))
            HpyCharId.FRAME_TX -> handleFrameTxNotification(value)
            HpyCharId.STREAM_TX -> handleStreamTxNotification(value)
            HpyCharId.SUOTA_STATUS -> { /* Handled by FW update controller */ }
            else -> {}
        }
    }

    fun onWriteComplete(charId: HpyCharId, status: Int) {
        val cmd = commandQueue.currentCommand ?: return
        if (cmd.completionType == CompletionType.ON_WRITE_ACK) {
            commandQueue.signalDone()
        }
        // ON_NOTIFICATION commands wait for the notification response
    }

    // ---- Internal ----

    private fun subscribeNotifications() {
        // Build list of chars to subscribe — send first one, queue the rest
        val charsToSubscribe = mutableListOf(HpyCharId.CMD_TX)
        if (HpyCharId.STREAM_TX in availableChars) charsToSubscribe.add(HpyCharId.STREAM_TX)
        if (HpyCharId.DEBUG_TX in availableChars) charsToSubscribe.add(HpyCharId.DEBUG_TX)
        if (HpyCharId.FRAME_TX in availableChars) charsToSubscribe.add(HpyCharId.FRAME_TX)
        if (HpyCharId.SUOTA_STATUS in availableChars) charsToSubscribe.add(HpyCharId.SUOTA_STATUS)

        // Send first, queue the rest — each onDescriptorWritten sends the next
        val first = charsToSubscribe.removeFirst()
        pendingNotifSubscriptions.clear()
        pendingNotifSubscriptions.addAll(charsToSubscribe)
        shim.subscribeNotifications(connId, first, true)
    }

    private fun startDisReads() {
        val disChars = mutableListOf(
            HpyCharId.DIS_SERIAL_NUMBER,
            HpyCharId.DIS_FW_VERSION,
            HpyCharId.DIS_SW_VERSION,
            HpyCharId.DIS_MANUFACTURER_NAME,
            HpyCharId.DIS_MODEL_NUMBER,
        ).filter { it in availableChars }.toMutableList()

        if (disChars.isEmpty()) {
            log("ERROR: No DIS characteristics found")
            emitEvent(HpyEvent.Error(connId, HpyErrorCode.CONNECT_FAIL, "DIS service missing"))
            shim.disconnect(connId)
            transition(HpyConnectionState.DISCONNECTED)
            return
        }

        // Send first read, queue the rest — each onCharacteristicRead sends the next
        val first = disChars.removeFirst()
        pendingDisReads.clear()
        pendingDisReads.addAll(disChars)
        shim.readCharacteristic(connId, first)
    }

    private fun onAllDisReadsComplete() {
        deviceInfo = DeviceInfoData(
            serialNumber = disSerialNumber,
            manufacturerName = disManufacturerName,
            fwVersion = disFwVersion,
            swVersion = disSwVersion,
            modelNumber = disModelNumber,
        )
        firmwareTier = deviceInfo.firmwareTier
        log("Device info: serial=${deviceInfo.serialNumber}, fw=${deviceInfo.fwVersion}, tier=$firmwareTier")
        emitEvent(HpyEvent.DeviceInfo(connId, deviceInfo))

        if (firmwareTier == FirmwareTier.TIER_0) {
            transition(HpyConnectionState.CONNECTED_LIMITED)
            return
        }

        memfaultBuffer.incrementConnectSeq()

        // Start handshake
        transition(HpyConnectionState.HANDSHAKING)
        handshakeRunner = HandshakeRunner(firmwareTier, config, timeSource)
        val firstCmd = handshakeRunner!!.start()
        if (firstCmd != null) {
            commandQueue.enqueue(firstCmd)
        } else {
            onHandshakeComplete()
        }
    }

    private fun handleCommandResponse(value: ByteArray) {
        if (value.isEmpty()) return
        val cmdByte = value[0]
        log("CMD response [${value.size}b]: ${value.toHex()}")

        // Check for unrecognized command
        if (cmdByte == CommandId.UNRECOGNIZED_RESPONSE) {
            emitEvent(HpyEvent.Error(connId, HpyErrorCode.COMMAND_UNRECOGNIZED, "Unrecognized command"))
            if (state == HpyConnectionState.DOWNLOADING) {
                // Route to download controller so it can fall back (e.g. L2CAP → GATT)
                handleDownloadCommandResponse(cmdByte, value)
                return
            }
            commandQueue.signalDone()
            return
        }

        // If in handshake, route to handshake runner
        if (state == HpyConnectionState.HANDSHAKING) {
            handleHandshakeResponse(cmdByte, value)
            return
        }

        // If downloading, route to download controller
        if (state == HpyConnectionState.DOWNLOADING) {
            handleDownloadCommandResponse(cmdByte, value)
            return
        }

        // In READY state, handle normally
        commandQueue.signalDone()

        when (cmdByte) {
            CommandId.GET_DEVICE_STATUS -> {
                val status = ResponseParser.parseDeviceStatus(value)
                if (status != null) {
                    lastStatus = status
                    emitEvent(HpyEvent.DeviceStatus(connId, status))
                    if (downloadPendingStatusPoll) {
                        downloadPendingStatusPoll = false
                        if (status.unsyncedFrames > 0) {
                            beginDownloadSession(status)
                        } else {
                            log("No unsynced frames — entering WAITING")
                            transition(HpyConnectionState.WAITING)
                        }
                    }
                }
            }
            CommandId.GET_DAQ_CONFIG -> {
                val config = ResponseParser.parseDaqConfig(value)
                if (config != null) {
                    emitEvent(HpyEvent.DaqConfig(connId, config))
                }
            }
            CommandId.GET_EXTENDED_DEVICE_STATUS -> {
                val ext = ResponseParser.parseExtendedDeviceStatus(value)
                if (ext != null) {
                    emitEvent(HpyEvent.ExtendedDeviceStatus(connId, ext))
                }
            }
            else -> {
                emitEvent(HpyEvent.CommandResult(connId, cmdByte, value.copyOf()))
            }
        }
    }

    private fun handleHandshakeResponse(cmdByte: Byte, value: ByteArray) {
        val runner = handshakeRunner ?: return
        commandQueue.signalDone()

        when (cmdByte) {
            CommandId.GET_DAQ_CONFIG -> {
                val config = ResponseParser.parseDaqConfig(value)
                if (config != null) {
                    log("HS: DAQ config version=${config.version}, mode=${config.modeString}")
                    emitEvent(HpyEvent.DaqConfig(connId, config))
                }
                val next = runner.onCommandComplete()
                enqueueHandshakeCommand(next)
            }
            CommandId.GET_DEVICE_STATUS -> {
                val status = ResponseParser.parseDeviceStatus(value)
                if (status != null) {
                    lastStatus = status
                    log("HS: DevStatus phy=${status.phyString}, DAQ=${status.daqString}, SOC=${status.soc}%, unsynced=${status.unsyncedFrames}, sendUtc=0x${status.sendUtcFlags.toString(16)}")
                    emitEvent(HpyEvent.DeviceStatus(connId, status))
                    val next = runner.onDeviceStatusReceived(status)
                    enqueueHandshakeCommand(next)
                } else {
                    log("HS: DeviceStatus parse failed")
                    val next = runner.onCommandComplete()
                    enqueueHandshakeCommand(next)
                }
            }
            CommandId.SET_UTC -> {
                val resp = ResponseParser.parseSetUtcResponse(value)
                log("HS: SET_UTC response reboots=${resp?.ringReboots}")
                val next = runner.onCommandComplete()
                enqueueHandshakeCommand(next)
            }
            CommandId.SET_INFO -> {
                log("HS: SET_INFO response")
                val next = runner.onCommandComplete()
                enqueueHandshakeCommand(next)
            }
            CommandId.SET_FINGER_DETECTION -> {
                log("HS: SET_FINGER_DETECTION response")
                val next = runner.onCommandComplete()
                enqueueHandshakeCommand(next)
            }
            CommandId.GET_FILE_LENGTH -> {
                val length = ResponseParser.parseGetFileLengthResponse(value)
                log("HS: GET_FILE_LENGTH (Memfault) length=$length")
                val next = runner.onMemfaultFileLengthReceived(length)
                if (next != null && length != null && length > 0u) {
                    if (!memfaultBuffer.canFitChunk(length.toInt())) {
                        log("HS: Memfault chunk too large for buffer ($length bytes)")
                        emitEvent(HpyEvent.Error(connId, HpyErrorCode.MEMFAULT_BUFFER_FULL,
                            "Memfault chunk exceeds buffer: $length bytes"))
                        onHandshakeComplete()
                        return
                    }
                    memfaultAccumulator = ByteArray(length.toInt())
                    memfaultAccumulatorPos = 0
                }
                enqueueHandshakeCommand(next)
            }
            CommandId.READ_FILE -> {
                val ringCrc = ResponseParser.parseReadFileCrcResponse(value)
                val data = memfaultAccumulator
                val dataLen = memfaultAccumulatorPos
                var crcValid = false
                if (data != null && ringCrc != null && dataLen > 0) {
                    val localCrc = finalizeCrc(updateCrc(CRC_INIT, data, 0, dataLen))
                    crcValid = (localCrc == ringCrc)
                }
                val crcStr = if (crcValid) "OK" else "MISMATCH"
                log("HS: READ_FILE (Memfault) $dataLen bytes, CRC $crcStr")

                // Write to persistent circular buffer
                if (data != null && dataLen > 0) {
                    memfaultBuffer.writeChunk(data.copyOfRange(0, dataLen), crcValid)
                }

                memfaultAccumulator = null
                memfaultAccumulatorPos = 0

                val next = runner.onMemfaultReadComplete()
                enqueueHandshakeCommand(next)
            }
            else -> {
                log("HS: Unexpected response 0x${cmdByte.toUByte().toString(16)}")
                val next = runner.onCommandComplete()
                enqueueHandshakeCommand(next)
            }
        }
    }

    private fun enqueueHandshakeCommand(cmd: QueuedCommand?) {
        if (cmd != null) {
            commandQueue.enqueue(cmd)
        } else if (handshakeRunner?.isComplete == true) {
            onHandshakeComplete()
        }
    }

    private fun onHandshakeComplete() {
        val chunksThisDrain = handshakeRunner?.memfaultChunksDownloaded ?: 0
        handshakeRunner = null
        memfaultAccumulator = null
        memfaultAccumulatorPos = 0
        if (chunksThisDrain > 0 || memfaultBuffer.getChunks().isNotEmpty()) {
            log("Memfault drain complete: $chunksThisDrain new chunks, ${memfaultBuffer.getChunks().size} total in buffer")
            emitEvent(HpyEvent.MemfaultComplete(connId, chunksThisDrain))
        }
        log("Handshake complete -> READY")
        transition(HpyConnectionState.READY)
    }

    private fun handleFrameTxNotification(value: ByteArray) {
        // During GATT download, FRAME_TX carries the final CRC response [0x17, CRC32_LE, status]
        if (state == HpyConnectionState.DOWNLOADING && value.isNotEmpty() &&
            value[0] == CommandId.GET_FRAMES
        ) {
            val controller = downloadController ?: return
            val action = controller.onCommandResponse(value[0], value)
            handleDownloadAction(action)
            return
        }

        // FRAME_TX normally carries autonomous device status notifications
        val status = ResponseParser.parseDeviceStatus(value)
        if (status != null) {
            lastStatus = status
            emitEvent(HpyEvent.DeviceStatus(connId, status))

            // Auto-trigger download on SuperframeClose if download is enabled
            if ((state == HpyConnectionState.READY || state == HpyConnectionState.WAITING) &&
                downloadEnabled &&
                status.notifSender == CommandId.NOTIF_SENDER_SUPERFRAME_CLOSE &&
                status.unsyncedFrames > 0
            ) {
                log("SuperframeClose auto-trigger: ${status.unsyncedFrames} unsynced frames")
                beginDownloadSession(status)
            }
        }
    }

    private fun handleStreamTxNotification(value: ByteArray) {
        if (state == HpyConnectionState.DOWNLOADING) {
            // STREAM_TX carries GATT frame data during download
            val action = downloadController?.onStreamTxData(value) ?: return
            handleDownloadAction(action)
        } else if (state == HpyConnectionState.HANDSHAKING && memfaultAccumulator != null) {
            // Accumulate memfault chunk data from STREAM_TX
            val buf = memfaultAccumulator!!
            val copyLen = minOf(value.size, buf.size - memfaultAccumulatorPos)
            value.copyInto(buf, memfaultAccumulatorPos, 0, copyLen)
            memfaultAccumulatorPos += copyLen
        }
    }

    // ---- Download ----

    private fun beginDownloadSession(status: DeviceStatusData) {
        log("L2CAP check: fw='${deviceInfo.fwVersion}', supportsL2cap=${deviceInfo.supportsL2capDownload}, preferL2cap=${config.preferL2capDownload}")
        val useL2cap = deviceInfo.supportsL2capDownload && config.preferL2capDownload
        val controller = DownloadController(
            connId = connId,
            batchSize = config.downloadBatchSize,
            maxRetries = config.downloadMaxRetries,
            supportsL2cap = useL2cap,
            onFrameEmit = { frameData -> emitEvent(HpyEvent.DownloadFrame(connId, frameData)) },
        )
        downloadController = controller
        downloadEnabled = true
        transition(HpyConnectionState.DOWNLOADING)
        log("Download starting: ${status.unsyncedFrames} frames, transport=${if (useL2cap) "L2CAP" else "GATT"}")

        val action = controller.startSession(
            syncFrameCount = status.syncFrameCount,
            syncFrameReboots = status.syncFrameReboots,
            unsyncedFrames = status.unsyncedFrames,
        )
        handleDownloadAction(action)
    }

    private fun handleDownloadCommandResponse(cmdByte: Byte, value: ByteArray) {
        val controller = downloadController ?: return
        commandQueue.signalDone()
        val action = controller.onCommandResponse(cmdByte, value)
        handleDownloadAction(action)
    }

    fun onL2capConnected() {
        val controller = downloadController ?: return
        log("L2CAP socket connected")
        val action = controller.onL2capConnected()
        handleDownloadAction(action)
    }

    fun onL2capFrame(frameData: ByteArray) {
        emitEvent(HpyEvent.DownloadFrame(connId, frameData))
        val controller = downloadController ?: return
        val action = controller.onFrameReceived()
        handleDownloadAction(action)
    }

    fun onL2capBatchComplete(framesReceived: Int, crcValid: Boolean) {
        val controller = downloadController ?: return
        log("L2CAP batch complete: $framesReceived frames, CRC valid=$crcValid")
        val action = controller.onL2capBatchComplete(framesReceived, crcValid)
        handleDownloadAction(action)
    }

    fun onL2capError(message: String) {
        val controller = downloadController ?: return
        log("L2CAP error: $message")
        val action = controller.onL2capError(message)
        handleDownloadAction(action)
    }

    private fun handleDownloadAction(action: DownloadAction) {
        when (action) {
            is DownloadAction.EnqueueCommand -> commandQueue.enqueue(action.cmd)
            is DownloadAction.OpenL2cap -> shim.l2capOpen(connId, action.psm)
            is DownloadAction.StartL2capReceive -> shim.l2capStartReceiving(connId, action.expectedFrames)
            is DownloadAction.CloseL2cap -> shim.l2capClose(connId)
            is DownloadAction.EmitEvent -> emitEvent(action.event)
            is DownloadAction.SessionComplete -> {
                downloadController = null
                if (downloadEnabled) {
                    log("Download session complete -> WAITING")
                    transition(HpyConnectionState.WAITING)
                } else {
                    log("Download session complete -> READY")
                    transition(HpyConnectionState.READY)
                }
            }
            is DownloadAction.Multiple -> action.actions.forEach { handleDownloadAction(it) }
            is DownloadAction.NoOp -> {}
        }
    }

    private fun sendCommand(cmd: QueuedCommand) {
        log("TX ${cmd.tag} [${cmd.data.size}b]")
        shim.writeCharacteristic(connId, cmd.charId, cmd.data, WriteType.WITHOUT_RESPONSE)
        commandQueue.startTimeoutTimer(scope)
    }

    private fun transition(newState: HpyConnectionState) {
        if (state == newState) return
        val oldState = state
        state = newState
        log("State: $oldState -> $newState")
        emitEvent(HpyEvent.StateChanged(connId, newState))
    }

    private fun log(msg: String) {
        emitEvent(HpyEvent.Log(connId, msg))
    }
}
