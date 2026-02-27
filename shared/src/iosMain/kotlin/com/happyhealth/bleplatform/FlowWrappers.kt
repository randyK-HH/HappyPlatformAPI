package com.happyhealth.bleplatform

import com.happyhealth.bleplatform.api.HappyPlatformApi
import com.happyhealth.bleplatform.api.HpyEvent
import com.happyhealth.bleplatform.internal.model.ScannedDeviceInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Cancellable handle returned to Swift. Call close() to stop collection.
 */
class Closeable(private val job: Job) {
    fun close() { job.cancel() }
}

/**
 * Collect the events SharedFlow from Swift. Runs on Dispatchers.Main.
 */
fun HappyPlatformApi.watchEvents(callback: (HpyEvent) -> Unit): Closeable {
    val job = MainScope().launch {
        events.collect { callback(it) }
    }
    return Closeable(job)
}

/**
 * Observe a StateFlow<Boolean> from Swift. Runs on Dispatchers.Main.
 */
fun StateFlow<Boolean>.watchBool(callback: (Boolean) -> Unit): Closeable {
    val job = MainScope().launch {
        collect { callback(it) }
    }
    return Closeable(job)
}

/**
 * Observe the discoveredDevices StateFlow from Swift. Runs on Dispatchers.Main.
 */
fun StateFlow<List<ScannedDeviceInfo>>.watchDevices(callback: (List<ScannedDeviceInfo>) -> Unit): Closeable {
    val job = MainScope().launch {
        collect { callback(it) }
    }
    return Closeable(job)
}
