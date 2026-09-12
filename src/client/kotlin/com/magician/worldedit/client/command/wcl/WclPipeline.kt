package com.magician.worldedit.client.command.wcl

import com.magician.worldedit.client.chunk.AgentRegionScope

/**
 * Full WCL pipeline: Lexer → Parser → Compiler → Minecraft commands.
 *
 * Usage:
 *   val result = WclPipeline.run(wclSource, playerX, playerY, playerZ)
 *   when (result) {
 *       is WclResult.Ok -> result.commands  // List<String> of MC commands
 *       is WclResult.Err -> result.msg       // human-readable error
 *   }
 */
object WclPipeline {

    /**
     * Compiles WCL and optionally enforces the captured agent write boundary.
     * Existing callers retain the compiler-only behavior unless [enforceScope]
     * is explicitly enabled for agent-generated execution.
     */
    fun run(
        source: String,
        playerX: Int,
        playerY: Int,
        playerZ: Int,
        seed: String = "",
        scope: AgentRegionScope? = null,
        enforceScope: Boolean = false,
    ): WclResult = run(source, WclCtx(playerX, playerY, playerZ, seed), scope, enforceScope)

    fun run(
        source: String,
        ctx: WclCtx,
        scope: AgentRegionScope? = null,
        enforceScope: Boolean = false,
    ): WclResult {
        // WCL uses a line-preserving compiler so command text (NBT, namespaces,
        // coordinates, selectors) is passed unchanged until WCL substitutions occur.
        val result = WclTextCompiler(source, ctx).compile()
        if (!enforceScope || result !is WclResult.Ok) return result
        val error = WclOperationScopeValidator.validate(
            commands = result.commands,
            scope = scope,
            anchor = BlockCoordinate(ctx.playerX.toLong(), ctx.playerY.toLong(), ctx.playerZ.toLong()),
        ) ?: return result
        return WclResult.Err(error)
    }
}
