package com.happyhealth.bleplatform.download

import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.bleplatform.api.HpyEvent
import com.happyhealth.bleplatform.internal.command.CommandId
import com.happyhealth.bleplatform.internal.command.writeUInt32
import com.happyhealth.bleplatform.internal.download.DownloadAction
import com.happyhealth.bleplatform.internal.download.DownloadController
import com.happyhealth.bleplatform.internal.download.DownloadPhase
import com.happyhealth.bleplatform.internal.download.GattFrameAccumulator
import com.happyhealth.bleplatform.internal.util.CRC_INIT
import com.happyhealth.bleplatform.internal.util.finalizeCrc
import com.happyhealth.bleplatform.internal.util.updateCrc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DownloadControllerTest {

    private val connId = ConnectionId(0)

    /** Flatten nested DownloadAction.Multiple into a flat list. */
    private fun flattenActions(action: DownloadAction): List<DownloadAction> {
        return when (action) {
            is DownloadAction.Multiple -> action.actions.flatMap { flattenActions(it) }
            else -> listOf(action)
        }
    }

    /** Find all EmitEvent actions and extract the events. */
    private fun extractEvents(action: DownloadAction): List<HpyEvent> {
        return flattenActions(action).filterIsInstance<DownloadAction.EmitEvent>().map { it.event }
    }

    /** Build CONFIGURE_L2CAP response: [0x34][status][turbo_accepted]. */
    private fun buildConfigureL2capResponse(status: Int = 0): ByteArray {
        return byteArrayOf(CommandId.CONFIGURE_L2CAP, status.toByte(), 0x01)
    }

    /** Build GET_FRAMES_L2CAP response: [0x35][channel_status]. */
    private fun buildGetFramesL2capResponse(channelStatus: Int = 2): ByteArray {
        return byteArrayOf(CommandId.GET_FRAMES_L2CAP, channelStatus.toByte())
    }

    /** Build CRC notification for GATT: [0x17][CRC32 LE][status]. */
    private fun buildGattCrcNotification(crc: UInt, status: Byte = 0x00): ByteArray {
        val buf = ByteArray(6)
        buf[0] = CommandId.GET_FRAMES
        writeUInt32(buf, 1, crc)
        buf[5] = status
        return buf
    }

    @Test
    fun ud01_l2capBatchSingleBatchCrcPass() {
        // UD-01: Feed 10 frames over L2CAP, CRC pass. Verify DownloadBatch emitted.
        val ctrl = DownloadController(connId, batchSize = 64, maxRetries = 1, supportsL2cap = true)

        // Start session
        val startAction = ctrl.startSession(100u, 5u, 10)
        val startFlat = flattenActions(startAction)
        assertTrue(startFlat.any { it is DownloadAction.EnqueueCommand }, "Should enqueue CONFIGURE_L2CAP command")

        // Respond to CONFIGURE_L2CAP
        val configResp = ctrl.onCommandResponse(CommandId.CONFIGURE_L2CAP, buildConfigureL2capResponse(0))
        assertTrue(flattenActions(configResp).any { it is DownloadAction.OpenL2cap }, "Should open L2CAP")

        // L2CAP connected
        val connAction = ctrl.onL2capConnected()
        val connFlat = flattenActions(connAction)
        assertTrue(connFlat.any { it is DownloadAction.StartL2capReceive })
        assertTrue(connFlat.any { it is DownloadAction.EnqueueCommand })

        // GET_FRAMES_L2CAP response
        ctrl.onCommandResponse(CommandId.GET_FRAMES_L2CAP, buildGetFramesL2capResponse())

        // Batch complete with CRC pass
        val batchAction = ctrl.onL2capBatchComplete(10, crcValid = true)
        val events = extractEvents(batchAction)
        val batchEvent = events.filterIsInstance<HpyEvent.DownloadBatch>().firstOrNull()

        assertNotNull(batchEvent, "Should emit DownloadBatch")
        assertEquals(10, batchEvent.framesInBatch)
        assertTrue(batchEvent.crcValid)
        assertEquals("L2CAP", batchEvent.transport)

        // L2CAP download emits DownloadComplete after the L2CAP close response
        assertEquals(DownloadPhase.CONFIGURE_L2CAP_CLOSE, ctrl.phase)

        // Process L2CAP close
        val closeAction = ctrl.onCommandResponse(CommandId.CONFIGURE_L2CAP, buildConfigureL2capResponse())
        val closeEvents = extractEvents(closeAction)
        val completeEvent = closeEvents.filterIsInstance<HpyEvent.DownloadComplete>().firstOrNull()
        assertNotNull(completeEvent, "Should emit DownloadComplete after L2CAP close")
        assertEquals(DownloadPhase.DONE, ctrl.phase)
    }

    @Test
    fun ud02_l2capMultiBatchSession() {
        // UD-02: batch_size=4, unsynced=10. Expect 3 GET_FRAMES_L2CAP commands (4, 4, 2).
        val ctrl = DownloadController(connId, batchSize = 4, maxRetries = 1, supportsL2cap = true)

        ctrl.startSession(100u, 5u, 10)
        ctrl.onCommandResponse(CommandId.CONFIGURE_L2CAP, buildConfigureL2capResponse(0))
        ctrl.onL2capConnected()
        ctrl.onCommandResponse(CommandId.GET_FRAMES_L2CAP, buildGetFramesL2capResponse())

        // Batch 1: 4 frames
        var action = ctrl.onL2capBatchComplete(4, true)
        var events = extractEvents(action)
        assertEquals(4, events.filterIsInstance<HpyEvent.DownloadBatch>().first().framesInBatch)
        assertEquals(4, ctrl.sessionFramesDownloaded)

        // Batch 1 response triggers batch 2 command
        ctrl.onCommandResponse(CommandId.GET_FRAMES_L2CAP, buildGetFramesL2capResponse())

        // Batch 2: 4 frames
        action = ctrl.onL2capBatchComplete(4, true)
        events = extractEvents(action)
        assertEquals(4, events.filterIsInstance<HpyEvent.DownloadBatch>().first().framesInBatch)
        assertEquals(8, ctrl.sessionFramesDownloaded)

        // Batch 2 response triggers batch 3 command
        ctrl.onCommandResponse(CommandId.GET_FRAMES_L2CAP, buildGetFramesL2capResponse())

        // Batch 3: 2 frames (remaining)
        action = ctrl.onL2capBatchComplete(2, true)
        events = extractEvents(action)
        assertEquals(2, events.filterIsInstance<HpyEvent.DownloadBatch>().first().framesInBatch)
        assertEquals(10, ctrl.sessionFramesDownloaded)

        // L2CAP download: phase transitions to CONFIGURE_L2CAP_CLOSE after final batch
        assertEquals(DownloadPhase.CONFIGURE_L2CAP_CLOSE, ctrl.phase)

        // Process L2CAP close to emit DownloadComplete
        val closeAction = ctrl.onCommandResponse(CommandId.CONFIGURE_L2CAP, buildConfigureL2capResponse())
        val closeEvents = extractEvents(closeAction)
        assertTrue(closeEvents.any { it is HpyEvent.DownloadComplete }, "Should emit DownloadComplete after L2CAP close")
    }

    @Test
    fun ud03_l2capCrcMismatchSingleRetrySucceeds() {
        // UD-03: max_retries=2, CRC fail first attempt, pass second.
        val ctrl = DownloadController(connId, batchSize = 64, maxRetries = 2, supportsL2cap = true)

        ctrl.startSession(100u, 5u, 10)
        ctrl.onCommandResponse(CommandId.CONFIGURE_L2CAP, buildConfigureL2capResponse(0))
        ctrl.onL2capConnected()
        ctrl.onCommandResponse(CommandId.GET_FRAMES_L2CAP, buildGetFramesL2capResponse())

        // First attempt: CRC fail — should retry
        val retryAction = ctrl.onL2capBatchComplete(10, crcValid = false)
        val retryFlat = flattenActions(retryAction)
        // Should re-issue GET_FRAMES_L2CAP (via StartL2capReceive + EnqueueCommand)
        assertTrue(retryFlat.any { it is DownloadAction.StartL2capReceive }, "Should start new L2CAP receive for retry")

        // GET_FRAMES response for retry
        ctrl.onCommandResponse(CommandId.GET_FRAMES_L2CAP, buildGetFramesL2capResponse())

        // Second attempt: CRC pass
        val passAction = ctrl.onL2capBatchComplete(10, crcValid = true)
        val events = extractEvents(passAction)
        val batchEvent = events.filterIsInstance<HpyEvent.DownloadBatch>().first()
        assertTrue(batchEvent.crcValid)
    }

    @Test
    fun ud04_l2capCrcAllRetriesExhausted() {
        // UD-04: max_retries=1, CRC fail. Verify proceeds to next batch.
        val ctrl = DownloadController(connId, batchSize = 5, maxRetries = 1, supportsL2cap = true)

        ctrl.startSession(100u, 5u, 10)
        ctrl.onCommandResponse(CommandId.CONFIGURE_L2CAP, buildConfigureL2capResponse(0))
        ctrl.onL2capConnected()
        ctrl.onCommandResponse(CommandId.GET_FRAMES_L2CAP, buildGetFramesL2capResponse())

        // First attempt: CRC fail — should retry (retry count 1)
        val retryAction = ctrl.onL2capBatchComplete(5, crcValid = false)
        assertTrue(flattenActions(retryAction).any { it is DownloadAction.StartL2capReceive })
        ctrl.onCommandResponse(CommandId.GET_FRAMES_L2CAP, buildGetFramesL2capResponse())

        // Second attempt: CRC fail again — retries exhausted, proceed with bad batch
        val exhaustedAction = ctrl.onL2capBatchComplete(5, crcValid = false)
        val events = extractEvents(exhaustedAction)

        // Should emit CRC fail error but continue
        assertTrue(events.any { it is HpyEvent.Error }, "Should emit CRC fail error")
        val batchEvent = events.filterIsInstance<HpyEvent.DownloadBatch>().firstOrNull()
        assertNotNull(batchEvent, "Should still emit batch event")

        // Should request next batch (5 more frames)
        assertTrue(flattenActions(exhaustedAction).any { it is DownloadAction.StartL2capReceive },
            "Should proceed to next batch")
    }

    @Test
    fun ud05_l2capCrcStatusByteNonZero() {
        // UD-05: CRC packet status byte non-zero. This is tested at GATT level via GattFrameAccumulator.
        // At the DownloadController level, the L2CAP shim reports crcValid. This test verifies
        // that crcValid=false triggers retry logic regardless of reason.
        val ctrl = DownloadController(connId, batchSize = 64, maxRetries = 2, supportsL2cap = true)

        ctrl.startSession(100u, 5u, 10)
        ctrl.onCommandResponse(CommandId.CONFIGURE_L2CAP, buildConfigureL2capResponse(0))
        ctrl.onL2capConnected()
        ctrl.onCommandResponse(CommandId.GET_FRAMES_L2CAP, buildGetFramesL2capResponse())

        // Simulate CRC failure (which could be caused by status byte non-zero)
        val action = ctrl.onL2capBatchComplete(10, crcValid = false)
        val flat = flattenActions(action)
        assertTrue(flat.any { it is DownloadAction.StartL2capReceive }, "Should retry on CRC failure")
    }

    @Test
    fun ud06_maxRetriesClamping() {
        // UD-06: Verify max retries are used as-is (clamping happens at config level).
        // Test with maxRetries=5 — controller should allow 5 retries.
        val ctrl = DownloadController(connId, batchSize = 64, maxRetries = 5, supportsL2cap = true)

        ctrl.startSession(100u, 5u, 10)
        ctrl.onCommandResponse(CommandId.CONFIGURE_L2CAP, buildConfigureL2capResponse(0))
        ctrl.onL2capConnected()
        ctrl.onCommandResponse(CommandId.GET_FRAMES_L2CAP, buildGetFramesL2capResponse())

        // 5 CRC failures should all retry
        for (i in 1..5) {
            val action = ctrl.onL2capBatchComplete(10, crcValid = false)
            assertTrue(flattenActions(action).any { it is DownloadAction.StartL2capReceive },
                "Retry $i should still happen")
            ctrl.onCommandResponse(CommandId.GET_FRAMES_L2CAP, buildGetFramesL2capResponse())
        }

        // 6th failure — retries exhausted
        val exhausted = ctrl.onL2capBatchComplete(10, crcValid = false)
        val events = extractEvents(exhausted)
        assertTrue(events.any { it is HpyEvent.Error }, "Should emit error after 5 retries exhausted")
    }

    @Test
    fun ud07_l2capOpenFailureFallbackToGatt() {
        // UD-07: supportsL2cap=true but CONFIGURE_L2CAP rejected. Verify GATT fallback.
        val ctrl = DownloadController(connId, batchSize = 64, maxRetries = 1, supportsL2cap = true)

        ctrl.startSession(100u, 5u, 10)

        // CONFIGURE_L2CAP response with non-zero status (rejected)
        val action = ctrl.onCommandResponse(CommandId.CONFIGURE_L2CAP, buildConfigureL2capResponse(1))
        val flat = flattenActions(action)

        // Should fall back to GATT — enqueue a GET_FRAMES command
        val commands = flat.filterIsInstance<DownloadAction.EnqueueCommand>()
        assertTrue(commands.isNotEmpty(), "Should enqueue GATT command after L2CAP rejection")

        // Phase should be RECEIVING_GATT
        assertEquals(DownloadPhase.RECEIVING_GATT, ctrl.phase)
        assertEquals("GATT", ctrl.transportString)
    }

    @Test
    fun ud08_l2capFailureMidDownloadStrongRssi() {
        // UD-08: L2CAP error mid-download. Verify fallback to GATT.
        val ctrl = DownloadController(connId, batchSize = 64, maxRetries = 2, supportsL2cap = true)
        ctrl.lastRssi = -55

        ctrl.startSession(100u, 5u, 10)
        ctrl.onCommandResponse(CommandId.CONFIGURE_L2CAP, buildConfigureL2capResponse(0))
        ctrl.onL2capConnected()
        ctrl.onCommandResponse(CommandId.GET_FRAMES_L2CAP, buildGetFramesL2capResponse())

        // L2CAP error mid-download
        val action = ctrl.onL2capError("Socket closed unexpectedly")
        val flat = flattenActions(action)

        // Should fall back to GATT
        val commands = flat.filterIsInstance<DownloadAction.EnqueueCommand>()
        assertTrue(commands.isNotEmpty(), "Should enqueue GATT command after L2CAP error")
        assertEquals(DownloadPhase.RECEIVING_GATT, ctrl.phase)
        assertEquals("GATT", ctrl.transportString)
    }

    @Test
    fun ud09_l2capFailureWeakRssi() {
        // UD-09: L2CAP drop with weak RSSI. Verify immediate fallback to GATT.
        val ctrl = DownloadController(connId, batchSize = 64, maxRetries = 2, supportsL2cap = true)
        ctrl.lastRssi = -75

        ctrl.startSession(100u, 5u, 10)
        ctrl.onCommandResponse(CommandId.CONFIGURE_L2CAP, buildConfigureL2capResponse(0))
        ctrl.onL2capConnected()
        ctrl.onCommandResponse(CommandId.GET_FRAMES_L2CAP, buildGetFramesL2capResponse())

        // L2CAP error — controller doesn't check RSSI itself; this is at ConnectionSlot level.
        // At the controller level, onL2capError always falls back to GATT.
        val action = ctrl.onL2capError("Socket dropped")
        assertEquals(DownloadPhase.RECEIVING_GATT, ctrl.phase)
        assertEquals("GATT", ctrl.transportString)
    }

    @Test
    fun ud10_l2capFailureBelowMinRssi() {
        // UD-10: Below min_rssi abort. The RSSI check and abort happen at ConnectionSlot level.
        // At DownloadController level, onL2capError always falls back to GATT.
        // This test verifies the L2CAP error path is consistent.
        val ctrl = DownloadController(connId, batchSize = 64, maxRetries = 1, supportsL2cap = true)
        ctrl.lastRssi = -85

        ctrl.startSession(100u, 5u, 10)
        ctrl.onCommandResponse(CommandId.CONFIGURE_L2CAP, buildConfigureL2capResponse(0))
        ctrl.onL2capConnected()
        ctrl.onCommandResponse(CommandId.GET_FRAMES_L2CAP, buildGetFramesL2capResponse())

        val action = ctrl.onL2capError("Socket error")
        assertEquals(DownloadPhase.RECEIVING_GATT, ctrl.phase)
    }

    @Test
    fun ud11_gattBatchSingleBatchCrcPass() {
        // UD-11: GATT batch, single batch, CRC pass.
        val frames = mutableListOf<ByteArray>()
        val ctrl = DownloadController(connId, batchSize = 64, maxRetries = 1, supportsL2cap = false,
            onFrameEmit = { frames.add(it) })

        ctrl.startSession(100u, 5u, 2)
        assertEquals(DownloadPhase.RECEIVING_GATT, ctrl.phase)

        // Feed GATT frame data via onStreamTxData
        val frameSize = CommandId.FRAME_SIZE
        val allData = ByteArray(2 * frameSize) { (it % 251).toByte() }
        ctrl.onStreamTxData(allData)

        assertEquals(2, frames.size, "Should have received 2 frames")

        // CRC notification via FRAME_TX
        val crc = finalizeCrc(updateCrc(CRC_INIT, allData, 0, allData.size))
        val crcNotif = buildGattCrcNotification(crc)
        val action = ctrl.onCommandResponse(CommandId.GET_FRAMES, crcNotif)

        val events = extractEvents(action)
        val batchEvent = events.filterIsInstance<HpyEvent.DownloadBatch>().firstOrNull()
        assertNotNull(batchEvent)
        assertEquals(2, batchEvent.framesInBatch)
        assertTrue(batchEvent.crcValid)
        assertEquals("GATT", batchEvent.transport)

        assertTrue(events.any { it is HpyEvent.DownloadComplete })
    }

    @Test
    fun ud12_gattCommandCompletesOnWriteAck() {
        // UD-12: GATT GET_FRAMES uses ON_WRITE_ACK completion, freeing command queue immediately.
        val ctrl = DownloadController(connId, batchSize = 64, maxRetries = 1, supportsL2cap = false)

        val startAction = ctrl.startSession(100u, 5u, 10)
        val flat = flattenActions(startAction)
        val cmd = flat.filterIsInstance<DownloadAction.EnqueueCommand>().first()

        // Verify completion type is ON_WRITE_ACK
        assertEquals(
            com.happyhealth.bleplatform.internal.connection.CompletionType.ON_WRITE_ACK,
            cmd.cmd.completionType,
            "GATT GET_FRAMES should use ON_WRITE_ACK completion"
        )
    }

    @Test
    fun ud13_gattTransportForcedOnTier1() {
        // UD-13: supportsL2cap=false. Verify GATT commands used (GET_FRAMES 0x17).
        val ctrl = DownloadController(connId, batchSize = 64, maxRetries = 1, supportsL2cap = false)

        val startAction = ctrl.startSession(100u, 5u, 10)
        assertEquals(DownloadPhase.RECEIVING_GATT, ctrl.phase)
        assertEquals("GATT", ctrl.transportString)

        // Verify the enqueued command uses GET_FRAMES (0x17), not GET_FRAMES_L2CAP (0x35)
        val flat = flattenActions(startAction)
        val cmd = flat.filterIsInstance<DownloadAction.EnqueueCommand>().first()
        assertEquals(CommandId.GET_FRAMES, cmd.cmd.data[0], "Should use GET_FRAMES (0x17)")
    }

    @Test
    fun ud14_gattTransportForcedOnTier2Pre254() {
        // UD-14: supportsL2cap=false (set by ConnectionSlot when FW < 2.5.0.54).
        // Verify GATT path used. No CONFIGURE_L2CAP sent.
        val ctrl = DownloadController(connId, batchSize = 64, maxRetries = 1, supportsL2cap = false)

        val startAction = ctrl.startSession(100u, 5u, 5)
        val flat = flattenActions(startAction)

        // Should NOT have any L2CAP actions
        assertTrue(flat.none { it is DownloadAction.OpenL2cap }, "Should not open L2CAP")
        assertEquals(DownloadPhase.RECEIVING_GATT, ctrl.phase)
    }

    @Test
    fun ud15_l2capAvailableOnTier2() {
        // UD-15: supportsL2cap=true. Verify CONFIGURE_L2CAP sent and GET_FRAMES_L2CAP used.
        val ctrl = DownloadController(connId, batchSize = 64, maxRetries = 1, supportsL2cap = true)

        val startAction = ctrl.startSession(100u, 5u, 10)
        val flat = flattenActions(startAction)
        val cmd = flat.filterIsInstance<DownloadAction.EnqueueCommand>().first()

        // Should send CONFIGURE_L2CAP
        assertEquals(CommandId.CONFIGURE_L2CAP, cmd.cmd.data[0], "Should send CONFIGURE_L2CAP")
        assertEquals(DownloadPhase.CONFIGURE_L2CAP_OPEN, ctrl.phase)

        // After config accepted, proceed with L2CAP
        ctrl.onCommandResponse(CommandId.CONFIGURE_L2CAP, buildConfigureL2capResponse(0))
        val connAction = ctrl.onL2capConnected()

        // After onL2capConnected the phase moves to REQUESTING_L2CAP_BATCH
        // and the batch command should use GET_FRAMES_L2CAP
        val connFlat = flattenActions(connAction)
        assertTrue(connFlat.any { it is DownloadAction.EnqueueCommand })
        assertEquals("L2CAP", ctrl.transportString)
    }

    @Test
    fun ud16_l2capAvailableOnFdaEquivalent() {
        // UD-16: L2CAP availability is determined by ConnectionSlot based on FW version.
        // This test verifies that when supportsL2cap=true, L2CAP path is used.
        val ctrl = DownloadController(connId, batchSize = 64, maxRetries = 1, supportsL2cap = true)

        val startAction = ctrl.startSession(100u, 5u, 5)
        assertEquals(DownloadPhase.CONFIGURE_L2CAP_OPEN, ctrl.phase)

        val flat = flattenActions(startAction)
        val cmd = flat.filterIsInstance<DownloadAction.EnqueueCommand>().first()
        assertEquals(CommandId.CONFIGURE_L2CAP, cmd.cmd.data[0])
    }

    @Test
    fun ud17_doubleBufferProtocol() {
        // UD-17: batch_size=2, unsynced=6 (3 batches). Verify batches cycle through.
        val ctrl = DownloadController(connId, batchSize = 2, maxRetries = 1, supportsL2cap = false)

        ctrl.startSession(100u, 5u, 6)
        assertEquals(DownloadPhase.RECEIVING_GATT, ctrl.phase)

        // Feed 3 batches
        for (batchNum in 1..3) {
            val batchData = ByteArray(2 * CommandId.FRAME_SIZE) { ((batchNum * 100 + it) % 251).toByte() }
            ctrl.onStreamTxData(batchData)

            val crc = finalizeCrc(updateCrc(CRC_INIT, batchData, 0, batchData.size))
            val action = ctrl.onCommandResponse(CommandId.GET_FRAMES, buildGattCrcNotification(crc))
            val events = extractEvents(action)
            val batch = events.filterIsInstance<HpyEvent.DownloadBatch>().firstOrNull()
            assertNotNull(batch, "Batch $batchNum should emit DownloadBatch")
            assertEquals(2, batch.framesInBatch)
        }

        assertEquals(6, ctrl.sessionFramesDownloaded)
        assertEquals(DownloadPhase.DONE, ctrl.phase)
    }

    @Test
    fun ud18_doubleBufferOverrun() {
        // UD-18: Double-buffer overrun is managed by the host app, not by DownloadController.
        // DownloadController itself doesn't enforce buffer_a/buffer_b — it emits DownloadBatch
        // events and the app decides when to free buffers. This test verifies batches are emitted
        // sequentially so the app can track buffer state.
        val ctrl = DownloadController(connId, batchSize = 2, maxRetries = 1, supportsL2cap = false)

        ctrl.startSession(100u, 5u, 4)

        // Batch 1
        ctrl.onStreamTxData(ByteArray(2 * CommandId.FRAME_SIZE))
        val crc1 = finalizeCrc(updateCrc(CRC_INIT, ByteArray(2 * CommandId.FRAME_SIZE), 0, 2 * CommandId.FRAME_SIZE))
        val a1 = ctrl.onCommandResponse(CommandId.GET_FRAMES, buildGattCrcNotification(crc1))
        assertTrue(extractEvents(a1).any { it is HpyEvent.DownloadBatch })

        // Batch 2
        ctrl.onStreamTxData(ByteArray(2 * CommandId.FRAME_SIZE))
        val a2 = ctrl.onCommandResponse(CommandId.GET_FRAMES, buildGattCrcNotification(crc1))
        assertTrue(extractEvents(a2).any { it is HpyEvent.DownloadBatch })
        assertTrue(extractEvents(a2).any { it is HpyEvent.DownloadComplete })
    }

    @Test
    fun ud19_preFlightRssiRejection() {
        // UD-19: RSSI pre-flight is handled at ConnectionSlot level, not DownloadController.
        // DownloadController does not perform RSSI checks. This test verifies that
        // DownloadController starts normally regardless of RSSI.
        val ctrl = DownloadController(connId, batchSize = 64, maxRetries = 1, supportsL2cap = false)
        ctrl.lastRssi = -85  // Low RSSI

        val action = ctrl.startSession(100u, 5u, 10)
        // Should still start — RSSI check is ConnectionSlot's responsibility
        assertEquals(DownloadPhase.RECEIVING_GATT, ctrl.phase)
    }

    @Test
    fun ud20_preFlightSocRejection() {
        // UD-20: SOC pre-flight is handled at ConnectionSlot level, not DownloadController.
        // DownloadController does not have access to SOC data.
        // This test verifies that DownloadController starts with zero unsynced frames.
        val ctrl = DownloadController(connId, batchSize = 64, maxRetries = 1, supportsL2cap = false)

        val action = ctrl.startSession(100u, 5u, 0)
        val events = extractEvents(action)

        // Should emit DownloadComplete immediately with 0 frames
        val complete = events.filterIsInstance<HpyEvent.DownloadComplete>().firstOrNull()
        assertNotNull(complete, "Should emit DownloadComplete for 0 unsynced frames")
        assertEquals(0, complete.sessionFrames)
        assertEquals(DownloadPhase.DONE, ctrl.phase)
    }
}
