package com.happyhealth.bleplatform.internal.download

import com.happyhealth.bleplatform.internal.command.readUInt32

internal data class FrameAnomaly(
    val frameIndex: Int,
    val expectedCount: UInt,
    val actualCount: UInt,
    val expectedReboots: UInt,
    val actualReboots: UInt,
)

internal class FrameContiguityTracker {
    private var expectedFrameCount: UInt = 0u
    private var expectedReboots: UInt = 0u
    private var checkpointFrameCount: UInt = 0u
    private var checkpointReboots: UInt = 0u
    private val batchAnomalies = mutableListOf<FrameAnomaly>()
    private var batchFrameIndex: Int = 0

    fun setBaseline(syncFrameCount: UInt, syncFrameReboots: UInt) {
        expectedFrameCount = syncFrameCount
        expectedReboots = syncFrameReboots
    }

    fun saveCheckpoint() {
        checkpointFrameCount = expectedFrameCount
        checkpointReboots = expectedReboots
        batchAnomalies.clear()
        batchFrameIndex = 0
    }

    fun restoreCheckpoint() {
        expectedFrameCount = checkpointFrameCount
        expectedReboots = checkpointReboots
        batchAnomalies.clear()
        batchFrameIndex = 0
    }

    fun checkFrame(frameData: ByteArray) {
        if (frameData.size < 42) return  // need bytes [30-33] and [38-41]

        val actualCount = readUInt32(frameData, 30)
        val actualReboots = readUInt32(frameData, 38)

        if (actualReboots != expectedReboots) {
            // Reboot boundary — re-sync expected values, not an anomaly
        } else if (actualCount != expectedFrameCount) {
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

    fun commitBatch(): List<FrameAnomaly> {
        val result = batchAnomalies.toList()
        batchAnomalies.clear()
        batchFrameIndex = 0
        return result
    }
}
