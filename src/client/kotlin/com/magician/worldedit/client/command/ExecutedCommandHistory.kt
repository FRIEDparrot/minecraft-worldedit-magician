package com.magician.worldedit.client.command

import java.time.Instant

/** Bounded, session-local audit history for commands that WEMC delivered to the server. */
class ExecutedCommandHistory(private val capacity: Int = DEFAULT_CAPACITY) {
    data class Entry(val command: String, val timestamp: Instant = Instant.now())

    private val entries = ArrayDeque<Entry>()

    fun record(command: String) {
        entries.addFirst(Entry(command))
        while (entries.size > capacity) entries.removeLast()
    }

    fun entries(): List<Entry> = entries.toList()

    companion object {
        const val DEFAULT_CAPACITY = 100
    }
}
