package com.magician.worldedit.client.mixin

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ChunkSelectionWorldRenderMixinContractTest {
    @Test
    fun `selection gizmos are emitted after the collector is installed`() {
        val source = Files.readString(
            Path.of("src/client/java/com/magician/worldedit/client/mixin/ChunkSelectionWorldRenderMixin.java"),
        )

        assertContains(source, "@Inject(method = \"collectPerFrameGizmos\", at = @At(\"RETURN\"))")
        assertFalse(source.contains("@At(\"HEAD\")"), "HEAD runs before Gizmos.withCollector installs the per-frame collector")
    }
}
