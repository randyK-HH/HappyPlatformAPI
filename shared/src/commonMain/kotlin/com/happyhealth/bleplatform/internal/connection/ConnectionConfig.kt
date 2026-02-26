package com.happyhealth.bleplatform.internal.connection

data class ConnectionConfig(
    val commandTimeoutMs: Long = 5000L,
    val skipFingerDetection: Boolean = false,
    val requestedMtu: Int = 247,
    val downloadBatchSize: Int = 64,
    val downloadMaxRetries: Int = 1,
    val preferL2capDownload: Boolean = true,
)
