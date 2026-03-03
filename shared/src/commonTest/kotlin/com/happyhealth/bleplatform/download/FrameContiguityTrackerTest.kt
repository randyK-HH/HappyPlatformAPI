package com.happyhealth.bleplatform.download

import com.happyhealth.bleplatform.internal.command.writeUInt32
import com.happyhealth.bleplatform.internal.download.FrameContiguityTracker
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameContiguityTrackerTest {

    private fun buildFrame(frameCount: UInt, reboots: UInt): ByteArray {
        val data = ByteArray(4096)
        writeUInt32(data, 29, frameCount)  // 1-based bytes 30-33
        writeUInt32(data, 37, reboots)     // 1-based bytes 38-41
        return data
    }

    @Test
    fun contiguousFrames_noAnomalies() {
        val tracker = FrameContiguityTracker()
        // Sync frame is fc=100 → first downloaded frame will be fc=101
        tracker.setBaseline(100u, 5u)
        tracker.saveCheckpoint()

        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(102u, 5u))
        tracker.checkFrame(buildFrame(103u, 5u))

        val anomalies = tracker.commitBatch()
        assertEquals(0, anomalies.size)
    }

    @Test
    fun gapDetected_oneAnomaly() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)
        tracker.saveCheckpoint()

        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(103u, 5u)) // skipped 102
        tracker.checkFrame(buildFrame(104u, 5u))

        val anomalies = tracker.commitBatch()
        assertEquals(1, anomalies.size)
        assertEquals(1, anomalies[0].frameIndex)
        assertEquals(102u, anomalies[0].expectedCount)
        assertEquals(103u, anomalies[0].actualCount)
        assertEquals(5u, anomalies[0].expectedReboots)
        assertEquals(5u, anomalies[0].actualReboots)
    }

    @Test
    fun rebootBoundary_fcOne_noAnomaly() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)
        tracker.saveCheckpoint()

        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(1u, 6u)) // valid reboot: rb+1, fc=1

        val anomalies = tracker.commitBatch()
        assertEquals(0, anomalies.size)
    }

    @Test
    fun rebootBoundary_fcGreaterThanOne_anomaly() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)
        tracker.saveCheckpoint()

        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(3u, 6u)) // reboot but fc=3, missed frames 1-2

        val anomalies = tracker.commitBatch()
        assertEquals(1, anomalies.size)
        assertEquals(1, anomalies[0].frameIndex)
        assertEquals(6u, anomalies[0].actualReboots)
        assertEquals(3u, anomalies[0].actualCount)
    }

    @Test
    fun rebootJumpMultiple_anomaly() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)
        tracker.saveCheckpoint()

        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(1u, 8u)) // rb jumped from 5 to 8, missed reboots

        val anomalies = tracker.commitBatch()
        assertEquals(1, anomalies.size)
        assertEquals(1, anomalies[0].frameIndex)
        assertEquals(5u, anomalies[0].expectedReboots)
        assertEquals(8u, anomalies[0].actualReboots)
    }

    @Test
    fun checkpointRestore_clearsAnomalies() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)
        tracker.saveCheckpoint()

        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(106u, 5u)) // gap
        tracker.checkFrame(buildFrame(107u, 5u))

        // Simulate CRC retry
        tracker.restoreCheckpoint()

        // Re-feed same frames as if retried
        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(102u, 5u))
        tracker.checkFrame(buildFrame(103u, 5u))

        val anomalies = tracker.commitBatch()
        assertEquals(0, anomalies.size)
    }

    @Test
    fun crossBatchContinuity_noGap() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)

        // Batch 1: frames 101-104
        tracker.saveCheckpoint()
        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(102u, 5u))
        tracker.checkFrame(buildFrame(103u, 5u))
        tracker.checkFrame(buildFrame(104u, 5u))
        val batch1 = tracker.commitBatch()
        assertEquals(0, batch1.size)

        // Batch 2: frames 105-108 (contiguous with batch 1)
        tracker.saveCheckpoint()
        tracker.checkFrame(buildFrame(105u, 5u))
        tracker.checkFrame(buildFrame(106u, 5u))
        tracker.checkFrame(buildFrame(107u, 5u))
        tracker.checkFrame(buildFrame(108u, 5u))
        val batch2 = tracker.commitBatch()
        assertEquals(0, batch2.size)
    }

    @Test
    fun crossBatchContinuity_withGap() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)

        // Batch 1: frames 101-104
        tracker.saveCheckpoint()
        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(102u, 5u))
        tracker.checkFrame(buildFrame(103u, 5u))
        tracker.checkFrame(buildFrame(104u, 5u))
        tracker.commitBatch()

        // Batch 2: starts at 106, skipping 105
        tracker.saveCheckpoint()
        tracker.checkFrame(buildFrame(106u, 5u)) // gap at frame[0]
        tracker.checkFrame(buildFrame(107u, 5u))
        tracker.checkFrame(buildFrame(108u, 5u))
        tracker.checkFrame(buildFrame(109u, 5u))
        val batch2 = tracker.commitBatch()
        assertEquals(1, batch2.size)
        assertEquals(0, batch2[0].frameIndex)
        assertEquals(105u, batch2[0].expectedCount)
        assertEquals(106u, batch2[0].actualCount)
    }
}
