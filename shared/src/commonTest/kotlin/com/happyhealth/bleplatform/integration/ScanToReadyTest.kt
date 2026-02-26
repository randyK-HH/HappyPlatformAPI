package com.happyhealth.bleplatform.integration

import com.happyhealth.bleplatform.api.HpyConnectionState
import com.happyhealth.bleplatform.api.HpyEvent
import com.happyhealth.bleplatform.api.createHappyPlatformApi
import com.happyhealth.bleplatform.internal.command.CommandId
import com.happyhealth.bleplatform.internal.command.writeUInt32
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScanToReadyTest {

    @Test
    fun scan_discoversDevices() = runTest {
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource())
        shim.callback = api.shimCallback

        api.scanStart()
        assertTrue(shim.scanStarted)

        shim.callback!!.onDeviceDiscovered("HH-Test", "HH-Test", "AA:BB:CC:DD:EE:FF", -50)

        assertEquals(1, api.discoveredDevices.value.size)
        assertEquals("HH-Test", api.discoveredDevices.value[0].name)

        api.scanStop()
        assertTrue(shim.scanStopped)
        api.destroy()
    }

    @Test
    fun connect_tier2_reachesReady() = runTest {
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource())
        shim.callback = api.shimCallback

        // Collect events — subscribe BEFORE triggering any events
        val events = mutableListOf<HpyEvent>()
        val collectJob = launch {
            api.events.collect { events.add(it) }
        }
        // Yield to ensure collector is subscribed
        kotlinx.coroutines.yield()

        val connId = api.connect("device_handle")
        assertTrue(connId.value >= 0)

        shim.simulateDisReads(connId, "2.5.0.54")

        // Handshake starts: GET_DAQ_CONFIG sent
        val daqResponse = ByteArray(65)
        daqResponse[0] = CommandId.GET_DAQ_CONFIG
        daqResponse[1] = 1
        daqResponse[2] = 5
        shim.simulateCommandResponse(connId, daqResponse)

        // GET_DEVICE_STATUS sent next
        val statusResponse = ByteArray(34)
        statusResponse[0] = CommandId.GET_DEVICE_STATUS
        statusResponse[1] = 0x01
        statusResponse[10] = 75
        statusResponse[11] = 0x03
        writeUInt32(statusResponse, 14, 100u)
        writeUInt32(statusResponse, 18, 5u)
        statusResponse[27] = 0x04 // no conditional commands needed
        shim.simulateCommandResponse(connId, statusResponse)

        // Verify READY state
        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId))

        // Verify events were collected
        kotlinx.coroutines.yield()
        assertTrue(events.any { it is HpyEvent.DeviceInfo })
        val deviceInfo = events.filterIsInstance<HpyEvent.DeviceInfo>().first()
        assertEquals("SN12345", deviceInfo.info.serialNumber)
        assertEquals("2.5.0.54", deviceInfo.info.fwVersion)

        // Verify handshake commands were written
        val cmdBytes = shim.writtenCommands.map { it.second[0] }
        assertTrue(cmdBytes.contains(CommandId.GET_DAQ_CONFIG))
        assertTrue(cmdBytes.contains(CommandId.GET_DEVICE_STATUS))

        collectJob.cancel()
        api.destroy()
    }

    @Test
    fun connect_tier0_reachesConnectedLimited() = runTest {
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource())
        shim.callback = api.shimCallback

        val connId = api.connect("device_handle")
        shim.simulateDisReads(connId, "0.0.0.0")

        assertEquals(HpyConnectionState.CONNECTED_LIMITED, api.getConnectionState(connId))

        api.destroy()
    }

    @Test
    fun connect_tier2_withConditionals_reachesReady() = runTest {
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource())
        shim.callback = api.shimCallback

        val connId = api.connect("device_handle")
        shim.simulateDisReads(connId, "2.5.0.76")

        // DAQ config response
        val daqResponse = ByteArray(65)
        daqResponse[0] = CommandId.GET_DAQ_CONFIG
        shim.simulateCommandResponse(connId, daqResponse)

        // Device status with all conditionals needed
        val statusResponse = ByteArray(34)
        statusResponse[0] = CommandId.GET_DEVICE_STATUS
        statusResponse[1] = 0x01
        statusResponse[10] = 75
        statusResponse[11] = 0x03
        writeUInt32(statusResponse, 14, 100u)
        writeUInt32(statusResponse, 18, 5u)
        statusResponse[27] = 0x03 // needsSetUtc + needsSetInfo, needsFingerDet (bit2=0)
        shim.simulateCommandResponse(connId, statusResponse)

        // SET_UTC response
        val utcResponse = ByteArray(13)
        utcResponse[0] = CommandId.SET_UTC
        writeUInt32(utcResponse, 1, 1708000000u)
        writeUInt32(utcResponse, 9, 5u)
        shim.simulateCommandResponse(connId, utcResponse)

        // SET_INFO response
        val infoResponse = ByteArray(2)
        infoResponse[0] = CommandId.SET_INFO
        shim.simulateCommandResponse(connId, infoResponse)

        // SET_FINGER_DETECTION response
        val fingerResponse = ByteArray(2)
        fingerResponse[0] = CommandId.SET_FINGER_DETECTION
        shim.simulateCommandResponse(connId, fingerResponse)

        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId))

        // Verify all handshake commands were sent in correct order
        val cmdBytes = shim.writtenCommands.map { it.second[0] }
        assertEquals(CommandId.GET_DAQ_CONFIG, cmdBytes[0])
        assertEquals(CommandId.GET_DEVICE_STATUS, cmdBytes[1])
        assertEquals(CommandId.SET_UTC, cmdBytes[2])
        assertEquals(CommandId.SET_INFO, cmdBytes[3])
        assertEquals(CommandId.SET_FINGER_DETECTION, cmdBytes[4])

        api.destroy()
    }

    @Test
    fun connect_tier1_noSetInfo_noDaqConfig() = runTest {
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource())
        shim.callback = api.shimCallback

        val connId = api.connect("device_handle")
        shim.simulateDisReads(connId, "2.4.0.0")

        // GET_DEVICE_STATUS (no GET_DAQ_CONFIG for Tier 1)
        val statusResponse = ByteArray(34)
        statusResponse[0] = CommandId.GET_DEVICE_STATUS
        statusResponse[1] = 0x01
        statusResponse[10] = 75
        statusResponse[27] = 0x05 // bit0: needsSetUtc, bit2: fingerDet NOT needed
        shim.simulateCommandResponse(connId, statusResponse)

        // SET_UTC response
        val utcResponse = ByteArray(13)
        utcResponse[0] = CommandId.SET_UTC
        shim.simulateCommandResponse(connId, utcResponse)

        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId))

        // Should have sent: GET_DEVICE_STATUS, SET_UTC — no GET_DAQ_CONFIG, no SET_INFO
        val cmdBytes = shim.writtenCommands.map { it.second[0] }
        assertEquals(CommandId.GET_DEVICE_STATUS, cmdBytes[0])
        assertEquals(CommandId.SET_UTC, cmdBytes[1])
        assertEquals(2, cmdBytes.size)

        api.destroy()
    }
}
