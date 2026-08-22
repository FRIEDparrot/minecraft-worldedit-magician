package com.magician.worldedit.client.command

/**
 * Deprecated compatibility shell.
 *
 * Vanilla Java Edition has no `/entity query` command. Entity inspection is
 * represented in the WEMC manifest by the wiki-backed `/data get entity` form.
 * World-changing entity commands are now sent only through
 * [MinecraftCommandExecutor] after category permission validation.
 */
object EntityCommandHandler {
    fun destroyEntities(rangeStr: String): Int = 0

    fun undo(): Boolean = false

    fun redo(): Boolean = false
}
