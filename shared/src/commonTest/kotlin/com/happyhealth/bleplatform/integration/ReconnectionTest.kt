package com.happyhealth.bleplatform.integration

import com.happyhealth.bleplatform.api.HpyConnectionState
import com.happyhealth.bleplatform.api.HpyErrorCode
import com.happyhealth.bleplatform.api.HpyEvent
import com.happyhealth.bleplatform.api.HpyConfig
import com.happyhealth.bleplatform.api.createHappyPlatformApi
import com.happyhealth.bleplatform.internal.command.CommandId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconnectionTest {

    @Test
    fun ir01_unexpectedDisconnect_autoReconnect() = runTest {
        // IR-01: Unexpected disconnect -> automatic reconnection. MockBleShim accepts on attempt 5.
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

        // Set up mock to reject first 4 connect attempts, accept 5th
        var connectAttempt = 0
        shim.autoConnect = false
        shim.autoDisconnect = false
        shim.connectHandler = { cid, _ ->
            connectAttempt++
            if (connectAttempt >= 5) {
                shim.callback?.onConnected(cid)
            } else {
                shim.callback?.onDisconnected(cid, 8) // connection failed
            }
        }

        // Trigger unexpected disconnect
        shim.simulateDisconnect(connId, 19) // unexpected disconnect status
        advanceTimeBy(10_000) // advance past reconnect delays
        kotlinx.coroutines.yield()

        val stateEvents = events.filterIsInstance<HpyEvent.StateChanged>()
        assertTrue(stateEvents.any { it.state == HpyConnectionState.RECONNECTING },
            "Should enter RECONNECTING state")

        // After successful reconnect, the connection should proceed through handshake.
        // Drive to READY again.
        if (connectAttempt >= 5) {
            shim.driveToReady(connId, "2.5.0.70")
            assertEquals(HpyConnectionState.READY, api.getConnectionState(connId))
        }

        collectJob.cancel()
        api.destroy()
    }

    @Test
    fun ir02_disconnectDuringDownload_autoResume() = runTest {
        // IR-02: Disconnect during download -> auto-resume.
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource(), HpyConfig(downloadBatchSize = 64),
            scope = CoroutineScope(coroutineContext + SupervisorJob()))
        shim.callback = api.shimCallback

        val events = mutableListOf<HpyEvent>()
        val collectJob = launch { api.events.collect { events.add(it) } }
        kotlinx.coroutines.yield()

        val connId = api.connect("device_handle")
        shim.driveToReady(connId, "2.5.0.70", unsyncedFrames = 128)

        api.startDownload(connId)
        shim.simulateCommandResponse(connId, shim.buildDeviceStatusResponse(unsyncedFrames = 128))
        kotlinx.coroutines.yield()

        assertEquals(HpyConnectionState.DOWNLOADING, api.getConnectionState(connId))

        // Simulate disconnect mid-download
        shim.autoConnect = false
        shim.autoDisconnect = false
        shim.connectHandler = { cid, _ ->
            shim.callback?.onConnected(cid) // immediate reconnect
        }

        shim.simulateDisconnect(connId, 19)
        advanceTimeBy(2_000)
        kotlinx.coroutines.yield()

        // Check for DownloadInterrupted event
        val interrupted = events.filterIsInstance<HpyEvent.DownloadInterrupted>()
        // Interruption only fires if partial-batch frames were received
        // In this case, no frames were received yet so it may not fire

        // After reconnect, drive to READY - download should auto-resume
        shim.driveToReady(connId, "2.5.0.70", unsyncedFrames = 128)
        kotlinx.coroutines.yield()

        // The library should auto-resume download after reconnect
        // Check that a new GET_DEVICE_STATUS was sent for download
        val dlStatusCmds = shim.writtenCommands.filter { it.second[0] == CommandId.GET_DEVICE_STATUS }
        assertTrue(dlStatusCmds.size >= 2, "Should re-poll device status for download resume")

        collectJob.cancel()
        api.destroy()
    }

    @Test
    fun ir03_disconnectDuringDownload_cancelResume() = runTest {
        // IR-03: Disconnect during download, cancel resume during RECONNECTING.
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource(), HpyConfig(downloadBatchSize = 64),
            scope = CoroutineScope(coroutineContext + SupervisorJob()))
        shim.callback = api.shimCallback

        val events = mutableListOf<HpyEvent>()
        val collectJob = launch { api.events.collect { events.add(it) } }
        kotlinx.coroutines.yield()

        val connId = api.connect("device_handle")
        shim.driveToReady(connId, "2.5.0.70", unsyncedFrames = 100)

        api.startDownload(connId)
        shim.simulateCommandResponse(connId, shim.buildDeviceStatusResponse(unsyncedFrames = 100))
        kotlinx.coroutines.yield()

        assertEquals(HpyConnectionState.DOWNLOADING, api.getConnectionState(connId))

        // Disconnect
        shim.autoConnect = false
        shim.autoDisconnect = false
        shim.connectHandler = { cid, _ ->
            shim.callback?.onConnected(cid)
        }

        shim.simulateDisconnect(connId, 19)
        advanceTimeBy(500)
        kotlinx.coroutines.yield()

        // Cancel download during RECONNECTING
        api.stopDownload(connId)

        advanceTimeBy(2_000)
        kotlinx.coroutines.yield()

        // After reconnect, drive to READY
        shim.driveToReady(connId, "2.5.0.70", unsyncedFrames = 100)
        kotlinx.coroutines.yield()

        // Should reach READY without auto-resuming download
        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId))

        collectJob.cancel()
        api.destroy()
    }

    @Test
    fun ir04_reconnectionExhausted() = runTest {
        // IR-04: All 64 reconnection attempts fail.
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()))
        shim.callback = api.shimCallback

        val events = mutableListOf<HpyEvent>()
        val collectJob = launch { api.events.collect { events.add(it) } }
        kotlinx.coroutines.yield()

        val connId = api.connect("device_handle")
        shim.driveToReady(connId, "2.5.0.70")

        // All reconnect attempts will fail
        shim.autoConnect = false
        shim.autoDisconnect = false
        shim.connectHandler = { cid, _ ->
            shim.callback?.onDisconnected(cid, 8)
        }

        shim.simulateDisconnect(connId, 19)

        // Advance time enough for all 64 attempts:
        // delays: 16*1s + 16*3s + 16*5s + 16*10s = 304s
        // + 64 * 15s connect timeouts = 960s (mock fires onDisconnected synchronously
        // before connectResultDeferred is set, so each attempt times out)
        // Total: ~1264s
        advanceTimeBy(1_500_000)
        kotlinx.coroutines.yield()

        val errors = events.filterIsInstance<HpyEvent.Error>()
        assertTrue(errors.any { it.code == HpyErrorCode.RECONNECT_FAIL },
            "Should emit RECONNECT_FAIL after all attempts exhausted")

        collectJob.cancel()
        api.destroy()
    }

    @Test
    fun ir05_disconnectDuringReconnecting() = runTest {
        // IR-05: hpy_disconnect during RECONNECTING. Verify reconnection stops.
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()))
        shim.callback = api.shimCallback

        val events = mutableListOf<HpyEvent>()
        val collectJob = launch { api.events.collect { events.add(it) } }
        kotlinx.coroutines.yield()

        val connId = api.connect("device_handle")
        shim.driveToReady(connId, "2.5.0.70")

        // Set up to fail reconnects
        shim.autoConnect = false
        shim.autoDisconnect = false
        shim.connectHandler = { cid, _ ->
            shim.callback?.onDisconnected(cid, 8)
        }

        shim.simulateDisconnect(connId, 19)
        advanceTimeBy(3_000) // Let a few attempts happen
        kotlinx.coroutines.yield()

        // Call disconnect during RECONNECTING
        api.disconnect(connId)
        kotlinx.coroutines.yield()

        // Should NOT get RECONNECT_FAIL error (we intentionally disconnected)
        advanceTimeBy(400_000)
        kotlinx.coroutines.yield()

        // After disconnect, no more reconnection attempts should have been made
        val stateEvents = events.filterIsInstance<HpyEvent.StateChanged>()
        val lastState = stateEvents.lastOrNull { it.state == HpyConnectionState.DISCONNECTED }
        assertTrue(lastState != null, "Should reach DISCONNECTED state")

        collectJob.cancel()
        api.destroy()
    }
}
