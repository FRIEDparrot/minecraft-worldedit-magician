package com.magician.worldedit.client.chunk

/**
 * Formats the confirmed agent region boundary for one LLM request.
 *
 * The operate region is the only area where generated commands may make
 * changes. The wider context region is deliberately marked observation-only,
 * so future chunk-inspection tools can provide neighboring structure without
 * silently increasing write authority.
 */
object AgentRegionScopePrompt {
    /**
     * Appends a deterministic, request-local scope envelope to [playerMessage].
     *
     * A missing confirmed torch selection leaves the message untouched: the
     * command validation layer remains responsible for rejecting writes that
     * need a selection.
     */
    fun appendTo(playerMessage: String, scope: AgentRegionScope?): String =
        scope?.let { "$playerMessage\n\n${describe(it)}" } ?: playerMessage

    /** Produces a compact, stable representation suitable for LLM context. */
    fun describe(scope: AgentRegionScope): String = buildString {
        appendLine("=== WEMC REGION SCOPE ===")
        appendLine("operate (write): chunks=${formatChunks(scope.operate.chunks)} y=${scope.operate.minY}..${scope.operate.maxY}")
        appendLine("context (read-only): chunks=${formatChunks(scope.context.chunks)} y=${scope.context.minY}..${scope.context.maxY}")
        appendLine("Only the operate area is writable; context is observation-only.")
        append("=== END WEMC REGION SCOPE ===")
    }

    private fun formatChunks(chunks: Set<ChunkPos>): String = chunks
        .sortedWith(compareBy<ChunkPos> { it.x }.thenBy { it.z })
        .joinToString(prefix = "[", postfix = "]") { "(${it.x},${it.z})" }
}
