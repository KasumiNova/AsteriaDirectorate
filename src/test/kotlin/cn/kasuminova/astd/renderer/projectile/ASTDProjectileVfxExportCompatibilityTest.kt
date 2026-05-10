package cn.kasuminova.astd.renderer.projectile

import kotlin.test.Test
import kotlin.test.assertTrue

class ASTDProjectileVfxExportCompatibilityTest {
    @Test
    fun `runtime exports class names used by frontend kotlin export`() {
        val names = listOf(
            ASTDProjectileVfxPreset::class.simpleName,
            ASTDTrailEntitySpec::class.simpleName,
            ASTDTrailLayerSpec::class.simpleName,
            ASTDTrailRibbonDecorationSpec::class.simpleName,
            ASTDProjectileVfxFadePolicy::class.simpleName,
        ).filterNotNull()

        listOf(
            "ASTDProjectileVfxPreset",
            "ASTDTrailEntitySpec",
            "ASTDTrailLayerSpec",
            "ASTDTrailRibbonDecorationSpec",
            "ASTDProjectileVfxFadePolicy",
        ).forEach { expected ->
            assertTrue(names.contains(expected), "missing exported runtime type: $expected")
        }
    }

    @Test
    fun `runtime model fields exclude preview only fields`() {
        val propertyNames = ASTDProjectileVfxPreset::class.java.declaredFields.map { it.name }.toSet()
        listOf("timeline", "simulation", "previewCamera", "projectileVelocity", "curve", "loop").forEach { forbidden ->
            assertTrue(forbidden !in propertyNames, "preview-only field leaked into runtime preset: $forbidden")
        }
    }
}
