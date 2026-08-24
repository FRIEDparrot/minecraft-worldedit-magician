package com.magician.worldedit.client.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlowRequestQueueTest {
    @Test
    fun `first request is queued and later request is rejected`() {
        val queue = FlowRequestQueue()

        assertEquals(FlowRequestQueue.EnqueueResult.Queued, queue.enqueue("first"))
        assertEquals(FlowRequestQueue.EnqueueResult.AlreadyQueued, queue.enqueue("second"))
        assertEquals("first", queue.peek())
    }

    @Test
    fun `edit replaces the queued request`() {
        val queue = FlowRequestQueue()
        queue.enqueue("first")

        assertEquals(FlowRequestQueue.EditResult.Edited, queue.edit("updated"))
        assertEquals("updated", queue.peek())
    }

    @Test
    fun `discard clears the queued request`() {
        val queue = FlowRequestQueue()
        queue.enqueue("first")

        assertEquals("first", queue.discard())
        assertNull(queue.peek())
        assertNull(queue.discard())
    }

    @Test
    fun `take removes the queued request`() {
        val queue = FlowRequestQueue()
        queue.enqueue("first")

        assertEquals("first", queue.take())
        assertNull(queue.peek())
    }
}
