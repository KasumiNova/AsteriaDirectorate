package cn.kasuminova.astd.renderer.projectile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ASTDProjectileVfxPresetTest {
    @Test
    fun `preset contains runtime vfx policies and supported trail layers`() {
        val preset = ASTDProjectileVfxPreset(
            id = "astd_test",
            layers = listOf(
                ASTDProjectileVfxLayer.Trail(id = "trail", width = 8f, length = ASTDProjectileVfxLengthPolicy.Fixed(160f), color = ASTDColor(0.4f, 0.8f, 1f, 0.9f)),
                ASTDProjectileVfxLayer.Glow(id = "glow", width = 18f, length = ASTDProjectileVfxLengthPolicy.VelocityScaled(0.12f), color = ASTDColor(0.1f, 0.5f, 1f, 0.5f)),
                ASTDProjectileVfxLayer.Ribbon(id = "ribbon", width = 4f, length = ASTDProjectileVfxLengthPolicy.Fixed(120f), color = ASTDColor(1f, 1f, 1f, 0.7f), frequency = 8f, amplitude = 6f),
                ASTDProjectileVfxLayer.HeadTrail(id = "head", width = 10f, length = ASTDProjectileVfxLengthPolicy.LifetimeWindow(0.08f), color = ASTDColor(0.8f, 1f, 1f, 1f)),
            ),
            samplingPolicy = ASTDProjectileVfxSamplingPolicy(historyFps = 60f, maxHistoryNodes = 96, minDistancePerNode = 2f, smoothingPasses = 1, distanceWindow = 260f),
            fadePolicy = ASTDProjectileVfxFadePolicy(fadeInSeconds = 0f, fadeOutSeconds = 0.16f, hitFadeOutSeconds = 0.10f, expireFadeOutSeconds = 0.20f),
        )

        assertEquals("astd_test", preset.id)
        assertEquals(4, preset.layers.size)
        assertTrue(preset.layers[0] is ASTDProjectileVfxLayer.Trail)
        assertTrue(preset.layers[1] is ASTDProjectileVfxLayer.Glow)
        assertTrue(preset.layers[2] is ASTDProjectileVfxLayer.Ribbon)
        assertTrue(preset.layers[3] is ASTDProjectileVfxLayer.HeadTrail)
    }

    @Test
    fun `runtime preset type names exclude preview only concepts`() {
        val runtimeNames = listOf(
            ASTDProjectileVfxPreset::class.simpleName.orEmpty(),
            ASTDProjectileVfxSamplingPolicy::class.simpleName.orEmpty(),
            ASTDProjectileVfxFadePolicy::class.simpleName.orEmpty(),
            ASTDProjectileVfxLengthPolicy::class.simpleName.orEmpty(),
        ).joinToString("\n")

        listOf("Timeline", "Simulation", "PreviewCamera", "ProjectileVelocity", "Curve", "Loop").forEach { forbidden ->
            assertFalse(runtimeNames.contains(forbidden), "preview-only concept leaked into runtime type names: $forbidden")
        }
    }
}
