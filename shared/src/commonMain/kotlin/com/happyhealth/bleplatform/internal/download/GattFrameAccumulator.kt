package com.happyhealth.bleplatform.internal.download

import com.happyhealth.bleplatform.internal.command.CommandId
import com.happyhealth.bleplatform.internal.command.readUInt32
import com.happyhealth.bleplatform.internal.util.CRC_INIT
import com.happyhealth.bleplatform.internal.util.finalizeCrc
import com.happyhealth.bleplatform.internal.util.updateCrc

internal data class GattBatchResult(
    val framesReceived: Int,
    val crcValid: Boolean,
)

internal class GattFrameAccumulator(
    private val onFrame: (ByteArray) -> Unit,
) {
    private val buffer = ByteArray(CommandId.FRAME_SIZE)
    private var bufferOffset = 0
    var framesReceived = 0
        private set
    private var runningCrc: UInt = CRC_INIT

    fun reset() {
        bufferOffset = 0
        framesReceived = 0
        runningCrc = CRC_INIT
    }

    fun onStreamTxData(data: ByteArray) {
        var srcOffset = 0
        var remaining = data.size

        while (remaining > 0) {
            val space = CommandId.FRAME_SIZE - bufferOffset
            val toCopy = minOf(remaining, space)
            data.copyInto(buffer, bufferOffset, srcOffset, srcOffset + toCopy)
            bufferOffset += toCopy
            srcOffset += toCopy
            remaining -= toCopy

            if (bufferOffset >= CommandId.FRAME_SIZE) {
                runningCrc = updateCrc(runningCrc, buffer, 0, CommandId.FRAME_SIZE)
                framesReceived++
                onFrame(buffer.copyOf())
                bufferOffset = 0
            }
        }
    }

    fun validateCrc(value: ByteArray): GattBatchResult {
        // CRC response format: [0x17][CRC32 LE (4 bytes)][0x00]
        if (value.size < 5) {
            return GattBatchResult(framesReceived, false)
        }
        val receivedCrc = readUInt32(value, 1)
        val finalCrc = finalizeCrc(runningCrc)
        val crcValid = (finalCrc == receivedCrc)
        return GattBatchResult(framesReceived, crcValid)
    }
}
