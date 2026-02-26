package com.happyhealth.bleplatform.internal.connection

import com.happyhealth.bleplatform.internal.command.CommandBuilder
import com.happyhealth.bleplatform.internal.command.CommandId
import com.happyhealth.bleplatform.internal.model.DeviceStatusData
import com.happyhealth.bleplatform.internal.model.FirmwareTier
import com.happyhealth.bleplatform.internal.shim.PlatformTimeSource

class HandshakeRunner(
    private val tier: FirmwareTier,
    private val config: ConnectionConfig,
    private val timeSource: PlatformTimeSource,
    private val memfaultEnabled: Boolean = true,
) {
    private val pendingCommands = ArrayDeque<HandshakeStep>()
    private var _isComplete = false

    private var memfaultDraining = false
    private var memfaultChunkCount = 0
    var memfaultExpectedLength: UInt = 0u
        private set

    val isComplete: Boolean get() = _isComplete
    val memfaultChunksDownloaded: Int get() = memfaultChunkCount

    sealed class HandshakeStep {
        data object GetDaqConfig : HandshakeStep()
        data object GetDeviceStatus : HandshakeStep()
        data object SetUtc : HandshakeStep()
        data object SetInfo : HandshakeStep()
        data object SetFingerDetection : HandshakeStep()
        data object GetMemfaultFileLength : HandshakeStep()
        data object ReadMemfaultFile : HandshakeStep()
    }

    fun start(): QueuedCommand? {
        pendingCommands.clear()
        _isComplete = false
        memfaultDraining = false
        memfaultChunkCount = 0
        memfaultExpectedLength = 0u

        when (tier) {
            FirmwareTier.TIER_0 -> {
                _isComplete = true
                return null
            }
            FirmwareTier.TIER_1 -> {
                pendingCommands.addLast(HandshakeStep.GetDeviceStatus)
            }
            FirmwareTier.TIER_2 -> {
                pendingCommands.addLast(HandshakeStep.GetDaqConfig)
                pendingCommands.addLast(HandshakeStep.GetDeviceStatus)
            }
        }

        return buildNextCommand()
    }

    fun onDeviceStatusReceived(status: DeviceStatusData): QueuedCommand? {
        if (status.needsSetUtc) {
            pendingCommands.addFirst(HandshakeStep.SetUtc)
        }
        if (status.needsSetInfo && tier == FirmwareTier.TIER_2) {
            // Insert after SetUtc if present, else at front
            val insertIdx = if (pendingCommands.firstOrNull() is HandshakeStep.SetUtc) 1 else 0
            pendingCommands.add(insertIdx, HandshakeStep.SetInfo)
        }
        if (status.needsSetFingerDetection && !config.skipFingerDetection) {
            pendingCommands.addLast(HandshakeStep.SetFingerDetection)
        }

        return buildNextCommand()
    }

    fun onCommandComplete(): QueuedCommand? {
        return buildNextCommand()
    }

    fun onMemfaultFileLengthReceived(length: UInt?): QueuedCommand? {
        if (length == null || length == 0u) {
            _isComplete = true
            return null
        }
        memfaultExpectedLength = length
        return buildStepCommand(HandshakeStep.ReadMemfaultFile)
    }

    fun onMemfaultReadComplete(): QueuedCommand? {
        memfaultChunkCount++
        if (memfaultChunkCount < MAX_MEMFAULT_CHUNKS) {
            return buildStepCommand(HandshakeStep.GetMemfaultFileLength)
        }
        _isComplete = true
        return null
    }

    private fun buildNextCommand(): QueuedCommand? {
        val step = pendingCommands.removeFirstOrNull()
        if (step == null) {
            // Queue empty — start memfault drain if applicable
            if (!memfaultDraining && memfaultEnabled && tier >= FirmwareTier.TIER_1) {
                memfaultDraining = true
                return buildStepCommand(HandshakeStep.GetMemfaultFileLength)
            }
            if (memfaultDraining) {
                // Drain finished
                _isComplete = true
                return null
            }
            _isComplete = true
            return null
        }

        return buildStepCommand(step)
    }

    private fun buildStepCommand(step: HandshakeStep): QueuedCommand {
        return when (step) {
            HandshakeStep.GetDaqConfig -> QueuedCommand(
                tag = "HS_GET_DAQ_CONFIG",
                charId = com.happyhealth.bleplatform.internal.model.HpyCharId.CMD_RX,
                data = CommandBuilder.buildGetDaqConfig(),
                timeoutMs = config.commandTimeoutMs,
                completionType = CompletionType.ON_NOTIFICATION,
            )
            HandshakeStep.GetDeviceStatus -> QueuedCommand(
                tag = "HS_GET_DEV_STATUS",
                charId = com.happyhealth.bleplatform.internal.model.HpyCharId.CMD_RX,
                data = CommandBuilder.buildGetDeviceStatus(),
                timeoutMs = config.commandTimeoutMs,
                completionType = CompletionType.ON_NOTIFICATION,
            )
            HandshakeStep.SetUtc -> QueuedCommand(
                tag = "HS_SET_UTC",
                charId = com.happyhealth.bleplatform.internal.model.HpyCharId.CMD_RX,
                data = CommandBuilder.buildSetUtc(timeSource.getUtcTimeSeconds()),
                timeoutMs = config.commandTimeoutMs,
                completionType = CompletionType.ON_NOTIFICATION,
            )
            HandshakeStep.SetInfo -> QueuedCommand(
                tag = "HS_SET_INFO",
                charId = com.happyhealth.bleplatform.internal.model.HpyCharId.CMD_RX,
                data = CommandBuilder.buildSetInfo(timeSource.getGmtOffsetHours()),
                timeoutMs = config.commandTimeoutMs,
                completionType = CompletionType.ON_NOTIFICATION,
            )
            HandshakeStep.SetFingerDetection -> QueuedCommand(
                tag = "HS_SET_FINGER_DET",
                charId = com.happyhealth.bleplatform.internal.model.HpyCharId.CMD_RX,
                data = CommandBuilder.buildSetFingerDetection(),
                timeoutMs = config.commandTimeoutMs,
                completionType = CompletionType.ON_NOTIFICATION,
            )
            HandshakeStep.GetMemfaultFileLength -> QueuedCommand(
                tag = "HS_MF_GET_LEN",
                charId = com.happyhealth.bleplatform.internal.model.HpyCharId.CMD_RX,
                data = CommandBuilder.buildGetFileLength(MEMFAULT_FILE_ID),
                timeoutMs = config.commandTimeoutMs,
                completionType = CompletionType.ON_NOTIFICATION,
            )
            HandshakeStep.ReadMemfaultFile -> QueuedCommand(
                tag = "HS_MF_READ",
                charId = com.happyhealth.bleplatform.internal.model.HpyCharId.CMD_RX,
                data = CommandBuilder.buildReadFile(MEMFAULT_FILE_ID, 0u, memfaultExpectedLength),
                timeoutMs = config.commandTimeoutMs,
                completionType = CompletionType.ON_NOTIFICATION,
            )
        }
    }

    companion object {
        private const val MAX_MEMFAULT_CHUNKS = 8
        private val MEMFAULT_FILE_ID: UShort = 2u
    }
}
