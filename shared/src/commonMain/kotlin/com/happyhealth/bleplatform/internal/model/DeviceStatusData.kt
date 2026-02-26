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

    val chargerStateString: String get() = when (chargerState) {
        0 -> "NoCharger"
        1 -> "ChargerDetected"
        2 -> "ChargerReady"
        else -> "Unknown($chargerState)"
    }

    val chargingStateString: String get() = when (chargingState) {
        0 -> "NotCharging"
        1 -> "PreCharge"
        2 -> "FastCharge"
        3 -> "TopOff"
        4 -> "Done"
        else -> "Unknown($chargingState)"
    }

    val chargingModeString: String get() = when (chargingMode) {
        0 -> "Off"
        1 -> "On"
        else -> "Unknown($chargingMode)"
    }

    val chargerBlockedReasonString: String get() = when (chargerBlockedReason) {
        0 -> "None"
        else -> "0x${chargerBlockedReason.toString(16)}"
    }

    val chargerStatusString: String get() = "0x${chargerStatus.toString(16).uppercase().padStart(2, '0')}"

    val opportunisticSamplingStateString: String get() = when (opportunisticSamplingState) {
        0 -> "Off"
        1 -> "On"
        else -> "Unknown($opportunisticSamplingState)"
    }

    val shipModeStatusString: String get() = when (shipModeStatus) {
        0 -> "Normal"
        1 -> "ShipMode"
        else -> "Unknown($shipModeStatus)"
    }

    val sleepStateString: String get() = when (sleepState) {
        0 -> "Awake"
        1 -> "Sleeping"
        else -> "Unknown($sleepState)"
    }

    val pseudoRingOnOffString: String get() = when (pseudoRingOnOff) {
        0 -> "Off"
        1 -> "On"
        else -> "Unknown($pseudoRingOnOff)"
    }

    val bootHandshakeFlagString: String get() = when (bootHandshakeFlag) {
        0 -> "NotDone"
        1 -> "Done"
        else -> "Unknown($bootHandshakeFlag)"
    }
}
