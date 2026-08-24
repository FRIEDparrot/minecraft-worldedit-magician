package com.magician.worldedit.client.command.wcl

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

    fun run(source: String, playerX: Int, playerY: Int, playerZ: Int, seed: String = ""): WclResult {
        return run(source, WclCtx(playerX, playerY, playerZ, seed))
    }

    fun run(source: String, ctx: WclCtx): WclResult {
        // WCL uses a line-preserving compiler so command text (NBT, namespaces,
        // coordinates, selectors) is passed unchanged until WCL substitutions occur.
        return WclTextCompiler(source, ctx).compile()
    }
}
