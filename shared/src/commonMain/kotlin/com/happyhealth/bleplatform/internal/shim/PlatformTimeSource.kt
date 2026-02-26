package com.happyhealth.bleplatform.internal.shim

interface PlatformTimeSource {
    fun getUtcTimeSeconds(): Long
    fun getGmtOffsetHours(): Int
}
