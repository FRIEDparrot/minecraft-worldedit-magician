package com.magician.worldedit.client.command.wcl

// ─── Token types ────────────────────────────────────────────────────────────────

sealed class WclTok {
    data class Num(val value: Long) : WclTok()
    data class StrTok(val value: String) : WclTok()     // "..."  lexer-produced
    data class Ident(val name: String) : WclTok()
    data class Keyword(val word: String) : WclTok()
    data object LBrace : WclTok()
    data object RBrace : WclTok()
    data object LBracket : WclTok()
    data object RBracket : WclTok()
    data object LParen : WclTok()
    data object RParen : WclTok()
    data object Comma : WclTok()
    data object Colon : WclTok()
    data object Dot : WclTok()
    data object Eq : WclTok()
    data object Plus : WclTok()
    data object Minus : WclTok()
    data object Star : WclTok()
    data object Slash : WclTok()
    data object Percent : WclTok()
    data object Lt : WclTok()
    data object Le : WclTok()
    data object Gt : WclTok()
    data object Ge : WclTok()
    data object Ne : WclTok()
    data object EqEq : WclTok()
    data object Newline : WclTok()
    data class McCmd(val text: String) : WclTok()   // raw MC command line
    data object Eof : WclTok()
}

class WclLexer(private val src: String) {

    private var pos = 0
    private val len = src.length
    private val tokens = mutableListOf<WclTok>()

    private val KW = setOf(
        "in", "from", "to", "step", "echo", "probe", "seed", "random",
        "volume", "shell", "line", "if", "else", "true", "false",
        "int", "str", "block", "for", "loop", "pattern"
    )

    fun tokenize(): List<WclTok> {
        while (pos < len) {
            when (val c = src[pos]) {
                ' ', '\t' -> pos++
                '\n' -> { tokens.add(WclTok.Newline); pos++ }
                '\r' -> pos++
                '{' -> { tokens.add(WclTok.LBrace); pos++ }
                '}' -> { tokens.add(WclTok.RBrace); pos++ }
                '[' -> { tokens.add(WclTok.LBracket); pos++ }
                ']' -> { tokens.add(WclTok.RBracket); pos++ }
                '(' -> { tokens.add(WclTok.LParen); pos++ }
                ')' -> { tokens.add(WclTok.RParen); pos++ }
                ',' -> { tokens.add(WclTok.Comma); pos++ }
                '.' -> { tokens.add(WclTok.Dot); pos++ }
                ':' -> { tokens.add(WclTok.Colon); pos++ }
                '+' -> { tokens.add(WclTok.Plus); pos++ }
                '*' -> { tokens.add(WclTok.Star); pos++ }
                '%' -> { tokens.add(WclTok.Percent); pos++ }
                '/' -> {
                    if (peek(1) == '/') skipLine()
                    else if (peek(1) == '*') skipBlock()
                    else { tokens.add(WclTok.Slash); pos++ }
                }
                '<' -> {
                    if (src.substring(pos).startsWith("<random(")) {
                        tokens.add(WclTok.Ident("random")); pos += 8
                    } else if (peek(1) == '=') { tokens.add(WclTok.Le); pos += 2 }
                    else { tokens.add(WclTok.Lt); pos++ }
                }
                '>' -> {
                    if (peek(1) == '=') { tokens.add(WclTok.Ge); pos += 2 }
                    else { tokens.add(WclTok.Gt); pos++ }
                }
                '=' -> {
                    if (peek(1) == '=') { tokens.add(WclTok.EqEq); pos += 2 }
                    else { tokens.add(WclTok.Eq); pos++ }
                }
                '!' -> {
                    if (peek(1) == '=') { tokens.add(WclTok.Ne); pos += 2 }
                    else pos++
                }
                '-' -> { tokens.add(WclTok.Minus); pos++ }
                '#' -> skipLine()
                '"' -> tokens.add(readString())
                else -> {
                    if (c.isDigit()) { tokens.add(readNum()) }
                    else if (c.isLetter() || c == '_') { tokens.add(readIdent()) }
                    else { tokens.add(readMcLine()) }
                }
            }
        }
        tokens.add(WclTok.Eof)
        return tokens
    }

    private fun peek(n: Int) = if (pos + n < len) src[pos + n] else '\u0000'
    private fun skipLine() { while (pos < len && src[pos] != '\n') pos++ }
    private fun skipBlock() {
        pos += 2
        while (pos < len - 1) {
            if (src[pos] == '*' && src[pos + 1] == '/') { pos += 2; break }
            pos++
        }
    }

    private fun readNum(): WclTok {
        val s = pos
        while (pos < len && (src[pos].isDigit() || src[pos] == '.')) pos++
        return WclTok.Num(src.substring(s, pos).toLongOrNull() ?: 0)
    }

    private fun readIdent(): WclTok {
        val s = pos
        while (pos < len && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
        val n = src.substring(s, pos)
        return if (n in KW) WclTok.Keyword(n) else WclTok.Ident(n)
    }

    private fun readString(): WclTok {
        pos++
        val s = pos
        while (pos < len && src[pos] != '"') pos++
        val v = src.substring(s, pos)
        pos++
        return WclTok.StrTok(v)
    }

    private fun readMcLine(): WclTok {
        val s = pos
        while (pos < len && src[pos] != '\n' && src[pos] != '\r') pos++
        return WclTok.McCmd(src.substring(s, pos).trim())
    }
}
