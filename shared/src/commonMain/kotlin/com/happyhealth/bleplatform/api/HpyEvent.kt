package com.happyhealth.bleplatform.api

import com.happyhealth.bleplatform.internal.command.ResponseParser
import com.happyhealth.bleplatform.internal.model.DaqConfigData
import com.happyhealth.bleplatform.internal.model.DeviceInfoData
import com.happyhealth.bleplatform.internal.model.DeviceStatusData

sealed class HpyEvent {
    abstract val connId: ConnectionId

    data class DeviceDiscovered(
        val name: String,
        val address: String,
        val rssi: Int,
        val deviceHandle: Any,
    ) : HpyEvent() {
        override val connId: ConnectionId get() = ConnectionId(-1)
    }

    data class StateChanged(
        override val connId: ConnectionId,
        val state: HpyConnectionState,
        val retryCount: Int = 0,
    ) : HpyEvent()

    data class DeviceInfo(
        override val connId: ConnectionId,
        val info: DeviceInfoData,
    ) : HpyEvent()

    data class DeviceStatus(
        override val connId: ConnectionId,
        val status: DeviceStatusData,
    ) : HpyEvent()

    data class DaqConfig(
        override val connId: ConnectionId,
        val config: DaqConfigData,
    ) : HpyEvent()

    data class ExtendedDeviceStatus(
        override val connId: ConnectionId,
        val extStatus: ResponseParser.ExtendedDeviceStatus,
    ) : HpyEvent()

    data class CommandResult(
        override val connId: ConnectionId,
        val commandId: Byte,
        val rawBytes: ByteArray,
    ) : HpyEvent()

    data class DebugMessage(
        override val connId: ConnectionId,
        val message: ByteArray,
    ) : HpyEvent()

    data class Error(
        override val connId: ConnectionId,
        val code: HpyErrorCode,
        val message: String,
    ) : HpyEvent()

    data class Log(
        override val connId: ConnectionId,
        val message: String,
    ) : HpyEvent()

    // Stubs for future download/FW events
    data class DownloadBatch(
        override val connId: ConnectionId,
        val framesInBatch: Int,
        val totalFramesDownloaded: Int,
        val crcValid: Boolean,
    ) : HpyEvent()

    data class DownloadProgress(
        override val connId: ConnectionId,
        val framesDownloaded: Int,
        val framesTotal: Int,
        val transport: String = "",
    ) : HpyEvent()

    data class DownloadFrame(
        override val connId: ConnectionId,
        val frameData: ByteArray,
    ) : HpyEvent()

    data class DownloadComplete(
        override val connId: ConnectionId,
        val totalFrames: Int,
    ) : HpyEvent()

    data class FwUpdateProgress(
        override val connId: ConnectionId,
        val bytesWritten: Int,
        val totalBytes: Int,
    ) : HpyEvent()

    data class FwUpdateComplete(
        override val connId: ConnectionId,
        val newFwVersion: String,
    ) : HpyEvent()

    data class MemfaultComplete(
        override val connId: ConnectionId,
        val chunksDownloaded: Int,
    ) : HpyEvent()
}

enum class HpyErrorCode {
    CONNECT_FAIL,
    HANDSHAKE_FAIL,
    COMMAND_TIMEOUT,
    COMMAND_UNRECOGNIZED,
    RECONNECT_FAIL,
    DOWNLOAD_BUFFER_OVERRUN,
    FW_IMAGE_INVALID,
    FW_TRANSFER_FAIL,
    FW_UPDATE_RECONNECT_FAIL,
    MEMFAULT_BUFFER_FULL,
    MAX_CONNECTIONS,
    GENERIC,
}
