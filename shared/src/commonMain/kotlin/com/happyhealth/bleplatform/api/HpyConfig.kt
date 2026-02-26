package com.happyhealth.bleplatform.api

data class HpyConfig(
    val commandTimeoutMs: Long = 5000L,
    val skipFingerDetection: Boolean = false,
    val requestedMtu: Int = 247,
    val downloadBatchSize: Int = 64,
    val downloadMaxRetries: Int = 1,
    val preferL2capDownload: Boolean = true,
    val minRssi: Int = -80,
)
