package com.happyhealth.bleplatform.internal.model

data class DaqConfigData(
    val version: Int,
    val mode: Int,
    val ambientLightEn: Boolean,
    val ambientLightPeriodMs: UInt,
    val ambientTempEn: Boolean,
    val ambientTempPeriodMs: UInt,
    val skinTempEn: Boolean,
    val skinTempPeriodMs: UInt,
    val ppgCycleTimeMs: UInt,
    val ppgIntervalTimeMs: UInt,
    val ppgOnDuringSleepEn: Boolean,
    val compressedSensingEn: Boolean,
    val multiSpectralEn: Boolean,
    val multiSpectralPeriodMs: UInt,
    val sfMaxLatencyMs: UInt,
    val ppgFsr: Int,
    val edaSweepEn: Boolean,
    val edaSweepPeriodMs: UInt,
    val accUlpEn: Int,
    val oppSampleEn: Boolean,
    val oppSamplePeriodMs: UInt,
    val oppSampleAltMode: Int,
    val memfaultConfig: Int,
    val oppSampleOnTimeMs: UInt,
    val acc2gDuringSleepEn: Boolean,
    val accInactivityConfig: Int,
    val ppgStopConfig: Int,
    val ppgAgcChannelConfig: Int,
    val sleepThreshConfig: Int,
    val csMode: Int,
    val resetRingCfg: Int,
    val edaSweepParamCfg: Int,
    val dailyDaqModeCfg: Int,
) {
    val modeString: String get() = when (mode) {
        0 -> "ALL_SENSORS_OFF"
        1 -> "ACC_EDA_ONLY"
        2 -> "ACC_ONLY"
        3 -> "IR_HR"
        4 -> "SPO2_50"
        5 -> "SPO2_100"
        6 -> "G_IR_HR_50"
        7 -> "G_IR_HR_100"
        8 -> "G_IR_HR_200"
        9 -> "IR_HR_200_1"
        10 -> "IR_HR_200_2"
        11 -> "SPO2_200"
        12 -> "PTT_100"
        13 -> "ACC_HI_FREQ_LO_RES"
        14 -> "IR_HR_400"
        15 -> "G_HR_400"
        16 -> "R_HR_400"
        17 -> "ACC_ONLY_LP_1"
        18 -> "ACC_ONLY_LP_2"
        19 -> "RGBIR_50"
        20 -> "RGBIR_100"
        21 -> "PTT_200"
        22 -> "ACC_52_8G"
        23 -> "ACC_52_2G"
        24 -> "ACC_104_EDA"
        25 -> "RGBIR_100_ACC_104_8G"
        26 -> "RGBIR_100_ACC_104_2G"
        else -> "Unknown($mode)"
    }

    val csModeString: String get() = when (csMode) {
        0 -> "Off"
        1 -> "On"
        else -> "Unknown($csMode)"
    }
}
