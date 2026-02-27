package com.happyhealth.bleplatform.internal.shim

import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.bleplatform.internal.model.HpyCharId

interface PlatformBleShim {

    fun scanStart()
    fun scanStop()

    fun connect(connId: ConnectionId, deviceHandle: Any)
    fun disconnect(connId: ConnectionId)

    fun discoverServices(connId: ConnectionId)

    fun writeCharacteristic(
        connId: ConnectionId,
        charId: HpyCharId,
        data: ByteArray,
        writeType: WriteType,
    )

    fun readCharacteristic(connId: ConnectionId, charId: HpyCharId)

    fun subscribeNotifications(connId: ConnectionId, charId: HpyCharId, enable: Boolean)

    fun requestMtu(connId: ConnectionId, mtu: Int)

    fun readRssi(connId: ConnectionId)

    fun l2capOpen(connId: ConnectionId, psm: Int)
    fun l2capStartReceiving(connId: ConnectionId, expectedFrames: Int)
    fun l2capClose(connId: ConnectionId)

    fun l2capStreamSend(
        connId: ConnectionId,
        psm: Int,
        imageBytes: ByteArray,
        blockSize: Int,
        interBlockDelayMs: Long,
    )
}

enum class WriteType {
    WITH_RESPONSE,
    WITHOUT_RESPONSE,
}
