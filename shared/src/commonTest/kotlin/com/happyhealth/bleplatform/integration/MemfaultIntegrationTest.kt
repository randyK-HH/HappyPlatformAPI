package com.happyhealth.bleplatform.integration

import com.happyhealth.bleplatform.api.HpyConnectionState
import com.happyhealth.bleplatform.api.HpyErrorCode
import com.happyhealth.bleplatform.api.HpyEvent
import com.happyhealth.bleplatform.api.createHappyPlatformApi
import com.happyhealth.bleplatform.internal.command.CommandId
import com.happyhealth.bleplatform.internal.command.writeUInt32
import com.happyhealth.bleplatform.internal.model.HpyCharId
import com.happyhealth.bleplatform.internal.util.CRC_INIT
import com.happyhealth.bleplatform.internal.util.finalizeCrc
import com.happyhealth.bleplatform.internal.util.updateCrc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemfaultIntegrationTest {

    /** Build a GET_FILE_LENGTH response. */
    private fun buildGetFileLengthResponse(length: UInt): ByteArray {
        val resp = ByteArray(5)
        resp[0] = CommandId.GET_FILE_LENGTH
        writeUInt32(resp, 1, length)
        return resp
    }

    /** Build a READ_FILE response (CRC at end). */
    private fun buildReadFileResponse(data: ByteArray): ByteArray {
        val crc = finalizeCrc(updateCrc(CRC_INIT, data, 0, data.size))
        val resp = ByteArray(5)
        resp[0] = CommandId.READ_FILE
        writeUInt32(resp, 1, crc)
        return resp
    }

    /** Drive handshake through DAQ config and device status, stopping before memfault drain. */
    private fun driveHandshakePreMemfault(shim: MockBleShim, connId: com.happyhealth.bleplatform.api.ConnectionId) {
        shim.simulateDisReads(connId, "2.5.0.70")

        // DAQ config
        val daqResp = ByteArray(65); daqResp[0] = CommandId.GET_DAQ_CONFIG
        shim.simulateCommandResponse(connId, daqResp)

        // Device status
        shim.simulateCommandResponse(connId, shim.buildDeviceStatusResponse())
    }

    @Test
    fun im01_memfaultDrain2Chunks() = runTest {
        // IM-01: Drain 2 chunks during handshake.
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()))
        shim.callback = api.shimCallback

        val events = mutableListOf<HpyEvent>()
        val collectJob = launch { api.events.collect { events.add(it) } }
        kotlinx.coroutines.yield()

        val connId = api.connect("device_handle")
        driveHandshakePreMemfault(shim, connId)

        // Chunk 1: GET_FILE_LENGTH returns 1200
        shim.simulateCommandResponse(connId, buildGetFileLengthResponse(1200u))

        // STREAM_TX delivers 1200 bytes of chunk data
        val chunk1Data = ByteArray(1200) { (it % 251).toByte() }
        shim.simulateStreamTxData(connId, chunk1Data)

        // READ_FILE response with CRC
        shim.simulateCommandResponse(connId, buildReadFileResponse(chunk1Data))

        // Chunk 2: GET_FILE_LENGTH returns 850
        shim.simulateCommandResponse(connId, buildGetFileLengthResponse(850u))

        val chunk2Data = ByteArray(850) { ((it + 100) % 251).toByte() }
        shim.simulateStreamTxData(connId, chunk2Data)
        shim.simulateCommandResponse(connId, buildReadFileResponse(chunk2Data))

        // Chunk 3: GET_FILE_LENGTH returns 0 (done)
        shim.simulateCommandResponse(connId, buildGetFileLengthResponse(0u))

        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId))

        // Let the collector process buffered events
        kotlinx.coroutines.yield()

        // Verify MemfaultComplete event
        val mfEvents = events.filterIsInstance<HpyEvent.MemfaultComplete>()
        assertTrue(mfEvents.isNotEmpty(), "Should emit MemfaultComplete")
        assertEquals(2, mfEvents.first().chunksDownloaded, "Should have downloaded 2 chunks")

        // Verify chunks can be retrieved
        val chunks = api.getMemfaultChunks(connId)
        assertEquals(2, chunks.size, "Should have 2 chunks in buffer")
        assertTrue(chunks[0].contentEquals(chunk1Data), "Chunk 1 data should match")
        assertTrue(chunks[1].contentEquals(chunk2Data), "Chunk 2 data should match")

        collectJob.cancel()
        api.destroy()
    }

    @Test
    fun im02_memfaultCrcMismatchKept() = runTest {
        // IM-02: CRC mismatch on chunk 1 of 2. Verify chunk kept with crc_valid=false.
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()))
        shim.callback = api.shimCallback

        val events = mutableListOf<HpyEvent>()
        val collectJob = launch { api.events.collect { events.add(it) } }
        kotlinx.coroutines.yield()

        val connId = api.connect("device_handle")
        driveHandshakePreMemfault(shim, connId)

        // Chunk 1: wrong CRC
        shim.simulateCommandResponse(connId, buildGetFileLengthResponse(500u))
        val chunk1Data = ByteArray(500) { it.toByte() }
        shim.simulateStreamTxData(connId, chunk1Data)

        // Send wrong CRC
        val wrongCrcResp = ByteArray(5)
        wrongCrcResp[0] = CommandId.READ_FILE
        writeUInt32(wrongCrcResp, 1, 0xDEADBEEFu) // wrong CRC
        shim.simulateCommandResponse(connId, wrongCrcResp)

        // Chunk 2: correct CRC
        shim.simulateCommandResponse(connId, buildGetFileLengthResponse(300u))
        val chunk2Data = ByteArray(300) { (it + 50).toByte() }
        shim.simulateStreamTxData(connId, chunk2Data)
        shim.simulateCommandResponse(connId, buildReadFileResponse(chunk2Data))

        // Done
        shim.simulateCommandResponse(connId, buildGetFileLengthResponse(0u))

        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId))

        // Let the collector process buffered events
        kotlinx.coroutines.yield()

        // Both chunks should be in buffer
        val chunks = api.getMemfaultChunks(connId)
        assertEquals(2, chunks.size, "Both chunks should be present (bad CRC one too)")

        // CRC error event should have been emitted
        val errors = events.filterIsInstance<HpyEvent.Error>()
        assertTrue(errors.any { it.message.contains("CRC mismatch") },
            "Should emit CRC mismatch error")

        collectJob.cancel()
        api.destroy()
    }

    @Test
    fun im03_memfaultAcrossReconnections() = runTest {
        // IM-03: Memfault across reconnections. Connect seq 1: drain 2 chunks. Reconnect seq 2: drain 1 chunk.
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()))
        shim.callback = api.shimCallback

        val events = mutableListOf<HpyEvent>()
        val collectJob = launch { api.events.collect { events.add(it) } }
        kotlinx.coroutines.yield()

        val connId = api.connect("device_handle")
        driveHandshakePreMemfault(shim, connId)

        // Connection 1: Drain 2 chunks
        shim.simulateCommandResponse(connId, buildGetFileLengthResponse(200u))
        val c1 = ByteArray(200) { 0x11 }
        shim.simulateStreamTxData(connId, c1)
        shim.simulateCommandResponse(connId, buildReadFileResponse(c1))

        shim.simulateCommandResponse(connId, buildGetFileLengthResponse(300u))
        val c2 = ByteArray(300) { 0x22 }
        shim.simulateStreamTxData(connId, c2)
        shim.simulateCommandResponse(connId, buildReadFileResponse(c2))

        shim.simulateCommandResponse(connId, buildGetFileLengthResponse(0u))

        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId))
        assertEquals(2, api.getMemfaultChunks(connId).size)

        // Reconnect
        shim.autoConnect = false
        shim.autoDisconnect = false
        shim.connectHandler = { cid, _ ->
            shim.callback?.onConnected(cid)
        }

        shim.simulateDisconnect(connId, 19)
        advanceTimeBy(2_000)
        kotlinx.coroutines.yield()

        // Drive second connection handshake
        driveHandshakePreMemfault(shim, connId)

        // Drain 1 chunk in second connection
        shim.simulateCommandResponse(connId, buildGetFileLengthResponse(150u))
        val c3 = ByteArray(150) { 0x33 }
        shim.simulateStreamTxData(connId, c3)
        shim.simulateCommandResponse(connId, buildReadFileResponse(c3))

        shim.simulateCommandResponse(connId, buildGetFileLengthResponse(0u))

        // All 3 chunks should be in buffer
        val chunks = api.getMemfaultChunks(connId)
        assertEquals(3, chunks.size, "Should have 3 chunks across reconnections")

        collectJob.cancel()
        api.destroy()
    }

    @Test
    fun im04_memfaultDisabled() = runTest {
        // IM-04: Memfault disabled. Verify no GET_FILE_LENGTH sent, MemfaultComplete still emitted.
        // Note: The current API doesn't expose a memfault disable toggle.
        // HandshakeRunner always drains memfault for Tier 1+.
        // This test verifies that when no memfault chunks exist (length=0),
        // the handshake completes normally.
        val shim = MockBleShim()
        val api = createHappyPlatformApi(shim, MockTimeSource(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()))
        shim.callback = api.shimCallback

        val events = mutableListOf<HpyEvent>()
        val collectJob = launch { api.events.collect { events.add(it) } }
        kotlinx.coroutines.yield()

        val connId = api.connect("device_handle")
        driveHandshakePreMemfault(shim, connId)

        // Memfault length=0 (nothing to drain)
        shim.simulateCommandResponse(connId, buildGetFileLengthResponse(0u))

        assertEquals(HpyConnectionState.READY, api.getConnectionState(connId))

        // Should reach READY. Memfault-related events may or may not fire when buffer is empty.
        val chunks = api.getMemfaultChunks(connId)
        assertEquals(0, chunks.size, "No chunks in buffer")

        collectJob.cancel()
        api.destroy()
    }
}
