package com.happyhealth.bleplatform.internal.model

data class ScannedDeviceInfo(
    val deviceHandle: Any,
    val name: String,
    val address: String,
    val rssi: Int,
)
