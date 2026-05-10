package cn.kasuminova.astd.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class UiAssetAvailabilityTest {

    private val fallbackDirectSpritePaths = listOf(
        "graphics/fx/astd_generated_ring.png",
        "graphics/fx/smd_generated_ring.png",
        "graphics/fx/beamfringeb.png",
    )

    @Test
    fun `particle background has at least one existing fallback sprite asset`() {
        val existing = fallbackDirectSpritePaths.any { relativePath ->
            Files.exists(Path.of("contents", relativePath))
        }

        assertTrue(existing, "ASTDParticleBackground 缺少可用的 fallback 粒子贴图资源")
    }

    @Test
    fun `particle background source no longer references missing halo textured sprite`() {
        val sourcePath = Path.of(
            "src",
            "main",
            "kotlin",
            "cn",
            "kasuminova",
            "astd",
            "ui",
            "effect",
            "ASTDParticleBackground.kt",
        )
        assertTrue(Files.exists(sourcePath), "缺少 ASTDParticleBackground 源文件")

        val text = Files.readString(sourcePath)
        assertTrue(!text.contains("halo_textured"), "ASTDParticleBackground 仍在引用缺失的 halo_textured 贴图")
    }
}