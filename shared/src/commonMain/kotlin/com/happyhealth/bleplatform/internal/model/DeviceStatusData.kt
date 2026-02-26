package com.happyhealth.bleplatform.internal.model

data class DeviceStatusData(
    val phyStatus: Int,
    val chargerState: Int,
    val chargingState: Int,
    val chargingMode: Int,
    val chargerBlockedReason: Int,
    val chargerRevId: Int,
    val chargerStatus: Int,
    val batteryVoltage: Int,
    val soc: Int,
    val daqMode: Int,
    val unsyncedFrames: Int,
    val syncFrameCount: UInt,
    val syncFrameReboots: UInt,
    val opportunisticSamplingState: Int,
    val opportunisticStateTime: Int,
    val shipModeStatus: Int,
    val sleepState: Int,
    val sendUtcFlags: Int,
    val bootHandshakeFlag: Int,
    val pseudoRingOnOff: Int,
    val notifSender: Int,
    val bleCi: Int,
    val clockRate: Int,
) {
    val needsSetUtc: Boolean get() = (sendUtcFlags and 0x01) != 0
    val needsSetInfo: Boolean get() = (sendUtcFlags and 0x02) != 0
    val needsSetFingerDetection: Boolean get() = (sendUtcFlags and 0x04) == 0

    val phyString: String get() = when (phyStatus) {
        0x01 -> "OnFinger"
        0x02 -> "OffFinger"
        0x03 -> "OnCharger"
        else -> "Unknown(0x${phyStatus.toString(16)})"
    }

    val daqString: String get() = when (daqMode) {
        0x01 -> "Off"
        0x02 -> "Sensing"
        0x03 -> "On"
        else -> "Unknown(0x${daqMode.toString(16)})"
    }

    val syncString: String get() = "boot${syncFrameReboots}:frame${syncFrameCount}"

    val notifSenderString: String get() = when (notifSender) {
        0x01 -> "ChargerStateChange"
        0x02 -> "OnFinger"
        0x03 -> "OffFinger"
        0x04 -> "SuperframeClose"
        0x05 -> "SleepStatusChange"
        0x06 -> "PseudoFastOnOff"
        0x07 -> "DevStatusReq"
        0x08 -> "CI_Changed"
        else -> "Unknown(0x${notifSender.toString(16)})"
    }

    val clockRateString: String get() = when (clockRate) {
        0 -> "16MHz"
        1 -> "48MHz"
        2 -> "96MHz"
        else -> "Unknown($clockRate)"
    }
}
