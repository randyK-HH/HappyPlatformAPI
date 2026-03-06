package com.happyhealth.bleplatform.download

import com.happyhealth.bleplatform.internal.command.CommandId
import com.happyhealth.bleplatform.internal.command.writeUInt32
import com.happyhealth.bleplatform.internal.download.GattFrameAccumulator
import com.happyhealth.bleplatform.internal.util.CRC_INIT
import com.happyhealth.bleplatform.internal.util.finalizeCrc
import com.happyhealth.bleplatform.internal.util.updateCrc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GattFrameAccumulatorTest {

    private fun makeAccumulator(onFrame: (ByteArray) -> Unit = {}): GattFrameAccumulator {
        return GattFrameAccumulator(onFrame)
    }

    /** Feed data in chunks of the given size until totalBytes are sent. */
    private fun feedData(acc: GattFrameAccumulator, totalBytes: Int, chunkSize: Int = 244): ByteArray {
        val allData = ByteArray(totalBytes) { (it % 251).toByte() }
        var offset = 0
        while (offset < totalBytes) {
            val len = minOf(chunkSize, totalBytes - offset)
            acc.onStreamTxData(allData.copyOfRange(offset, offset + len))
            offset += len
        }
        return allData
    }

    /** Build a CRC notification: [0x17][CRC32 LE 4 bytes][trailing byte]. */
    private fun buildCrcNotification(crc: UInt, trailing: Byte = 0x00): ByteArray {
        val buf = ByteArray(6)
        buf[0] = CommandId.GET_FRAMES
        writeUInt32(buf, 1, crc)
        buf[5] = trailing
        return buf
    }

    /** Compute CRC32 over raw data. */
    private fun computeCrc(data: ByteArray): UInt {
        return finalizeCrc(updateCrc(CRC_INIT, data, 0, data.size))
    }

    @Test
    fun ug01_singleFrameReassembly() {
        // UG-01: Feed 5 GATT notifications (244+244+244+244+48 = 1024 per chunk, 4 chunks = 4096 bytes).
        // Verify accumulated frame is exactly 4096 bytes.
        val frames = mutableListOf<ByteArray>()
        val acc = makeAccumulator { frames.add(it) }

        // Feed exactly 4096 bytes in realistic notification sizes
        val chunkSizes = listOf(244, 244, 244, 244, 48) // 1024 bytes per "chunk set"
        val allData = ByteArray(CommandId.FRAME_SIZE) { (it % 251).toByte() }
        var offset = 0
        for (repeat in 0 until 4) { // 4 chunk sets = 4096 bytes
            for (sz in chunkSizes) {
                acc.onStreamTxData(allData.copyOfRange(offset, offset + sz))
                offset += sz
            }
        }

        assertEquals(1, frames.size, "Expected exactly 1 frame callback")
        assertEquals(CommandId.FRAME_SIZE, frames[0].size, "Frame should be 4096 bytes")
        assertEquals(1, acc.framesReceived)
    }

    @Test
    fun ug02_multiFrameAccumulation() {
        // UG-02: Feed 3 complete frames (12288 bytes via 60 notifications). Verify 3 frames ready.
        val frames = mutableListOf<ByteArray>()
        val acc = makeAccumulator { frames.add(it) }

        feedData(acc, 3 * CommandId.FRAME_SIZE)

        assertEquals(3, frames.size, "Expected 3 frame callbacks")
        assertEquals(3, acc.framesReceived)
        frames.forEach { assertEquals(CommandId.FRAME_SIZE, it.size) }
    }

    @Test
    fun ug03_crcValidationCorrect() {
        // UG-03: Feed 2 frames, then correct CRC notification. Verify CRC valid.
        val frames = mutableListOf<ByteArray>()
        val acc = makeAccumulator { frames.add(it) }

        val allData = feedData(acc, 2 * CommandId.FRAME_SIZE)
        assertEquals(2, acc.framesReceived)

        val expectedCrc = computeCrc(allData)
        val crcNotif = buildCrcNotification(expectedCrc)
        val result = acc.validateCrc(crcNotif)

        assertEquals(2, result.framesReceived)
        assertTrue(result.crcValid, "CRC should be valid")
    }

    @Test
    fun ug04_crcValidationIncorrect() {
        // UG-04: Feed 2 frames, then wrong CRC notification. Verify CRC invalid.
        val acc = makeAccumulator()

        feedData(acc, 2 * CommandId.FRAME_SIZE)

        val wrongCrc = 0xDEADBEEFu
        val crcNotif = buildCrcNotification(wrongCrc)
        val result = acc.validateCrc(crcNotif)

        assertEquals(2, result.framesReceived)
        assertFalse(result.crcValid, "CRC should be invalid")
    }

    @Test
    fun ug05_partialNotificationUndersized() {
        // UG-05: Feed an undersized notification mid-frame. Verify accumulator
        // buffers partial data and continues correctly.
        val frames = mutableListOf<ByteArray>()
        val acc = makeAccumulator { frames.add(it) }

        // Feed some data, then a very small notification, then the rest
        val allData = ByteArray(CommandId.FRAME_SIZE) { (it % 251).toByte() }
        acc.onStreamTxData(allData.copyOfRange(0, 244))       // normal
        acc.onStreamTxData(allData.copyOfRange(244, 247))      // undersized (3 bytes)
        acc.onStreamTxData(allData.copyOfRange(247, 491))      // normal
        acc.onStreamTxData(allData.copyOfRange(491, CommandId.FRAME_SIZE)) // rest

        assertEquals(1, frames.size, "Should still produce exactly 1 frame")
        assertEquals(CommandId.FRAME_SIZE, frames[0].size)
        assertTrue(frames[0].contentEquals(allData), "Frame data should match")
    }

    @Test
    fun ug06_frameBoundaryAlignment() {
        // UG-06: Feed 4097 bytes (one frame + 1 byte of next frame). Verify first
        // frame complete and extra byte buffered.
        val frames = mutableListOf<ByteArray>()
        val acc = makeAccumulator { frames.add(it) }

        val allData = ByteArray(CommandId.FRAME_SIZE + 1) { (it % 251).toByte() }
        acc.onStreamTxData(allData)

        assertEquals(1, frames.size, "First frame should be complete")
        assertEquals(CommandId.FRAME_SIZE, frames[0].size)
        assertEquals(1, acc.framesReceived)

        // Feed the rest to complete frame 2
        val remaining = ByteArray(CommandId.FRAME_SIZE - 1) { ((it + CommandId.FRAME_SIZE + 1) % 251).toByte() }
        acc.onStreamTxData(remaining)

        assertEquals(2, frames.size, "Second frame should now be complete")
        assertEquals(2, acc.framesReceived)
    }

    @Test
    fun ug07_emptyBatchZeroFramesBeforeCrc() {
        // UG-07: Feed CRC notification with no preceding frame data. Verify graceful handling.
        val acc = makeAccumulator()

        // CRC of zero bytes: CRC_INIT xor FINAL = 0xFFFFFFFF xor 0xFFFFFFFF = 0x00000000
        val emptyCrc = finalizeCrc(CRC_INIT)
        val crcNotif = buildCrcNotification(emptyCrc)
        val result = acc.validateCrc(crcNotif)

        assertEquals(0, result.framesReceived)
        assertTrue(result.crcValid, "CRC of zero data should match")
    }

    @Test
    fun ug08_maximumBatch128Frames() {
        // UG-08: Feed 128 frames (524288 bytes) followed by CRC. Verify no overflow.
        val frames = mutableListOf<ByteArray>()
        val acc = makeAccumulator { frames.add(it) }

        val totalBytes = 128 * CommandId.FRAME_SIZE
        val allData = feedData(acc, totalBytes)

        assertEquals(128, frames.size, "Expected 128 frames")
        assertEquals(128, acc.framesReceived)

        val expectedCrc = computeCrc(allData)
        val crcNotif = buildCrcNotification(expectedCrc)
        val result = acc.validateCrc(crcNotif)

        assertTrue(result.crcValid)
        assertEquals(128, result.framesReceived)
    }

    @Test
    fun ug09_crcNotificationTrailingByteIgnored() {
        // UG-09: Feed CRC notification with non-zero trailing byte [0x17][CRC0-3][0x42].
        // Verify accumulator ignores the trailing byte and uses only bytes[1-4] for CRC.
        val acc = makeAccumulator()

        val allData = feedData(acc, CommandId.FRAME_SIZE)
        val expectedCrc = computeCrc(allData)

        val crcNotif = buildCrcNotification(expectedCrc, trailing = 0x42)
        val result = acc.validateCrc(crcNotif)

        assertEquals(1, result.framesReceived)
        assertTrue(result.crcValid, "Trailing byte should be ignored in CRC comparison")
    }
}
