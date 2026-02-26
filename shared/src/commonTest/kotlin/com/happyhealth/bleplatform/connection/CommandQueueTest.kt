package com.happyhealth.bleplatform.connection

import com.happyhealth.bleplatform.internal.connection.CommandQueue
import com.happyhealth.bleplatform.internal.connection.CompletionType
import com.happyhealth.bleplatform.internal.connection.QueuedCommand
import com.happyhealth.bleplatform.internal.model.HpyCharId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandQueueTest {

    private val sentCommands = mutableListOf<QueuedCommand>()
    private val timedOutCommands = mutableListOf<QueuedCommand>()

    private fun createQueue(maxDepth: Int = 4) = CommandQueue(
        maxDepth = maxDepth,
        onSend = { sentCommands.add(it) },
        onTimeout = { timedOutCommands.add(it) },
    )

    private fun makeCmd(tag: String) = QueuedCommand(
        tag = tag,
        charId = HpyCharId.CMD_RX,
        data = byteArrayOf(0x1E),
        timeoutMs = 5000,
        completionType = CompletionType.ON_NOTIFICATION,
    )

    @Test
    fun enqueue_firstCommand_sendsImmediately() {
        val queue = createQueue()
        val cmd = makeCmd("GET_STATUS")
        assertTrue(queue.enqueue(cmd))
        assertEquals(1, sentCommands.size)
        assertEquals("GET_STATUS", sentCommands[0].tag)
        assertTrue(queue.isBusy)
        assertEquals(0, queue.pending)
    }

    @Test
    fun enqueue_whileBusy_queuesCommand() {
        val queue = createQueue()
        queue.enqueue(makeCmd("CMD1"))
        queue.enqueue(makeCmd("CMD2"))
        assertEquals(1, sentCommands.size) // Only first sent
        assertEquals(1, queue.pending) // Second queued
    }

    @Test
    fun signalDone_drainsNextCommand() {
        val queue = createQueue()
        queue.enqueue(makeCmd("CMD1"))
        queue.enqueue(makeCmd("CMD2"))
        assertEquals(1, sentCommands.size)

        queue.signalDone()
        assertEquals(2, sentCommands.size)
        assertEquals("CMD2", sentCommands[1].tag)
    }

    @Test
    fun queueFull_rejectsCommand() {
        val queue = createQueue(maxDepth = 2)
        queue.enqueue(makeCmd("CMD1")) // sent immediately, not in queue
        queue.enqueue(makeCmd("CMD2")) // queued
        queue.enqueue(makeCmd("CMD3")) // queued
        assertFalse(queue.enqueue(makeCmd("CMD4"))) // rejected, queue full
        assertEquals(2, queue.pending)
    }

    @Test
    fun flush_clearsEverything() {
        val queue = createQueue()
        queue.enqueue(makeCmd("CMD1"))
        queue.enqueue(makeCmd("CMD2"))
        queue.enqueue(makeCmd("CMD3"))

        queue.flush()
        assertFalse(queue.isBusy)
        assertEquals(0, queue.pending)
        assertNull(queue.currentCommand)
    }

    @Test
    fun signalDone_whenEmpty_becomesIdle() {
        val queue = createQueue()
        queue.enqueue(makeCmd("CMD1"))
        queue.signalDone()
        assertFalse(queue.isBusy)
        assertEquals(0, queue.pending)
    }
}
