package com.happyhealth.bleplatform.integration

import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.bleplatform.internal.command.CommandId
import com.happyhealth.bleplatform.internal.command.writeUInt32
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

    // ---- Configurable behavior ----

    /** Set to customize which chars are discovered. null = all standard chars. */
    var discoveredChars: Set<HpyCharId>? = null

    /** RSSI value returned by readRssi. */
    var rssiValue: Int = -50

    /** Whether readRssi auto-fires onRssiRead. */
    var autoRssi: Boolean = false

    /** Whether connect auto-fires onConnected. Set false for reconnect tests. */
    var autoConnect: Boolean = true

    /** Whether disconnect auto-fires onDisconnected. Set false for FW update tests. */
    var autoDisconnect: Boolean = true

    /** Set of chars where writeCharacteristic returns non-zero status. */
    var writeFailChars: Set<HpyCharId> = emptySet()

    /** Custom connect handler. Called instead of default auto-connect. */
    var connectHandler: ((ConnectionId, Any) -> Unit)? = null

    /** Custom disconnect handler. Called instead of default auto-disconnect. */
    var disconnectHandler: ((ConnectionId) -> Unit)? = null

    // ---- L2CAP tracking ----
    var l2capOpened = false
    var l2capPsm: Int = 0
    var l2capClosed = false
    var l2capStreamSendCalls = mutableListOf<L2capStreamSendCall>()
    var l2capStartReceiveCalls = mutableListOf<Pair<ConnectionId, Int>>()

    data class L2capStreamSendCall(
        val connId: ConnectionId,
        val psm: Int,
        val imageSize: Int,
        val blockSize: Int,
        val interBlockDelayMs: Long,
        val drainDelayMs: Long,
    )

    // ---- SUOTA write tracking ----
    val suotaWrites = mutableListOf<Pair<HpyCharId, ByteArray>>()

    override fun scanStart() { scanStarted = true }
    override fun scanStop() { scanStopped = true }

    override fun connect(connId: ConnectionId, deviceHandle: Any) {
        val handler = connectHandler
        if (handler != null) {
            handler(connId, deviceHandle)
        } else if (autoConnect) {
            callback?.onConnected(connId)
        }
    }

    override fun disconnect(connId: ConnectionId) {
        val handler = disconnectHandler
        if (handler != null) {
            handler(connId)
        } else if (autoDisconnect) {
            callback?.onDisconnected(connId, 0)
        }
    }

    override fun discoverServices(connId: ConnectionId) {
        val chars = discoveredChars ?: setOf(
            HpyCharId.CMD_RX, HpyCharId.CMD_TX, HpyCharId.STREAM_TX,
            HpyCharId.DEBUG_TX, HpyCharId.FRAME_TX,
            HpyCharId.DIS_SERIAL_NUMBER, HpyCharId.DIS_FW_VERSION,
            HpyCharId.DIS_SW_VERSION, HpyCharId.DIS_MANUFACTURER_NAME,
            HpyCharId.DIS_MODEL_NUMBER,
            HpyCharId.SUOTA_MEM_DEV, HpyCharId.SUOTA_PATCH_LEN,
            HpyCharId.SUOTA_PATCH_DATA, HpyCharId.SUOTA_STATUS,
        )
        callback?.onServicesDiscovered(connId, chars)
    }

    override fun writeCharacteristic(connId: ConnectionId, charId: HpyCharId, data: ByteArray, writeType: WriteType) {
        writtenCommands.add(connId to data.copyOf())

        // Track SUOTA writes separately
        if (charId.name.startsWith("SUOTA_")) {
            suotaWrites.add(charId to data.copyOf())
        }

        val status = if (charId in writeFailChars) 1 else 0
        callback?.onWriteComplete(connId, charId, status)
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

    override fun readRssi(connId: ConnectionId) {
        if (autoRssi) {
            callback?.onRssiRead(connId, rssiValue)
        }
    }

    override fun l2capOpen(connId: ConnectionId, psm: Int) {
        l2capOpened = true
        l2capPsm = psm
    }

    override fun l2capStartReceiving(connId: ConnectionId, expectedFrames: Int) {
        l2capStartReceiveCalls.add(connId to expectedFrames)
    }

    override fun l2capClose(connId: ConnectionId) {
        l2capClosed = true
    }

    override fun l2capStreamSend(connId: ConnectionId, psm: Int, imageBytes: ByteArray, blockSize: Int, interBlockDelayMs: Long, drainDelayMs: Long) {
        l2capStreamSendCalls.add(L2capStreamSendCall(connId, psm, imageBytes.size, blockSize, interBlockDelayMs, drainDelayMs))
    }

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

    fun simulateL2capConnected(connId: ConnectionId) {
        callback?.onL2capConnected(connId)
    }

    fun simulateL2capFrame(connId: ConnectionId, frameData: ByteArray) {
        callback?.onL2capFrame(connId, frameData)
    }

    fun simulateL2capBatchComplete(connId: ConnectionId, framesReceived: Int, crcValid: Boolean) {
        callback?.onL2capBatchComplete(connId, framesReceived, crcValid)
    }

    fun simulateL2capError(connId: ConnectionId, message: String) {
        callback?.onL2capError(connId, message)
    }

    fun simulateDisconnect(connId: ConnectionId, status: Int = 0) {
        callback?.onDisconnected(connId, status)
    }

    fun simulateStreamTxData(connId: ConnectionId, data: ByteArray) {
        callback?.onCharacteristicChanged(connId, HpyCharId.STREAM_TX, data)
    }

    fun simulateFrameTxNotification(connId: ConnectionId, data: ByteArray) {
        callback?.onCharacteristicChanged(connId, HpyCharId.FRAME_TX, data)
    }

    fun simulateSuotaStatus(connId: ConnectionId, code: Int) {
        callback?.onCharacteristicChanged(connId, HpyCharId.SUOTA_STATUS, byteArrayOf(code.toByte()))
    }

    fun simulateRssiRead(connId: ConnectionId, rssi: Int) {
        callback?.onRssiRead(connId, rssi)
    }

    fun simulateL2capSendProgress(connId: ConnectionId, blocksSent: Int, blocksTotal: Int) {
        callback?.onL2capSendProgress(connId, blocksSent, blocksTotal)
    }

    fun simulateL2capSendComplete(connId: ConnectionId) {
        callback?.onL2capSendComplete(connId)
    }

    fun simulateL2capSendError(connId: ConnectionId, message: String) {
        callback?.onL2capSendError(connId, message)
    }

    /** Build a device status response with configurable fields. */
    fun buildDeviceStatusResponse(
        soc: Int = 75,
        unsyncedFrames: Int = 0,
        syncFrameCount: UInt = 100u,
        syncFrameReboots: UInt = 5u,
        sendUtcFlags: Int = 0x04,  // 0x04 = no conditionals needed
        daqMode: Int = 0x03,
        phyStatus: Int = 0x01,
        notifSender: Int = 0,
    ): ByteArray {
        val resp = ByteArray(34)
        resp[0] = CommandId.GET_DEVICE_STATUS
        resp[1] = phyStatus.toByte()
        resp[10] = soc.toByte()
        resp[11] = daqMode.toByte()
        resp[12] = (unsyncedFrames and 0xFF).toByte()
        resp[13] = ((unsyncedFrames shr 8) and 0xFF).toByte()
        writeUInt32(resp, 14, syncFrameCount)
        writeUInt32(resp, 18, syncFrameReboots)
        resp[27] = sendUtcFlags.toByte()
        resp[30] = notifSender.toByte()
        return resp
    }

    /** Drive a connection through handshake to READY state (Tier 2, no conditionals, no memfault). */
    fun driveToReady(connId: ConnectionId, fwVersion: String = "2.5.0.70", unsyncedFrames: Int = 0) {
        simulateDisReads(connId, fwVersion)

        // DAQ config response (Tier 2 only)
        val tier = com.happyhealth.bleplatform.internal.model.FirmwareTier.fromVersionString(fwVersion)
        if (tier == com.happyhealth.bleplatform.internal.model.FirmwareTier.TIER_2) {
            val daqResponse = ByteArray(65)
            daqResponse[0] = CommandId.GET_DAQ_CONFIG
            simulateCommandResponse(connId, daqResponse)
        }

        // Device status response (no conditionals needed)
        simulateCommandResponse(connId, buildDeviceStatusResponse(unsyncedFrames = unsyncedFrames))

        // Memfault drain: length=0 (nothing to drain)
        val mfLenResponse = ByteArray(5)
        mfLenResponse[0] = CommandId.GET_FILE_LENGTH
        simulateCommandResponse(connId, mfLenResponse)
    }

    /** Reset all tracking state for reuse. */
    fun resetTracking() {
        writtenCommands.clear()
        subscribedChars.clear()
        readChars.clear()
        suotaWrites.clear()
        l2capOpened = false
        l2capPsm = 0
        l2capClosed = false
        l2capStreamSendCalls.clear()
        l2capStartReceiveCalls.clear()
        scanStarted = false
        scanStopped = false
    }
}

class MockTimeSource : PlatformTimeSource {
    override fun getUtcTimeSeconds(): Long = 1708000000L
    override fun getGmtOffsetHours(): Int = -6
}
