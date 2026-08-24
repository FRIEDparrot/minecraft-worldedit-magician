package com.magician.worldedit.client.command.wcl

import java.util.Random

/**
 * Line-preserving WCL compiler. Minecraft command lines remain verbatim, while WCL
 * control statements expand into a concrete command list before blacklist gating.
 */
internal class WclTextCompiler(private val source: String, private val ctx: WclCtx) {
    private val lines = source.lineSequence().toList()
    private var index = 0
    private val random = Random(if (ctx.seed.isBlank()) System.nanoTime() else ctx.seed.hashCode().toLong())
    private val output = mutableListOf<String>()
    private val echoes = mutableListOf<String>()

    fun compile(): WclResult = try {
        compileBlock(mutableMapOf(), false)
        WclResult.Ok(output, echoes)
    } catch (error: WclTextError) {
        WclResult.Err(error.message ?: "Invalid WCL.", error.line)
    }

    private fun compileBlock(scope: MutableMap<String, Long>, closingBraceRequired: Boolean) {
        while (index < lines.size) {
            val raw = lines[index]
            val line = raw.substringBefore("//").substringBefore('#').trim()
            index++
            if (line.isBlank()) continue
            if (line == "}") {
                if (!closingBraceRequired) throw WclTextError("Unexpected closing brace.", index)
                return
            }

            parseSeed(line)
                ?: parseNativeLoop(line, scope)
                ?: rejectUnsupportedControl(line)
                ?: parseEcho(line, scope)
                ?: emitCommand(line, scope)
        }
        if (closingBraceRequired) throw WclTextError("Loop block is missing a closing brace.", lines.size)
    }

    private fun parseSeed(line: String): Unit? {
        val match = SEED.matchEntire(line) ?: return null
        ctx.seed = match.groupValues[1]
        return Unit
    }

    private fun parseNativeLoop(line: String, parentScope: MutableMap<String, Long>): Unit? {
        val match = NATIVE_LOOP.matchEntire(line) ?: return null
        val variable = match.groupValues[1]
        val start = match.groupValues[2].toLongOrNull()
            ?: throw WclTextError("Loop start must be an integer.", index)
        val end = match.groupValues[3].toLongOrNull()
            ?: throw WclTextError("Loop end must be an integer.", index)
        val bodyStart = index
        val bodyEnd = findBlockEnd(bodyStart)
        val step = if (start <= end) 1L else -1L
        val count = kotlin.math.abs(end - start) + 1
        if (count > MAX_LOOP_ITERATIONS) throw WclTextError("Loop has $count iterations; maximum is $MAX_LOOP_ITERATIONS.", index)

        var value = start
        while (true) {
            val scope = parentScope.toMutableMap()
            scope[variable] = value
            index = bodyStart
            compileBlock(scope, true)
            if (value == end) break
            value += step
        }
        index = bodyEnd + 1
        return Unit
    }

    private fun rejectUnsupportedControl(line: String): Unit? {
        if (!line.startsWith("for ") && !line.startsWith("repeat ")) return null
        throw WclTextError(
            "Unsupported WCL loop syntax. Use: i in [start..end] { ... } with the body on following lines.",
            index,
        )
    }

    private fun parseEcho(line: String, scope: Map<String, Long>): Unit? {
        if (!line.startsWith("echo ")) return null
        echoes.add(substitute(line.removePrefix("echo ").trim(), scope))
        return Unit
    }

    private fun emitCommand(line: String, scope: Map<String, Long>) {
        if (output.size >= MAX_COMMANDS) throw WclTextError("Exceeded MAX_COMMANDS ($MAX_COMMANDS).", index)
        val command = substitute(line, scope).removePrefix("/").trim()
        if (command.isNotBlank()) output.add(command)
    }

    private fun findBlockEnd(start: Int): Int {
        var depth = 1
        var cursor = start
        while (cursor < lines.size) {
            val line = lines[cursor].substringBefore("//").substringBefore('#')
            depth += line.count { it == '{' }
            depth -= line.count { it == '}' }
            if (depth == 0) return cursor
            cursor++
        }
        throw WclTextError("Loop block is missing a closing brace.", start + 1)
    }

    private fun substitute(command: String, scope: Map<String, Long>): String {
        var result = command
        scope.forEach { (name, value) ->
            result = result.replace("\${$name}", value.toString())
            result = result.replace("\$$name", value.toString())
        }
        result = RANDOM_OFFSET.replace(result) { match ->
            "~${randomInt(match.groupValues[1].toInt(), match.groupValues[2].toInt())}"
        }
        result = RANDOM_LIST.replace(result) { match ->
            val entries = match.groupValues[1].split(',').map(String::trim).filter(String::isNotBlank)
            if (entries.isEmpty()) throw WclTextError("random list must contain at least one item.", index)
            entries[random.nextInt(entries.size)]
        }
        result = RANDOM_WEIGHTED.replace(result) { match ->
            val choices = match.groupValues[1].split(',').map { item ->
                val parts = item.split(':', limit = 2)
                if (parts.size != 2) throw WclTextError("weighted random entries require item:weight.", index)
                parts[0].trim() to (parts[1].trim().toIntOrNull()
                    ?: throw WclTextError("weighted random weights must be integers.", index))
            }
            val total = choices.sumOf { it.second }
            if (total <= 0) throw WclTextError("weighted random total must be positive.", index)
            var pick = random.nextInt(total)
            choices.first { (_, weight) -> (pick - weight).also { pick = it } < 0 }.first
        }
        return result
    }

    private fun randomInt(first: Int, second: Int): Int {
        val low = minOf(first, second)
        val high = maxOf(first, second)
        return low + random.nextInt(high - low + 1)
    }

    private class WclTextError(message: String, val line: Int) : IllegalArgumentException(message)

    private companion object {
        const val MAX_COMMANDS = 1000
        const val MAX_LOOP_ITERATIONS = 1000L
        val NATIVE_LOOP = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s+in\s+\[(-?\d+)\.\.(-?\d+)\]\s*\{""")
        val SEED = Regex("""seed\s+\"([^\"]+)\"""")
        val RANDOM_OFFSET = Regex("""~<random\((-?\d+)\s*,\s*(-?\d+)\)>""")
        val RANDOM_LIST = Regex("""<random\(\[([^\]]+)\]\)>""")
        val RANDOM_WEIGHTED = Regex("""<random\(\{([^}]+)\}\)>""")
    }
}
