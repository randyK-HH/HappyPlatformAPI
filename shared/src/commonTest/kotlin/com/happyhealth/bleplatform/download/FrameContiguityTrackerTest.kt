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

        val result = tracker.commitBatch()
        assertEquals(0, result.anomalies.size)
        assertEquals(0, result.reboots.size)
        assertEquals(103u, result.lastFrameCount)
        assertEquals(5u, result.lastReboots)
    }

    @Test
    fun gapDetected_oneAnomaly() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)
        tracker.saveCheckpoint()

        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(103u, 5u)) // skipped 102
        tracker.checkFrame(buildFrame(104u, 5u))

        val result = tracker.commitBatch()
        assertEquals(1, result.anomalies.size)
        assertEquals(1, result.anomalies[0].frameIndex)
        assertEquals(102u, result.anomalies[0].expectedCount)
        assertEquals(103u, result.anomalies[0].actualCount)
        assertEquals(5u, result.anomalies[0].expectedReboots)
        assertEquals(5u, result.anomalies[0].actualReboots)
    }

    @Test
    fun rebootBoundary_fcOne_noAnomaly() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)
        tracker.saveCheckpoint()

        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(1u, 6u)) // valid reboot: rb+1, fc=1

        val result = tracker.commitBatch()
        assertEquals(0, result.anomalies.size)
        assertEquals(1, result.reboots.size)
        assertEquals(6u, result.reboots[0].newReboots)
        assertEquals(1u, result.reboots[0].firstFrameCount)
        assertEquals(1u, result.lastFrameCount)
        assertEquals(6u, result.lastReboots)
    }

    @Test
    fun rebootBoundary_fcGreaterThanOne_anomaly() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)
        tracker.saveCheckpoint()

        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(3u, 6u)) // reboot but fc=3, missed frames 1-2

        val result = tracker.commitBatch()
        assertEquals(1, result.anomalies.size)
        assertEquals(1, result.anomalies[0].frameIndex)
        assertEquals(6u, result.anomalies[0].actualReboots)
        assertEquals(3u, result.anomalies[0].actualCount)
        assertEquals(1, result.reboots.size)
        assertEquals(6u, result.reboots[0].newReboots)
        assertEquals(3u, result.reboots[0].firstFrameCount)
    }

    @Test
    fun rebootJumpMultiple_anomaly() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)
        tracker.saveCheckpoint()

        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(1u, 8u)) // rb jumped from 5 to 8, missed reboots

        val result = tracker.commitBatch()
        assertEquals(1, result.anomalies.size)
        assertEquals(1, result.anomalies[0].frameIndex)
        assertEquals(5u, result.anomalies[0].expectedReboots)
        assertEquals(8u, result.anomalies[0].actualReboots)
        assertEquals(1, result.reboots.size)
        assertEquals(8u, result.reboots[0].newReboots)
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

        val result = tracker.commitBatch()
        assertEquals(0, result.anomalies.size)
        assertEquals(0, result.reboots.size)
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
        assertEquals(0, batch1.anomalies.size)

        // Batch 2: frames 105-108 (contiguous with batch 1)
        tracker.saveCheckpoint()
        tracker.checkFrame(buildFrame(105u, 5u))
        tracker.checkFrame(buildFrame(106u, 5u))
        tracker.checkFrame(buildFrame(107u, 5u))
        tracker.checkFrame(buildFrame(108u, 5u))
        val batch2 = tracker.commitBatch()
        assertEquals(0, batch2.anomalies.size)
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
        assertEquals(1, batch2.anomalies.size)
        assertEquals(0, batch2.anomalies[0].frameIndex)
        assertEquals(105u, batch2.anomalies[0].expectedCount)
        assertEquals(106u, batch2.anomalies[0].actualCount)
    }

    @Test
    fun rebootMidBatch_updatesLastPosition() {
        val tracker = FrameContiguityTracker()
        // Simulate: sync at boot1750:frame4, batch has frames 5-6 from boot 1750,
        // then frames 1-62 from boot 1751 (reboot boundary)
        tracker.setBaseline(4u, 1750u)
        tracker.saveCheckpoint()

        tracker.checkFrame(buildFrame(5u, 1750u))
        tracker.checkFrame(buildFrame(6u, 1750u))
        tracker.checkFrame(buildFrame(1u, 1751u)) // reboot
        for (fc in 2u..62u) {
            tracker.checkFrame(buildFrame(fc, 1751u))
        }

        val result = tracker.commitBatch()
        assertEquals(0, result.anomalies.size)
        assertEquals(1, result.reboots.size)
        assertEquals(1751u, result.reboots[0].newReboots)
        assertEquals(1u, result.reboots[0].firstFrameCount)
        assertEquals(2, result.reboots[0].frameIndex)
        // Critical: last position should be boot1751:frame62, NOT boot1750:frame68
        assertEquals(62u, result.lastFrameCount)
        assertEquals(1751u, result.lastReboots)
    }

    @Test
    fun checkpointRestore_clearsReboots() {
        val tracker = FrameContiguityTracker()
        tracker.setBaseline(100u, 5u)
        tracker.saveCheckpoint()

        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(1u, 6u)) // reboot

        // Simulate CRC retry — restore should clear reboots
        tracker.restoreCheckpoint()

        tracker.checkFrame(buildFrame(101u, 5u))
        tracker.checkFrame(buildFrame(102u, 5u))

        val result = tracker.commitBatch()
        assertEquals(0, result.reboots.size)
        assertEquals(102u, result.lastFrameCount)
        assertEquals(5u, result.lastReboots)
    }
}
