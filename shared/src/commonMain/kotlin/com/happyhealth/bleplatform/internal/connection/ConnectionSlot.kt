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
import com.happyhealth.bleplatform.internal.fwupdate.FwUpdateAction
import com.happyhealth.bleplatform.internal.fwupdate.FwUpdateController
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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

    // FW update
    private var fwUpdateController: FwUpdateController? = null
    private var fwUpdateJob: Job? = null
    private var fwErrorPendingDisconnect: Boolean = false
    private var fwErrorFallbackJob: Job? = null

    // Reconnection
    private var reconnectJob: Job? = null
    private var reconnectAttempt: Int = 0
    private var isUserDisconnect: Boolean = false
    private var wasFwUpdateRebooting: Boolean = false
    private var connectResultDeferred: CompletableDeferred<Boolean>? = null
    private var resumeDownloadAfterReconnect: Boolean = false

    // Persists across reconnections — NOT cleared on disconnect
    private val memfaultBuffer = MemfaultBuffer()

    // Transient per-drain accumulator for memfault STREAM_TX data
    private var memfaultAccumulator: ByteArray? = null
    private var memfaultAccumulatorPos: Int = 0

    // Guard against duplicate Android BLE callbacks (onConnectionStateChange/onMtuChanged can fire twice)
    private var connectHandled: Boolean = false
    private var serviceDiscoveryStarted: Boolean = false

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
        isUserDisconnect = false
        connectHandled = false
        transition(HpyConnectionState.CONNECTING)
        shim.connect(connId, handle)
    }

    fun disconnect() {
        isUserDisconnect = true
        resumeDownloadAfterReconnect = false
        fwErrorPendingDisconnect = false
        fwErrorFallbackJob?.cancel()
        fwErrorFallbackJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        connectResultDeferred?.complete(false)
        connectResultDeferred = null
        cleanupOperations()
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
        resumeDownloadAfterReconnect = false
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

    // ---- FW Update ----

    fun startFwUpdate(imageBytes: ByteArray) {
        if (state != HpyConnectionState.READY) return
        val controller = FwUpdateController(connId, imageBytes)
        fwUpdateController = controller
        transition(HpyConnectionState.FW_UPDATING)
        handleFwUpdateAction(controller.start())
    }

    fun cancelFwUpdate() {
        val controller = fwUpdateController ?: return
        fwUpdateJob?.cancel()
        fwUpdateJob = null
        handleFwUpdateAction(controller.cancel())
        fwUpdateController = null
        log("FW update cancelled — awaiting disconnect")
        // Abort triggers a ring reboot; don't show READY while ring is offline.
        fwErrorPendingDisconnect = true
        fwErrorFallbackJob = scope.launch {
            delay(5_000L)
            if (fwErrorPendingDisconnect) {
                fwErrorPendingDisconnect = false
                log("Ring still connected after FW cancel — returning to READY")
                transition(HpyConnectionState.READY)
            }
        }
    }

    fun onL2capSendProgress(blocksSent: Int, blocksTotal: Int) {
        handleFwUpdateAction(fwUpdateController?.onStreamProgress(blocksSent, blocksTotal) ?: return)
    }

    fun onL2capSendComplete() {
        handleFwUpdateAction(fwUpdateController?.onStreamComplete() ?: return)
    }

    fun onL2capSendError(message: String) {
        handleFwUpdateAction(fwUpdateController?.onStreamError(message) ?: return)
    }

    private fun handleFwUpdateAction(action: FwUpdateAction) {
        when (action) {
            is FwUpdateAction.WriteSuota -> {
                log("FW TX ${action.charId}")
                shim.writeCharacteristic(connId, action.charId, action.data, WriteType.WITH_RESPONSE)
            }
            is FwUpdateAction.StartL2capStream -> {
                shim.l2capStreamSend(connId, action.psm, action.imageBytes,
                    action.blockSize, action.delayMs)
            }
            is FwUpdateAction.EmitEvent -> {
                emitEvent(action.event)
                // If the controller entered ERROR state, clean up but DON'T go to READY yet —
                // the ring is likely rebooting and a BLE disconnect will arrive shortly.
                // If no disconnect arrives within 5s, fall back to READY.
                if (fwUpdateController?.currentState == FwUpdateController.State.ERROR) {
                    fwUpdateController = null
                    fwUpdateJob?.cancel()
                    fwUpdateJob = null
                    fwErrorPendingDisconnect = true
                    log("FW update error — awaiting disconnect")
                    fwErrorFallbackJob = scope.launch {
                        delay(5_000L)
                        if (fwErrorPendingDisconnect) {
                            fwErrorPendingDisconnect = false
                            log("Ring still connected after FW error — returning to READY")
                            transition(HpyConnectionState.READY)
                        }
                    }
                }
            }
            is FwUpdateAction.ScheduleCallback -> {
                fwUpdateJob = scope.launch {
                    delay(action.delayMs)
                    val next = fwUpdateController?.onScheduledCallback(action.tag) ?: return@launch
                    handleFwUpdateAction(next)
                }
            }
            is FwUpdateAction.SessionComplete -> {
                fwUpdateController = null
                fwUpdateJob = null
                wasFwUpdateRebooting = true
                log("FW update complete — releasing GATT for ring reboot")
                transition(HpyConnectionState.FW_UPDATE_REBOOTING)
                // Close the GATT so the ring can reboot cleanly.
                // gatt.close() does NOT fire onDisconnected, so start reconnection explicitly.
                shim.disconnect(connId)
                if (reconnectJob == null) {
                    startFwRebootReconnection()
                }
            }
            is FwUpdateAction.NoOp -> {}
            is FwUpdateAction.Multiple -> action.actions.forEach { handleFwUpdateAction(it) }
        }
    }

    // ---- Shim callbacks ----

    fun onConnected() {
        if (connectHandled) return
        connectHandled = true
        connectResultDeferred?.complete(true)
        connectResultDeferred = null
        serviceDiscoveryStarted = false
        log("Connected")
        shim.requestMtu(connId, config.requestedMtu)
    }

    fun onMtuChanged(mtu: Int) {
        if (serviceDiscoveryStarted) return
        serviceDiscoveryStarted = true
        log("MTU negotiated: $mtu")
        shim.discoverServices(connId)
    }

    fun onDisconnected(status: Int) {
        log("Disconnected (status=$status)")

        // Cancel FW error fallback timer — the disconnect we were waiting for has arrived
        fwErrorFallbackJob?.cancel()
        fwErrorFallbackJob = null
        val hadFwError = fwErrorPendingDisconnect
        fwErrorPendingDisconnect = false

        // Capture download state before cleanup destroys it
        val wasDownloading = downloadEnabled
        val interruptedBatchFrames = downloadController?.batchFramesReceived ?: 0

        cleanupOperations()

        // 1. If a reconnect connect attempt just failed, signal failure — retry loop handles it
        val deferred = connectResultDeferred
        if (deferred != null) {
            deferred.complete(false)
            connectResultDeferred = null
            return
        }

        // 2. User-initiated disconnect — go straight to DISCONNECTED
        if (isUserDisconnect) {
            if (state != HpyConnectionState.DISCONNECTED) {
                transition(HpyConnectionState.DISCONNECTED)
            }
            return
        }

        // 3. FW update reboot — start FW reboot reconnection
        //    Also detect early reboot: ring may reboot before SessionComplete fires,
        //    so check state (FW_UPDATING or FW_UPDATE_REBOOTING) as well as the flag.
        //    Skip if we just had a FW error (CRC_ERR etc.) — use normal reconnection instead.
        val isFwRebootDisconnect = !hadFwError && (wasFwUpdateRebooting ||
            state == HpyConnectionState.FW_UPDATING ||
            state == HpyConnectionState.FW_UPDATE_REBOOTING)
        if (isFwRebootDisconnect && reconnectJob == null) {
            wasFwUpdateRebooting = true  // ensure flag is set for onHandshakeComplete
            startFwRebootReconnection()
            return
        }

        // 4. Already reconnecting with active job — let the loop handle it
        if ((state == HpyConnectionState.RECONNECTING || state == HpyConnectionState.FW_UPDATE_REBOOTING)
            && reconnectJob != null
        ) {
            return
        }

        // 5. Unexpected disconnect — start normal reconnection
        if (wasDownloading) {
            resumeDownloadAfterReconnect = true
            if (interruptedBatchFrames > 0) {
                log("Download interrupted: $interruptedBatchFrames partial-batch frames to discard")
                emitEvent(HpyEvent.DownloadInterrupted(connId, interruptedBatchFrames))
            }
        }
        startNormalReconnection()
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
            HpyCharId.SUOTA_STATUS -> {
                val controller = fwUpdateController ?: return
                if (value.isEmpty()) return
                val statusCode = value[0].toUByte().toInt()
                log("SUOTA_STATUS: $statusCode")
                handleFwUpdateAction(controller.onSuotaStatus(statusCode))
            }
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

    // ---- Reconnection ----

    private fun startNormalReconnection() {
        val handle = deviceHandle ?: run {
            log("Cannot reconnect — no device handle")
            transition(HpyConnectionState.DISCONNECTED)
            return
        }
        reconnectAttempt = 0
        transition(HpyConnectionState.RECONNECTING)
        reconnectJob = scope.launch {
            for (attempt in 1..config.reconnectMaxAttempts) {
                reconnectAttempt = attempt
                val delayMs = config.reconnectSchedule.delayForAttempt(attempt)
                delay(delayMs)
                transition(HpyConnectionState.RECONNECTING, retryCount = attempt)
                log("Reconnect attempt $attempt/${config.reconnectMaxAttempts}")
                connectHandled = false
                shim.connect(connId, handle)
                val connected = waitForConnectResult()
                if (connected) {
                    // onConnected() has already been called — handshake flow takes over
                    reconnectJob = null
                    reconnectAttempt = 0
                    return@launch
                }
            }
            // All attempts exhausted
            log("Reconnection failed after ${config.reconnectMaxAttempts} attempts")
            emitEvent(HpyEvent.Error(connId, HpyErrorCode.RECONNECT_FAIL,
                "Reconnection failed after ${config.reconnectMaxAttempts} attempts"))
            reconnectJob = null
            reconnectAttempt = 0
            transition(HpyConnectionState.DISCONNECTED)
        }
    }

    private fun startFwRebootReconnection() {
        val handle = deviceHandle ?: run {
            log("Cannot reconnect after FW update — no device handle")
            wasFwUpdateRebooting = false
            transition(HpyConnectionState.DISCONNECTED)
            return
        }
        reconnectAttempt = 0
        reconnectJob = scope.launch {
            log("Waiting ${config.fwRebootWaitMs}ms for ring to reboot...")
            delay(config.fwRebootWaitMs)
            for (attempt in 1..config.reconnectMaxAttempts) {
                reconnectAttempt = attempt
                val delayMs = config.fwReconnectSchedule.delayForAttempt(attempt)
                delay(delayMs)
                transition(HpyConnectionState.FW_UPDATE_REBOOTING, retryCount = attempt)
                log("FW reboot reconnect attempt $attempt/${config.reconnectMaxAttempts}")
                connectHandled = false
                shim.connect(connId, handle)
                val connected = waitForConnectResult()
                if (connected) {
                    // onConnected() has already been called — handshake flow takes over
                    reconnectJob = null
                    reconnectAttempt = 0
                    return@launch
                }
            }
            // All attempts exhausted
            log("FW update reconnection failed after ${config.reconnectMaxAttempts} attempts")
            emitEvent(HpyEvent.Error(connId, HpyErrorCode.FW_UPDATE_RECONNECT_FAIL,
                "FW update reconnection failed after ${config.reconnectMaxAttempts} attempts"))
            wasFwUpdateRebooting = false
            reconnectJob = null
            reconnectAttempt = 0
            transition(HpyConnectionState.DISCONNECTED)
        }
    }

    private suspend fun waitForConnectResult(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        connectResultDeferred = deferred
        return try {
            withTimeout(15_000L) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            connectResultDeferred = null
            shim.disconnect(connId)  // cancel the pending BLE connect
            false
        }
    }

    // ---- Internal ----

    private fun cleanupOperations() {
        commandQueue.flush()
        handshakeRunner = null
        if (downloadController != null) {
            shim.l2capClose(connId)
            downloadController = null
        }
        downloadEnabled = false
        downloadPendingStatusPoll = false
        fwUpdateController = null
        fwUpdateJob?.cancel()
        fwUpdateJob = null
        memfaultAccumulator = null
        memfaultAccumulatorPos = 0
    }

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
        if (wasFwUpdateRebooting) {
            wasFwUpdateRebooting = false
            log("FW update reconnect complete -> READY (FW: ${deviceInfo.fwVersion})")
            emitEvent(HpyEvent.FwUpdateComplete(connId, deviceInfo.fwVersion))
            transition(HpyConnectionState.READY)
        } else {
            log("Handshake complete -> READY")
            transition(HpyConnectionState.READY)
        }
        if (resumeDownloadAfterReconnect) {
            resumeDownloadAfterReconnect = false
            log("Resuming download after reconnection")
            startDownload()
        }
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

    private fun transition(newState: HpyConnectionState, retryCount: Int = 0) {
        if (state == newState && retryCount == 0) return
        val oldState = state
        state = newState
        log("State: $oldState -> $newState" + if (retryCount > 0) " (retry $retryCount)" else "")
        emitEvent(HpyEvent.StateChanged(connId, newState, retryCount))
    }

    private fun log(msg: String) {
        emitEvent(HpyEvent.Log(connId, msg))
    }
}
