package com.magician.worldedit.client.chunk

import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level

/** Produces the canonical resource-location identifier for a Minecraft dimension. */
object WorldDimensionKey {
    /** Returns the stable namespace:path text for a dimension identifier. */
    fun from(identifier: Identifier): String = identifier.toString()

    /** Returns the stable namespace:path identifier for a dimension registry key. */
    fun from(dimension: ResourceKey<Level>): String = from(dimension.identifier())

    /** Returns the stable namespace:path location for [level]'s current dimension. */
    fun from(level: Level): String = from(level.dimension())
}
