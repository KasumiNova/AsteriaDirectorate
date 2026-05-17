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
            headLayers = listOf(
                ASTDProjectileVfxHeadLayerSpec(
                    id = "astd_test_head",
                    length = 138f,
                    width = 24f,
                    shoulderRatio = 0.5f,
                    rearRatio = 0.95f,
                    shellColorStart = ASTDColor(0.2f, 0.3f, 0.4f, 0.2f),
                    shellColorMid = ASTDColor(0.6f, 0.8f, 1f, 0.7f),
                    shellColorEnd = ASTDColor(1f, 1f, 1f, 1f),
                    blur = 0.35f,
                    alphaScale = 1f,
                ),
            ),
            glowLayers = listOf(ASTDProjectileVfxGlowLayerSpec("astd_test_glow", widthScale = 5.4f, alphaScale = 0.18f, blur = 34f, yOffset = -0.36f, colorMixTail = 0.52f, colorMixHead = 0.44f)),
            mistLayers = listOf(ASTDProjectileVfxMistLayerSpec("astd_test_mist", blobCount = 52, lengthScale = 1f, widthScale = 1f, rxRange = ASTDFloatRangeSpec(2.4f, 7.2f), ryRange = ASTDFloatRangeSpec(0.45f, 1.8f), alphaRange = ASTDFloatRangeSpec(0.016f, 0.075f), noiseScale = 5.2f, driftSpeed = 0.32f, colorStart = ASTDColor(0f, 0f, 1f, 0.1f), colorEnd = ASTDColor(1f, 1f, 1f, 1f))),
            sideWispLayers = listOf(ASTDProjectileVfxSideWispLayerSpec("astd_test_side", offsets = listOf(-2.1f, -1.36f, 1.28f, 2f), widthScale = 0.2f, alphaScale = 0.24f, blur = 10f, lengthStartRatio = 0.64f, lengthEndRatio = 0.28f, color = ASTDColor(0.45f, 0.7f, 1f, 0.72f))),
            lifecycle = ASTDProjectileVfxLifecycleSpec(durationSeconds = 1.25f),
        )

        assertEquals("astd_test", preset.id)
        assertEquals(4, preset.layers.size)
        assertTrue(preset.layers[0] is ASTDProjectileVfxLayer.Trail)
        assertTrue(preset.layers[1] is ASTDProjectileVfxLayer.Glow)
        assertTrue(preset.layers[2] is ASTDProjectileVfxLayer.Ribbon)
        assertTrue(preset.layers[3] is ASTDProjectileVfxLayer.HeadTrail)
        assertEquals(1, preset.headLayers.size)
        assertEquals(1, preset.glowLayers.size)
        assertEquals(1, preset.mistLayers.size)
        assertEquals(1, preset.sideWispLayers.size)
        assertEquals(1.25f, preset.lifecycle.durationSeconds)
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
