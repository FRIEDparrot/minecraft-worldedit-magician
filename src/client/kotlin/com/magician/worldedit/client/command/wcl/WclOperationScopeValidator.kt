package com.magician.worldedit.client.command.wcl

import com.magician.worldedit.client.chunk.AgentRegionScope
import com.magician.worldedit.client.chunk.ChunkPos
import com.magician.worldedit.client.chunk.ContextRegion
import com.magician.worldedit.client.chunk.OperateRegion

/** A block-coordinate anchor used to resolve integer Minecraft `~` positions. */
data class BlockCoordinate(val x: Long, val y: Long, val z: Long) {
    override fun toString(): String = "($x,$y,$z)"
}

/**
 * Validates coordinate-bearing world operations emitted by WCL before dispatch.
 *
 * This does not replace the server permission gate. It protects known block and
 * entity operations; commands whose coordinates are not statically inspectable
 * remain subject to the existing command gate in this incremental slice.
 */
object WclOperationScopeValidator {
    private val WHITESPACE = Regex("\\s+")
    private val SCOPED_ROOTS = setOf("setblock", "fill", "clone", "data", "item", "summon")
    private val DATA_MUTATIONS = setOf("merge", "modify", "remove")
    private val ITEM_MUTATIONS = setOf("replace", "modify")

    /** Returns a facing error, or null when every known operation is in scope. */
    fun validate(
        commands: List<String>,
        scope: AgentRegionScope?,
        anchor: BlockCoordinate,
    ): String? {
        commands.forEachIndexed { index, raw ->
            val command = raw.trim().removePrefix("/")
            if (command.isBlank()) return@forEachIndexed
            validateCommand(index + 1, command, scope, anchor)?.let { return it }
        }
        return null
    }

    private fun validateCommand(
        number: Int,
        command: String,
        scope: AgentRegionScope?,
        anchor: BlockCoordinate,
    ): String? {
        val tokens = command.split(WHITESPACE)
        val root = tokens.firstOrNull()?.lowercase() ?: return null
        if (root == "execute") return validateExecute(number, tokens, scope, anchor)
        if (root !in SCOPED_ROOTS) return null
        if (scope == null) return "command $number $root requires a confirmed operate region"

        val operate = Bounds(scope.operate.chunks, scope.operate.minY.toLong(), scope.operate.maxY.toLong(), "operate")
        return when (root) {
            "setblock" -> validatePoint(number, "setblock target", tokens, 1, operate, anchor)
            "fill" -> validateBox(number, "fill cuboid", parsePoint(tokens, 1, anchor), parsePoint(tokens, 4, anchor), operate)
            "clone" -> validateClone(number, tokens, scope, anchor)
            "data" -> if (tokens.size >= 3 && tokens[1].lowercase() in DATA_MUTATIONS && tokens[2].equals("block", true)) {
                validatePoint(number, "data block target", tokens, 3, operate, anchor)
            } else null
            "item" -> if (tokens.size >= 3 && tokens[1].lowercase() in ITEM_MUTATIONS && tokens[2].equals("block", true)) {
                validatePoint(number, "item block target", tokens, 3, operate, anchor)
            } else null
            "summon" -> validateSummon(number, tokens, operate, anchor)
            else -> null
        }
    }

    private fun validateExecute(
        number: Int,
        tokens: List<String>,
        scope: AgentRegionScope?,
        anchor: BlockCoordinate,
    ): String? {
        val run = tokens.indexOfFirst { it.equals("run", true) }
        if (run < 0 || run == tokens.lastIndex) return null
        val nestedRoot = tokens[run + 1].lowercase()
        if (nestedRoot !in SCOPED_ROOTS) return null
        if (scope == null) return "command $number execute contains $nestedRoot and requires a confirmed operate region"
        if (!isSenderPositionPreserving(tokens.subList(1, run))) {
            return "command $number execute cannot be scope-validated around $nestedRoot; use direct coordinates or execute as @s at @s"
        }
        return validateCommand(number, tokens.subList(run + 1, tokens.size).joinToString(" "), scope, anchor)
    }

    private fun isSenderPositionPreserving(prefix: List<String>): Boolean {
        if (prefix.isEmpty()) return true
        var index = 0
        while (index < prefix.size) {
            when (prefix[index].lowercase()) {
                "as", "at" -> {
                    if (index + 1 >= prefix.size || prefix[index + 1] != "@s") return false
                    index += 2
                }
                else -> return false
            }
        }
        return true
    }

    private fun validateSummon(number: Int, tokens: List<String>, operate: Bounds, anchor: BlockCoordinate): String? {
        // Without a position, Minecraft summons at the command sender's position.
        val point = if (tokens.size >= 5) parsePoint(tokens, 2, anchor) else ParsedPoint(anchor, null)
        point.error?.let { return "command $number summon position is not scope-checkable: $it" }
        val value = point.value ?: return "command $number summon position is missing coordinates"
        return if (inside(operate, value)) null
        else "command $number summon position $value is outside the operate region"
    }

    private fun validatePoint(
        number: Int,
        label: String,
        tokens: List<String>,
        start: Int,
        region: Bounds,
        anchor: BlockCoordinate,
    ): String? {
        val point = parsePoint(tokens, start, anchor)
        point.error?.let { return "command $number $label is not scope-checkable: $it" }
        val value = point.value ?: return "command $number $label is missing coordinates"
        return if (inside(region, value)) null
        else "command $number $label $value is outside the operate region"
    }

    private fun validateClone(
        number: Int,
        tokens: List<String>,
        scope: AgentRegionScope,
        anchor: BlockCoordinate,
    ): String? {
        val sourceFrom = parsePoint(tokens, 1, anchor)
        val sourceTo = parsePoint(tokens, 4, anchor)
        val context = Bounds(scope.context.chunks, scope.context.minY.toLong(), scope.context.maxY.toLong(), "context")
        validateBox(number, "clone source cuboid", sourceFrom, sourceTo, context)?.let { return it }

        val sourceA = sourceFrom.value ?: return "command $number clone source cuboid is missing coordinates"
        val sourceB = sourceTo.value ?: return "command $number clone source cuboid is missing coordinates"
        val destination = parsePoint(tokens, 7, anchor)
        destination.error?.let { return "command $number clone destination is not scope-checkable: $it" }
        val destinationPoint = destination.value ?: return "command $number clone destination is missing coordinates"
        val destinationTo = runCatching {
            BlockCoordinate(
                Math.addExact(destinationPoint.x, kotlin.math.abs(Math.subtractExact(sourceB.x, sourceA.x))),
                Math.addExact(destinationPoint.y, kotlin.math.abs(Math.subtractExact(sourceB.y, sourceA.y))),
                Math.addExact(destinationPoint.z, kotlin.math.abs(Math.subtractExact(sourceB.z, sourceA.z))),
            )
        }.getOrNull() ?: return "command $number clone destination cuboid is not scope-checkable: coordinate overflow"
        val destinationParsed = ParsedPoint(destinationTo, null)
        val operate = Bounds(scope.operate.chunks, scope.operate.minY.toLong(), scope.operate.maxY.toLong(), "operate")
        return validateBox(number, "clone destination cuboid", ParsedPoint(destinationPoint, null), destinationParsed, operate)
    }

    private fun validateBox(number: Int, label: String, from: ParsedPoint, to: ParsedPoint, region: Bounds): String? {
        from.error?.let { return "command $number $label is not scope-checkable: $it" }
        to.error?.let { return "command $number $label is not scope-checkable: $it" }
        val first = from.value ?: return "command $number $label is missing coordinates"
        val second = to.value ?: return "command $number $label is missing coordinates"
        val minX = minOf(first.x, second.x)
        val maxX = maxOf(first.x, second.x)
        val minY = minOf(first.y, second.y)
        val maxY = maxOf(first.y, second.y)
        val minZ = minOf(first.z, second.z)
        val maxZ = maxOf(first.z, second.z)
        if (minY < region.minY || maxY > region.maxY) return "command $number $label is not fully inside the ${region.label} region"

        val minChunkX = Math.floorDiv(minX, 16L)
        val maxChunkX = Math.floorDiv(maxX, 16L)
        val minChunkZ = Math.floorDiv(minZ, 16L)
        val maxChunkZ = Math.floorDiv(maxZ, 16L)
        val width = maxChunkX - minChunkX + 1L
        val depth = maxChunkZ - minChunkZ + 1L
        val chunkCount = if (width <= 0L || depth <= 0L) Long.MAX_VALUE else runCatching {
            Math.multiplyExact(width, depth)
        }.getOrDefault(Long.MAX_VALUE)
        if (chunkCount > region.chunks.size.toLong()) return "command $number $label is not fully inside the ${region.label} region"

        var chunkX = minChunkX
        while (chunkX <= maxChunkX) {
            var chunkZ = minChunkZ
            while (chunkZ <= maxChunkZ) {
                if (chunkCoordinate(chunkX, chunkZ) !in region.chunks) {
                    return "command $number $label is not fully inside the ${region.label} region"
                }
                if (chunkZ == Long.MAX_VALUE) break
                chunkZ++
            }
            if (chunkX == Long.MAX_VALUE) break
            chunkX++
        }
        return null
    }

    private fun inside(region: Bounds, point: BlockCoordinate): Boolean =
        point.y in region.minY..region.maxY && chunkCoordinate(Math.floorDiv(point.x, 16L), Math.floorDiv(point.z, 16L)) in region.chunks

    private fun parsePoint(tokens: List<String>, start: Int, anchor: BlockCoordinate): ParsedPoint {
        if (start + 2 >= tokens.size) return ParsedPoint(null, "three coordinates are required")
        val x = parseCoordinate(tokens[start], anchor.x)
        val y = parseCoordinate(tokens[start + 1], anchor.y)
        val z = parseCoordinate(tokens[start + 2], anchor.z)
        if (x == null || y == null || z == null) return ParsedPoint(null, "coordinates must be integer or `~` positions")
        return ParsedPoint(BlockCoordinate(x, y, z), null)
    }

    private fun parseCoordinate(raw: String, anchor: Long): Long? = when {
        raw == "~" -> anchor
        raw.startsWith("~") -> raw.substring(1).toLongOrNull()?.let { runCatching { Math.addExact(anchor, it) }.getOrNull() }
        raw.startsWith("^") -> null
        else -> raw.toLongOrNull()
    }

    private fun chunkCoordinate(x: Long, z: Long): ChunkPos? {
        if (x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || z !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
        return ChunkPos(x.toInt(), z.toInt())
    }

    private data class ParsedPoint(val value: BlockCoordinate?, val error: String?)
    private data class Bounds(val chunks: Set<ChunkPos>, val minY: Long, val maxY: Long, val label: String)
}
