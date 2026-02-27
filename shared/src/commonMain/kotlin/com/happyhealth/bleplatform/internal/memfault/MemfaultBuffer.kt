package com.happyhealth.bleplatform.internal.memfault

data class MemfaultChunkDescriptor(
    val connectSeq: UShort,
    val offset: Int,
    val length: Int,
    val crcValid: Boolean,
)

class MemfaultBuffer(private val bufferSize: Int = 131072) {

    private val buffer = ByteArray(bufferSize)
    private var writeOffset = 0
    private val chunks = mutableListOf<MemfaultChunkDescriptor>()
    private val uploadedChunks = mutableSetOf<MemfaultChunkDescriptor>()
    private var connectSeq: UShort = 0u

    fun incrementConnectSeq() {
        connectSeq++
    }

    fun writeChunk(data: ByteArray, crcValid: Boolean): MemfaultChunkDescriptor? {
        if (data.isEmpty()) return null
        if (!canFitChunk(data.size)) return null

        // Invalidate any existing chunks that overlap with the new write region
        val newStart = writeOffset
        val newEnd = newStart + data.size
        chunks.removeAll { chunk ->
            val overlaps = rangesOverlap(newStart, newEnd, chunk.offset, chunk.offset + chunk.length)
            if (overlaps) uploadedChunks.remove(chunk)
            overlaps
        }

        // Evict oldest chunks if we exceed the max descriptor count
        while (chunks.size >= 16) {
            val evicted = chunks.removeAt(0)
            uploadedChunks.remove(evicted)
        }

        // Copy data with wrap-around
        val firstPart = minOf(data.size, bufferSize - writeOffset)
        data.copyInto(buffer, writeOffset, 0, firstPart)
        if (firstPart < data.size) {
            data.copyInto(buffer, 0, firstPart, data.size)
        }

        // Record descriptor
        val chunk = MemfaultChunkDescriptor(
            connectSeq = connectSeq,
            offset = writeOffset,
            length = data.size,
            crcValid = crcValid,
        )
        chunks.add(chunk)

        // Advance write offset with wrap
        writeOffset = (writeOffset + data.size) % bufferSize
        return chunk
    }

    fun canFitChunk(length: Int): Boolean = length <= bufferSize

    fun getChunks(): List<MemfaultChunkDescriptor> = chunks.toList()

    fun getUnuploadedChunks(): List<MemfaultChunkDescriptor> =
        chunks.filter { it !in uploadedChunks }

    fun markUploaded(descriptors: List<MemfaultChunkDescriptor>) {
        uploadedChunks.addAll(descriptors)
    }

    fun readChunkData(chunk: MemfaultChunkDescriptor): ByteArray {
        val result = ByteArray(chunk.length)
        val firstPart = minOf(chunk.length, bufferSize - chunk.offset)
        buffer.copyInto(result, 0, chunk.offset, chunk.offset + firstPart)
        if (firstPart < chunk.length) {
            buffer.copyInto(result, firstPart, 0, chunk.length - firstPart)
        }
        return result
    }

    private fun rangesOverlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean {
        // Handle circular ranges: a range wraps if end > bufferSize
        val aWraps = aEnd > bufferSize
        val bWraps = bEnd > bufferSize

        return when {
            !aWraps && !bWraps -> aStart < bEnd && bStart < aEnd
            aWraps && !bWraps -> bStart < (aEnd % bufferSize) || bEnd > aStart
            !aWraps && bWraps -> aStart < (bEnd % bufferSize) || aEnd > bStart
            else -> true // both wrap — guaranteed overlap
        }
    }
}
