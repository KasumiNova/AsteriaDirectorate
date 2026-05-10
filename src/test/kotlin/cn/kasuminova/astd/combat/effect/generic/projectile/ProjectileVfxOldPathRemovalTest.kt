package cn.kasuminova.astd.combat.effect.generic.projectile

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class ProjectileVfxOldPathRemovalTest {
    @Test
    fun `active projectile entry sources do not call old renderer main path`() {
        val forbiddenCalls = listOf(
            "ProjectileTracerManager.track(",
            "CodeProjectileRenderer.onSpawn(",
            "ProjectileVfxPresets.",
            "CompositeProjectileVisual(",
        )

        activeEntrySources().forEach { sourcePath ->
            val text = Files.readString(sourcePath)
            forbiddenCalls.forEach { forbiddenCall ->
                assertFalse(
                    text.contains(forbiddenCall),
                    "${sourcePath.fileName} still calls old projectile VFX main path: $forbiddenCall",
                )
            }
        }
    }

    @Test
    fun `combat bootstrap uses runtime plugin as projectile VFX main path`() {
        val text = Files.readString(Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/CombatVfxBootstrap.kt"))

        assertFalse(text.contains("ProjectileSpawnVfxDispatcher"), "combat bootstrap still installs scanner dispatcher")
    }

    private fun activeEntrySources(): List<Path> = listOf(
        Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile/ProjectileVfxRegistry.kt"),
        Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile/ProjectileSpecOnFireDispatcher.kt"),
        Path.of("src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile/ProjectileSpawnVfxDispatcher.kt"),
    )
}