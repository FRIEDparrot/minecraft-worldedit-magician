package com.magician.worldedit.client.command.wcl

// ─── AST ───────────────────────────────────────────────────────────────────────

sealed class WclExpr {
    data class Num(val value: Long) : WclExpr()
    data class Str(val value: String) : WclExpr()
    data class Var(val name: String) : WclExpr()
    data class BinOp(val op: String, val left: WclExpr, val right: WclExpr) : WclExpr()
    data class Call(val name: String, val args: List<WclExpr>) : WclExpr()
    data class RandRange(val lo: WclExpr, val hi: WclExpr) : WclExpr()
    data class RandPick(val items: List<WclExpr>) : WclExpr()
    data class RandWeight(val choices: List<Pair<WclExpr, Long>>) : WclExpr()
}

sealed class WclStmt {
    data class Cmd(val text: String) : WclStmt()
    data class Echo(val msg: WclExpr) : WclStmt()
    data class Probe(val cmd: String) : WclStmt()
    data class Seed(val name: String) : WclStmt()
    data class Assign(val name: String, val value: WclExpr) : WclStmt()
    data class LoopRange(val variable: String, val start: WclExpr, val end: WclExpr, val step: WclExpr?, val body: List<WclStmt>) : WclStmt()
    data class LoopEnum(val variable: String, val values: List<WclExpr>, val body: List<WclStmt>) : WclStmt()
    data class If(val cond: WclExpr, val then: List<WclStmt>, val else_: List<WclStmt>?) : WclStmt()
    data class PatternDef(val name: String, val params: List<Pair<String, String>>, val body: List<WclStmt>) : WclStmt()
    data class PatternCall(val name: String, val args: Map<String, WclExpr>) : WclStmt()
}

data class WclProgram(val stmts: List<WclStmt>, val patterns: Map<String, WclStmt.PatternDef> = emptyMap())

// ─── Parser ────────────────────────────────────────────────────────────────────

class WclParser(private val toks: List<WclTok>) {
    private var i = 0

    fun parse(): WclProgram {
        val stmts = mutableListOf<WclStmt>()
        val patterns = mutableMapOf<String, WclStmt.PatternDef>()
        skipToNext()
        while (!eof()) {
            val s = parseStmt() ?: break
            if (s is WclStmt.PatternDef) patterns[s.name] = s
            else stmts.add(s)
            skipToNext()
        }
        return WclProgram(stmts, patterns)
    }

    private fun parseStmt(): WclStmt? {
        skipToNext()
        if (eof()) return null
        val tok = peek()
        return when {
            tok is WclTok.Ident -> {
                when (tok.name) {
                    "echo"    -> { adv(); WclStmt.Echo(parseExpr()) }
                    "probe"   -> { adv(); WclStmt.Probe(parseProbeCmd()) }
                    "seed"    -> { adv(); WclStmt.Seed(parseSeedName()) }
                    "if"      -> { adv(); parseIf() }
                    "for"     -> { adv(); parseLoop() }
                    "loop"    -> { adv(); parseLoop() }
                    "pattern" -> { adv(); parsePatternDef() }
                    else      -> parseIdentOrCmd(tok.name)
                }
            }
            tok is WclTok.Keyword -> {
                when (tok.word) {
                    "echo"    -> { adv(); WclStmt.Echo(parseExpr()) }
                    "probe"   -> { adv(); WclStmt.Probe(parseProbeCmd()) }
                    "seed"    -> { adv(); WclStmt.Seed(parseSeedName()) }
                    "if"      -> { adv(); parseIf() }
                    "for"     -> { adv(); parseLoop() }
                    "loop"    -> { adv(); parseLoop() }
                    "pattern" -> { adv(); parsePatternDef() }
                    else      -> parseMcLine()
                }
            }
            tok is WclTok.McCmd -> { adv(); WclStmt.Cmd(tok.text) }
            else -> parseMcLine()
        }
    }

    // ── Loop ─────────────────────────────────────────────────────────────────

    private fun parseLoop(): WclStmt {
        val varTok = expectIdent("loop needs variable name")
        val variable = varTok.name
        skipToNext()
        acceptIn()
        return when (peek()) {
            is WclTok.LBracket -> {
                adv()
                skipToNext()
                // [start..end] or [a, b, c]
                if (peek() is WclTok.Num && peek2() is WclTok.Dot && peekAhead(2) is WclTok.Dot) {
                    // Range form
                    val start = parseExpr()
                    expectDot("range"); expectDot("range")
                    val end = parseExpr()
                    skipToNext()
                    var step: WclExpr? = null
                    if (peek() is WclTok.Comma) {
                        adv(); skipToNext()
                        if (peek() !is WclTok.RBracket) step = parseExpr()
                    }
                    expect(WclTok.RBracket, "range needs ]")
                    val body = parseBlock()
                    WclStmt.LoopRange(variable, start, end, step, body)
                } else {
                    // Enum form
                    val values = mutableListOf<WclExpr>()
                    while (!eof() && peek() !is WclTok.RBracket) {
                        skipToNext()
                        if (peek() is WclTok.RBracket) break
                        values.add(parseExpr()); skipToNext()
                        if (peek() is WclTok.Comma) adv()
                    }
                    expect(WclTok.RBracket, "enum needs ]")
                    val body = parseBlock()
                    WclStmt.LoopEnum(variable, values, body)
                }
            }
            else -> {
                // Bare range: in start..end [step N]
                val start = parseExpr()
                if (peek() is WclTok.Dot && peek2() is WclTok.Dot) {
                    expectDot("range"); expectDot("range")
                    val end = parseExpr()
                    var step: WclExpr? = null
                    skipToNext()
                    if (peek() is WclTok.Keyword && (peek() as WclTok.Keyword).word == "step") {
                        adv(); step = parseExpr()
                    }
                    val body = parseBlock()
                    WclStmt.LoopRange(variable, start, end, step, body)
                } else {
                    // Single-value enum
                    val body = parseBlock()
                    WclStmt.LoopEnum(variable, listOf(start), body)
                }
            }
        }
    }

    private fun acceptIn() {
        skipToNext()
        if (peek() is WclTok.Keyword && (peek() as WclTok.Keyword).word == "in") adv()
    }

    // ── If ────────────────────────────────────────────────────────────────────

    private fun parseIf(): WclStmt {
        val cond = parseExpr()
        val then = parseBlock()
        skipToNext()
        var else_: List<WclStmt>? = null
        if (peek() is WclTok.Keyword && (peek() as WclTok.Keyword).word == "else") {
            adv(); skipToNext()
            if (peek() is WclTok.Keyword && (peek() as WclTok.Keyword).word == "if") {
                adv(); else_ = listOf(parseIf())
            } else {
                else_ = parseBlock()
            }
        }
        return WclStmt.If(cond, then, else_)
    }

    // ── Pattern ──────────────────────────────────────────────────────────────

    private fun parsePatternDef(): WclStmt {
        val nameTok = expectIdent("pattern needs name"); adv()
        val params = mutableListOf<Pair<String, String>>()
        if (peek() is WclTok.LParen) {
            adv()
            while (!eof() && peek() !is WclTok.RParen) {
                skipToNext()
                val pTok = expectIdent("param name"); adv()
                val ptype = if (peek() is WclTok.Colon) { adv(); parseTypeName() } else "int"
                params.add(pTok.name to ptype)
                if (peek() is WclTok.Comma) adv()
            }
            expect(WclTok.RParen, "params need )")
        }
        val body = parseBlock()
        return WclStmt.PatternDef(nameTok.name, params, body)
    }

    private fun parseTypeName(): String = when (peek()) {
        is WclTok.Ident -> { adv(); (peek(-1) as WclTok.Ident).name }
        is WclTok.Keyword -> { adv(); (peek(-1) as WclTok.Keyword).word }
        else -> "int"
    }

    private fun parseBlock(): List<WclStmt> {
        skipToNext()
        expect(WclTok.LBrace, "block needs {")
        val stmts = mutableListOf<WclStmt>()
        while (!eof() && peek() !is WclTok.RBrace) {
            val s = parseStmt(); if (s != null) stmts.add(s)
            skipToNext()
        }
        expect(WclTok.RBrace, "block needs }")
        return stmts
    }

    // ── Statement helpers ───────────────────────────────────────────────────

    private fun parseIdentOrCmd(name: String): WclStmt {
        skipToNext()
        return when {
            peek() is WclTok.Eq    -> { adv(); WclStmt.Assign(name, parseExpr()) }
            peek() is WclTok.LParen -> { adv(); WclStmt.PatternCall(name, parseCallArgsMap()) }
            else                     -> WclStmt.Cmd(buildMcCmd(name))
        }
    }

    private fun parseMcLine(): WclStmt {
        val cmd = buildMcCmd(null)
        return WclStmt.Cmd(cmd)
    }

    // Build MC command from position until newline/block/keyword
    private fun buildMcCmd(prefixName: String?): String {
        val sb = StringBuilder()
        if (prefixName != null) sb.append(prefixName).append(' ')
        while (!eof()) {
            when (val tok = peek()) {
                is WclTok.Newline, is WclTok.Eof -> { adv(); break }
                is WclTok.LBrace, is WclTok.RBrace -> break
                is WclTok.Keyword -> break
                is WclTok.Ident if peek2() is WclTok.Colon -> break // pattern param type:
                else -> { sb.append(tokToStr(tok)); adv() }
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    // ── Expr ──────────────────────────────────────────────────────────────────

    private fun parseExpr(): WclExpr {
        return parseCmp()
    }

    private fun parseCmp(): WclExpr {
        var left = parseAdd()
        while (true) {
            left = when (peek()) {
                is WclTok.Le   -> { adv(); WclExpr.BinOp("<=", left, parseAdd()) }
                is WclTok.Ge   -> { adv(); WclExpr.BinOp(">=", left, parseAdd()) }
                is WclTok.Lt   -> { adv(); WclExpr.BinOp("<", left, parseAdd()) }
                is WclTok.Gt   -> { adv(); WclExpr.BinOp(">", left, parseAdd()) }
                is WclTok.Ne   -> { adv(); WclExpr.BinOp("!=", left, parseAdd()) }
                is WclTok.EqEq -> { adv(); WclExpr.BinOp("==", left, parseAdd()) }
                else           -> return left
            }
        }
    }

    private fun parseAdd(): WclExpr {
        var left = parseMul()
        while (true) {
            left = when (peek()) {
                is WclTok.Plus  -> { adv(); WclExpr.BinOp("+", left, parseMul()) }
                is WclTok.Minus -> { adv(); WclExpr.BinOp("-", left, parseMul()) }
                else            -> return left
            }
        }
    }

    private fun parseMul(): WclExpr {
        var left = parsePrimary()
        while (true) {
            left = when (peek()) {
                is WclTok.Star    -> { adv(); WclExpr.BinOp("*", left, parsePrimary()) }
                is WclTok.Slash   -> { adv(); WclExpr.BinOp("/", left, parsePrimary()) }
                is WclTok.Percent -> { adv(); WclExpr.BinOp("%", left, parsePrimary()) }
                else              -> return left
            }
        }
    }

    private fun parsePrimary(): WclExpr {
        val tok = peek()
        return when {
            tok is WclTok.Num   -> { adv(); WclExpr.Num(tok.value) }
            tok is WclTok.StrTok -> { adv(); WclExpr.Str(tok.value) }
            tok is WclTok.Ident -> {
                adv()
                when (peek()) {
                    is WclTok.LParen -> {
                        adv()
                        when (tok.name) {
                            "random" -> {
                                if (peek() is WclTok.LBracket) {
                                    adv()
                                    if (peek() is WclTok.LBrace) {
                                        // weighted: {a: 60, b: 40}
                                        adv()
                                        val choices = mutableListOf<Pair<WclExpr, Long>>()
                                        while (!eof() && peek() !is WclTok.RBrace) {
                                            skipToNext()
                                            if (peek() is WclTok.RBrace) break
                                            val item = parseExpr()
                                            expect(WclTok.Colon, "weight needs :")
                                            val w = expectNum("weight"); adv()
                                            choices.add(item to w.value)
                                            if (peek() is WclTok.Comma) adv()
                                        }
                                        expect(WclTok.RBrace, "weighted needs }")
                                        expect(WclTok.RParen, "random(...) needs )")
                                        WclExpr.RandWeight(choices)
                                    } else {
                                        // list: [a, b, c]
                                        val items = mutableListOf<WclExpr>()
                                        while (!eof() && peek() !is WclTok.RBracket) {
                                            skipToNext()
                                            if (peek() is WclTok.RBracket) break
                                            items.add(parseExpr()); skipToNext()
                                            if (peek() is WclTok.Comma) adv()
                                        }
                                        expect(WclTok.RBracket, "list needs ]")
                                        expect(WclTok.RParen, "random(...) needs )")
                                        WclExpr.RandPick(items)
                                    }
                                } else {
                                    val lo = parseExpr(); expect(WclTok.Comma, "random(lo,hi)"); val hi = parseExpr()
                                    expect(WclTok.RParen, "random(lo,hi) needs )")
                                    WclExpr.RandRange(lo, hi)
                                }
                            }
                            else -> WclExpr.Call(tok.name, parseCallArgs())
                        }
                    }
                    is WclTok.LBracket -> {
                        adv(); val idx = parseExpr()
                        expect(WclTok.RBracket, "index needs ]")
                        WclExpr.Call("index", listOf(WclExpr.Var(tok.name), idx))
                    }
                    else -> WclExpr.Var(tok.name)
                }
            }
            tok is WclTok.LParen -> {
                adv(); val e = parseExpr(); expect(WclTok.RParen, "paren needs )"); e
            }
            tok is WclTok.Keyword -> {
                adv()
                when (tok.word) {
                    "true"  -> WclExpr.Num(1)
                    "false" -> WclExpr.Num(0)
                    else    -> WclExpr.Num(0)
                }
            }
            else -> WclExpr.Num(0)
        }
    }

    private fun parseCallArgs(): List<WclExpr> {
        val args = mutableListOf<WclExpr>()
        while (!eof() && peek() !is WclTok.RParen) {
            skipToNext(); if (peek() is WclTok.RParen) break
            args.add(parseExpr()); skipToNext()
            if (peek() is WclTok.Comma) adv()
        }
        expect(WclTok.RParen, "call needs )"); return args
    }

    private fun parseCallArgsMap(): Map<String, WclExpr> {
        return parseCallArgs().mapIndexed { idx, e -> "arg$idx" to e }.toMap()
    }

    // ── Probe / Seed ─────────────────────────────────────────────────────────

    private fun parseProbeCmd(): String {
        val sb = StringBuilder()
        while (!eof() && peek() !is WclTok.Newline) {
            sb.append(tokToStr(peek())); adv()
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun parseSeedName(): String = when (peek()) {
        is WclTok.StrTok -> { adv(); (peek(-1) as WclTok.StrTok).value }
        is WclTok.Ident  -> { adv(); (peek(-1) as WclTok.Ident).name }
        else             -> ""
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    private fun eof() = i >= toks.size || toks[i] is WclTok.Eof
    private fun peek(n: Int = 0) = toks.getOrElse(i + n) { WclTok.Eof }
    private fun adv(): WclTok = toks.getOrElse(i++) { WclTok.Eof }
    private fun peek2() = peek(1)
    private fun peekAhead(n: Int) = peek(n)

    private fun expect(tok: WclTok, msg: String) {
        if (peek() == tok) adv()
    }
    private fun expectDot(msg: String) {
        if (peek() is WclTok.Dot) adv()
    }
    private fun expectIdent(msg: String): WclTok.Ident {
        return peek() as? WclTok.Ident ?: WclTok.Ident("")
    }
    private fun expectNum(msg: String): WclTok.Num {
        return peek() as? WclTok.Num ?: WclTok.Num(0)
    }

    // Skip over newlines and comment lines to the next meaningful token
    private fun skipToNext() {
        while (!eof()) {
            when (peek()) {
                is WclTok.Newline -> adv()
                is WclTok.Slash -> {
                    if (peekAhead(1) is WclTok.Slash) {
                        while (!eof() && peek() !is WclTok.Newline && peek() !is WclTok.Eof) adv()
                    } else if (peekAhead(1) is WclTok.Star) {
                        adv(); adv()
                        while (!eof()) {
                            if (peek() is WclTok.Star && peekAhead(1) is WclTok.Slash) { adv(); adv(); break }
                            adv()
                        }
                    } else break
                }
                else -> break
            }
        }
    }

    private fun tokToStr(tok: WclTok): String = when (tok) {
        is WclTok.Num     -> tok.value.toString()
        is WclTok.StrTok  -> tok.value
        is WclTok.Ident   -> tok.name
        is WclTok.Keyword -> tok.word
        is WclTok.McCmd   -> " ${tok.text}"
        is WclTok.LParen  -> "("
        is WclTok.RParen  -> ")"
        is WclTok.LBracket -> "["
        is WclTok.RBracket -> "]"
        is WclTok.LBrace  -> " { "
        is WclTok.RBrace  -> " }"
        is WclTok.Comma   -> ", "
        is WclTok.Colon   -> ": "
        is WclTok.Dot     -> "."
        is WclTok.Eq      -> " = "
        is WclTok.Plus    -> " + "
        is WclTok.Minus   -> " - "
        is WclTok.Star    -> " * "
        is WclTok.Slash   -> "/"
        is WclTok.Percent -> " % "
        is WclTok.Lt      -> " < "
        is WclTok.Le      -> " <= "
        is WclTok.Gt      -> " > "
        is WclTok.Ge      -> " >= "
        is WclTok.EqEq    -> " == "
        is WclTok.Ne      -> " != "
        is WclTok.Newline -> "\n"
        is WclTok.Eof     -> ""
    }
}
