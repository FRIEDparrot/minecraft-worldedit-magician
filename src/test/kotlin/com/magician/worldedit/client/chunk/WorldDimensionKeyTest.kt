package com.magician.worldedit.client.chunk

import net.minecraft.resources.Identifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WorldDimensionKeyTest {
    @Test
    fun `canonical dimension key uses the resource location instead of diagnostic text`() {
        val dimensionKey = WorldDimensionKey.from(Identifier.fromNamespaceAndPath("minecraft", "overworld"))

        assertEquals("minecraft:overworld", dimensionKey)
        assertFalse(dimensionKey.startsWith("ResourceKey["))
    }
}
