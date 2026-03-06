package com.happyhealth.bleplatform.integration

import com.happyhealth.bleplatform.api.HpyConnectionState
import com.happyhealth.bleplatform.api.HpyErrorCode
import com.happyhealth.bleplatform.api.HpyEvent
import com.happyhealth.bleplatform.api.HpyResult
import com.happyhealth.bleplatform.api.createHappyPlatformApi
import com.happyhealth.bleplatform.internal.command.CommandId
import com.happyhealth.bleplatform.internal.fwupdate.FwUpdateController
import com.happyhealth.bleplatform.internal.model.HpyCharId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FwUpdateIntegrationTest {

    private fun buildTestImage(size: Int = 240): ByteArray {
        val image = ByteArray(size)
        image[0] = 0x70
        image[1] = 0x61
        for (i in 2 until size) image[i] = (i % 251).toByte()
        return image
    }

    @Test
    fun if01_gattFwUpdateEndToEnd() = runTest {
        // IF-01: GATT FW update end-to-end with reboot.
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()))
        shim.callback = api.shimCallback

        val events = mutableListOf<HpyEvent>()
        val collectJob = launch { api.events.collect { events.add(it) } }
        kotlinx.coroutines.yield()

        val connId = api.connect("device_handle")
        shim.driveToReady(connId, "2.5.0.70")
        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId))

        // Start FW update
        val image = buildTestImage(480) // 2 blocks of 240
        val result = api.startFwUpdate(connId, image)
        assertEquals(HpyResult.Ok, result)
        assertEquals(HpyConnectionState.FW_UPDATING, api.getConnectionState(connId))

        // SUOTA writes should include FLASH command to MEM_DEV
        val flashWrite = shim.suotaWrites.firstOrNull { it.first == HpyCharId.SUOTA_MEM_DEV }
        assertTrue(flashWrite != null, "Should write FLASH to MEM_DEV")
        assertTrue(flashWrite.second.contentEquals(FwUpdateController.SUOTA_CMD_FLASH))

        // Simulate IMG_STARTED
        shim.simulateSuotaStatus(connId, 16)
        kotlinx.coroutines.yield()

        // PATCH_LEN should be written
        val patchLenWrite = shim.suotaWrites.firstOrNull { it.first == HpyCharId.SUOTA_PATCH_LEN }
        assertTrue(patchLenWrite != null, "Should write PATCH_LEN")

        // Wait for stream schedule (200ms delay)
        advanceTimeBy(300)
        kotlinx.coroutines.yield()

        // Stream send should have been called
        assertTrue(shim.l2capStreamSendCalls.isNotEmpty(), "Should call l2capStreamSend")
        val streamCall = shim.l2capStreamSendCalls.first()
        assertEquals(FwUpdateController.L2CAP_SUOTA_PSM, streamCall.psm)
        assertEquals(FwUpdateController.SUOTA_BLOCK_SIZE, streamCall.blockSize)

        // Simulate stream progress and completion
        shim.simulateL2capSendProgress(connId, 1, 2)
        shim.simulateL2capSendComplete(connId)
        kotlinx.coroutines.yield()

        // Wait for finalize schedule (5s)
        advanceTimeBy(6_000)
        kotlinx.coroutines.yield()

        // FINALIZE should be written
        val finalizeWrite = shim.suotaWrites.filter { it.first == HpyCharId.SUOTA_MEM_DEV }
            .firstOrNull { it.second.contentEquals(FwUpdateController.SUOTA_CMD_FINALIZE) }
        assertTrue(finalizeWrite != null, "Should write FINALIZE to MEM_DEV")

        // Simulate CMP_OK — SessionComplete will call shim.disconnect internally,
        // so set autoDisconnect=false to prevent cleanup during the flow
        shim.autoDisconnect = false
        shim.simulateSuotaStatus(connId, FwUpdateController.SUOTA_CMP_OK)
        kotlinx.coroutines.yield()

        // RESET should be written
        val resetWrite = shim.suotaWrites.filter { it.first == HpyCharId.SUOTA_MEM_DEV }
            .firstOrNull { it.second.contentEquals(FwUpdateController.SUOTA_CMD_RESET) }
        assertTrue(resetWrite != null, "Should write RESET to MEM_DEV")

        // State should transition to FW_UPDATE_REBOOTING
        val stateEvents = events.filterIsInstance<HpyEvent.StateChanged>()
        assertTrue(stateEvents.any { it.state == HpyConnectionState.FW_UPDATE_REBOOTING },
            "Should enter FW_UPDATE_REBOOTING")

        // Progress events should have been emitted
        val progress = events.filterIsInstance<HpyEvent.FwUpdateProgress>()
        assertTrue(progress.isNotEmpty(), "Should emit progress events")

        collectJob.cancel()
        api.destroy()
    }

    @Test
    fun if02_l2capFwUpdateEndToEnd() = runTest {
        // IF-02: L2CAP FW update. Verify blocks streamed with correct parameters.
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()))
        shim.callback = api.shimCallback

        val events = mutableListOf<HpyEvent>()
        val collectJob = launch { api.events.collect { events.add(it) } }
        kotlinx.coroutines.yield()

        val connId = api.connect("device_handle")
        shim.driveToReady(connId, "2.5.0.70")

        val image = buildTestImage(720) // 3 blocks
        api.startFwUpdate(connId, image)

        // FLASH -> IMG_STARTED
        shim.simulateSuotaStatus(connId, 16)
        advanceTimeBy(300) // stream schedule
        kotlinx.coroutines.yield()

        // Verify stream parameters
        assertTrue(shim.l2capStreamSendCalls.isNotEmpty(), "Should call l2capStreamSend")
        val streamCall = shim.l2capStreamSendCalls.first()
        assertEquals(FwUpdateController.L2CAP_SUOTA_PSM, streamCall.psm)
        assertEquals(720, streamCall.imageSize)
        assertEquals(240, streamCall.blockSize)
        assertEquals(30L, streamCall.interBlockDelayMs)

        collectJob.cancel()
        api.destroy()
    }

    @Test
    fun if03_tier0BootloaderUpdate() = runTest {
        // IF-03: FW update from bootloader (Tier 0).
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()))
        shim.callback = api.shimCallback

        val events = mutableListOf<HpyEvent>()
        val collectJob = launch { api.events.collect { events.add(it) } }
        kotlinx.coroutines.yield()

        val connId = api.connect("device_handle")
        shim.simulateDisReads(connId, "0.0.0.0")

        assertEquals(HpyConnectionState.CONNECTED_LIMITED, api.getConnectionState(connId))

        // FW update from CONNECTED_LIMITED should be rejected by API
        // because it only allows from READY state
        val image = buildTestImage()
        val result = api.startFwUpdate(connId, image)
        assertEquals(HpyResult.ErrCommandRejected, result,
            "FW update from CONNECTED_LIMITED requires READY state")

        collectJob.cancel()
        api.destroy()
    }

    @Test
    fun if04_fwUpdateReconnectionFails() = runTest {
        // IF-04: FW update reconnection fails after 64 attempts.
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()))
        shim.callback = api.shimCallback

        val events = mutableListOf<HpyEvent>()
        val collectJob = launch { api.events.collect { events.add(it) } }
        kotlinx.coroutines.yield()

        val connId = api.connect("device_handle")
        shim.driveToReady(connId, "2.5.0.70")

        // Start FW update and complete through to RESET
        val image = buildTestImage()
        api.startFwUpdate(connId, image)

        shim.simulateSuotaStatus(connId, 16) // IMG_STARTED
        advanceTimeBy(300)
        kotlinx.coroutines.yield()

        shim.simulateL2capSendComplete(connId)
        advanceTimeBy(6_000) // finalize delay
        kotlinx.coroutines.yield()

        // FINALIZE -> CMP_OK
        // SessionComplete calls shim.disconnect internally. Set autoDisconnect=false
        // so the ConnectionSlot.onDisconnected path handles it properly via the flag.
        shim.autoDisconnect = false
        shim.simulateSuotaStatus(connId, FwUpdateController.SUOTA_CMP_OK)
        kotlinx.coroutines.yield()

        // Now in FW_UPDATE_REBOOTING — all reconnect attempts will fail
        shim.autoConnect = false
        shim.connectHandler = { cid, _ ->
            shim.callback?.onDisconnected(cid, 8)
        }

        // Advance through FW reboot wait (30s) + all 64 attempts:
        // delays: 30s + 16*2s + 16*3s + 16*5s + 16*10s = 350s
        // + 64 * 15s connect timeouts = 960s (mock fires onDisconnected synchronously
        // before connectResultDeferred is set, so each attempt times out)
        // Total: ~1310s
        advanceTimeBy(1_500_000)
        kotlinx.coroutines.yield()

        val errors = events.filterIsInstance<HpyEvent.Error>()
        assertTrue(errors.any { it.code == HpyErrorCode.FW_UPDATE_RECONNECT_FAIL },
            "Should emit FW_UPDATE_RECONNECT_FAIL")

        collectJob.cancel()
        api.destroy()
    }
}
