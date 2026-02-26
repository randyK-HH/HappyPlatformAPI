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
) {
    private val pendingCommands = ArrayDeque<HandshakeStep>()
    private var _isComplete = false

    val isComplete: Boolean get() = _isComplete

    sealed class HandshakeStep {
        data object GetDaqConfig : HandshakeStep()
        data object GetDeviceStatus : HandshakeStep()
        data object SetUtc : HandshakeStep()
        data object SetInfo : HandshakeStep()
        data object SetFingerDetection : HandshakeStep()
    }

    fun start(): QueuedCommand? {
        pendingCommands.clear()
        _isComplete = false

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

    private fun buildNextCommand(): QueuedCommand? {
        val step = pendingCommands.removeFirstOrNull()
        if (step == null) {
            _isComplete = true
            return null
        }

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
        }
    }
}
