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
import com.happyhealth.bleplatform.internal.memfault.MemfaultChunkDescriptor
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
    internal var config: ConnectionConfig,
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
    private var downloadFailsafeJob: Job? = null
    private var l2capConnectTimeoutJob: Job? = null
    private var subscriptionTimeoutJob: Job? = null
    private var downloadStallJob: Job? = null
    private var cumulativeFramesDownloaded: Int = 0
    private var cumulativeFramesTotal: Int = 0
    private var lastDownloadStartTime: Long = 0L  // UTC seconds from timeSource

    // RSSI pre-check for auto-download
    private var pendingAutoDownloadStatus: DeviceStatusData? = null
    private var rssiTimeoutJob: Job? = null

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

    // RSSI tracking for download metadata
    private var lastRssi: Int? = null

    // Reconnect frame accounting
    private var preDisconnectSyncFrameCount: UInt? = null
    private var preDisconnectSyncReboots: UInt? = null

    // App's last CRC-validated sync position — used for sync correction after stall
    private var savedAppSyncFrameCount: UInt? = null
    private var savedAppSyncReboots: UInt? = null
    private var syncCorrectionPending: Boolean = false

    // Cross-session NCF detection: last committed sync position
    private var lastCommittedSyncFrameCount: UInt? = null
    private var lastCommittedSyncReboots: UInt? = null

    // Persists across reconnections — NOT cleared on disconnect
    private val memfaultBuffer = MemfaultBuffer()
    private var lastMemfaultDrainTimeMs: Long = 0L

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
            if (state == HpyConnectionState.HANDSHAKING) {
                log("Advancing handshake past timed-out step: ${cmd.tag}")
                val next = handshakeRunner?.onCommandComplete()
                enqueueHandshakeCommand(next)
            } else if (state == HpyConnectionState.DOWNLOADING) {
                abortDownloadSession("Download command timeout")
            }
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
        savedAppSyncFrameCount = null
        savedAppSyncReboots = null
        lastCommittedSyncFrameCount = null
        lastCommittedSyncReboots = null
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
        cumulativeFramesDownloaded = 0
        cumulativeFramesTotal = 0
        lastCommittedSyncFrameCount = null
        lastCommittedSyncReboots = null
        savedAppSyncFrameCount = null
        savedAppSyncReboots = null
        syncCorrectionPending = false
        commandQueue.enqueue(QueuedCommand(
            tag = "DL_GET_DEV_STATUS",
            charId = HpyCharId.CMD_RX,
            data = CommandBuilder.buildGetDeviceStatus(),
            timeoutMs = config.commandTimeoutMs,
            completionType = CompletionType.ON_NOTIFICATION,
        ))
    }

    private fun resumeDownload() {
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
        savedAppSyncFrameCount = null
        savedAppSyncReboots = null
        syncCorrectionPending = false
        lastCommittedSyncFrameCount = null
        lastCommittedSyncReboots = null
        downloadFailsafeJob?.cancel()
        downloadFailsafeJob = null
        l2capConnectTimeoutJob?.cancel()
        l2capConnectTimeoutJob = null
        downloadStallJob?.cancel()
        downloadStallJob = null
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
        if (state != HpyConnectionState.READY && state != HpyConnectionState.CONNECTED_LIMITED) return
        val controller = FwUpdateController(
            connId, imageBytes, config.fwStreamInterBlockDelayMs, config.fwStreamDrainDelayMs,
        )
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
                    action.blockSize, action.delayMs, action.drainDelayMs)
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
                    val timingMsg = "interBlockDelay=${config.fwStreamInterBlockDelayMs}ms, drainDelay=${config.fwStreamDrainDelayMs}ms"
                    log("FW update error — awaiting disconnect ($timingMsg)")
                    emitEvent(HpyEvent.Log(connId, "FW update error ($timingMsg)"))
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
                val timingMsg = "interBlockDelay=${config.fwStreamInterBlockDelayMs}ms, drainDelay=${config.fwStreamDrainDelayMs}ms"
                log("FW update complete — releasing GATT for ring reboot ($timingMsg)")
                emitEvent(HpyEvent.Log(connId, "FW update complete ($timingMsg)"))
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

        // Cancel failsafe timer early to minimize race window — prevents it from
        // enqueuing a command into a dead connection between now and cleanupOperations()
        downloadFailsafeJob?.cancel()

        // Cancel FW error fallback timer — the disconnect we were waiting for has arrived
        fwErrorFallbackJob?.cancel()
        fwErrorFallbackJob = null
        val hadFwError = fwErrorPendingDisconnect
        fwErrorPendingDisconnect = false

        // Capture download state before cleanup destroys it
        val wasDownloading = downloadEnabled
        val interruptedBatchFrames = downloadController?.batchFramesReceived ?: 0
        val preSyncFc = downloadController?.currentSyncFrameCount
        val preSyncRb = downloadController?.currentSyncReboots

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

        // 5. Auto-reconnect disabled — go straight to DISCONNECTED
        if (!config.autoReconnect) {
            log("Auto-reconnect disabled — going to DISCONNECTED")
            transition(HpyConnectionState.DISCONNECTED)
            return
        }

        // 6. Unexpected disconnect — start normal reconnection
        if (wasDownloading) {
            resumeDownloadAfterReconnect = true
            // Only update sync position if we had an active controller.
            // A WAITING-state disconnect has preSyncFc=null — don't clobber
            // the value saved from a previous DOWNLOADING-state disconnect.
            if (preSyncFc != null) {
                preDisconnectSyncFrameCount = preSyncFc
                preDisconnectSyncReboots = preSyncRb
                lastCommittedSyncFrameCount = preSyncFc
                lastCommittedSyncReboots = preSyncRb
            }
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

    fun onCharacteristicReadFailed(charId: HpyCharId) {
        log("DIS read failed: $charId (skipping)")
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

    fun onRssiRead(rssi: Int) {
        rssiTimeoutJob?.cancel()
        rssiTimeoutJob = null
        lastRssi = rssi
        downloadController?.lastRssi = rssi
        val status = pendingAutoDownloadStatus
        if (status != null) {
            pendingAutoDownloadStatus = null
            if (rssi > config.minRssi) {
                log("RSSI $rssi dBm OK, starting session")
                beginDownloadSession(status)
            } else {
                log("RSSI $rssi dBm too low (<= ${config.minRssi}), skipping session")
            }
        }
        emitEvent(HpyEvent.RssiRead(connId, rssi))
    }

    // ---- Reconnection ----

    private fun startNormalReconnection() {
        val handle = deviceHandle ?: run {
            log("Cannot reconnect — no device handle")
            transition(HpyConnectionState.DISCONNECTED)
            return
        }
        reconnectAttempt = 0
        val unlimited = config.reconnectMaxAttempts == Int.MAX_VALUE
        val maxLabel = if (unlimited) "Unlimited" else config.reconnectMaxAttempts.toString()
        val schedule = if (unlimited) config.reconnectSchedule + UNLIMITED_RECONNECT_TIER else config.reconnectSchedule
        transition(HpyConnectionState.RECONNECTING)
        reconnectJob = scope.launch {
            var attempt = 0
            while (true) {
                attempt++
                if (!unlimited && attempt > config.reconnectMaxAttempts) break
                reconnectAttempt = attempt
                val delayMs = schedule.delayForAttempt(attempt)
                delay(delayMs)
                transition(HpyConnectionState.RECONNECTING, retryCount = attempt)
                log("Reconnect attempt $attempt/$maxLabel")
                connectHandled = false
                shim.connect(connId, handle)
                val connected = waitForConnectResult()
                if (connected) {
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
        val unlimited = config.reconnectMaxAttempts == Int.MAX_VALUE
        val maxLabel = if (unlimited) "Unlimited" else config.reconnectMaxAttempts.toString()
        val schedule = if (unlimited) config.fwReconnectSchedule + UNLIMITED_RECONNECT_TIER else config.fwReconnectSchedule
        reconnectJob = scope.launch {
            log("Waiting ${config.fwRebootWaitMs}ms for ring to reboot...")
            delay(config.fwRebootWaitMs)
            var attempt = 0
            while (true) {
                attempt++
                if (!unlimited && attempt > config.reconnectMaxAttempts) break
                reconnectAttempt = attempt
                val delayMs = schedule.delayForAttempt(attempt)
                delay(delayMs)
                transition(HpyConnectionState.FW_UPDATE_REBOOTING, retryCount = attempt)
                log("FW reboot reconnect attempt $attempt/$maxLabel")
                connectHandled = false
                shim.connect(connId, handle)
                val connected = waitForConnectResult()
                if (connected) {
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
            downloadController?.let { ctrl ->
                cumulativeFramesDownloaded += ctrl.sessionFramesDownloaded
                cumulativeFramesTotal += ctrl.sessionFramesDownloaded
            }
            shim.l2capClose(connId)
            downloadController = null
        }
        downloadEnabled = false
        downloadPendingStatusPoll = false
        syncCorrectionPending = false
        downloadFailsafeJob?.cancel()
        downloadFailsafeJob = null
        l2capConnectTimeoutJob?.cancel()
        l2capConnectTimeoutJob = null
        subscriptionTimeoutJob?.cancel()
        subscriptionTimeoutJob = null
        downloadStallJob?.cancel()
        downloadStallJob = null
        pendingAutoDownloadStatus = null
        rssiTimeoutJob?.cancel()
        rssiTimeoutJob = null
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

        // Timeout: if all subscriptions don't complete within 20s, disconnect
        subscriptionTimeoutJob = scope.launch {
            delay(20_000L)
            log("Notification subscription timeout (20s)")
            emitEvent(HpyEvent.Error(connId, HpyErrorCode.NOTIFICATION_SUBSCRIBE_FAIL,
                "Notification subscription timed out"))
            shim.disconnect(connId)
            transition(HpyConnectionState.DISCONNECTED)
        }
    }

    private fun startDisReads() {
        subscriptionTimeoutJob?.cancel()
        subscriptionTimeoutJob = null
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
        val memfaultEnabled = if (config.memfaultMinIntervalMs > 0L) {
            val elapsed = timeSource.getUtcTimeSeconds() * 1000 - lastMemfaultDrainTimeMs
            elapsed >= config.memfaultMinIntervalMs
        } else true
        if (!memfaultEnabled) {
            log("Memfault drain skipped — throttle interval not elapsed (${config.memfaultMinIntervalMs / 60000}min)")
        }
        handshakeRunner = HandshakeRunner(firmwareTier, config, timeSource, memfaultEnabled)
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
                            startDownloadFailsafeTimer()
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
            CommandId.GET_SYNC_FRAME -> {
                val sf = ResponseParser.parseSyncFrame(value)
                if (sf != null) {
                    emitEvent(HpyEvent.SyncFrame(connId, sf.frameCount, sf.reboots))
                }
            }
            CommandId.SET_SYNC_FRAME -> {
                if (syncCorrectionPending) {
                    syncCorrectionPending = false
                    log("Sync correction applied — re-polling device status")
                    // Re-poll to get corrected unsyncedFrames count
                    downloadPendingStatusPoll = true
                    commandQueue.enqueue(QueuedCommand(
                        tag = "DL_GET_DEV_STATUS (post-sync-correction)",
                        charId = HpyCharId.CMD_RX,
                        data = CommandBuilder.buildGetDeviceStatus(),
                        timeoutMs = config.commandTimeoutMs,
                        completionType = CompletionType.ON_NOTIFICATION,
                    ))
                } else {
                    emitEvent(HpyEvent.CommandResult(connId, cmdByte, value.copyOf()))
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
                // Guard against duplicate Android BLE callbacks: if an accumulator
                // is already allocated, a READ_FILE is pending — skip the duplicate.
                if (memfaultAccumulator != null) return

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
                // Guard against duplicate Android BLE callbacks: if the accumulator
                // was already consumed, this is a duplicate CRC response — skip it.
                if (memfaultAccumulator == null) return

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
                if (!crcValid) {
                    emitEvent(HpyEvent.Error(connId, HpyErrorCode.GENERIC,
                        "Memfault chunk CRC mismatch ($dataLen bytes)"))
                }

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

    // Snapshot of descriptors returned by the last getMemfaultChunks() call,
    // so markMemfaultChunksUploaded() marks exactly those (not any that arrived since).
    private var lastReturnedChunkDescriptors: List<MemfaultChunkDescriptor> = emptyList()

    fun getMemfaultChunks(): List<ByteArray> {
        val unuploaded = memfaultBuffer.getUnuploadedChunks()
        lastReturnedChunkDescriptors = unuploaded
        return unuploaded.map { memfaultBuffer.readChunkData(it) }
    }

    fun markMemfaultChunksUploaded() {
        if (lastReturnedChunkDescriptors.isNotEmpty()) {
            memfaultBuffer.markUploaded(lastReturnedChunkDescriptors)
            lastReturnedChunkDescriptors = emptyList()
        }
    }

    private fun onHandshakeComplete() {
        val chunksThisDrain = handshakeRunner?.memfaultChunksDownloaded ?: 0
        if (chunksThisDrain > 0) {
            lastMemfaultDrainTimeMs = timeSource.getUtcTimeSeconds() * 1000
        }
        handshakeRunner = null
        memfaultAccumulator = null
        memfaultAccumulatorPos = 0
        if (chunksThisDrain > 0 || memfaultBuffer.getChunks().isNotEmpty()) {
            val badCrcCount = memfaultBuffer.getChunks().count { !it.crcValid }
            log("Memfault drain complete: $chunksThisDrain new chunks, ${memfaultBuffer.getChunks().size} total in buffer" +
                if (badCrcCount > 0) ", $badCrcCount with bad CRC" else "")
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
            val elapsedMs = (timeSource.getUtcTimeSeconds() - lastDownloadStartTime) * 1000
            if (elapsedMs < config.downloadCooldownMs) {
                val minutesAgo = elapsedMs / 60000
                log("Skipping download resume — last download was ${minutesAgo}m ago (< ${config.downloadCooldownMs / 60000}m cooldown)")
                downloadEnabled = true
                transition(HpyConnectionState.WAITING)
                startDownloadFailsafeTimer()
            } else {
                log("Resuming download after reconnection")
                resumeDownload()
            }
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

            // Auto-trigger download on device status notification if download is enabled
            if ((state == HpyConnectionState.READY || state == HpyConnectionState.WAITING) &&
                downloadEnabled &&
                status.unsyncedFrames > 0
            ) {
                val shouldTrigger = if (deviceInfo.supportsNotifSender) {
                    // FW >= 2.5.0.59: only trigger on SuperframeClose
                    status.notifSender == CommandId.NOTIF_SENDER_SUPERFRAME_CLOSE
                } else {
                    // FW < 2.5.0.59: any notification is a potential trigger
                    // (notifSender byte is not meaningful)
                    true
                }
                if (shouldTrigger) {
                    log("Download auto-trigger: ${status.unsyncedFrames} unsynced — checking RSSI")
                    pendingAutoDownloadStatus = status
                    shim.readRssi(connId)
                    rssiTimeoutJob?.cancel()
                    rssiTimeoutJob = scope.launch {
                        delay(3000)
                        pendingAutoDownloadStatus?.let { pending ->
                            pendingAutoDownloadStatus = null
                            log("RSSI read timeout, proceeding with download")
                            beginDownloadSession(pending)
                        }
                    }
                }
            }
        }
    }

    private fun handleStreamTxNotification(value: ByteArray) {
        if (state == HpyConnectionState.DOWNLOADING) {
            // STREAM_TX carries GATT frame data during download
            resetDownloadStallTimer()
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

    private fun resetDownloadStallTimer() {
        downloadStallJob?.cancel()
        downloadStallJob = scope.launch {
            delay(config.downloadStallTimeoutMs)
            if (state != HpyConnectionState.DOWNLOADING) return@launch
            val rssiStr = lastRssi?.let { ", RSSI=$it" } ?: ""
            log("Download stall detected: no data received for ${config.downloadStallTimeoutMs / 1000}s$rssiStr")
            emitEvent(HpyEvent.Error(connId, HpyErrorCode.DOWNLOAD_STALL,
                "No download data received for ${config.downloadStallTimeoutMs / 1000}s$rssiStr"))
            downloadStallJob = null  // prevent self-cancellation in abortDownloadSession
            abortDownloadSession("Download stall")
        }
    }

    private fun abortDownloadSession(reason: String) {
        downloadStallJob?.cancel()
        downloadStallJob = null
        downloadController?.let { ctrl ->
            savedAppSyncFrameCount = ctrl.currentSyncFrameCount
            savedAppSyncReboots = ctrl.currentSyncReboots
            cumulativeFramesDownloaded += ctrl.sessionFramesDownloaded
            cumulativeFramesTotal += ctrl.sessionFramesDownloaded
            lastCommittedSyncFrameCount = ctrl.currentSyncFrameCount
            lastCommittedSyncReboots = ctrl.currentSyncReboots
            val partialFrames = ctrl.batchFramesReceived
            if (partialFrames > 0) {
                log("$reason: $partialFrames partial-batch frames to discard")
                emitEvent(HpyEvent.DownloadInterrupted(connId, partialFrames))
            }
            shim.l2capClose(connId)
        }
        downloadController = null
        l2capConnectTimeoutJob?.cancel()
        l2capConnectTimeoutJob = null
        commandQueue.flush()
        log("$reason -> WAITING")
        transition(HpyConnectionState.WAITING)
        startDownloadFailsafeTimer()
    }

    private fun beginDownloadSession(status: DeviceStatusData) {
        downloadFailsafeJob?.cancel()
        downloadFailsafeJob = null

        // --- Sync correction: detect if ring advanced past app's position ---
        val appFc = savedAppSyncFrameCount ?: preDisconnectSyncFrameCount
        val appRb = savedAppSyncReboots ?: preDisconnectSyncReboots
        if (appFc != null) {
            val ringFc = status.syncFrameCount
            val ringRb = status.syncFrameReboots ?: 0u
            val appRbVal = appRb ?: 0u

            // Rewind if ring advanced past app's position (same boot epoch)
            // or if a reboot occurred — firmware will serve remaining frames
            // from the old boot epoch before continuing with the new one.
            val needsCorrection = if (ringRb == appRbVal) {
                ringFc > appFc
            } else {
                true  // Cross-reboot: rewind to recover tail frames from previous boot
            }

            if (needsCorrection) {
                val detail = if (ringRb == appRbVal) {
                    "gap=${ringFc - appFc} frames"
                } else {
                    "cross-reboot recovery"
                }
                log("Sync correction: ring at (fc=$ringFc, rb=$ringRb) but app at (fc=$appFc, rb=$appRbVal) — rewinding ring ($detail)")
                // Send SET_SYNC_FRAME to rewind ring to app's position
                // Then re-poll device status to get corrected unsyncedFrames count
                savedAppSyncFrameCount = null
                savedAppSyncReboots = null
                preDisconnectSyncFrameCount = null
                preDisconnectSyncReboots = null
                syncCorrectionPending = true
                commandQueue.enqueue(QueuedCommand(
                    tag = "DL_SET_SYNC_FRAME (rewind fc=$appFc, rb=$appRbVal)",
                    charId = HpyCharId.CMD_RX,
                    data = CommandBuilder.buildSetSyncFrame(appFc, appRbVal),
                    timeoutMs = config.commandTimeoutMs,
                    completionType = CompletionType.ON_NOTIFICATION,
                ))
                return  // Wait for SET_SYNC_FRAME response, then re-poll status
            }
        }

        // Clear saved positions — not needed after this point
        savedAppSyncFrameCount = null
        savedAppSyncReboots = null
        preDisconnectSyncFrameCount = null
        preDisconnectSyncReboots = null

        startDownloadSessionWithStatus(status)
    }

    private fun startDownloadSessionWithStatus(status: DeviceStatusData) {
        log("L2CAP check: fw='${deviceInfo.fwVersion}', supportsL2cap=${deviceInfo.supportsL2capDownload}, preferL2cap=${config.preferL2capDownload}")
        val useL2cap = deviceInfo.supportsL2capDownload && config.preferL2capDownload
        val controller = DownloadController(
            connId = connId,
            batchSize = config.downloadBatchSize,
            maxRetries = config.downloadMaxRetries,
            supportsL2cap = useL2cap,
            cumulativeFramesOffset = cumulativeFramesDownloaded,
            cumulativeTotalOffset = cumulativeFramesTotal,
            onFrameEmit = { frameData -> emitEvent(HpyEvent.DownloadFrame(connId, frameData)) },
            previousSyncFrameCount = lastCommittedSyncFrameCount,
            previousSyncReboots = lastCommittedSyncReboots,
        )
        downloadController = controller
        controller.lastRssi = lastRssi
        downloadEnabled = true
        lastDownloadStartTime = timeSource.getUtcTimeSeconds()
        transition(HpyConnectionState.DOWNLOADING)
        resetDownloadStallTimer()

        log("Download starting: ${status.unsyncedFrames} frames, transport=${if (useL2cap) "L2CAP" else "GATT"}")

        val action = controller.startSession(
            syncFrameCount = status.syncFrameCount,
            syncFrameReboots = status.syncFrameReboots,
            unsyncedFrames = status.unsyncedFrames,
        )
        handleDownloadAction(action)
    }

    private fun startDownloadFailsafeTimer() {
        downloadFailsafeJob?.cancel()
        val delayMs = if (firmwareTier == FirmwareTier.TIER_1) {
            minOf(config.downloadFailsafeIntervalMs, 10L * 60 * 1000)
        } else {
            config.downloadFailsafeIntervalMs
        }
        downloadFailsafeJob = scope.launch {
            delay(delayMs)
            if (downloadEnabled && (state == HpyConnectionState.WAITING || state == HpyConnectionState.READY)) {
                val minutes = delayMs / 60000
                log("Download failsafe timer fired (${minutes}min) — polling device status")
                downloadPendingStatusPoll = true
                commandQueue.enqueue(QueuedCommand(
                    tag = "DL_FAILSAFE_STATUS",
                    charId = HpyCharId.CMD_RX,
                    data = CommandBuilder.buildGetDeviceStatus(),
                    timeoutMs = config.commandTimeoutMs,
                    completionType = CompletionType.ON_NOTIFICATION,
                ))
            }
        }
    }

    private fun handleDownloadCommandResponse(cmdByte: Byte, value: ByteArray) {
        val controller = downloadController ?: return
        commandQueue.signalDone()
        val action = controller.onCommandResponse(cmdByte, value)
        handleDownloadAction(action)
    }

    fun onL2capConnected() {
        l2capConnectTimeoutJob?.cancel()
        l2capConnectTimeoutJob = null
        val controller = downloadController ?: return
        log("L2CAP socket connected")
        val action = controller.onL2capConnected()
        handleDownloadAction(action)
    }

    fun onL2capFrame(frameData: ByteArray) {
        resetDownloadStallTimer()
        val controller = downloadController ?: return
        controller.onFrameData(frameData)
        // Increment batchFramesReceived BEFORE emitting the frame to FrameWriter,
        // so that onDisconnected() captures the correct count if a disconnect races in.
        val action = controller.onFrameReceived()
        emitEvent(HpyEvent.DownloadFrame(connId, frameData))
        handleDownloadAction(action)
    }

    fun onL2capBatchComplete(framesReceived: Int, crcValid: Boolean) {
        resetDownloadStallTimer()
        val controller = downloadController ?: return
        log("L2CAP batch complete: $framesReceived frames, CRC valid=$crcValid")
        val action = controller.onL2capBatchComplete(framesReceived, crcValid)
        handleDownloadAction(action)
    }

    fun onL2capCrcTimeout(framesReceived: Int) {
        val rssiStr = lastRssi?.let { ", RSSI=$it" } ?: ""
        log("CRC timeout after ${config.l2capCrcTimeoutMs / 1000}s$rssiStr")
        abortDownloadSession("CRC timeout recovery")
    }

    fun onL2capError(message: String) {
        l2capConnectTimeoutJob?.cancel()
        l2capConnectTimeoutJob = null
        val controller = downloadController ?: return
        log("L2CAP error: $message")
        val action = controller.onL2capError(message)
        handleDownloadAction(action)
    }

    private fun handleDownloadAction(action: DownloadAction) {
        when (action) {
            is DownloadAction.EnqueueCommand -> commandQueue.enqueue(action.cmd)
            is DownloadAction.OpenL2cap -> {
                shim.l2capOpen(connId, action.psm)
                l2capConnectTimeoutJob?.cancel()
                l2capConnectTimeoutJob = scope.launch {
                    delay(config.l2capConnectTimeoutMs)
                    log("L2CAP connect timeout (${config.l2capConnectTimeoutMs}ms) — falling back to GATT")
                    onL2capError("L2CAP connect timeout")
                }
            }
            is DownloadAction.StartL2capReceive -> shim.l2capStartReceiving(connId, action.expectedFrames, config.l2capCrcTimeoutMs)
            is DownloadAction.CloseL2cap -> shim.l2capClose(connId)
            is DownloadAction.EmitEvent -> emitEvent(action.event)
            is DownloadAction.SessionComplete -> {
                downloadStallJob?.cancel()
                downloadStallJob = null
                downloadController?.let { ctrl ->
                    cumulativeFramesDownloaded += ctrl.sessionFramesDownloaded
                    cumulativeFramesTotal += ctrl.sessionFramesToDownload
                    lastCommittedSyncFrameCount = ctrl.currentSyncFrameCount
                    lastCommittedSyncReboots = ctrl.currentSyncReboots
                }
                downloadController = null
                if (downloadEnabled) {
                    log("Download session complete -> WAITING")
                    transition(HpyConnectionState.WAITING)
                    startDownloadFailsafeTimer()
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
        if (cmd.tag == "DL_CONFIGURE_L2CAP_OPEN") {
            val rssiStr = lastRssi?.let { "$it dBm" } ?: "unknown"
            log("RSSI: $rssiStr")
        }
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
