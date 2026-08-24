package com.magician.worldedit.client.command

/** Stores at most one user request while a flow request is in flight. */
class FlowRequestQueue {
    sealed interface EnqueueResult {
        data object Queued : EnqueueResult
        data object AlreadyQueued : EnqueueResult
    }

    sealed interface EditResult {
        data object Edited : EditResult
        data object Empty : EditResult
    }

    private var queuedPrompt: String? = null

    fun enqueue(prompt: String): EnqueueResult {
        if (queuedPrompt != null) return EnqueueResult.AlreadyQueued
        queuedPrompt = prompt
        return EnqueueResult.Queued
    }

    fun edit(prompt: String): EditResult {
        if (queuedPrompt == null) return EditResult.Empty
        queuedPrompt = prompt
        return EditResult.Edited
    }

    fun peek(): String? = queuedPrompt

    fun take(): String? {
        val prompt = queuedPrompt
        queuedPrompt = null
        return prompt
    }

    fun discard(): String? {
        return take()
    }
}
