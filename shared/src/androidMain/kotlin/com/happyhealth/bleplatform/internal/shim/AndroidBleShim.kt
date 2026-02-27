package com.happyhealth.bleplatform.internal.shim

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.bleplatform.internal.command.CommandId
import com.happyhealth.bleplatform.internal.command.readUInt32
import com.happyhealth.bleplatform.internal.model.HpyCharId
import com.happyhealth.bleplatform.internal.util.CRC_INIT
import com.happyhealth.bleplatform.internal.util.finalizeCrc
import com.happyhealth.bleplatform.internal.util.updateCrc
import kotlinx.coroutines.*

private const val TAG = "AndroidBleShim"

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
class AndroidBleShim(private val context: Context) : PlatformBleShim {

    var callback: ShimCallback? = null

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var scanner: BluetoothLeScanner? = null

    // Per-connection GATT handles
    private val gattMap = mutableMapOf<Int, BluetoothGatt>()
    private val deviceMap = mutableMapOf<Int, BluetoothDevice>()

    // L2CAP state per connection
    private val l2capSockets = mutableMapOf<Int, BluetoothSocket>()
    private val l2capJobs = mutableMapOf<Int, Job>()
    private val l2capScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- Scanning ----

    override fun scanStart() {
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available (adapter=${adapter != null}, enabled=${adapter?.isEnabled})")
            return
        }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner is null — is Bluetooth fully enabled?")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(CharacteristicMap.UUID_HPY_HCS))
            .build()
        Log.d(TAG, "Starting BLE scan with HCS filter")
        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    override fun scanStop() {
        Log.d(TAG, "scanStop() called")
        scanner?.stopScan(scanCallback)
        scanner = null
    }

    private fun extractManufacturerData(result: ScanResult): ByteArray? {
        val mfgData = result.scanRecord?.manufacturerSpecificData ?: return null
        if (mfgData.size() == 0) return null
        // Take the first entry — data bytes after the 2-byte company ID
        return mfgData.valueAt(0)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: return
            val mfgData = extractManufacturerData(result)
            Log.d(TAG, "Scan found: $name (${device.address}) rssi=${result.rssi}")
            callback?.onDeviceDiscovered(device, name, device.address, result.rssi, mfgData)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            Log.d(TAG, "Batch scan results: ${results.size} entries")
            for (result in results) {
                val device = result.device
                val name = result.scanRecord?.deviceName ?: device.name ?: continue
                val mfgData = extractManufacturerData(result)
                Log.d(TAG, "  Batch: $name (${device.address}) rssi=${result.rssi}")
                callback?.onDeviceDiscovered(device, name, device.address, result.rssi, mfgData)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REGISTRATION_FAILED"
                SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                else -> "UNKNOWN($errorCode)"
            }
            Log.e(TAG, "BLE scan failed: $reason")
        }
    }

    // ---- Connection ----

    override fun connect(connId: ConnectionId, deviceHandle: Any) {
        val device = deviceHandle as? BluetoothDevice ?: return
        deviceMap[connId.value] = device
        device.connectGatt(context, false, GattCallback(connId), BluetoothDevice.TRANSPORT_LE)
    }

    override fun disconnect(connId: ConnectionId) {
        gattMap[connId.value]?.close()
        gattMap.remove(connId.value)
        deviceMap.remove(connId.value)
    }

    override fun discoverServices(connId: ConnectionId) {
        gattMap[connId.value]?.discoverServices()
    }

    override fun writeCharacteristic(
        connId: ConnectionId,
        charId: HpyCharId,
        data: ByteArray,
        writeType: WriteType,
    ) {
        val g = gattMap[connId.value] ?: return
        val loc = CharacteristicMap.getLocation(charId) ?: return
        val service = g.getService(loc.serviceUuid) ?: run {
            Log.e(TAG, "Service not found for $charId"); return
        }
        val char = service.getCharacteristic(loc.charUuid) ?: run {
            Log.e(TAG, "Char not found for $charId"); return
        }
        char.writeType = when (writeType) {
            WriteType.WITH_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            WriteType.WITHOUT_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        char.value = data
        g.writeCharacteristic(char)
    }

    override fun readCharacteristic(connId: ConnectionId, charId: HpyCharId) {
        val g = gattMap[connId.value] ?: return
        val loc = CharacteristicMap.getLocation(charId) ?: return
        val service = g.getService(loc.serviceUuid) ?: return
        val char = service.getCharacteristic(loc.charUuid) ?: return
        g.readCharacteristic(char)
    }

    override fun subscribeNotifications(connId: ConnectionId, charId: HpyCharId, enable: Boolean) {
        val g = gattMap[connId.value] ?: return
        val loc = CharacteristicMap.getLocation(charId) ?: return
        val service = g.getService(loc.serviceUuid) ?: return
        val char = service.getCharacteristic(loc.charUuid) ?: return
        g.setCharacteristicNotification(char, enable)
        val desc = char.getDescriptor(CharacteristicMap.UUID_CCC) ?: run {
            Log.e(TAG, "CCC descriptor not found for $charId"); return
        }
        desc.value = if (enable) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        g.writeDescriptor(desc)
    }

    override fun requestMtu(connId: ConnectionId, mtu: Int) {
        gattMap[connId.value]?.requestMtu(mtu)
    }

    override fun readRssi(connId: ConnectionId) {
        gattMap[connId.value]?.readRemoteRssi()
    }

    // ---- L2CAP ----

    override fun l2capOpen(connId: ConnectionId, psm: Int) {
        l2capScope.launch {
            val device = deviceMap[connId.value]
            if (device == null) {
                callback?.onL2capError(connId, "No device for connId=$connId")
                return@launch
            }
            try {
                val socket = device.createInsecureL2capChannel(psm)
                socket.connect()
                l2capSockets[connId.value] = socket
                Log.d(TAG, "[${connId}] L2CAP socket connected (PSM=$psm)")
                callback?.onL2capConnected(connId)
            } catch (e: Exception) {
                Log.e(TAG, "[${connId}] L2CAP connect failed: ${e.message}")
                callback?.onL2capError(connId, "L2CAP connect failed: ${e.message}")
            }
        }
    }

    override fun l2capStartReceiving(connId: ConnectionId, expectedFrames: Int) {
        val socket = l2capSockets[connId.value]
        if (socket == null) {
            callback?.onL2capError(connId, "No L2CAP socket for connId=$connId")
            return
        }
        l2capJobs[connId.value]?.cancel()
        l2capJobs[connId.value] = l2capScope.launch {
            val inputStream = socket.inputStream
            val readBuf = ByteArray(512)
            val buf0 = ByteArray(CommandId.FRAME_SIZE)
            val buf1 = ByteArray(CommandId.FRAME_SIZE)
            var bufState = 0
            var buf0Cnt = 0
            var buf1Cnt = 0
            var framesReceived = 0
            var runningCrc: UInt = CRC_INIT
            var batchDone = false

            try {
                while (isActive && !batchDone) {
                    val bytesRead = inputStream.read(readBuf)
                    if (bytesRead <= 0) break

                    var offset = 0
                    var remaining = bytesRead

                    while (remaining > 0) {
                        if (framesReceived >= expectedFrames) {
                            // Accumulate CRC packet (5 bytes)
                            val residualBuf = if (bufState == 0) buf0 else buf1
                            val residualCnt = if (bufState == 0) buf0Cnt else buf1Cnt
                            val toCopy = minOf(remaining, 5 - residualCnt)
                            if (toCopy > 0) {
                                readBuf.copyInto(residualBuf, residualCnt, offset, offset + toCopy)
                                if (bufState == 0) buf0Cnt += toCopy else buf1Cnt += toCopy
                                offset += toCopy
                                remaining -= toCopy
                            }
                            val newCnt = if (bufState == 0) buf0Cnt else buf1Cnt
                            if (newCnt >= 5) {
                                val receivedCrc = readUInt32(residualBuf, 0)
                                val finalCrc = finalizeCrc(runningCrc)
                                val crcValid = (finalCrc == receivedCrc)
                                Log.d(TAG, "[${connId}] L2CAP CRC: computed=0x${finalCrc.toString(16)} received=0x${receivedCrc.toString(16)} valid=$crcValid")
                                batchDone = true
                                callback?.onL2capBatchComplete(connId, framesReceived, crcValid)
                            }
                            break
                        }

                        if (bufState == 0) {
                            val space = CommandId.FRAME_SIZE - buf0Cnt
                            val toCopy = minOf(remaining, space)
                            readBuf.copyInto(buf0, buf0Cnt, offset, offset + toCopy)
                            buf0Cnt += toCopy
                            offset += toCopy
                            remaining -= toCopy

                            if (buf0Cnt >= CommandId.FRAME_SIZE) {
                                runningCrc = updateCrc(runningCrc, buf0, 0, CommandId.FRAME_SIZE)
                                framesReceived++
                                callback?.onL2capFrame(connId, buf0.copyOf())
                                bufState = 1
                                buf1Cnt = 0
                            }
                        } else {
                            val space = CommandId.FRAME_SIZE - buf1Cnt
                            val toCopy = minOf(remaining, space)
                            readBuf.copyInto(buf1, buf1Cnt, offset, offset + toCopy)
                            buf1Cnt += toCopy
                            offset += toCopy
                            remaining -= toCopy

                            if (buf1Cnt >= CommandId.FRAME_SIZE) {
                                runningCrc = updateCrc(runningCrc, buf1, 0, CommandId.FRAME_SIZE)
                                framesReceived++
                                callback?.onL2capFrame(connId, buf1.copyOf())
                                bufState = 0
                                buf0Cnt = 0
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "[${connId}] L2CAP read error: ${e.message}")
                    callback?.onL2capError(connId, "L2CAP read error: ${e.message}")
                }
            }
        }
    }

    override fun l2capClose(connId: ConnectionId) {
        l2capJobs[connId.value]?.cancel()
        l2capJobs.remove(connId.value)
        try {
            l2capSockets[connId.value]?.close()
        } catch (_: Exception) {}
        l2capSockets.remove(connId.value)
        Log.d(TAG, "[${connId}] L2CAP closed")
    }

    override fun l2capStreamSend(
        connId: ConnectionId,
        psm: Int,
        imageBytes: ByteArray,
        blockSize: Int,
        interBlockDelayMs: Long,
    ) {
        l2capJobs[connId.value]?.cancel()
        l2capJobs[connId.value] = l2capScope.launch {
            var socket: BluetoothSocket? = null
            try {
                val device = deviceMap[connId.value] ?: throw Exception("No device")
                socket = device.createInsecureL2capChannel(psm)
                socket.connect()
                val out = socket.outputStream
                val totalBlocks = (imageBytes.size + blockSize - 1) / blockSize

                for (i in 0 until totalBlocks) {
                    if (!isActive) return@launch
                    val block = ByteArray(blockSize)
                    val start = i * blockSize
                    val end = minOf(start + blockSize, imageBytes.size)
                    imageBytes.copyInto(block, 0, start, end)
                    out.write(block)
                    out.flush()
                    callback?.onL2capSendProgress(connId, i + 1, totalBlocks)
                    if (i < totalBlocks - 1) delay(interBlockDelayMs)
                }
                // Allow BLE stack to drain its transmit buffer before closing the socket
                delay(500L)
            } catch (e: Exception) {
                if (isActive) {
                    callback?.onL2capSendError(connId, "L2CAP send: ${e.message}")
                    return@launch
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
            if (isActive) callback?.onL2capSendComplete(connId)
        }
    }

    fun getBluetoothDevice(connId: ConnectionId): BluetoothDevice? = deviceMap[connId.value]

    // ---- GATT Callback (per connection) ----

    private inner class GattCallback(private val connId: ConnectionId) : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "[${connId}] Connected to ${g.device.address}")
                gattMap[connId.value] = g
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                callback?.onConnected(connId)
            } else {
                Log.w(TAG, "[${connId}] Disconnected (status=$status, newState=$newState)")
                g.close()
                gattMap.remove(connId.value)
                callback?.onDisconnected(connId, status)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "[${connId}] MTU changed to $mtu (status=$status)")
            callback?.onMtuChanged(connId, mtu)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[${connId}] Service discovery failed: $status")
                return
            }
            val available = CharacteristicMap.discoverAvailableChars(g.services)
            Log.d(TAG, "[${connId}] Services discovered: ${available.size} characteristics")
            callback?.onServicesDiscovered(connId, available)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            desc: BluetoothGattDescriptor,
            status: Int,
        ) {
            val charId = CharacteristicMap.getCharId(desc.characteristic.uuid)
            if (charId != null) {
                callback?.onDescriptorWritten(connId, charId, status)
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val charId = CharacteristicMap.getCharId(char.uuid) ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                callback?.onCharacteristicRead(connId, charId, char.value ?: byteArrayOf())
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val charId = CharacteristicMap.getCharId(char.uuid) ?: return
            callback?.onWriteComplete(connId, charId, status)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
        ) {
            val charId = CharacteristicMap.getCharId(char.uuid) ?: return
            val value = char.value ?: return
            callback?.onCharacteristicChanged(connId, charId, value)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                callback?.onRssiRead(connId, rssi)
            }
        }
    }
}
