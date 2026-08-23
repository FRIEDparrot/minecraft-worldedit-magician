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
        // 1. Lex
        val tokens = WclLexer(source).tokenize()

        // 2. Parse
        val prog = WclParser(tokens).parse()

        // 3. Compile
        return WclCompiler(prog).compile(ctx)
    }
}
