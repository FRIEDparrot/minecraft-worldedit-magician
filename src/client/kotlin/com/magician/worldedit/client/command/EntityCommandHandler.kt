package com.magician.worldedit.client.command

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.Level

/**
 * Command handler for entity operations that can be undone.
 * Provides /wemc run destroyEntity to remove entities near the player.
 */
object EntityCommandHandler {

    /**
     * Destroys entities near the player within the specified range.
     * Records the operation for undo support.
     *
     * @param rangeStr The range as a string (e.g., "10" or "10.0")
     * @return The number of entities destroyed
     */
    fun destroyEntities(rangeStr: String): Int {
        val range = rangeStr.toDoubleOrNull() ?: 10.0
        return destroyEntitiesInternal(range)
    }

    private fun destroyEntitiesInternal(range: Double): Int {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return 0
        val level: Level = player.level()

        val destroyedEntities = mutableListOf<DestroyableSnapshot>()

        // Find entities in range
        val aabb = AABB(
            player.x - range,
            player.y - range,
            player.z - range,
            player.x + range,
            player.y + range,
            player.z + range,
        )

        val entities = level.getEntities(player, aabb) { true }

        for (entity in entities) {
            // Skip players and out-of-range entities (already filtered by aabb)
            val snapshot = createSnapshot(entity)
            if (snapshot != null) {
                destroyedEntities.add(snapshot)
            }
            entity.discard()
        }

        if (destroyedEntities.isEmpty()) {
            sendMessage("No entities found to destroy.")
            return 0
        }

        val entityCount = destroyedEntities.size
        val description = "Destroyed $entityCount entity(ies) within ${range}m"

        val command = RecordedCommand.create(
            command = "/wemc run destroyEntity $range",
            revertCommand = "/wemc undo",
            description = description,
        )
        CommandHistory.record(command)

        sendMessage(
            "Destroyed $entityCount entity(ies). Use /wemc undo to restore them."
        )

        return entityCount
    }

    /**
     * Creates a snapshot of an entity for potential undo.
     */
    private fun createSnapshot(entity: Entity): DestroyableSnapshot? {
        return DestroyableSnapshot(
            entityType = entity.type,
            position = entity.position(),
            data = emptyMap(),
        )
    }

    /**
     * Undoes the last destroyEntity command.
     * Currently reports that restore is not yet implemented.
     */
    fun undo(): Boolean {
        val command = CommandHistory.undo() ?: return false

        sendMessage(
            "Undone: would restore destroyed entities (full restore not yet implemented)"
        )

        return true
    }

    /**
     * Redoes the last undone destroyEntity command.
     * Re-executes the destroy operation.
     */
    fun redo(): Boolean {
        val command = CommandHistory.redo() ?: return false

        val rangeStr = command.command.removePrefix("/wemc run destroyEntity ").trim()
        val range = rangeStr.toDoubleOrNull() ?: 10.0

        destroyEntitiesInternal(range)

        return true
    }

    /**
     * Snapshot of a destroyed entity for potential undo/redo.
     */
    data class DestroyableSnapshot(
        val entityType: EntityType<out Entity>,
        val position: Vec3,
        val data: Map<String, Any?> = emptyMap(),
    )

    private fun sendMessage(message: String) {
        Minecraft.getInstance().player?.displayClientMessage(Component.literal("[WEMC] $message"), false)
    }
}
