package com.happyhealth.bleplatform.internal.shim

import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.bleplatform.internal.model.HpyCharId

interface ShimCallback {

    fun onDeviceDiscovered(deviceHandle: Any, name: String, address: String, rssi: Int)

    fun onConnected(connId: ConnectionId)

    fun onDisconnected(connId: ConnectionId, status: Int)

    fun onServicesDiscovered(connId: ConnectionId, availableChars: Set<HpyCharId>)

    fun onCharacteristicRead(connId: ConnectionId, charId: HpyCharId, value: ByteArray)

    fun onCharacteristicChanged(connId: ConnectionId, charId: HpyCharId, value: ByteArray)

    fun onWriteComplete(connId: ConnectionId, charId: HpyCharId, status: Int)

    fun onDescriptorWritten(connId: ConnectionId, charId: HpyCharId, status: Int)

    fun onMtuChanged(connId: ConnectionId, mtu: Int)

    fun onRssiRead(connId: ConnectionId, rssi: Int)

    fun onL2capConnected(connId: ConnectionId)
    fun onL2capFrame(connId: ConnectionId, frameData: ByteArray)
    fun onL2capBatchComplete(connId: ConnectionId, framesReceived: Int, crcValid: Boolean)
    fun onL2capError(connId: ConnectionId, message: String)

    fun onL2capSendProgress(connId: ConnectionId, blocksSent: Int, blocksTotal: Int)
    fun onL2capSendComplete(connId: ConnectionId)
    fun onL2capSendError(connId: ConnectionId, message: String)
}
