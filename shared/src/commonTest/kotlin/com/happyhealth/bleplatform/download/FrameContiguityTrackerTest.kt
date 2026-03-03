package com.happyhealth.bleplatform.download

import com.happyhealth.bleplatform.internal.command.writeUInt32
import com.happyhealth.bleplatform.internal.download.FrameContiguityTracker
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameContiguityTrackerTest {

    private fun buildFrame(frameCount: UInt, reboots: UInt): ByteArray {
        val data = ByteArray(4096)
        writeUInt32(data, 30, frameCount)
        writeUInt32(data, 38, reboots)
        return data
    }

    @Test
    fun contiguousFrames_noAnomalies() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)
        tracker.saveCheckpoint()

        tracker.checkFrame(buildFrame(100u, 5u))
        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(102u, 5u))

        val anomalies = tracker.commitBatch()
        assertEquals(0, anomalies.size)
    }

    @Test
    fun gapDetected_oneAnomaly() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)
        tracker.saveCheckpoint()

        tracker.checkFrame(buildFrame(100u, 5u))
        tracker.checkFrame(buildFrame(102u, 5u)) // skipped 101
        tracker.checkFrame(buildFrame(103u, 5u))

        val anomalies = tracker.commitBatch()
        assertEquals(1, anomalies.size)
        assertEquals(1, anomalies[0].frameIndex)
        assertEquals(101u, anomalies[0].expectedCount)
        assertEquals(102u, anomalies[0].actualCount)
        assertEquals(5u, anomalies[0].expectedReboots)
        assertEquals(5u, anomalies[0].actualReboots)
    }

    @Test
    fun rebootBoundary_noAnomaly() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)
        tracker.saveCheckpoint()

        tracker.checkFrame(buildFrame(100u, 5u))
        tracker.checkFrame(buildFrame(0u, 6u)) // reboot boundary

        val anomalies = tracker.commitBatch()
        assertEquals(0, anomalies.size)
    }

    @Test
    fun checkpointRestore_clearsAnomalies() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)
        tracker.saveCheckpoint()

        tracker.checkFrame(buildFrame(100u, 5u))
        tracker.checkFrame(buildFrame(105u, 5u)) // gap
        tracker.checkFrame(buildFrame(106u, 5u))

        // Simulate CRC retry
        tracker.restoreCheckpoint()

        // Re-feed same frames as if retried
        tracker.checkFrame(buildFrame(100u, 5u))
        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(102u, 5u))

        val anomalies = tracker.commitBatch()
        assertEquals(0, anomalies.size)
    }

    @Test
    fun crossBatchContinuity_noGap() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)

        // Batch 1: frames 100-103
        tracker.saveCheckpoint()
        tracker.checkFrame(buildFrame(100u, 5u))
        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(102u, 5u))
        tracker.checkFrame(buildFrame(103u, 5u))
        val batch1 = tracker.commitBatch()
        assertEquals(0, batch1.size)

        // Batch 2: frames 104-107 (contiguous with batch 1)
        tracker.saveCheckpoint()
        tracker.checkFrame(buildFrame(104u, 5u))
        tracker.checkFrame(buildFrame(105u, 5u))
        tracker.checkFrame(buildFrame(106u, 5u))
        tracker.checkFrame(buildFrame(107u, 5u))
        val batch2 = tracker.commitBatch()
        assertEquals(0, batch2.size)
    }

    @Test
    fun crossBatchContinuity_withGap() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)

        // Batch 1: frames 100-103
        tracker.saveCheckpoint()
        tracker.checkFrame(buildFrame(100u, 5u))
        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(102u, 5u))
        tracker.checkFrame(buildFrame(103u, 5u))
        tracker.commitBatch()

        // Batch 2: starts at 105, skipping 104
        tracker.saveCheckpoint()
        tracker.checkFrame(buildFrame(105u, 5u)) // gap at frame[0]
        tracker.checkFrame(buildFrame(106u, 5u))
        tracker.checkFrame(buildFrame(107u, 5u))
        tracker.checkFrame(buildFrame(108u, 5u))
        val batch2 = tracker.commitBatch()
        assertEquals(1, batch2.size)
        assertEquals(0, batch2[0].frameIndex)
        assertEquals(104u, batch2[0].expectedCount)
        assertEquals(105u, batch2[0].actualCount)
    }
}
