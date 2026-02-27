package com.happyhealth.bleplatform.api

import com.happyhealth.bleplatform.internal.command.CommandBuilder
import com.happyhealth.bleplatform.internal.command.CommandId
import com.happyhealth.bleplatform.internal.connection.CompletionType
import com.happyhealth.bleplatform.internal.connection.ConnectionConfig
import com.happyhealth.bleplatform.internal.connection.QueuedCommand
import com.happyhealth.bleplatform.internal.manager.ConnectionManager
import com.happyhealth.bleplatform.internal.model.HpyCharId
import com.happyhealth.bleplatform.internal.model.FirmwareTier
import com.happyhealth.bleplatform.internal.model.ScannedDeviceInfo
import com.happyhealth.bleplatform.internal.shim.PlatformBleShim
import com.happyhealth.bleplatform.internal.shim.PlatformTimeSource
import com.happyhealth.bleplatform.internal.shim.ShimCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class HappyPlatformApi internal constructor(
    private val manager: ConnectionManager,
    private val scope: CoroutineScope,
) {
    val events: SharedFlow<HpyEvent> get() = manager.events
    val discoveredDevices: StateFlow<List<ScannedDeviceInfo>> get() = manager.discoveredDevices
    val isScanning: StateFlow<Boolean> get() = manager.isScanning

    val shimCallback: ShimCallback get() = manager

    // ---- Scanning ----

    fun scanStart() = manager.scanStart()
    fun scanStop() = manager.scanStop()

    // ---- Connection ----

    fun connect(deviceHandle: Any): ConnectionId = manager.connect(deviceHandle)

    fun disconnect(connId: ConnectionId): HpyResult {
        if (manager.getSlot(connId) == null) return HpyResult.ErrInvalidConnId
        manager.disconnect(connId)
        return HpyResult.Ok
    }

    // ---- Device Commands ----

    fun getDeviceInfo(connId: ConnectionId): HpyResult {
        val slot = manager.getSlot(connId) ?: return HpyResult.ErrInvalidConnId
        manager.events.tryEmit(HpyEvent.DeviceInfo(connId, slot.deviceInfo))
        return HpyResult.Ok
    }

    fun getDeviceStatus(connId: ConnectionId): HpyResult {
        return enqueueHcsCommand(connId, "GET_DEV_STATUS", CommandBuilder.buildGetDeviceStatus(), FirmwareTier.TIER_1)
    }

    fun getDaqConfig(connId: ConnectionId): HpyResult {
        return enqueueHcsCommand(connId, "GET_DAQ_CONFIG", CommandBuilder.buildGetDaqConfig(), FirmwareTier.TIER_2)
    }

    fun identify(connId: ConnectionId): HpyResult {
        return enqueueHcsCommand(connId, "IDENTIFY", CommandBuilder.buildIdentify(), FirmwareTier.TIER_2)
    }

    fun startDaq(connId: ConnectionId): HpyResult {
        return enqueueHcsCommand(connId, "START_DAQ", CommandBuilder.buildStartDaq(), FirmwareTier.TIER_1)
    }

    fun stopDaq(connId: ConnectionId): HpyResult {
        return enqueueHcsCommand(connId, "STOP_DAQ", CommandBuilder.buildStopDaq(), FirmwareTier.TIER_1)
    }

    fun getExtendedDeviceStatus(connId: ConnectionId): HpyResult {
        return enqueueHcsCommand(connId, "GET_EXT_STATUS", CommandBuilder.buildGetExtendedDeviceStatus(), FirmwareTier.TIER_2)
    }

    fun setFingerDetection(connId: ConnectionId, enable: Boolean): HpyResult {
        return enqueueHcsCommand(connId, "SET_FINGER_DET", CommandBuilder.buildSetFingerDetection(enable), FirmwareTier.TIER_2)
    }

    // ---- FW Update ----

    fun startFwUpdate(connId: ConnectionId, imageBytes: ByteArray): HpyResult {
        val slot = manager.getSlot(connId) ?: return HpyResult.ErrInvalidConnId
        if (slot.state != HpyConnectionState.READY) return HpyResult.ErrCommandRejected
        slot.startFwUpdate(imageBytes)
        return HpyResult.Ok
    }

    fun cancelFwUpdate(connId: ConnectionId): HpyResult {
        val slot = manager.getSlot(connId) ?: return HpyResult.ErrInvalidConnId
        slot.cancelFwUpdate()
        return HpyResult.Ok
    }

    // ---- Download ----

    fun startDownload(connId: ConnectionId): HpyResult {
        val slot = manager.getSlot(connId) ?: return HpyResult.ErrInvalidConnId
        if (slot.state != HpyConnectionState.READY) return HpyResult.ErrCommandRejected
        if (slot.firmwareTier < FirmwareTier.TIER_1) return HpyResult.ErrFwNotSupported
        slot.startDownload()
        return HpyResult.Ok
    }

    fun stopDownload(connId: ConnectionId): HpyResult {
        val slot = manager.getSlot(connId) ?: return HpyResult.ErrInvalidConnId
        slot.stopDownload()
        return HpyResult.Ok
    }

    // ---- Memfault Chunks ----

    fun getMemfaultChunks(connId: ConnectionId): List<ByteArray> {
        val slot = manager.getSlot(connId) ?: return emptyList()
        return slot.getMemfaultChunks()
    }

    fun markMemfaultChunksUploaded(connId: ConnectionId) {
        manager.getSlot(connId)?.markMemfaultChunksUploaded()
    }

    fun getActiveConnections(): List<ConnectionId> {
        return manager.getActiveConnections().map { it.connId }
    }

    fun getConnectionState(connId: ConnectionId): HpyConnectionState? {
        return manager.getSlot(connId)?.state
    }

    fun destroy() {
        manager.destroy()
        scope.cancel()
    }

    // ---- Internal ----

    private fun enqueueHcsCommand(
        connId: ConnectionId,
        tag: String,
        data: ByteArray,
        minTier: FirmwareTier,
    ): HpyResult {
        val slot = manager.getSlot(connId) ?: return HpyResult.ErrInvalidConnId
        if (slot.state == HpyConnectionState.DISCONNECTED) return HpyResult.ErrNotConnected
        if (slot.state == HpyConnectionState.RECONNECTING) return HpyResult.ErrNotConnected
        if (slot.state != HpyConnectionState.READY && slot.state != HpyConnectionState.WAITING) return HpyResult.ErrCommandRejected
        if (slot.firmwareTier < minTier) return HpyResult.ErrFwNotSupported

        val cmd = QueuedCommand(
            tag = tag,
            charId = HpyCharId.CMD_RX,
            data = data,
            timeoutMs = 5000L,
            completionType = CompletionType.ON_NOTIFICATION,
        )
        return if (slot.enqueueCommand(cmd)) HpyResult.Ok else HpyResult.ErrQueueFull
    }
}

fun createHappyPlatformApi(
    shim: PlatformBleShim,
    timeSource: PlatformTimeSource,
    config: HpyConfig = HpyConfig(),
): HappyPlatformApi {
    val scope = CoroutineScope(SupervisorJob())
    val connConfig = ConnectionConfig(
        commandTimeoutMs = config.commandTimeoutMs,
        skipFingerDetection = config.skipFingerDetection,
        requestedMtu = config.requestedMtu,
        downloadBatchSize = config.downloadBatchSize,
        downloadMaxRetries = config.downloadMaxRetries,
        preferL2capDownload = config.preferL2capDownload,
    )
    val manager = ConnectionManager(shim, timeSource, scope, connConfig)
    return HappyPlatformApi(manager, scope)
}
