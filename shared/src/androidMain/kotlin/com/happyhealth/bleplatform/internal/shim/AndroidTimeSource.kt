package com.happyhealth.bleplatform.internal.shim

import java.util.TimeZone

class AndroidTimeSource : PlatformTimeSource {
    override fun getUtcTimeSeconds(): Long = System.currentTimeMillis() / 1000

    override fun getGmtOffsetHours(): Int =
        TimeZone.getDefault().rawOffset / (3600 * 1000)
}
