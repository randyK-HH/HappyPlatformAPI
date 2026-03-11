package com.happyhealth.bleplatform.internal.download

import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.bleplatform.api.HpyErrorCode
import com.happyhealth.bleplatform.api.HpyEvent
import com.happyhealth.bleplatform.internal.command.CommandBuilder
import com.happyhealth.bleplatform.internal.command.CommandId
import com.happyhealth.bleplatform.internal.command.CommandId.UNRECOGNIZED_RESPONSE
import com.happyhealth.bleplatform.internal.command.ResponseParser
import com.happyhealth.bleplatform.internal.connection.CompletionType
import com.happyhealth.bleplatform.internal.connection.QueuedCommand
import com.happyhealth.bleplatform.internal.model.HpyCharId

internal sealed class DownloadAction {
    data class EnqueueCommand(val cmd: QueuedCommand) : DownloadAction()
    data class OpenL2cap(val psm: Int) : DownloadAction()
    data class StartL2capReceive(val expectedFrames: Int) : DownloadAction()
    data object CloseL2cap : DownloadAction()
    data class EmitEvent(val event: HpyEvent) : DownloadAction()
    data object SessionComplete : DownloadAction()
    data class Multiple(val actions: List<DownloadAction>) : DownloadAction()
    data object NoOp : DownloadAction()
}

internal enum class DownloadPhase {
    IDLE,
    CONFIGURE_L2CAP_OPEN,
    WAITING_L2CAP_SOCKET,
    REQUESTING_L2CAP_BATCH,
    RECEIVING_L2CAP,
    RECEIVING_GATT,
    CONFIGURE_L2CAP_CLOSE,
    DONE;

    fun isL2capPhase(): Boolean = this in L2CAP_PHASES

    companion object {
        private val L2CAP_PHASES = setOf(
            CONFIGURE_L2CAP_OPEN, WAITING_L2CAP_SOCKET,
            REQUESTING_L2CAP_BATCH, RECEIVING_L2CAP,
        )
    }
}

internal class DownloadController(
    private val connId: ConnectionId,
    private val batchSize: Int,
    private val maxRetries: Int,
    private val supportsL2cap: Boolean,
    private val l2capClockByte: Byte = 0x01,
    private val cumulativeFramesOffset: Int = 0,
    private val cumulativeTotalOffset: Int = 0,
    private val onFrameEmit: ((ByteArray) -> Unit)? = null,
    private val previousSyncFrameCount: UInt? = null,
    private val previousSyncReboots: UInt? = null,
) {
    var phase: DownloadPhase = DownloadPhase.IDLE
        private set

    private var syncFrameCount: UInt = 0u
    private var syncFrameReboots: UInt = 0u
    private var totalFramesToDownload: Int = 0
    private var totalFramesDownloaded: Int = 0
    private var batchFramesExpected: Int = 0
    var batchFramesReceived: Int = 0
        private set
    private var batchRetryCount: Int = 0
    private var usingL2cap: Boolean = false
    val transportString: String get() = if (usingL2cap) "L2CAP" else "GATT"

    /** Session-level totals exposed for cumulative tracking by ConnectionSlot. */
    val sessionFramesDownloaded: Int get() = totalFramesDownloaded
    val sessionFramesToDownload: Int get() = totalFramesToDownload

    /** Sync position exposed for reconnect accounting by ConnectionSlot. */
    val currentSyncFrameCount: UInt get() = syncFrameCount
    val currentSyncReboots: UInt get() = syncFrameReboots

    private val contiguityTracker = FrameContiguityTracker()
    var lastRssi: Int? = null

    private var gattAccumulator: GattFrameAccumulator? = null
    private var crossSessionNcfAction: DownloadAction? = null

    fun startSession(
        syncFrameCount: UInt,
        syncFrameReboots: UInt,
        unsyncedFrames: Int,
    ): DownloadAction {
        this.syncFrameCount = syncFrameCount
        this.syncFrameReboots = syncFrameReboots
        this.totalFramesToDownload = unsyncedFrames
        this.totalFramesDownloaded = 0
        this.batchRetryCount = 0

        // Cross-session contiguity check: compare previous session's end
        // with this session's start. If there's a gap, emit NCF event.
        if (previousSyncFrameCount != null) {
            val prevRb = previousSyncReboots ?: 0u
            if (prevRb == syncFrameReboots && previousSyncFrameCount != syncFrameCount) {
                val gap = if (syncFrameCount > previousSyncFrameCount) {
                    (syncFrameCount - previousSyncFrameCount).toInt()
                } else 0
                crossSessionNcfAction = DownloadAction.EmitEvent(HpyEvent.Error(connId, HpyErrorCode.NCF,
                    "Cross-session NCF: previous session ended at fc=$previousSyncFrameCount, " +
                    "new session starts at fc=$syncFrameCount (gap=$gap frames, rb=$syncFrameReboots)"))
            }
        }

        contiguityTracker.setBaseline(syncFrameCount, syncFrameReboots)

        // Drain the cross-session NCF action (if any) so it's emitted once
        val ncfAction = crossSessionNcfAction
        crossSessionNcfAction = null

        if (unsyncedFrames <= 0) {
            phase = DownloadPhase.DONE
            val actions = listOfNotNull(ncfAction) + listOf(
                DownloadAction.EmitEvent(HpyEvent.DownloadComplete(connId, cumulativeFramesOffset, sessionFrames = 0)),
                DownloadAction.SessionComplete,
            )
            return DownloadAction.Multiple(actions)
        }

        val downloadAction = if (supportsL2cap) {
            startL2capPath()
        } else {
            startGattPath()
        }
        return if (ncfAction != null) {
            DownloadAction.Multiple(listOf(ncfAction, downloadAction))
        } else {
            downloadAction
        }
    }

    fun onFrameData(frameData: ByteArray) {
        contiguityTracker.checkFrame(frameData)
    }

    fun onCommandResponse(cmdByte: Byte, value: ByteArray): DownloadAction {
        // If the ring doesn't recognize a download command, fall back to GATT
        if (cmdByte == UNRECOGNIZED_RESPONSE && phase.isL2capPhase()) {
            return onL2capError("Command unrecognized by ring — falling back to GATT")
        }

        return when (phase) {
            DownloadPhase.CONFIGURE_L2CAP_OPEN -> handleConfigureL2capOpenResponse(cmdByte, value)
            DownloadPhase.REQUESTING_L2CAP_BATCH -> handleGetFramesL2capResponse(cmdByte, value)
            DownloadPhase.RECEIVING_GATT -> {
                // Ring sends a single CMD_TX response [0x17, CRC32_LE, status] after all
                // frame data has been delivered on FRAME_TX.
                if (cmdByte == CommandId.GET_FRAMES) {
                    val accumulator = gattAccumulator ?: return DownloadAction.NoOp
                    val result = accumulator.validateCrc(value)
                    return onGattBatchComplete(result.framesReceived, result.crcValid)
                }
                DownloadAction.NoOp
            }
            DownloadPhase.CONFIGURE_L2CAP_CLOSE -> handleConfigureL2capCloseResponse(cmdByte, value)
            else -> DownloadAction.NoOp
        }
    }

    fun onL2capConnected(): DownloadAction {
        if (phase != DownloadPhase.WAITING_L2CAP_SOCKET) return DownloadAction.NoOp
        usingL2cap = true
        return requestNextL2capBatch()
    }

    fun onL2capBatchComplete(framesReceived: Int, crcValid: Boolean): DownloadAction {
        // Batch may complete before GET_FRAMES_L2CAP response arrives on GATT,
        // so accept both REQUESTING and RECEIVING phases.
        if (phase != DownloadPhase.RECEIVING_L2CAP && phase != DownloadPhase.REQUESTING_L2CAP_BATCH) {
            return DownloadAction.NoOp
        }

        if (!crcValid && batchRetryCount < maxRetries) {
            batchRetryCount++
            contiguityTracker.restoreCheckpoint()
            return DownloadAction.Multiple(listOf(
                DownloadAction.EmitEvent(HpyEvent.Log(connId,
                    "Batch CRC failed (L2CAP) — retry $batchRetryCount/$maxRetries, fc=$syncFrameCount, expected=$batchFramesExpected frames")),
                requestNextL2capBatch(),
            ))
        }

        val batchResult = contiguityTracker.commitBatch()
        val retries = batchRetryCount
        totalFramesDownloaded += framesReceived
        // Use actual position from frame headers instead of blind arithmetic
        syncFrameCount = batchResult.lastFrameCount
        syncFrameReboots = batchResult.lastReboots
        batchRetryCount = 0

        val crcFailEvent = if (!crcValid) {
            listOf(DownloadAction.EmitEvent(HpyEvent.Error(connId, HpyErrorCode.DOWNLOAD_CRC_FAIL,
                "Batch accepted with bad CRC after $retries retries ($framesReceived frames at fc=$syncFrameCount, L2CAP)")))
        } else emptyList()

        val rebootActions = batchResult.reboots.flatMap { reboot ->
            val actions = mutableListOf<DownloadAction>(
                DownloadAction.EmitEvent(HpyEvent.Log(connId,
                    "Reboot detected (rb:${reboot.oldReboots}->${reboot.newReboots}). [fc=${reboot.firstFrameCount}]"))
            )
            if (reboot.firstFrameCount != 1u) {
                actions.add(DownloadAction.EmitEvent(HpyEvent.Error(connId, HpyErrorCode.NCF,
                    "First frame after reboot has fc=${reboot.firstFrameCount} (expected 1), rb=${reboot.newReboots}")))
            }
            actions
        }

        val anomalyActions = batchResult.anomalies.map { a ->
            DownloadAction.EmitEvent(HpyEvent.Log(connId,
                formatNcfMessage(a)))
        }
        val batchEvent = DownloadAction.EmitEvent(
            HpyEvent.DownloadBatch(connId, framesReceived, cumulativeFramesOffset + totalFramesDownloaded, crcValid,
                sessionFramesDownloaded = totalFramesDownloaded,
                transport = transportString,
                rssi = lastRssi,
                retryCount = retries,
                ncfCount = batchResult.anomalies.size)
        )
        val remaining = totalFramesToDownload - totalFramesDownloaded
        return if (remaining <= 0) {
            phase = DownloadPhase.CONFIGURE_L2CAP_CLOSE
            DownloadAction.Multiple(crcFailEvent + rebootActions + anomalyActions + listOf(
                batchEvent,
                closeL2capCommand(),
            ))
        } else {
            DownloadAction.Multiple(crcFailEvent + rebootActions + anomalyActions + listOf(
                batchEvent,
                requestNextL2capBatch(),
            ))
        }
    }

    fun onFrameReceived(): DownloadAction {
        batchFramesReceived++
        return DownloadAction.EmitEvent(
            HpyEvent.DownloadProgress(
                connId, cumulativeFramesOffset + totalFramesDownloaded + batchFramesReceived,
                cumulativeTotalOffset + totalFramesToDownload, transportString,
                sessionFramesDownloaded = totalFramesDownloaded + batchFramesReceived,
                sessionFramesTotal = totalFramesToDownload,
                currentFc = contiguityTracker.lastFrameCount.toInt(),
            )
        )
    }

    fun onL2capError(message: String): DownloadAction {
        // Fall back to GATT for remaining frames
        usingL2cap = false
        val remaining = totalFramesToDownload - totalFramesDownloaded
        return DownloadAction.Multiple(listOf(
            DownloadAction.EmitEvent(HpyEvent.Log(connId,
                "L2CAP fallback to GATT: $message ($remaining frames remaining)")),
            startGattPath(),
        ))
    }

    fun onStreamTxData(data: ByteArray): DownloadAction {
        if (phase != DownloadPhase.RECEIVING_GATT) return DownloadAction.NoOp
        val accumulator = gattAccumulator ?: return DownloadAction.NoOp
        val prevFrames = accumulator.framesReceived
        accumulator.onStreamTxData(data)
        // Emit progress for each new frame completed in the accumulator
        val newFrames = accumulator.framesReceived - prevFrames
        if (newFrames > 0) {
            val actions = (0 until newFrames).map { onFrameReceived() }
            return if (actions.size == 1) actions[0] else DownloadAction.Multiple(actions)
        }
        return DownloadAction.NoOp
    }

    fun onGattBatchComplete(framesReceived: Int, crcValid: Boolean): DownloadAction {
        if (phase != DownloadPhase.RECEIVING_GATT) return DownloadAction.NoOp

        if (!crcValid && batchRetryCount < maxRetries) {
            batchRetryCount++
            contiguityTracker.restoreCheckpoint()
            return DownloadAction.Multiple(listOf(
                DownloadAction.EmitEvent(HpyEvent.Log(connId,
                    "Batch CRC failed (GATT) — retry $batchRetryCount/$maxRetries, fc=$syncFrameCount, expected=$batchFramesExpected frames")),
                requestNextGattBatch(),
            ))
        }

        val batchResult = contiguityTracker.commitBatch()
        val retries = batchRetryCount
        totalFramesDownloaded += framesReceived
        // Use actual position from frame headers instead of blind arithmetic
        syncFrameCount = batchResult.lastFrameCount
        syncFrameReboots = batchResult.lastReboots
        batchRetryCount = 0

        val crcFailEvent = if (!crcValid) {
            listOf(DownloadAction.EmitEvent(HpyEvent.Error(connId, HpyErrorCode.DOWNLOAD_CRC_FAIL,
                "Batch accepted with bad CRC after $retries retries ($framesReceived frames at fc=$syncFrameCount, GATT)")))
        } else emptyList()

        val rebootActions = batchResult.reboots.flatMap { reboot ->
            val actions = mutableListOf<DownloadAction>(
                DownloadAction.EmitEvent(HpyEvent.Log(connId,
                    "Reboot detected (rb:${reboot.oldReboots}->${reboot.newReboots}). [fc=${reboot.firstFrameCount}]"))
            )
            if (reboot.firstFrameCount != 1u) {
                actions.add(DownloadAction.EmitEvent(HpyEvent.Error(connId, HpyErrorCode.NCF,
                    "First frame after reboot has fc=${reboot.firstFrameCount} (expected 1), rb=${reboot.newReboots}")))
            }
            actions
        }

        val anomalyActions = batchResult.anomalies.map { a ->
            DownloadAction.EmitEvent(HpyEvent.Log(connId,
                formatNcfMessage(a)))
        }
        val batchEvent = DownloadAction.EmitEvent(
            HpyEvent.DownloadBatch(connId, framesReceived, cumulativeFramesOffset + totalFramesDownloaded, crcValid,
                sessionFramesDownloaded = totalFramesDownloaded,
                transport = transportString,
                rssi = lastRssi,
                retryCount = retries,
                ncfCount = batchResult.anomalies.size)
        )
        val remaining = totalFramesToDownload - totalFramesDownloaded
        return if (remaining <= 0) {
            finishSession(crcFailEvent + rebootActions + anomalyActions + listOf(batchEvent))
        } else {
            DownloadAction.Multiple(crcFailEvent + rebootActions + anomalyActions + listOf(
                batchEvent,
                requestNextGattBatch(),
            ))
        }
    }

    // ---- Internal: L2CAP path ----

    private fun startL2capPath(): DownloadAction {
        phase = DownloadPhase.CONFIGURE_L2CAP_OPEN
        return DownloadAction.EnqueueCommand(QueuedCommand(
            tag = "DL_CONFIGURE_L2CAP_OPEN",
            charId = HpyCharId.CMD_RX,
            data = CommandBuilder.buildConfigureL2cap(listen = true, clockByte = l2capClockByte),
            timeoutMs = 5000L,
            completionType = CompletionType.ON_NOTIFICATION,
        ))
    }

    private fun handleConfigureL2capOpenResponse(cmdByte: Byte, value: ByteArray): DownloadAction {
        if (cmdByte != CommandId.CONFIGURE_L2CAP) return DownloadAction.NoOp
        val resp = ResponseParser.parseConfigureL2capResponse(value)
        if (resp == null || resp.status != 0) {
            // L2CAP configuration failed, fall back to GATT
            val statusStr = resp?.status?.toString() ?: "null"
            return DownloadAction.Multiple(listOf(
                DownloadAction.EmitEvent(HpyEvent.Log(connId,
                    "L2CAP config rejected (status=$statusStr) — falling back to GATT")),
                startGattPath(),
            ))
        }
        phase = DownloadPhase.WAITING_L2CAP_SOCKET
        return DownloadAction.OpenL2cap(L2CAP_PSM)
    }

    private fun requestNextL2capBatch(): DownloadAction {
        contiguityTracker.saveCheckpoint()
        val remaining = totalFramesToDownload - totalFramesDownloaded
        batchFramesExpected = minOf(remaining, batchSize)
        batchFramesReceived = 0
        phase = DownloadPhase.REQUESTING_L2CAP_BATCH
        // Start L2CAP receiver BEFORE sending the command (matching Data_Downloader pattern).
        // The ring begins transmitting immediately after accepting the command, so the
        // receiver must already be listening on the L2CAP socket.
        return DownloadAction.Multiple(listOf(
            DownloadAction.StartL2capReceive(batchFramesExpected),
            DownloadAction.EnqueueCommand(QueuedCommand(
                tag = "DL_GET_FRAMES_L2CAP (sync=boot${syncFrameReboots}:frame${syncFrameCount}, lim=$batchFramesExpected)",
                charId = HpyCharId.CMD_RX,
                data = CommandBuilder.buildGetFramesL2cap(syncFrameCount, syncFrameReboots, batchFramesExpected),
                timeoutMs = 5000L,
                completionType = CompletionType.ON_NOTIFICATION,
            )),
        ))
    }

    private fun handleGetFramesL2capResponse(cmdByte: Byte, value: ByteArray): DownloadAction {
        if (cmdByte != CommandId.GET_FRAMES_L2CAP) return DownloadAction.NoOp
        // Response byte[1] is L2CAP channel status (HpyBleL2CapChStatus_t):
        //   0=DISCONNECTED, 1=LISTENING, 2=CONNECTED, 9=UNINITIALIZED
        // The Data_Downloader ignores this status — the L2CAP receiver is already listening
        // and will report errors/timeouts if the transfer fails.
        phase = DownloadPhase.RECEIVING_L2CAP
        return DownloadAction.NoOp
    }

    private fun closeL2capCommand(): DownloadAction {
        return DownloadAction.Multiple(listOf(
            DownloadAction.CloseL2cap,
            DownloadAction.EnqueueCommand(QueuedCommand(
                tag = "DL_CONFIGURE_L2CAP_CLOSE",
                charId = HpyCharId.CMD_RX,
                data = CommandBuilder.buildConfigureL2cap(listen = false),
                timeoutMs = 5000L,
                completionType = CompletionType.ON_NOTIFICATION,
            )),
        ))
    }

    private fun handleConfigureL2capCloseResponse(cmdByte: Byte, value: ByteArray): DownloadAction {
        if (cmdByte != CommandId.CONFIGURE_L2CAP) return DownloadAction.NoOp
        return finishSession(emptyList())
    }

    // ---- Internal: GATT path ----

    private fun startGattPath(): DownloadAction {
        gattAccumulator = GattFrameAccumulator(
            onFrame = { frameData ->
                contiguityTracker.checkFrame(frameData)
                onFrameEmit?.invoke(frameData)
            },
        )
        return requestNextGattBatch()
    }

    private fun requestNextGattBatch(): DownloadAction {
        contiguityTracker.saveCheckpoint()
        val remaining = totalFramesToDownload - totalFramesDownloaded
        batchFramesExpected = minOf(remaining, batchSize)
        batchFramesReceived = 0
        gattAccumulator?.reset()
        // Set RECEIVING_GATT immediately — the ring starts sending STREAM_TX data as soon
        // as it accepts the command, so we must be ready before any response arrives.
        // Use ON_WRITE_ACK so the command queue is freed immediately — the CRC response
        // arrives on FRAME_TX (not CMD_TX), handled separately by onCommandResponse.
        // ON_NOTIFICATION would break because device status notifications on CMD_TX
        // would prematurely signal command completion.
        phase = DownloadPhase.RECEIVING_GATT
        return DownloadAction.EnqueueCommand(QueuedCommand(
            tag = "DL_GET_FRAMES_GATT (sync=boot${syncFrameReboots}:frame${syncFrameCount}, lim=$batchFramesExpected)",
            charId = HpyCharId.CMD_RX,
            data = CommandBuilder.buildGetFramesGatt(syncFrameCount, syncFrameReboots, batchFramesExpected),
            timeoutMs = 30000L,
            completionType = CompletionType.ON_WRITE_ACK,
        ))
    }

    // ---- Internal: Session completion ----

    private fun finishSession(precedingActions: List<DownloadAction>): DownloadAction {
        phase = DownloadPhase.DONE
        val actions = precedingActions + listOf(
            DownloadAction.EmitEvent(HpyEvent.DownloadComplete(connId, cumulativeFramesOffset + totalFramesDownloaded,
                sessionFrames = totalFramesDownloaded)),
            DownloadAction.SessionComplete,
        )
        return DownloadAction.Multiple(actions)
    }

    private fun formatNcfMessage(a: FrameAnomaly): String {
        return when {
            a.actualReboots != a.expectedReboots && a.actualCount == 1u -> {
                // Reboot gap: rb jumped by more than 1, but fc=1 is correct for a reboot
                val skipped = a.actualReboots.toLong() - a.expectedReboots.toLong() - 1
                "NCF: frame[${a.frameIndex}] reboot gap rb=${a.expectedReboots}\u2192${a.actualReboots}" +
                    " (skipped $skipped boot${if (skipped != 1L) "s" else ""})"
            }
            a.actualReboots != a.expectedReboots -> {
                // Reboot with unexpected fc (not 1)
                "NCF: frame[${a.frameIndex}] reboot rb=${a.expectedReboots}\u2192${a.actualReboots}" +
                    ", fc=${a.actualCount} (expected fc=1)"
            }
            else -> {
                // Same boot, frame gap
                "NCF: frame[${a.frameIndex}] expected fc=${a.expectedCount}, got fc=${a.actualCount}" +
                    " (rb=${a.expectedReboots})"
            }
        }
    }

    companion object {
        private const val L2CAP_PSM = 130
    }
}
