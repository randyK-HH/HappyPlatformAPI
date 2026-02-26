package com.happyhealth.bleplatform.internal.command

import com.happyhealth.bleplatform.internal.model.DaqConfigData
import com.happyhealth.bleplatform.internal.model.DeviceStatusData

object ResponseParser {

    fun parseDeviceStatus(value: ByteArray): DeviceStatusData? {
        if (value.size < 22) return null
        return DeviceStatusData(
            phyStatus = value.ubyte(1),
            chargerState = value.ubyte(2),
            chargingState = value.ubyte(3),
            chargingMode = value.ubyte(4),
            chargerBlockedReason = value.ubyte(5),
            chargerRevId = value.ubyte(6),
            chargerStatus = value.ubyte(7),
            batteryVoltage = (value.ubyte(9) shl 8) or value.ubyte(8),
            soc = value.ubyte(10),
            daqMode = value.ubyte(11),
            unsyncedFrames = (value.ubyte(13) shl 8) or value.ubyte(12),
            syncFrameCount = readUInt32(value, 14),
            syncFrameReboots = readUInt32(value, 18),
            opportunisticSamplingState = value.ubyte(22),
            opportunisticStateTime = if (value.size > 24) {
                (value.ubyte(24) shl 8) or value.ubyte(23)
            } else 0,
            shipModeStatus = value.ubyte(25),
            sleepState = value.ubyte(26),
            sendUtcFlags = if (value.size > 27) value.ubyte(27) else 0xFF,
            bootHandshakeFlag = value.ubyte(28),
            pseudoRingOnOff = value.ubyte(29),
            notifSender = value.ubyte(30),
            bleCi = value.ubyte(31),
            clockRate = value.ubyte(32),
        )
    }

    data class SetUtcResponse(
        val ringUtc: UInt,
        val ringReboots: UInt,
    )

    fun parseSetUtcResponse(value: ByteArray): SetUtcResponse? {
        if (value.size < 13) return null
        return SetUtcResponse(
            ringUtc = readUInt32(value, 1),
            ringReboots = readUInt32(value, 9),
        )
    }

    data class ConfigureL2capResponse(
        val status: Int,
        val turboAccepted: Boolean,
    )

    fun parseConfigureL2capResponse(value: ByteArray): ConfigureL2capResponse? {
        if (value.size < 3) return null
        return ConfigureL2capResponse(
            status = value.ubyte(1),
            turboAccepted = value.ubyte(2) != 0,
        )
    }

    data class ExtendedDeviceStatus(
        val bpState: Int,
        val bpTimeLeftSec: Int,
    )

    fun parseExtendedDeviceStatus(value: ByteArray): ExtendedDeviceStatus? {
        if (value.size < 4) return null
        return ExtendedDeviceStatus(
            bpState = value.ubyte(1),
            bpTimeLeftSec = readUInt16(value, 2).toInt(),
        )
    }

    fun parseGetFramesL2capResponse(value: ByteArray): Int? {
        if (value.size < 2) return null
        return value.ubyte(1)
    }

    fun parseDaqConfig(value: ByteArray): DaqConfigData? {
        if (value.size < 3) return null
        return DaqConfigData(
            version = value.ubyte(1),
            mode = value.ubyte(2),
            ambientLightEn = value.bool(3),
            ambientLightPeriodMs = value.u32(4),
            ambientTempEn = value.bool(8),
            ambientTempPeriodMs = value.u32(9),
            skinTempEn = value.bool(13),
            skinTempPeriodMs = value.u32(14),
            ppgCycleTimeMs = value.u32(18),
            ppgIntervalTimeMs = value.u32(22),
            ppgOnDuringSleepEn = value.bool(26),
            compressedSensingEn = value.bool(27),
            multiSpectralEn = value.bool(28),
            multiSpectralPeriodMs = value.u32(29),
            sfMaxLatencyMs = value.u32(33),
            ppgFsr = value.ubyte(37),
            edaSweepEn = value.bool(38),
            edaSweepPeriodMs = value.u32(39),
            accUlpEn = value.ubyte(43),
            oppSampleEn = value.bool(44),
            oppSamplePeriodMs = value.u32(45),
            oppSampleAltMode = value.ubyte(49),
            memfaultConfig = value.ubyte(50),
            oppSampleOnTimeMs = value.u32(51),
            acc2gDuringSleepEn = value.bool(55),
            accInactivityConfig = value.ubyte(56),
            ppgStopConfig = value.ubyte(57),
            ppgAgcChannelConfig = value.ubyte(58),
            sleepThreshConfig = value.ubyte(59),
            csMode = value.ubyte(60),
            resetRingCfg = value.ubyte(61),
            edaSweepParamCfg = value.ubyte(62),
            dailyDaqModeCfg = value.ubyte(63),
        )
    }

    fun parseGetFileLengthResponse(value: ByteArray): UInt? {
        if (value.size < 5) return null
        return readUInt32(value, 1)
    }

    fun parseReadFileCrcResponse(value: ByteArray): UInt? {
        if (value.size < 5) return null
        return readUInt32(value, 1)
    }
}
