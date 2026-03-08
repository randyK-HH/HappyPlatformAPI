package com.happyhealth.bleplatform.internal.download

import com.happyhealth.bleplatform.internal.command.readUInt32

internal data class FrameAnomaly(
    val frameIndex: Int,
    val expectedCount: UInt,
    val actualCount: UInt,
    val expectedReboots: UInt,
    val actualReboots: UInt,
)

internal data class RebootEvent(
    val frameIndex: Int,        // index within batch
    val oldReboots: UInt,       // rb value before reboot
    val newReboots: UInt,       // rb value after reboot
    val firstFrameCount: UInt,  // fc of first frame after reboot (should be 1)
)

internal data class BatchResult(
    val anomalies: List<FrameAnomaly>,
    val reboots: List<RebootEvent>,
    val lastFrameCount: UInt,   // fc of last frame in batch
    val lastReboots: UInt,      // rb of last frame in batch
)

internal class FrameContiguityTracker {
    private var expectedFrameCount: UInt = 0u
    private var expectedReboots: UInt = 0u
    val lastFrameCount: UInt get() = if (expectedFrameCount > 0u) expectedFrameCount - 1u else 0u
    private var checkpointFrameCount: UInt = 0u
    private var checkpointReboots: UInt = 0u
    private val batchAnomalies = mutableListOf<FrameAnomaly>()
    private val batchReboots = mutableListOf<RebootEvent>()
    private var batchFrameIndex: Int = 0

    fun setBaseline(syncFrameCount: UInt, syncFrameReboots: UInt) {
        // Sync frame is the last frame already read; first downloaded frame = fc + 1
        expectedFrameCount = syncFrameCount + 1u
        expectedReboots = syncFrameReboots
    }

    fun saveCheckpoint() {
        checkpointFrameCount = expectedFrameCount
        checkpointReboots = expectedReboots
        batchAnomalies.clear()
        batchReboots.clear()
        batchFrameIndex = 0
    }

    fun restoreCheckpoint() {
        expectedFrameCount = checkpointFrameCount
        expectedReboots = checkpointReboots
        batchAnomalies.clear()
        batchReboots.clear()
        batchFrameIndex = 0
    }

    fun checkFrame(frameData: ByteArray) {
        if (frameData.size < 41) return  // need bytes [29-32] and [37-40]

        val actualCount = readUInt32(frameData, 29)
        val actualReboots = readUInt32(frameData, 37)

        // Track reboot event (separate from anomaly detection)
        if (actualReboots != expectedReboots) {
            batchReboots.add(RebootEvent(batchFrameIndex, expectedReboots, actualReboots, actualCount))
        }

        val isAnomaly = when {
            actualReboots == expectedReboots ->
                // Same boot: frame_count must match expected
                actualCount != expectedFrameCount
            actualReboots == expectedReboots + 1u ->
                // Single reboot: fc must restart at 1
                actualCount != 1u
            else ->
                // rb jumped by more than 1: missed reboots
                true
        }

        if (isAnomaly) {
            batchAnomalies.add(FrameAnomaly(
                frameIndex = batchFrameIndex,
                expectedCount = expectedFrameCount,
                actualCount = actualCount,
                expectedReboots = expectedReboots,
                actualReboots = actualReboots,
            ))
        }

        expectedFrameCount = actualCount + 1u
        expectedReboots = actualReboots
        batchFrameIndex++
    }

    fun commitBatch(): BatchResult {
        val result = BatchResult(
            anomalies = batchAnomalies.toList(),
            reboots = batchReboots.toList(),
            lastFrameCount = expectedFrameCount - 1u,  // fc of last frame seen
            lastReboots = expectedReboots,
        )
        batchAnomalies.clear()
        batchReboots.clear()
        batchFrameIndex = 0
        return result
    }
}
