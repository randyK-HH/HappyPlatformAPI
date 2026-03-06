package com.happyhealth.bleplatform.integration

import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.bleplatform.api.HpyConnectionState
import com.happyhealth.bleplatform.api.HpyEvent
import com.happyhealth.bleplatform.api.HpyResult
import com.happyhealth.bleplatform.api.createHappyPlatformApi
import com.happyhealth.bleplatform.internal.command.CommandId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MultiConnectionTest {

    @Test
    fun ic01_twoSimultaneousConnections() = runTest {
        // IC-01: Two connections, independent state.
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()))
        shim.callback = api.shimCallback

        val events = mutableListOf<HpyEvent>()
        val collectJob = launch { api.events.collect { events.add(it) } }
        kotlinx.coroutines.yield()

        // Connect ring A
        val connA = api.connect("device_A")
        assertTrue(connA.value >= 0)
        shim.driveToReady(connA, "2.5.0.70", unsyncedFrames = 10)
        assertEquals(HpyConnectionState.READY, api.getConnectionState(connA))

        // Connect ring B
        val connB = api.connect("device_B")
        assertTrue(connB.value >= 0)
        assertNotEquals(connA.value, connB.value, "Should use different slots")
        shim.driveToReady(connB, "2.5.0.70")
        assertEquals(HpyConnectionState.READY, api.getConnectionState(connB))

        // Start download on A
        api.startDownload(connA)
        shim.simulateCommandResponse(connA, shim.buildDeviceStatusResponse(unsyncedFrames = 10))
        kotlinx.coroutines.yield()
        assertEquals(HpyConnectionState.DOWNLOADING, api.getConnectionState(connA))

        // B should still be READY
        assertEquals(HpyConnectionState.READY, api.getConnectionState(connB))

        // Send command on B — should succeed (not rejected by A's download)
        val result = api.getDeviceStatus(connB)
        assertEquals(HpyResult.Ok, result, "Command on B should succeed while A downloads")

        // Respond to B's command
        shim.simulateCommandResponse(connB, shim.buildDeviceStatusResponse(soc = 80))

        // B should still be READY
        assertEquals(HpyConnectionState.READY, api.getConnectionState(connB))

        // Let collector process buffered events
        kotlinx.coroutines.yield()

        // Verify events have correct connId
        val bStatusEvents = events.filterIsInstance<HpyEvent.DeviceStatus>().filter { it.connId == connB }
        assertTrue(bStatusEvents.any { it.status.soc == 80 }, "B's status should have soc=80")

        collectJob.cancel()
        api.destroy()
    }

    @Test
    fun ic02_maxConnections8Slots() = runTest {
        // IC-02: Max 8 connections. 9th rejected.
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()))
        shim.callback = api.shimCallback

        // Connect 8 devices
        val connIds = mutableListOf<ConnectionId>()
        for (i in 0 until 8) {
            val connId = api.connect("device_$i")
            assertTrue(connId.value >= 0, "Connection $i should succeed")
            connIds.add(connId)
            // Drive each to READY
            shim.driveToReady(connId, "2.5.0.70")
        }

        assertEquals(8, connIds.size)
        assertEquals(8, api.getActiveConnections().size)

        // 9th connection should fail
        val connId9 = api.connect("device_8")
        assertEquals(ConnectionId.INVALID, connId9, "9th connection should return INVALID")

        collectJob@ for (id in connIds) {
            assertEquals(HpyConnectionState.READY, api.getConnectionState(id))
        }

        api.destroy()
    }

    @Test
    fun ic03_slotReuseAfterDisconnect() = runTest {
        // IC-03: Connection slot reuse after disconnect.
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()))
        shim.callback = api.shimCallback

        val events = mutableListOf<HpyEvent>()
        val collectJob = launch { api.events.collect { events.add(it) } }
        kotlinx.coroutines.yield()

        // Connect ring on slot 0
        val connId1 = api.connect("device_1")
        assertEquals(0, connId1.value)
        shim.driveToReady(connId1, "2.5.0.70")
        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId1))

        // Disconnect
        api.disconnect(connId1)
        kotlinx.coroutines.yield()

        // Slot should be freed — connect a different ring
        val connId2 = api.connect("device_2")
        assertTrue(connId2.value >= 0, "Should get a valid slot")
        assertEquals(0, connId2.value, "Should reuse slot 0")

        shim.driveToReady(connId2, "2.5.0.54")
        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId2))

        // Let collector process buffered events
        kotlinx.coroutines.yield()

        // Verify new device info is from device_2's handshake
        val deviceInfoEvents = events.filterIsInstance<HpyEvent.DeviceInfo>()
        assertTrue(deviceInfoEvents.size >= 2, "Should have device info from both connections")

        collectJob.cancel()
        api.destroy()
    }
}
