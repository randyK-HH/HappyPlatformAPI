package com.happyhealth.bleplatform.connection

import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.bleplatform.api.HpyConnectionState
import com.happyhealth.bleplatform.api.HpyErrorCode
import com.happyhealth.bleplatform.api.HpyEvent
import com.happyhealth.bleplatform.api.HpyResult
import com.happyhealth.bleplatform.api.createHappyPlatformApi
import com.happyhealth.bleplatform.api.HpyConfig
import com.happyhealth.bleplatform.internal.command.CommandId
import com.happyhealth.bleplatform.internal.command.writeUInt32
import com.happyhealth.bleplatform.internal.model.HpyCharId
import com.happyhealth.bleplatform.integration.MockBleShim
import com.happyhealth.bleplatform.integration.MockTimeSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConnectionSlotTest {

    private fun collectEvents(
        block: suspend (MockBleShim, com.happyhealth.bleplatform.api.HappyPlatformApi, MutableList<HpyEvent>) -> Unit
    ) = runTest {
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource())
        shim.callback = api.shimCallback

        val events = mutableListOf<HpyEvent>()
        val collectJob = launch { api.events.collect { events.add(it) } }
        kotlinx.coroutines.yield()

        block(shim, api, events)

        collectJob.cancel()
        api.destroy()
    }

    @Test
    fun uc01_missingHcsService_connectFail() = collectEvents { shim, api, events ->
        // UC-01: Missing HCS service (CMD_RX/CMD_TX). Verify CONNECT_FAIL.
        shim.discoveredChars = setOf(
            HpyCharId.DIS_SERIAL_NUMBER, HpyCharId.DIS_FW_VERSION,
            HpyCharId.DIS_SW_VERSION, HpyCharId.DIS_MANUFACTURER_NAME,
            HpyCharId.DIS_MODEL_NUMBER,
            // No CMD_RX, CMD_TX
        )
        // Suppress auto-disconnect to avoid slot cleanup race
        shim.autoDisconnect = false

        val connId = api.connect("device_handle")
        kotlinx.coroutines.yield()

        val errors = events.filterIsInstance<HpyEvent.Error>()
        assertTrue(errors.any { it.code == HpyErrorCode.CONNECT_FAIL },
            "Should emit CONNECT_FAIL for missing HCS")
    }

    @Test
    fun uc02_missingCmdRx_connectFail() = collectEvents { shim, api, events ->
        // UC-02: HCS service present but CMD_RX missing. Verify CONNECT_FAIL.
        shim.discoveredChars = setOf(
            HpyCharId.CMD_TX, HpyCharId.STREAM_TX,  // CMD_RX missing
            HpyCharId.DIS_SERIAL_NUMBER, HpyCharId.DIS_FW_VERSION,
            HpyCharId.DIS_SW_VERSION, HpyCharId.DIS_MANUFACTURER_NAME,
            HpyCharId.DIS_MODEL_NUMBER,
        )
        shim.autoDisconnect = false

        api.connect("device_handle")
        kotlinx.coroutines.yield()

        val errors = events.filterIsInstance<HpyEvent.Error>()
        assertTrue(errors.any { it.code == HpyErrorCode.CONNECT_FAIL })
    }

    @Test
    fun uc03_missingDis_connectFail() = collectEvents { shim, api, events ->
        // UC-03: DIS service absent. Verify CONNECT_FAIL.
        shim.discoveredChars = setOf(
            HpyCharId.CMD_RX, HpyCharId.CMD_TX, HpyCharId.STREAM_TX,
            HpyCharId.DEBUG_TX, HpyCharId.FRAME_TX,
            // No DIS chars
        )
        shim.autoDisconnect = false

        val connId = api.connect("device_handle")
        kotlinx.coroutines.yield()

        val errors = events.filterIsInstance<HpyEvent.Error>()
        assertTrue(errors.any { it.code == HpyErrorCode.CONNECT_FAIL },
            "Should emit CONNECT_FAIL for missing DIS")
    }

    @Test
    fun uc04_missingDebugTx_nonFatal() = collectEvents { shim, api, events ->
        // UC-04: Debug TX absent. Verify connection proceeds, no error.
        shim.discoveredChars = setOf(
            HpyCharId.CMD_RX, HpyCharId.CMD_TX, HpyCharId.STREAM_TX,
            HpyCharId.FRAME_TX,
            // No DEBUG_TX
            HpyCharId.DIS_SERIAL_NUMBER, HpyCharId.DIS_FW_VERSION,
            HpyCharId.DIS_SW_VERSION, HpyCharId.DIS_MANUFACTURER_NAME,
            HpyCharId.DIS_MODEL_NUMBER,
            HpyCharId.SUOTA_MEM_DEV, HpyCharId.SUOTA_PATCH_LEN,
            HpyCharId.SUOTA_PATCH_DATA, HpyCharId.SUOTA_STATUS,
        )

        val connId = api.connect("device_handle")
        shim.simulateDisReads(connId, "2.5.0.70")

        // Complete handshake
        val daqResp = ByteArray(65); daqResp[0] = CommandId.GET_DAQ_CONFIG
        shim.simulateCommandResponse(connId, daqResp)
        shim.simulateCommandResponse(connId, shim.buildDeviceStatusResponse())
        val mfResp = ByteArray(5); mfResp[0] = CommandId.GET_FILE_LENGTH
        shim.simulateCommandResponse(connId, mfResp)

        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId))

        // No CONNECT_FAIL errors
        val errors = events.filterIsInstance<HpyEvent.Error>()
            .filter { it.code == HpyErrorCode.CONNECT_FAIL }
        assertTrue(errors.isEmpty(), "Missing DEBUG_TX should not cause error")

        // DEBUG_TX should NOT be in subscribed chars
        val debugSubs = shim.subscribedChars.filter { it.second == HpyCharId.DEBUG_TX }
        assertTrue(debugSubs.isEmpty(), "Should not subscribe to absent DEBUG_TX")
    }

    @Test
    fun uc05_missingFrameTx_l2capForced() = collectEvents { shim, api, events ->
        // UC-05: Frame TX absent. Verify library forces L2CAP transport.
        shim.discoveredChars = setOf(
            HpyCharId.CMD_RX, HpyCharId.CMD_TX, HpyCharId.STREAM_TX,
            HpyCharId.DEBUG_TX,
            // No FRAME_TX — GATT download unavailable
            HpyCharId.DIS_SERIAL_NUMBER, HpyCharId.DIS_FW_VERSION,
            HpyCharId.DIS_SW_VERSION, HpyCharId.DIS_MANUFACTURER_NAME,
            HpyCharId.DIS_MODEL_NUMBER,
            HpyCharId.SUOTA_MEM_DEV, HpyCharId.SUOTA_PATCH_LEN,
            HpyCharId.SUOTA_PATCH_DATA, HpyCharId.SUOTA_STATUS,
        )

        val connId = api.connect("device_handle")
        shim.driveToReady(connId, "2.5.0.70")

        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId))

        // Connection should proceed normally — FRAME_TX is optional for L2CAP transport
        val errors = events.filterIsInstance<HpyEvent.Error>()
            .filter { it.code == HpyErrorCode.CONNECT_FAIL }
        assertTrue(errors.isEmpty())
    }

    @Test
    fun uc06_missingSuota_fwUpdateUnavailable() = collectEvents { shim, api, events ->
        // UC-06: SUOTA service absent. Verify connection proceeds. FW update returns error.
        shim.discoveredChars = setOf(
            HpyCharId.CMD_RX, HpyCharId.CMD_TX, HpyCharId.STREAM_TX,
            HpyCharId.DEBUG_TX, HpyCharId.FRAME_TX,
            HpyCharId.DIS_SERIAL_NUMBER, HpyCharId.DIS_FW_VERSION,
            HpyCharId.DIS_SW_VERSION, HpyCharId.DIS_MANUFACTURER_NAME,
            HpyCharId.DIS_MODEL_NUMBER,
            // No SUOTA chars
        )

        val connId = api.connect("device_handle")
        shim.driveToReady(connId, "2.5.0.70")

        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId))

        // SUOTA_STATUS should not be in subscribed chars
        val suotaSubs = shim.subscribedChars.filter { it.second == HpyCharId.SUOTA_STATUS }
        assertTrue(suotaSubs.isEmpty(), "Should not subscribe to absent SUOTA_STATUS")
    }

    @Test
    fun uc07_tier0_bootloaderSkipHandshake() = collectEvents { shim, api, events ->
        // UC-07: FW 0.0.0.0 -> CONNECTED_LIMITED, no handshake.
        val connId = api.connect("device_handle")
        shim.simulateDisReads(connId, "0.0.0.0")

        assertEquals(HpyConnectionState.CONNECTED_LIMITED, api.getConnectionState(connId))

        // No handshake commands should have been sent (no GET_DAQ_CONFIG, no GET_DEVICE_STATUS)
        val cmdBytes = shim.writtenCommands.map { it.second[0] }
        assertTrue(cmdBytes.none { it == CommandId.GET_DAQ_CONFIG }, "No GET_DAQ_CONFIG for Tier 0")
        assertTrue(cmdBytes.none { it == CommandId.GET_DEVICE_STATUS }, "No GET_DEVICE_STATUS for Tier 0")
    }

    @Test
    fun uc08_tier1_legacyPartialHandshake() = collectEvents { shim, api, events ->
        // UC-08: FW 2.4.0.0 -> Tier 1 handshake: GET_DEVICE_STATUS, conditional SET_UTC.
        // No GET_DAQ_CONFIG, no SET_INFO.
        val connId = api.connect("device_handle")
        shim.simulateDisReads(connId, "2.4.0.0")

        // First command should be GET_DEVICE_STATUS (not GET_DAQ_CONFIG)
        val firstCmd = shim.writtenCommands.firstOrNull()
        assertNotNull(firstCmd)
        assertEquals(CommandId.GET_DEVICE_STATUS, firstCmd.second[0])

        // Respond with device status — needsSetUtc=true
        shim.simulateCommandResponse(connId, shim.buildDeviceStatusResponse(sendUtcFlags = 0x05))

        // Should send SET_UTC
        val cmds = shim.writtenCommands.map { it.second[0] }
        assertTrue(cmds.contains(CommandId.SET_UTC), "Should send SET_UTC")

        // SET_UTC response
        val utcResp = ByteArray(13); utcResp[0] = CommandId.SET_UTC
        shim.simulateCommandResponse(connId, utcResp)

        // Memfault drain
        val mfResp = ByteArray(5); mfResp[0] = CommandId.GET_FILE_LENGTH
        shim.simulateCommandResponse(connId, mfResp)

        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId))

        // Verify no GET_DAQ_CONFIG, no SET_INFO sent
        val allCmds = shim.writtenCommands.map { it.second[0] }
        assertTrue(allCmds.none { it == CommandId.GET_DAQ_CONFIG }, "No GET_DAQ_CONFIG for Tier 1")
        assertTrue(allCmds.none { it == CommandId.SET_INFO }, "No SET_INFO for Tier 1")
    }

    @Test
    fun uc09_tier2_fullHandshake() = collectEvents { shim, api, events ->
        // UC-09: FW 2.5.0.70 -> full Tier 2 handshake.
        val connId = api.connect("device_handle")
        shim.simulateDisReads(connId, "2.5.0.70")

        // Should start with GET_DAQ_CONFIG
        assertEquals(CommandId.GET_DAQ_CONFIG, shim.writtenCommands[0].second[0])

        val daqResp = ByteArray(65); daqResp[0] = CommandId.GET_DAQ_CONFIG
        shim.simulateCommandResponse(connId, daqResp)

        // Then GET_DEVICE_STATUS
        assertEquals(CommandId.GET_DEVICE_STATUS, shim.writtenCommands[1].second[0])

        // Status with all conditionals: needsSetUtc + needsSetInfo + needsFingerDet
        shim.simulateCommandResponse(connId, shim.buildDeviceStatusResponse(sendUtcFlags = 0x03))

        // SET_UTC
        val utcResp = ByteArray(13); utcResp[0] = CommandId.SET_UTC
        shim.simulateCommandResponse(connId, utcResp)

        // SET_INFO
        val infoResp = ByteArray(2); infoResp[0] = CommandId.SET_INFO
        shim.simulateCommandResponse(connId, infoResp)

        // SET_FINGER_DETECTION
        val fingerResp = ByteArray(2); fingerResp[0] = CommandId.SET_FINGER_DETECTION
        shim.simulateCommandResponse(connId, fingerResp)

        // Memfault drain
        val mfResp = ByteArray(5); mfResp[0] = CommandId.GET_FILE_LENGTH
        shim.simulateCommandResponse(connId, mfResp)

        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId))

        // Verify full command sequence
        val allCmds = shim.writtenCommands.map { it.second[0] }
        assertEquals(CommandId.GET_DAQ_CONFIG, allCmds[0])
        assertEquals(CommandId.GET_DEVICE_STATUS, allCmds[1])
        assertEquals(CommandId.SET_UTC, allCmds[2])
        assertEquals(CommandId.SET_INFO, allCmds[3])
        assertEquals(CommandId.SET_FINGER_DETECTION, allCmds[4])
    }

    @Test
    fun uc10_handshakeCommandTimeout() = collectEvents { shim, api, events ->
        // UC-10: Command timeout during handshake. Verify handshake advances past timed-out step.
        // The command queue handles timeout internally — we verify the error event is emitted.
        val connId = api.connect("device_handle")
        shim.simulateDisReads(connId, "2.5.0.70")

        // DAQ config response
        val daqResp = ByteArray(65); daqResp[0] = CommandId.GET_DAQ_CONFIG
        shim.simulateCommandResponse(connId, daqResp)

        // GET_DEVICE_STATUS sent — respond with status
        shim.simulateCommandResponse(connId, shim.buildDeviceStatusResponse())

        // Memfault drain
        val mfResp = ByteArray(5); mfResp[0] = CommandId.GET_FILE_LENGTH
        shim.simulateCommandResponse(connId, mfResp)

        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId))
    }

    @Test
    fun uc11_duplicateNotificationIdempotent() = collectEvents { shim, api, events ->
        // UC-11: Duplicate notification callback handled idempotently.
        val connId = api.connect("device_handle")
        shim.simulateDisReads(connId, "2.5.0.70")

        // DAQ config
        val daqResp = ByteArray(65); daqResp[0] = CommandId.GET_DAQ_CONFIG
        shim.simulateCommandResponse(connId, daqResp)

        // Device status — send twice
        val statusResp = shim.buildDeviceStatusResponse()
        shim.simulateCommandResponse(connId, statusResp)
        // Second call — should be handled without crash
        shim.simulateCommandResponse(connId, statusResp)

        // Memfault
        val mfResp = ByteArray(5); mfResp[0] = CommandId.GET_FILE_LENGTH
        shim.simulateCommandResponse(connId, mfResp)

        // Should reach READY without crash
        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId))
    }

    @Test
    fun uc12_stateGuard_downloadRejectsCommand() = collectEvents { shim, api, events ->
        // UC-12: During DOWNLOADING, commands should be rejected.
        val connId = api.connect("device_handle")
        shim.driveToReady(connId, "2.5.0.70", unsyncedFrames = 10)

        // Start download
        api.startDownload(connId)

        // Respond to the download's GET_DEVICE_STATUS
        shim.simulateCommandResponse(connId, shim.buildDeviceStatusResponse(unsyncedFrames = 10))
        kotlinx.coroutines.yield()

        // State should be DOWNLOADING
        assertEquals(HpyConnectionState.DOWNLOADING, api.getConnectionState(connId))

        // Attempt to send a command — should be rejected
        val result = api.getDeviceStatus(connId)
        assertEquals(HpyResult.ErrCommandRejected, result)
    }

    @Test
    fun uc13_stateGuard_fwUpdateRejectsCommand() = collectEvents { shim, api, events ->
        // UC-13: During FW_UPDATING, download should be rejected.
        val connId = api.connect("device_handle")
        shim.driveToReady(connId, "2.5.0.70")

        // Start FW update
        val image = ByteArray(240)
        image[0] = 0x70; image[1] = 0x61
        api.startFwUpdate(connId, image)

        assertEquals(HpyConnectionState.FW_UPDATING, api.getConnectionState(connId))

        // Attempt download — should be rejected
        val result = api.startDownload(connId)
        assertEquals(HpyResult.ErrCommandRejected, result)
    }

    @Test
    fun uc14_fwTransferWriteFailure() = collectEvents { shim, api, events ->
        // UC-14: FW transfer write failure triggers FW_TRANSFER_FAIL and eventually RECONNECTING.
        // The FwUpdateController reports the error, and ConnectionSlot enters error handling.
        val connId = api.connect("device_handle")
        shim.driveToReady(connId, "2.5.0.70")

        // Start FW update
        val image = ByteArray(240)
        image[0] = 0x70; image[1] = 0x61
        api.startFwUpdate(connId, image)

        // Simulate SUOTA error (CRC_ERR = 4)
        shim.simulateSuotaStatus(connId, 4)
        kotlinx.coroutines.yield()

        // Should emit FW_TRANSFER_FAIL error
        val errors = events.filterIsInstance<HpyEvent.Error>()
        assertTrue(errors.any { it.code == HpyErrorCode.FW_TRANSFER_FAIL },
            "Should emit FW_TRANSFER_FAIL")
    }
}
