package com.happyhealth.bleplatform.integration

import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.bleplatform.internal.model.HpyCharId
import com.happyhealth.bleplatform.internal.shim.PlatformBleShim
import com.happyhealth.bleplatform.internal.shim.PlatformTimeSource
import com.happyhealth.bleplatform.internal.shim.ShimCallback
import com.happyhealth.bleplatform.internal.shim.WriteType

class MockBleShim : PlatformBleShim {

    var callback: ShimCallback? = null
    val writtenCommands = mutableListOf<Pair<ConnectionId, ByteArray>>()
    val subscribedChars = mutableListOf<Pair<ConnectionId, HpyCharId>>()
    val readChars = mutableListOf<Pair<ConnectionId, HpyCharId>>()

    var scanStarted = false
    var scanStopped = false

    override fun scanStart() { scanStarted = true }
    override fun scanStop() { scanStopped = true }

    override fun connect(connId: ConnectionId, deviceHandle: Any) {
        // Simulate immediate connection
        callback?.onConnected(connId)
    }

    override fun disconnect(connId: ConnectionId) {
        callback?.onDisconnected(connId, 0)
    }

    override fun discoverServices(connId: ConnectionId) {
        // Simulate discovering all standard HPY chars
        val chars = setOf(
            HpyCharId.CMD_RX, HpyCharId.CMD_TX, HpyCharId.STREAM_TX,
            HpyCharId.DEBUG_TX, HpyCharId.FRAME_TX,
            HpyCharId.DIS_SERIAL_NUMBER, HpyCharId.DIS_FW_VERSION,
            HpyCharId.DIS_SW_VERSION, HpyCharId.DIS_MANUFACTURER_NAME,
            HpyCharId.DIS_MODEL_NUMBER,
        )
        callback?.onServicesDiscovered(connId, chars)
    }

    override fun writeCharacteristic(connId: ConnectionId, charId: HpyCharId, data: ByteArray, writeType: WriteType) {
        writtenCommands.add(connId to data.copyOf())
        // For CMD_RX, simulate immediate write complete (WNR)
        callback?.onWriteComplete(connId, charId, 0)
    }

    override fun readCharacteristic(connId: ConnectionId, charId: HpyCharId) {
        readChars.add(connId to charId)
    }

    override fun subscribeNotifications(connId: ConnectionId, charId: HpyCharId, enable: Boolean) {
        subscribedChars.add(connId to charId)
        callback?.onDescriptorWritten(connId, charId, 0)
    }

    override fun requestMtu(connId: ConnectionId, mtu: Int) {
        callback?.onMtuChanged(connId, mtu)
    }

    override fun readRssi(connId: ConnectionId) {}

    override fun l2capOpen(connId: ConnectionId, psm: Int) {}
    override fun l2capStartReceiving(connId: ConnectionId, expectedFrames: Int) {}
    override fun l2capClose(connId: ConnectionId) {}
    override fun l2capStreamSend(connId: ConnectionId, psm: Int, imageBytes: ByteArray, blockSize: Int, interBlockDelayMs: Long) {}

    // ---- Test helpers ----

    fun simulateDisReads(connId: ConnectionId, fwVersion: String) {
        callback?.onCharacteristicRead(connId, HpyCharId.DIS_SERIAL_NUMBER, "SN12345".encodeToByteArray())
        callback?.onCharacteristicRead(connId, HpyCharId.DIS_FW_VERSION, fwVersion.encodeToByteArray())
        callback?.onCharacteristicRead(connId, HpyCharId.DIS_SW_VERSION, "1.0.0".encodeToByteArray())
        callback?.onCharacteristicRead(connId, HpyCharId.DIS_MANUFACTURER_NAME, "HappyHealth".encodeToByteArray())
        callback?.onCharacteristicRead(connId, HpyCharId.DIS_MODEL_NUMBER, "Ring v2".encodeToByteArray())
    }

    fun simulateCommandResponse(connId: ConnectionId, data: ByteArray) {
        callback?.onCharacteristicChanged(connId, HpyCharId.CMD_TX, data)
    }
}

class MockTimeSource : PlatformTimeSource {
    override fun getUtcTimeSeconds(): Long = 1708000000L
    override fun getGmtOffsetHours(): Int = -6
}
