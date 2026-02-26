package com.happyhealth.bleplatform.internal.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CommandQueue(
    private val maxDepth: Int = MAX_QUEUE_DEPTH,
    private val onSend: (QueuedCommand) -> Unit,
    private val onTimeout: (QueuedCommand) -> Unit,
) {
    companion object {
        const val MAX_QUEUE_DEPTH = 4
    }

    private val queue = ArrayDeque<QueuedCommand>()
    private var inFlight: QueuedCommand? = null
    private var timeoutJob: Job? = null

    val pending: Int get() = queue.size
    val isBusy: Boolean get() = inFlight != null
    val currentCommand: QueuedCommand? get() = inFlight

    fun enqueue(command: QueuedCommand): Boolean {
        if (queue.size >= maxDepth) return false
        queue.addLast(command)
        drainIfIdle()
        return true
    }

    fun signalDone() {
        timeoutJob?.cancel()
        timeoutJob = null
        inFlight = null
        drainIfIdle()
    }

    fun signalTimeout() {
        val cmd = inFlight ?: return
        timeoutJob?.cancel()
        timeoutJob = null
        inFlight = null
        onTimeout(cmd)
        drainIfIdle()
    }

    fun flush() {
        timeoutJob?.cancel()
        timeoutJob = null
        queue.clear()
        inFlight = null
    }

    fun startTimeoutTimer(scope: CoroutineScope) {
        val cmd = inFlight ?: return
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(cmd.timeoutMs)
            signalTimeout()
        }
    }

    private fun drainIfIdle() {
        if (inFlight != null) return
        val next = queue.removeFirstOrNull() ?: return
        inFlight = next
        onSend(next)
    }
}
