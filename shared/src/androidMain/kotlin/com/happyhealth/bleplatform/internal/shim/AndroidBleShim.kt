package com.happyhealth.bleplatform.internal.shim

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.bleplatform.internal.model.HpyCharId

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

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: return
            Log.d(TAG, "Scan found: $name (${device.address}) rssi=${result.rssi}")
            callback?.onDeviceDiscovered(device, name, device.address, result.rssi)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            Log.d(TAG, "Batch scan results: ${results.size} entries")
            for (result in results) {
                val device = result.device
                val name = result.scanRecord?.deviceName ?: device.name ?: continue
                Log.d(TAG, "  Batch: $name (${device.address}) rssi=${result.rssi}")
                callback?.onDeviceDiscovered(device, name, device.address, result.rssi)
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
