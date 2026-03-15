package com.happyhealth.bleplatform.internal.manager

import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.bleplatform.api.HpyConnectionState
import com.happyhealth.bleplatform.api.HpyEvent
import com.happyhealth.bleplatform.internal.connection.ConnectionConfig
import com.happyhealth.bleplatform.internal.connection.ConnectionSlot
import com.happyhealth.bleplatform.internal.model.HpyCharId
import com.happyhealth.bleplatform.internal.model.ScannedDeviceInfo
import com.happyhealth.bleplatform.internal.shim.PlatformBleShim
import com.happyhealth.bleplatform.internal.shim.PlatformTimeSource
import com.happyhealth.bleplatform.internal.shim.ShimCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ConnectionManager(
    private val shim: PlatformBleShim,
    private val timeSource: PlatformTimeSource,
    private val scope: CoroutineScope,
    internal var config: ConnectionConfig = ConnectionConfig(),
) : ShimCallback {

    companion object {
        const val MAX_CONNECTIONS = 8
    }

    private val slots = arrayOfNulls<ConnectionSlot>(MAX_CONNECTIONS)

    val events = MutableSharedFlow<HpyEvent>(extraBufferCapacity = 64)

    private val _discoveredDevices = MutableStateFlow<List<ScannedDeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<ScannedDeviceInfo>> = _discoveredDevices

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    // ---- Scanning ----

    fun scanStart() {
        _discoveredDevices.value = emptyList()
        _isScanning.value = true
        shim.scanStart()
    }

    fun scanStop() {
        _isScanning.value = false
        shim.scanStop()
    }

    // ---- Connection ----

    fun connect(deviceHandle: Any): ConnectionId {
        val slotIdx = slots.indexOfFirst { it == null }
        if (slotIdx < 0) return ConnectionId.INVALID

        val connId = ConnectionId(slotIdx)
        val slot = ConnectionSlot(
            connId = connId,
            shim = shim,
            timeSource = timeSource,
            config = config,
            scope = scope,
            emitEvent = { event -> events.tryEmit(event) },
        )
        slots[slotIdx] = slot
        slot.connect(deviceHandle)
        return connId
    }

    fun disconnect(connId: ConnectionId) {
        val slot = getSlot(connId) ?: return
        slot.disconnect()
        slots[connId.value] = null
    }

    fun getSlot(connId: ConnectionId): ConnectionSlot? {
        if (connId.value < 0 || connId.value >= MAX_CONNECTIONS) return null
        return slots[connId.value]
    }

    fun getActiveConnections(): List<ConnectionSlot> {
        return slots.filterNotNull()
    }

    fun updateSlotConfig(connId: ConnectionId, newConfig: ConnectionConfig) {
        getSlot(connId)?.config = newConfig
    }

    // ---- ShimCallback implementation ----

    override fun onDeviceDiscovered(deviceHandle: Any, name: String, address: String, rssi: Int,
                                    manufacturerData: ByteArray?) {
        scope.launch {
            // Parse ring color and size from manufacturer-specific data.
            // Format (after company ID): [formatVersion][color][size]
            var ringSize = 0
            var ringColor = 0
            if (manufacturerData != null && manufacturerData.size >= 3) {
                ringColor = manufacturerData[1].toInt() and 0xFF
                ringSize = manufacturerData[2].toInt() and 0xFF
            }
            val existing = _discoveredDevices.value.toMutableList()
            val idx = existing.indexOfFirst { it.address == address }
            val info = ScannedDeviceInfo(deviceHandle, name, address, rssi, ringSize, ringColor)
            if (idx >= 0) existing[idx] = info else existing.add(info)
            _discoveredDevices.value = existing
            events.tryEmit(HpyEvent.DeviceDiscovered(name, address, rssi, deviceHandle))
        }
    }

    override fun onConnected(connId: ConnectionId) {
        scope.launch { getSlot(connId)?.onConnected() }
    }

    override fun onDisconnected(connId: ConnectionId, status: Int) {
        scope.launch {
            val slot = getSlot(connId)
            slot?.onDisconnected(status)
            if (slot?.state == HpyConnectionState.DISCONNECTED) {
                slots[connId.value] = null
            }
        }
    }

    override fun onServicesDiscovered(connId: ConnectionId, availableChars: Set<HpyCharId>) {
        scope.launch { getSlot(connId)?.onServicesDiscovered(availableChars) }
    }

    override fun onCharacteristicRead(connId: ConnectionId, charId: HpyCharId, value: ByteArray) {
        scope.launch { getSlot(connId)?.onCharacteristicRead(charId, value) }
    }

    override fun onCharacteristicReadFailed(connId: ConnectionId, charId: HpyCharId) {
        scope.launch { getSlot(connId)?.onCharacteristicReadFailed(charId) }
    }

    override fun onCharacteristicChanged(connId: ConnectionId, charId: HpyCharId, value: ByteArray) {
        scope.launch { getSlot(connId)?.onCharacteristicChanged(charId, value) }
    }

    override fun onWriteComplete(connId: ConnectionId, charId: HpyCharId, status: Int) {
        scope.launch { getSlot(connId)?.onWriteComplete(charId, status) }
    }

    override fun onDescriptorWritten(connId: ConnectionId, charId: HpyCharId, status: Int) {
        scope.launch { getSlot(connId)?.onDescriptorWritten(charId, status) }
    }

    override fun onMtuChanged(connId: ConnectionId, mtu: Int) {
        scope.launch { getSlot(connId)?.onMtuChanged(mtu) }
    }

    override fun onRssiRead(connId: ConnectionId, rssi: Int) {
        scope.launch { getSlot(connId)?.onRssiRead(rssi) }
    }

    fun readRssi(connId: ConnectionId) {
        shim.readRssi(connId)
    }

    override fun onL2capConnected(connId: ConnectionId) {
        scope.launch { getSlot(connId)?.onL2capConnected() }
    }

    override fun onL2capFrame(connId: ConnectionId, frameData: ByteArray) {
        scope.launch { getSlot(connId)?.onL2capFrame(frameData) }
    }

    override fun onL2capBatchComplete(connId: ConnectionId, framesReceived: Int, crcValid: Boolean) {
        scope.launch { getSlot(connId)?.onL2capBatchComplete(framesReceived, crcValid) }
    }

    override fun onL2capCrcTimeout(connId: ConnectionId, framesReceived: Int) {
        scope.launch { getSlot(connId)?.onL2capCrcTimeout(framesReceived) }
    }

    override fun onL2capThroughputProgress(connId: ConnectionId, packetsReceived: Int, expectedPackets: Int) {
        scope.launch { getSlot(connId)?.onL2capThroughputProgress(packetsReceived, expectedPackets) }
    }

    override fun onL2capThroughputComplete(connId: ConnectionId, packetsReceived: Int, elapsedMs: Long) {
        scope.launch { getSlot(connId)?.onL2capThroughputComplete(packetsReceived, elapsedMs) }
    }

    override fun onL2capThroughputTimeout(connId: ConnectionId, packetsReceived: Int, elapsedMs: Long) {
        scope.launch { getSlot(connId)?.onL2capThroughputTimeout(packetsReceived, elapsedMs) }
    }

    override fun onL2capError(connId: ConnectionId, message: String) {
        scope.launch { getSlot(connId)?.onL2capError(message) }
    }

    override fun onL2capSendProgress(connId: ConnectionId, blocksSent: Int, blocksTotal: Int) {
        scope.launch { getSlot(connId)?.onL2capSendProgress(blocksSent, blocksTotal) }
    }

    override fun onL2capSendComplete(connId: ConnectionId) {
        scope.launch { getSlot(connId)?.onL2capSendComplete() }
    }

    override fun onL2capSendError(connId: ConnectionId, message: String) {
        scope.launch { getSlot(connId)?.onL2capSendError(message) }
    }

    fun destroy() {
        scanStop()
        for (i in slots.indices) {
            slots[i]?.disconnect()
            slots[i] = null
        }
    }
}
