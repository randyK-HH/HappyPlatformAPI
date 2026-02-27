package com.happyhealth.bleplatform.internal.connection

data class ReconnectTier(val fromAttempt: Int, val toAttempt: Int, val intervalMs: Long)

fun List<ReconnectTier>.delayForAttempt(attempt: Int): Long =
    firstOrNull { attempt in it.fromAttempt..it.toAttempt }?.intervalMs ?: last().intervalMs

val NORMAL_RECONNECT_SCHEDULE = listOf(
    ReconnectTier(1, 16, 1_000L),
    ReconnectTier(17, 32, 3_000L),
    ReconnectTier(33, 48, 5_000L),
    ReconnectTier(49, 64, 10_000L),
)

val FW_RECONNECT_SCHEDULE = listOf(
    ReconnectTier(1, 16, 2_000L),
    ReconnectTier(17, 32, 3_000L),
    ReconnectTier(33, 48, 5_000L),
    ReconnectTier(49, 64, 10_000L),
)

data class ConnectionConfig(
    val commandTimeoutMs: Long = 5000L,
    val skipFingerDetection: Boolean = false,
    val requestedMtu: Int = 247,
    val downloadBatchSize: Int = 64,
    val downloadMaxRetries: Int = 1,
    val preferL2capDownload: Boolean = true,
    val reconnectMaxAttempts: Int = 64,
    val reconnectSchedule: List<ReconnectTier> = NORMAL_RECONNECT_SCHEDULE,
    val fwRebootWaitMs: Long = 30_000L,
    val fwReconnectSchedule: List<ReconnectTier> = FW_RECONNECT_SCHEDULE,
)
