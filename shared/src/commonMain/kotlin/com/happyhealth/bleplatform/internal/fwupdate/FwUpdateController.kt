package com.happyhealth.bleplatform.internal.fwupdate

import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.bleplatform.api.HpyErrorCode
import com.happyhealth.bleplatform.api.HpyEvent
import com.happyhealth.bleplatform.internal.model.HpyCharId

internal sealed class FwUpdateAction {
    data class WriteSuota(val charId: HpyCharId, val data: ByteArray) : FwUpdateAction()
    data class StartL2capStream(
        val psm: Int,
        val imageBytes: ByteArray,
        val blockSize: Int,
        val delayMs: Long,
    ) : FwUpdateAction()
    data class EmitEvent(val event: HpyEvent) : FwUpdateAction()
    data class ScheduleCallback(val delayMs: Long, val tag: String) : FwUpdateAction()
    data object SessionComplete : FwUpdateAction()
    data object NoOp : FwUpdateAction()
    data class Multiple(val actions: List<FwUpdateAction>) : FwUpdateAction()
}

internal class FwUpdateController(
    private val connId: ConnectionId,
    private val imageBytes: ByteArray,
) {
    enum class State {
        IDLE, FLASH, PATCH_LEN, STREAMING, WAIT_CLOSE, FINALIZE, RESET,
        COMPLETE, ERROR, CANCELLED
    }

    private var state = State.IDLE
    val totalBlocks = (imageBytes.size + SUOTA_BLOCK_SIZE - 1) / SUOTA_BLOCK_SIZE
    val currentState: State get() = state

    fun start(): FwUpdateAction {
        state = State.FLASH
        return FwUpdateAction.WriteSuota(HpyCharId.SUOTA_MEM_DEV, SUOTA_CMD_FLASH)
    }

    fun onSuotaStatus(statusCode: Int): FwUpdateAction {
        // Any SUOTA error code in any state is fatal
        if (isSuotaError(statusCode)) {
            state = State.ERROR
            return FwUpdateAction.EmitEvent(
                HpyEvent.Error(connId, HpyErrorCode.FW_TRANSFER_FAIL,
                    "SUOTA error: ${suotaStatusName(statusCode)}")
            )
        }

        return when (state) {
            State.FLASH -> {
                // Ring sends SRV_STARTED(1) or IMG_STARTED(16) — accept any non-error
                state = State.PATCH_LEN
                val patchLenBytes = byteArrayOf(
                    (SUOTA_BLOCK_SIZE and 0xFF).toByte(),
                    ((SUOTA_BLOCK_SIZE shr 8) and 0xFF).toByte(),
                )
                FwUpdateAction.Multiple(listOf(
                    FwUpdateAction.WriteSuota(HpyCharId.SUOTA_PATCH_LEN, patchLenBytes),
                    FwUpdateAction.ScheduleCallback(PATCH_LEN_DELAY_MS, "stream"),
                ))
            }
            State.FINALIZE -> {
                if (statusCode == SUOTA_CMP_OK) {
                    state = State.COMPLETE
                    FwUpdateAction.Multiple(listOf(
                        FwUpdateAction.WriteSuota(HpyCharId.SUOTA_MEM_DEV, SUOTA_CMD_RESET),
                        FwUpdateAction.SessionComplete,
                    ))
                } else {
                    state = State.ERROR
                    FwUpdateAction.EmitEvent(
                        HpyEvent.Error(connId, HpyErrorCode.FW_TRANSFER_FAIL,
                            "SUOTA FINALIZE failed: status=$statusCode")
                    )
                }
            }
            else -> FwUpdateAction.NoOp
        }
    }

    fun onScheduledCallback(tag: String): FwUpdateAction {
        return when (tag) {
            "stream" -> {
                if (state != State.PATCH_LEN) return FwUpdateAction.NoOp
                state = State.STREAMING
                FwUpdateAction.StartL2capStream(
                    L2CAP_SUOTA_PSM, imageBytes, SUOTA_BLOCK_SIZE, L2CAP_STREAM_DELAY_MS,
                )
            }
            "finalize" -> {
                if (state != State.WAIT_CLOSE) return FwUpdateAction.NoOp
                state = State.FINALIZE
                FwUpdateAction.WriteSuota(HpyCharId.SUOTA_MEM_DEV, SUOTA_CMD_FINALIZE)
            }
            else -> FwUpdateAction.NoOp
        }
    }

    fun onStreamProgress(blocksSent: Int, blocksTotal: Int): FwUpdateAction {
        if (state != State.STREAMING) return FwUpdateAction.NoOp
        return FwUpdateAction.EmitEvent(
            HpyEvent.FwUpdateProgress(connId, blocksSent * SUOTA_BLOCK_SIZE, blocksTotal * SUOTA_BLOCK_SIZE)
        )
    }

    fun onStreamComplete(): FwUpdateAction {
        if (state != State.STREAMING) return FwUpdateAction.NoOp
        state = State.WAIT_CLOSE
        return FwUpdateAction.ScheduleCallback(CLOSE_WAIT_MS, "finalize")
    }

    fun onStreamError(msg: String): FwUpdateAction {
        state = State.ERROR
        return FwUpdateAction.EmitEvent(
            HpyEvent.Error(connId, HpyErrorCode.FW_TRANSFER_FAIL, "L2CAP stream error: $msg")
        )
    }

    fun cancel(): FwUpdateAction {
        if (state == State.IDLE || state == State.COMPLETE ||
            state == State.ERROR || state == State.CANCELLED
        ) {
            return FwUpdateAction.NoOp
        }
        state = State.CANCELLED
        return FwUpdateAction.WriteSuota(HpyCharId.SUOTA_MEM_DEV, SUOTA_CMD_ABORT)
    }

    companion object {
        const val SUOTA_BLOCK_SIZE = 240
        const val L2CAP_SUOTA_PSM = 129
        const val L2CAP_STREAM_DELAY_MS = 30L
        const val PATCH_LEN_DELAY_MS = 200L
        const val CLOSE_WAIT_MS = 5000L

        val SUOTA_CMD_FLASH = byteArrayOf(0x00, 0x00, 0x00, 0x13)
        val SUOTA_CMD_FINALIZE = byteArrayOf(0x00, 0x00, 0x00, 0xFE.toByte())
        val SUOTA_CMD_RESET = byteArrayOf(0x00, 0x00, 0x00, 0xFD.toByte())
        val SUOTA_CMD_ABORT = byteArrayOf(0x00, 0x00, 0x00, 0xFF.toByte())

        // SUOTA status codes
        private const val SUOTA_SRV_STARTED = 1
        const val SUOTA_CMP_OK = 2
        private const val SUOTA_SRV_EXIT = 3
        private const val SUOTA_APP_ERROR = 9
        private const val SUOTA_IMG_STARTED = 16

        private fun isSuotaError(code: Int): Boolean = code in SUOTA_SRV_EXIT..SUOTA_APP_ERROR

        private fun suotaStatusName(code: Int): String = when (code) {
            SUOTA_SRV_STARTED -> "SRV_STARTED"
            SUOTA_CMP_OK -> "CMP_OK"
            SUOTA_SRV_EXIT -> "SRV_EXIT"
            4 -> "CRC_ERR"
            5 -> "PATCH_LEN_ERR"
            6 -> "EXT_MEM_WRITE_ERR"
            7 -> "INT_MEM_ERR"
            8 -> "INVAL_MEM_TYPE"
            SUOTA_APP_ERROR -> "APP_ERROR"
            SUOTA_IMG_STARTED -> "IMG_STARTED"
            else -> "UNKNOWN($code)"
        }
    }
}
