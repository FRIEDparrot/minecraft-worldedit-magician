package com.magician.worldedit.client.command.wcl

// ─── AST ───────────────────────────────────────────────────────────────────────

sealed class WclExpr {
    data class Num(val value: Long) : WclExpr()
    data class Str(val value: String) : WclExpr()
    data class Var(val name: String) : WclExpr()   // $name
    data class BinOp(val op: String, val left: WclExpr, val right: WclExpr) : WclExpr()
    data class Call(val name: String, val args: List<WclExpr>) : WclExpr()
    data class RandRange(val lo: WclExpr, val hi: WclExpr) : WclExpr()  // <random(LO, HI)>
    data class RandPick(val items: List<WclExpr>) : WclExpr()             // random([a, b, c])
    data class RandWeight(val choices: List<Pair<WclExpr, Long>>) : WclExpr() // random({a: 60, b: 40})
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
        skipNl()
        while (!eof()) {
            val s = parseStmt()
            if (s != null) {
                if (s is WclStmt.PatternDef) patterns[s.name] = s
                else stmts.add(s)
            }
            skipNl()
        }
        return WclProgram(stmts, patterns)
    }

    private fun parseStmt(): WclStmt? {
        skipNl()
        return when (val tok = peek()) {
            is WclTok.Eof -> null
            is WclTok.Newline -> { advance(); null }
            is WclTok.Slash -> { advance(); null }
            is WclTok.Ident -> {
                when (tok.name) {
                    "echo"    -> { advance(); WclStmt.Echo(parseExpr()) }
                    "probe"   -> { advance(); WclStmt.Probe(parseProbeCmd()) }
                    "seed"    -> { advance(); WclStmt.Seed(parseSeedName()) }
                    "if"      -> { advance(); parseIf() }
                    "for", "loop" -> { advance(); parseLoop() }
                    "pattern" -> { advance(); parsePatternDef() }
                    else      -> parseIdentOrCmd(tok.name)
                }
            }
            is WclTok.Keyword -> {
                when (tok.word) {
                    "echo"    -> { advance(); WclStmt.Echo(parseExpr()) }
                    "probe"   -> { advance(); WclStmt.Probe(parseProbeCmd()) }
                    "seed"    -> { advance(); WclStmt.Seed(parseSeedName()) }
                    "if"      -> { advance(); parseIf() }
                    "for", "loop" -> { advance(); parseLoop() }
                    "pattern" -> { advance(); parsePatternDef() }
                    else      -> parseMcLine()
                }
            }
            is WclTok.McCmd -> { advance(); WclStmt.Cmd(tok.text) }
            else            -> parseMcLine()
        }
    }

    // "probe ..." — rest of the line as MC command
    private fun parseProbeCmd(): String {
        val sb = StringBuilder()
        while (!eof() && peek() !is WclTok.Newline) {
            sb.append(tokToStr(peek())); advance()
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun parseSeedName(): String {
        return when (val tok = peek()) {
            is WclTok.StrTok -> { advance(); tok.value }
            is WclTok.Ident  -> { advance(); tok.name }
            else             -> ""
        }
    }

    private fun parseIf(): WclStmt.If {
        val cond = parseExpr()
        val then = parseBlock()
        skipNl()
        var else_: List<WclStmt>? = null
        if (peek() is WclTok.Keyword && (peek() as WclTok.Keyword).word == "else") {
            advance(); skipNl()
            if (peek() is WclTok.Keyword && (peek() as WclTok.Keyword).word == "if") {
                advance(); else_ = listOf(parseIf())
            } else {
                else_ = parseBlock()
            }
        }
        return WclStmt.If(cond, then, else_)
    }

    private fun parseLoop(): WclStmt {
        val varTok = expectIdent("loop needs variable name")
        advance()
        skipNl()
        expectKeyword("in", "loop needs 'in'")
        val variable = varTok.name
        return when (peek()) {
            is WclTok.LBracket -> {
                advance()
                val values = mutableListOf<WclExpr>()
                while (!eof() && peek() !is WclTok.RBracket) {
                    skipNl()
                    if (peek() is WclTok.RBracket) break
                    values.add(parseExpr()); skipNl()
                    if (peek() is WclTok.Comma) advance()
                }
                expect(WclTok.RBracket, "enum loop needs ]")
                val body = parseBlock()
                WclStmt.LoopEnum(variable, values, body)
            }
            else -> {
                val start = parseExpr()
                expectDot("range needs .."); expectDot("range needs ..")
                val end = parseExpr()
                var step: WclExpr? = null
                skipNl()
                if (peek() is WclTok.Keyword && (peek() as WclTok.Keyword).word == "step") {
                    advance(); step = parseExpr()
                }
                val body = parseBlock()
                WclStmt.LoopRange(variable, start, end, step, body)
            }
        }
    }

    private fun parsePatternDef(): WclStmt {
        val nameTok = expectIdent("pattern needs a name"); advance()
        val params = mutableListOf<Pair<String, String>>()
        if (peek() is WclTok.LParen) {
            advance()
            while (!eof() && peek() !is WclTok.RParen) {
                val pTok = expectIdent("param name"); advance()
                val ptype = if (peek() is WclTok.Colon) { advance(); parseTypeName() } else "int"
                params.add(pTok.name to ptype)
                if (peek() is WclTok.Comma) advance()
            }
            expect(WclTok.RParen, "params need )")
        }
        val body = parseBlock()
        return WclStmt.PatternDef(nameTok.name, params, body)
    }

    private fun parseTypeName(): String {
        return when (val tok = peek()) {
            is WclTok.Ident   -> { advance(); tok.name }
            is WclTok.Keyword  -> { advance(); tok.word }
            else -> "int"
        }
    }

    private fun parseBlock(): List<WclStmt> {
        expect(WclTok.LBrace, "block needs {")
        val stmts = mutableListOf<WclStmt>()
        skipNl()
        while (!eof() && peek() !is WclTok.RBrace) {
            val s = parseStmt(); if (s != null) stmts.add(s)
            skipNl()
        }
        expect(WclTok.RBrace, "block needs }")
        return stmts
    }

    // "name = expr" | "name(...)" | MC command
    private fun parseIdentOrCmd(name: String): WclStmt {
        return when (peek()) {
            is WclTok.Eq    -> { advance(); WclStmt.Assign(name, parseExpr()) }
            is WclTok.LParen -> { advance(); WclStmt.PatternCall(name, parseCallArgsMap()) }
            else              -> { val cmd = parseMcLineRaw(name); WclStmt.Cmd(cmd) }
        }
    }

    private fun parseMcLine(): WclStmt {
        val cmd = parseMcLineRaw(null)
        return WclStmt.Cmd(cmd)
    }

    // Build MC command string from current position until newline
    private fun parseMcLineRaw(prefixName: String?): String {
        val sb = StringBuilder()
        if (prefixName != null) sb.append(prefixName).append(' ')
        while (!eof() && peek() !is WclTok.Newline) {
            sb.append(tokToStr(peek())); advance()
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    // ── Expr ──────────────────────────────────────────────────────────────────

    private fun parseExpr(): WclExpr = parseCmp()

    private fun parseCmp(): WclExpr {
        var left = parseAdd()
        while (true) {
            left = when (peek()) {
                is WclTok.Le   -> { advance(); WclExpr.BinOp("<=", left, parseAdd()) }
                is WclTok.Ge   -> { advance(); WclExpr.BinOp(">=", left, parseAdd()) }
                is WclTok.Lt   -> { advance(); WclExpr.BinOp("<", left, parseAdd()) }
                is WclTok.Gt   -> { advance(); WclExpr.BinOp(">", left, parseAdd()) }
                is WclTok.Ne   -> { advance(); WclExpr.BinOp("!=", left, parseAdd()) }
                is WclTok.EqEq -> { advance(); WclExpr.BinOp("==", left, parseAdd()) }
                else           -> return left
            }
        }
    }

    private fun parseAdd(): WclExpr {
        var left = parseMul()
        while (true) {
            left = when (peek()) {
                is WclTok.Plus  -> { advance(); WclExpr.BinOp("+", left, parseMul()) }
                is WclTok.Minus -> { advance(); WclExpr.BinOp("-", left, parseMul()) }
                else            -> return left
            }
        }
    }

    private fun parseMul(): WclExpr {
        var left = parsePrimary()
        while (true) {
            left = when (peek()) {
                is WclTok.Star    -> { advance(); WclExpr.BinOp("*", left, parsePrimary()) }
                is WclTok.Slash   -> { advance(); WclExpr.BinOp("/", left, parsePrimary()) }
                is WclTok.Percent -> { advance(); WclExpr.BinOp("%", left, parsePrimary()) }
                else              -> return left
            }
        }
    }

    private fun parsePrimary(): WclExpr {
        val tok = peek()
        return when (tok) {
            is WclTok.Num    -> { advance(); WclExpr.Num(tok.value) }
            is WclTok.StrTok -> { advance(); WclExpr.Str(tok.value) }
            is WclTok.Ident  -> {
                advance()
                when (peek()) {
                    is WclTok.LParen -> {
                        advance()
                        when (tok.name) {
                            "random" -> {
                                if (peek() is WclTok.LBracket) {
                                    advance()
                                    if (peek() is WclTok.LBrace) {
                                        // weighted: {a: 60, b: 40}
                                        advance()
                                        val choices = mutableListOf<Pair<WclExpr, Long>>()
                                        while (!eof() && peek() !is WclTok.RBrace) {
                                            val item = parseExpr()
                                            expect(WclTok.Colon, "weight needs :")
                                            val w = expectNum("weight"); advance()
                                            choices.add(item to w.value)
                                            if (peek() is WclTok.Comma) advance()
                                        }
                                        expect(WclTok.RBrace, "weighted needs }")
                                        expect(WclTok.RParen, "random needs )")
                                        WclExpr.RandWeight(choices)
                                    } else {
                                        // list: [a, b, c]
                                        val items = mutableListOf<WclExpr>()
                                        while (!eof() && peek() !is WclTok.RBracket) {
                                            skipNl()
                                            if (peek() is WclTok.RBracket) break
                                            items.add(parseExpr()); skipNl()
                                            if (peek() is WclTok.Comma) advance()
                                        }
                                        expect(WclTok.RBracket, "list needs ]")
                                        expect(WclTok.RParen, "random needs )")
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
                        // index: name[expr]
                        advance(); val idx = parseExpr()
                        expect(WclTok.RBracket, "index needs ]")
                        WclExpr.Call("index", listOf(WclExpr.Var(tok.name), idx))
                    }
                    else -> WclExpr.Var(tok.name)
                }
            }
            is WclTok.LParen -> {
                advance(); val e = parseExpr(); expect(WclTok.RParen, "paren needs )"); e
            }
            is WclTok.Keyword -> {
                advance()
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
            skipNl(); if (peek() is WclTok.RParen) break
            args.add(parseExpr()); skipNl()
            if (peek() is WclTok.Comma) advance()
        }
        expect(WclTok.RParen, "call needs )"); return args
    }

    private fun parseCallArgsMap(): Map<String, WclExpr> {
        val args = parseCallArgs()
        return args.mapIndexed { idx, e -> "arg$idx" to e }.toMap()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun eof() = peek() is WclTok.Eof
    private fun peek() = toks.getOrElse(i) { WclTok.Eof }
    private fun peek2() = toks.getOrElse(i + 1) { WclTok.Eof }
    private fun advance(): WclTok = toks.getOrElse(i++) { WclTok.Eof }

    private fun <T> expect(tok: WclTok, msg: String): T {
        val cur = peek()
        if (cur == tok) return cur as T
        return toks.firstOrNull() as? T ?: toks.first() as T
    }

    private fun expectIdent(msg: String): WclTok.Ident {
        val cur = peek()
        return if (cur is WclTok.Ident) cur else WclTok.Ident("")
    }

    private fun expectKeyword(w: String, msg: String) {
        if (peek() is WclTok.Keyword && (peek() as WclTok.Keyword).word == w) advance()
    }

    private fun expectDot(msg: String) {
        if (peek() is WclTok.Dot) advance()
    }

    private fun expectNum(msg: String): WclTok.Num {
        val cur = peek()
        return if (cur is WclTok.Num) cur else WclTok.Num(0)
    }

    private fun expect(tok: WclTok, msg: String) {
        if (peek() == tok) advance()
    }

    private fun skipNl() {
        while (!eof() && (peek() is WclTok.Newline || peek() is WclTok.Slash)) {
            if (peek() is WclTok.Newline) advance()
            else {
                // slash might be a division or comment start
                if (peek2() is WclTok.Slash) { while (!eof() && peek() !is WclTok.Newline) advance() }
                else if (peek2() is WclTok.Star) { advance(); advance(); while (!eof()) { if (peek() is WclTok.Star && peek2() is WclTok.Slash) { advance(); advance(); break }; advance() } }
                else break
            }
        }
    }

    private fun tokToStr(tok: WclTok): String = when (tok) {
        is WclTok.Num    -> tok.value.toString()
        is WclTok.StrTok -> tok.value
        is WclTok.Ident  -> tok.name
        is WclTok.Keyword -> tok.word
        is WclTok.McCmd  -> " ${tok.text}"
        is WclTok.LParen -> "("
        is WclTok.RParen -> ")"
        is WclTok.LBracket -> "["
        is WclTok.RBracket -> "]"
        is WclTok.LBrace -> " { "
        is WclTok.RBrace -> " }"
        is WclTok.Comma  -> ", "
        is WclTok.Colon  -> ": "
        is WclTok.Dot    -> "."
        is WclTok.Eq     -> " = "
        is WclTok.Plus   -> " + "
        is WclTok.Minus  -> " - "
        is WclTok.Star   -> " * "
        is WclTok.Slash  -> "/"
        is WclTok.Percent -> " % "
        is WclTok.Lt     -> " < "
        is WclTok.Le     -> " <= "
        is WclTok.Gt     -> " > "
        is WclTok.Ge     -> " >= "
        is WclTok.EqEq   -> " == "
        is WclTok.Ne     -> " != "
        is WclTok.Newline -> "\n"
        is WclTok.Eof    -> ""
    }
}
