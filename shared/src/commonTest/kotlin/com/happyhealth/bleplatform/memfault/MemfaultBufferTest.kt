package com.happyhealth.bleplatform.memfault

import com.happyhealth.bleplatform.internal.memfault.MemfaultBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemfaultBufferTest {

    @Test
    fun um01_singleChunkWrite() {
        // UM-01: Write a 1200-byte chunk. Verify descriptor offset=0, length=1200, crcValid=true.
        val buffer = MemfaultBuffer(131072)

        val data = ByteArray(1200) { (it % 256).toByte() }
        val chunk = buffer.writeChunk(data, crcValid = true)

        assertNotNull(chunk, "writeChunk should return a descriptor")
        assertEquals(0, chunk.offset, "First chunk offset should be 0")
        assertEquals(1200, chunk.length, "Chunk length should be 1200")
        assertTrue(chunk.crcValid, "CRC should be valid")

        val chunks = buffer.getChunks()
        assertEquals(1, chunks.size)

        // Verify data can be read back
        val readBack = buffer.readChunkData(chunk)
        assertTrue(readBack.contentEquals(data), "Read-back data should match written data")
    }

    @Test
    fun um02_multipleChunksSequential() {
        // UM-02: Write 3 chunks (1200, 850, 500 bytes). Verify all 3 descriptors. Offsets: 0, 1200, 2050.
        val buffer = MemfaultBuffer(131072)

        val c1 = buffer.writeChunk(ByteArray(1200), true)
        val c2 = buffer.writeChunk(ByteArray(850), true)
        val c3 = buffer.writeChunk(ByteArray(500), true)

        assertNotNull(c1)
        assertNotNull(c2)
        assertNotNull(c3)

        assertEquals(0, c1.offset)
        assertEquals(1200, c2.offset)
        assertEquals(2050, c3.offset)

        assertEquals(1200, c1.length)
        assertEquals(850, c2.length)
        assertEquals(500, c3.length)

        val chunks = buffer.getChunks()
        assertEquals(3, chunks.size)
    }

    @Test
    fun um03_circularWrap() {
        // UM-03: buffer_size=1000, write_offset=900. Write 200-byte chunk. Verify wrap.
        val buffer = MemfaultBuffer(1000)

        // First write 900 bytes to advance write_offset to 900
        buffer.writeChunk(ByteArray(900), true)

        // Now write 200 bytes — should wrap: 100 bytes at 900..1000, 100 at 0..100
        val data = ByteArray(200) { (it + 1).toByte() }
        val chunk = buffer.writeChunk(data, true)

        assertNotNull(chunk)
        assertEquals(900, chunk.offset, "Chunk starts at 900")
        assertEquals(200, chunk.length, "Chunk length is 200")

        // Verify read-back works across the wrap
        val readBack = buffer.readChunkData(chunk)
        assertTrue(readBack.contentEquals(data), "Wrapped data should read back correctly")
    }

    @Test
    fun um04_wrapInvalidation() {
        // UM-04: Buffer_size=1000. Write A(400), B(400), advance to 800, write C(300 wrapping).
        // C wraps from 800..1000 + 0..100. Verify A invalidated. B survives.
        val buffer = MemfaultBuffer(1000)

        val chunkA = buffer.writeChunk(ByteArray(400), true) // offset 0..400
        val chunkB = buffer.writeChunk(ByteArray(400), true) // offset 400..800

        assertNotNull(chunkA)
        assertNotNull(chunkB)

        // Write C — 300 bytes starting at offset 800 wraps to offset 100
        val chunkC = buffer.writeChunk(ByteArray(300), true) // offset 800..1000 + 0..100

        assertNotNull(chunkC)
        assertEquals(800, chunkC.offset)
        assertEquals(300, chunkC.length)

        // A should be invalidated (overlap with 0..100), B should survive
        val chunks = buffer.getChunks()
        assertEquals(2, chunks.size, "Only B and C should survive")
        assertTrue(chunks.any { it.offset == 400 && it.length == 400 }, "B should survive")
        assertTrue(chunks.any { it.offset == 800 && it.length == 300 }, "C should be present")
    }

    @Test
    fun um05_largeChunkInvalidatesMultiple() {
        // UM-05: Write 5 small chunks (100 bytes each) at offsets 0-499. Then write 400-byte chunk
        // at offset 500 that wraps and overlaps offsets 0-199. Verify chunks at 0 and 100 invalidated.
        val buffer = MemfaultBuffer(700)

        buffer.writeChunk(ByteArray(100), true) // 0..100
        buffer.writeChunk(ByteArray(100), true) // 100..200
        buffer.writeChunk(ByteArray(100), true) // 200..300
        buffer.writeChunk(ByteArray(100), true) // 300..400
        buffer.writeChunk(ByteArray(100), true) // 400..500

        assertEquals(5, buffer.getChunks().size)

        // Write 400 bytes at offset 500 — wraps: 500..700 (200 bytes) + 0..200 (200 bytes)
        buffer.writeChunk(ByteArray(400), true) // offset 500, wraps through 0..200

        val chunks = buffer.getChunks()
        // Chunks at 0 and 100 should be invalidated (overlap with 0..200)
        assertFalse(chunks.any { it.offset == 0 && it.length == 100 }, "Chunk at 0 should be invalidated")
        assertFalse(chunks.any { it.offset == 100 && it.length == 100 }, "Chunk at 100 should be invalidated")
        // Chunks at 200, 300, 400 should survive
        assertTrue(chunks.any { it.offset == 200 && it.length == 100 }, "Chunk at 200 should survive")
        assertTrue(chunks.any { it.offset == 300 && it.length == 100 }, "Chunk at 300 should survive")
        assertTrue(chunks.any { it.offset == 400 && it.length == 100 }, "Chunk at 400 should survive")
        assertTrue(chunks.any { it.offset == 500 && it.length == 400 }, "New chunk should be present")
    }

    @Test
    fun um06_crcMismatchChunkKept() {
        // UM-06: Write chunk with crcValid=false. Verify descriptor has crcValid=false.
        val buffer = MemfaultBuffer(131072)

        val chunk = buffer.writeChunk(ByteArray(500), crcValid = false)

        assertNotNull(chunk)
        assertFalse(chunk.crcValid, "Chunk should have crcValid=false")

        val chunks = buffer.getChunks()
        assertEquals(1, chunks.size)
        assertFalse(chunks[0].crcValid, "Stored chunk should have crcValid=false")
    }

    @Test
    fun um07_descriptorLimitEviction() {
        // UM-07: Write >16 chunks. Verify oldest chunks evicted when descriptor limit reached.
        val buffer = MemfaultBuffer(131072)

        // Write 17 chunks — the 17th should evict the 1st
        for (i in 0 until 17) {
            buffer.writeChunk(ByteArray(100) { i.toByte() }, true)
        }

        val chunks = buffer.getChunks()
        // Max 16 descriptors — first chunk should have been evicted
        assertTrue(chunks.size <= 16, "Should not exceed 16 descriptors")
        // The first chunk (offset=0) should be evicted
        assertFalse(chunks.any { it.offset == 0 }, "First chunk should be evicted")
    }

    @Test
    fun um08_oversizedChunkReturnsNull() {
        // UM-08: Verify canFitChunk / writeChunk returns null for oversized chunk.
        val buffer = MemfaultBuffer(1000)

        assertFalse(buffer.canFitChunk(1001), "1001 bytes should not fit in 1000-byte buffer")
        assertTrue(buffer.canFitChunk(1000), "1000 bytes should fit")

        val result = buffer.writeChunk(ByteArray(1001), true)
        assertNull(result, "writeChunk should return null for oversized chunk")
    }

    @Test
    fun um09_bufferFullBehavior() {
        // UM-09: buffer_size=1000, chunk=1500. Verify null return.
        val buffer = MemfaultBuffer(1000)

        val result = buffer.writeChunk(ByteArray(1500), true)
        assertNull(result, "Chunk larger than buffer should return null")

        // Buffer should still be usable for smaller chunks
        val small = buffer.writeChunk(ByteArray(100), true)
        assertNotNull(small, "Smaller chunk should still work")
    }

    @Test
    fun um10_crossReconnectionAccumulation() {
        // UM-10: incrementConnectSeq between writes. Verify connectSeq in descriptors.
        val buffer = MemfaultBuffer(131072)

        // seq=1 (after first increment)
        buffer.incrementConnectSeq()
        val c1 = buffer.writeChunk(ByteArray(200), true)
        val c2 = buffer.writeChunk(ByteArray(300), true)

        // seq=2 (after second increment)
        buffer.incrementConnectSeq()
        val c3 = buffer.writeChunk(ByteArray(150), true)

        assertNotNull(c1)
        assertNotNull(c2)
        assertNotNull(c3)

        assertEquals(1u.toUShort(), c1.connectSeq, "First chunk should have connectSeq=1")
        assertEquals(1u.toUShort(), c2.connectSeq, "Second chunk should have connectSeq=1")
        assertEquals(2u.toUShort(), c3.connectSeq, "Third chunk should have connectSeq=2")

        // All 3 chunks should be in manifest
        val chunks = buffer.getChunks()
        assertEquals(3, chunks.size, "All 3 chunks should be present")
    }
}
