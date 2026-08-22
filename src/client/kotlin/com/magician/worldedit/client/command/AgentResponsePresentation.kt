package com.magician.worldedit.client.command

/** Removes command-only transport blocks from the text shown in the in-game chat. */
object AgentResponsePresentation {
    private val commandBlock = Regex("""(?s)```wemc-commands\s*\n.*?```""", RegexOption.IGNORE_CASE)
    private val planBlock = Regex("""(?s)```wemc-plan\s*\n.*?```""", RegexOption.IGNORE_CASE)
    private val excessiveBlankLines = Regex("""\n[ \t]*\n(?:[ \t]*\n)+""")

    fun displayText(response: String): String = response
        .replace(commandBlock, "")
        .replace(planBlock, "")
        .replace(excessiveBlankLines, "\n\n")
        .trim()
}
