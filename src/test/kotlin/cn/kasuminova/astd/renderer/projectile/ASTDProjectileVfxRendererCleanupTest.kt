package cn.kasuminova.astd.renderer.projectile

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertFalse

class ASTDProjectileVfxRendererCleanupTest {
    @Test
    fun `runtime renderer does not reintroduce screenshot tuning scale constants`() {
        runtimeSources().forEach { sourcePath ->
            val text = Files.readString(sourcePath)
            forbiddenScaleNeedles.forEach { needle ->
                assertFalse(text.contains(needle), "${sourcePath.fileName} contains forbidden VFX tuning token: $needle")
            }
        }
    }

    @Test
    fun `runtime renderer has no parallel shader geometry path`() {
        runtimeSources().forEach { sourcePath ->
            val text = Files.readString(sourcePath)
            forbiddenShaderNeedles.forEach { needle ->
                assertFalse(text.contains(needle), "${sourcePath.fileName} contains forbidden shader dual-path token: $needle")
            }
        }
    }

    private fun runtimeSources(): List<Path> {
        val root = Path.of("src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime")
        return Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && it.toString().endsWith(".kt") }.toList()
        }
    }

    private companion object {
        private val forbiddenScaleNeedles = listOf(
            "PREVIEW_",
            "BODY_X_SCALE",
            "VERTICAL_SCALE",
            "bodyXScale",
            "bodyYScale",
            "magicScale",
            "looksRight",
            "tune",
            "xScale",
            "yScale",
        )

        private val forbiddenShaderNeedles = listOf(
            "ASTDProjectileVfxShaderRenderer",
            "shaderQuad",
            "GL20",
            "GLContext.getCapabilities().OpenGL20",
        )
    }
}
