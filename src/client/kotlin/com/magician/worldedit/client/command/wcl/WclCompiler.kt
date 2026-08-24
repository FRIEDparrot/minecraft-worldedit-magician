package com.magician.worldedit.client.command.wcl

import java.util.Random

// ─── Compile context ─────────────────────────────────────────────────────────────

class WclCtx(
    val playerX: Int,
    val playerY: Int,
    val playerZ: Int,
    var seed: String = "",
) {
    private var rng: Random? = null

    fun rng(): Random {
        if (rng == null) {
            rng = if (seed.isNotEmpty()) Random(seed.hashCode().toLong()) else Random()
        }
        return rng!!
    }

    fun randInt(lo: Long, hi: Long): Long {
        return lo + (kotlin.math.abs(rng().nextLong()) % (hi - lo + 1))
    }

    fun pickStr(items: List<String>): String {
        return items[rng().nextInt(items.size)]
    }

    fun weightedPick(choices: List<Pair<String, Long>>): String {
        val r = rng()
        val total = choices.sumOf { it.second }
        var roll = r.nextLong(total)
        for ((item, weight) in choices) {
            roll -= weight
            if (roll < 0) return item
        }
        return choices.last().first
    }
}

// ─── Result ─────────────────────────────────────────────────────────────────────

sealed class WclResult {
    data class Ok(val commands: List<String>, val echoes: List<String>) : WclResult()
    data class Err(val msg: String, val line: Int = 0) : WclResult()
}

// ─── Compiler ─────────────────────────────────────────────────────────────────

class WclCompiler(private val prog: WclProgram) {

    companion object {
        const val MAX = 1000
        private val RAND_RE = Regex("""~<random\((-?\d+),(-?\d+)\)""")
    }

    fun compile(ctx: WclCtx): WclResult {
        return try {
            val out = mutableListOf<String>()
            val echoes = mutableListOf<String>()
            execStmts(prog.stmts, ctx, mutableMapOf(), out, echoes)
            WclResult.Ok(out, echoes)
        } catch (e: CErr) {
            WclResult.Err(e.message, e.line)
        }
    }

    private fun execStmts(
        stmts: List<WclStmt>,
        ctx: WclCtx,
        scope: MutableMap<String, Long>,
        out: MutableList<String>,
        echoes: MutableList<String>,
    ) {
        for (s in stmts) execStmt(s, ctx, scope, out, echoes)
    }

    private fun execStmt(
        s: WclStmt,
        ctx: WclCtx,
        scope: MutableMap<String, Long>,
        out: MutableList<String>,
        echoes: MutableList<String>,
    ) {
        if (out.size >= MAX) throw CErr("Exceeded MAX_COMMANDS ($MAX).")

        when (s) {
            is WclStmt.Cmd -> {
                if (s.text.isNotBlank()) out.add(substVars(s.text, ctx, scope))
            }
            is WclStmt.Echo -> echoes.add(evalStr(s.msg, ctx, scope))
            is WclStmt.Probe -> {
                val cmd = substVars(s.cmd, ctx, scope)
                echoes.add("[PROBE] $cmd")
            }
            is WclStmt.Seed -> { ctx.seed = s.name /* affects ctx RNG from now on */ }
            is WclStmt.Assign -> scope[s.name] = evalLong(s.value, ctx, scope)
            is WclStmt.LoopRange -> {
                var i = evalLong(s.start, ctx, scope)
                val end = evalLong(s.end, ctx, scope)
                val step = s.step?.let { evalLong(it, ctx, scope) } ?: 1L
                val dir = if (step >= 0) 1L else -1L
                val maxIters = if (step == 0L) 0L else kotlin.math.abs((end - i) / step) + 1
                if (maxIters > MAX * 10) throw CErr("Loop too large (${maxIters} iterations).")
                while (dir * i <= dir * end) {
                    scope[s.variable] = i
                    execStmts(s.body, ctx, scope, out, echoes)
                    i += step
                    if (out.size >= MAX) throw CErr("Loop exceeded MAX_COMMANDS ($MAX).")
                }
            }
            is WclStmt.LoopEnum -> {
                for (v in s.values) {
                    scope[s.variable] = evalLong(v, ctx, scope)
                    execStmts(s.body, ctx, scope, out, echoes)
                }
            }
            is WclStmt.If -> {
                if (evalBool(s.cond, ctx, scope)) execStmts(s.then, ctx, scope, out, echoes)
                else s.else_?.let { execStmts(it, ctx, scope, out, echoes) }
            }
            is WclStmt.PatternDef -> { /* stored in prog.patterns, called via PatternCall */ }
            is WclStmt.PatternCall -> {
                val pat = prog.patterns[s.name] ?: throw CErr("Unknown pattern: ${s.name}")
                val patScope = scope.toMutableMap()
                s.args.forEach { (k, v) -> patScope[k] = evalLong(v, ctx, scope) }
                execStmts(pat.body, ctx, patScope, out, echoes)
            }
        }
    }

    // ── eval ─────────────────────────────────────────────────────────────────

    private fun evalLong(e: WclExpr, ctx: WclCtx, scope: Map<String, Long>): Long {
        return when (e) {
            is WclExpr.Num   -> e.value
            is WclExpr.Var   -> scope[e.name] ?: 0L
            is WclExpr.BinOp -> {
                val l = evalLong(e.left, ctx, scope)
                val r = evalLong(e.right, ctx, scope)
                when (e.op) {
                    "+"  -> l + r; "-" -> l - r; "*" -> l * r
                    "/"  -> if (r != 0L) l / r else 0L
                    "%"  -> if (r != 0L) l % r else 0L
                    else -> 0L
                }
            }
            is WclExpr.RandRange -> ctx.randInt(evalLong(e.lo, ctx, scope), evalLong(e.hi, ctx, scope))
            is WclExpr.RandPick, is WclExpr.RandWeight -> 0L
            is WclExpr.Call -> 0L
            is WclExpr.Str -> 0L
        }
    }

    private fun evalBool(e: WclExpr, ctx: WclCtx, scope: Map<String, Long>): Boolean {
        return when (e) {
            is WclExpr.Num   -> e.value != 0L
            is WclExpr.Var   -> (scope[e.name] ?: 0L) != 0L
            is WclExpr.BinOp -> {
                val l = evalLong(e.left, ctx, scope)
                val r = evalLong(e.right, ctx, scope)
                when (e.op) {
                    "==" -> l == r; "!=" -> l != r
                    "<"  -> l < r; "<=" -> l <= r
                    ">"  -> l > r; ">=" -> l >= r
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun evalStr(e: WclExpr, ctx: WclCtx, scope: Map<String, Long>): String {
        return when (e) {
            is WclExpr.Str -> e.value
            is WclExpr.Num -> e.value.toString()
            is WclExpr.Var -> (scope[e.name] ?: 0L).toString()
            is WclExpr.BinOp -> {
                val l = evalStr(e.left, ctx, scope)
                val r = evalStr(e.right, ctx, scope)
                if (e.op == "+") l + r else l
            }
            is WclExpr.RandRange -> ctx.randInt(
                evalLong(e.lo, ctx, scope), evalLong(e.hi, ctx, scope)
            ).toString()
            is WclExpr.RandPick -> {
                val items = e.items.map { evalStr(it, ctx, scope) }
                ctx.pickStr(items)
            }
            is WclExpr.RandWeight -> {
                val choices = e.choices.map { evalStr(it.first, ctx, scope) to it.second }
                ctx.weightedPick(choices)
            }
            is WclExpr.Call -> ""
        }
    }

    // Substitute $var and ~<random(lo,hi)> in MC command strings
    private fun substVars(cmd: String, ctx: WclCtx, scope: Map<String, Long>): String {
        var r = cmd
        for ((n, v) in scope) r = r.replace("\$$n", v.toString())
        r = RAND_RE.replace(r) { m ->
            val lo = m.groupValues[1].toLongOrNull() ?: 0L
            val hi = m.groupValues[2].toLongOrNull() ?: 0L
            ctx.randInt(lo, hi).toString()
        }
        return r
    }
}

class CErr(override val message: String, val line: Int = 0) : Exception(message)
