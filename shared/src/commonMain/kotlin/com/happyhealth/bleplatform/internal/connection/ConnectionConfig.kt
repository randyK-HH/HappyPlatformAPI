package com.happyhealth.bleplatform.internal.connection

data class ConnectionConfig(
    val commandTimeoutMs: Long = 5000L,
    val skipFingerDetection: Boolean = false,
    val requestedMtu: Int = 247,
)
