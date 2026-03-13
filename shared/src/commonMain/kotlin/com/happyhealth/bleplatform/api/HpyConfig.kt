package com.happyhealth.bleplatform.api

data class HpyConfig(
    val commandTimeoutMs: Long = 5000L,
    val skipFingerDetection: Boolean = false,
    val requestedMtu: Int = 247,
    val downloadBatchSize: Int = 64,
    val downloadMaxRetries: Int = 1,
    val preferL2capDownload: Boolean = true,
    val l2capClockByte: Byte = 0x01,  // 0x00=16MHz, 0x01=48MHz, 0x02=96MHz
    val minRssi: Int = -80,
    val downloadStallTimeoutMs: Long = 60_000L,
    val reconnectMaxAttempts: Int = 64,
    val downloadFailsafeIntervalMs: Long = 21L * 60 * 1000,
    val memfaultMinIntervalMs: Long = 0L,
    val autoReconnect: Boolean = true,
    val fwStreamInterBlockDelayMs: Long = 30L,
    val fwStreamDrainDelayMs: Long = 2000L,
    val fwUpdateUseGatt: Boolean = false,
)
