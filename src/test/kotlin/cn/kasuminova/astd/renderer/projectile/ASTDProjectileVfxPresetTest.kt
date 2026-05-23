package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.component.ASTDProjectileVfxComponentSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ASTDProjectileVfxPresetTest {
    @Test
    fun `preset contains runtime vfx policies and supported trail layers`() {
        val trail = ASTDProjectileVfxComponentSpec.Trail(
            id = "trail",
            layer = ASTDTrailLayerSpec(width = 8f, length = 160f, color = ASTDColor(0.4f, 0.8f, 1f, 0.9f)),
        )
        val preset = ASTDProjectileVfxPreset(
            id = "astd_test",
            components = listOf(
                trail,
                ASTDProjectileVfxComponentSpec.Glow(
                    id = "glow",
                    trailId = "trail",
                    layers = listOf(ASTDProjectileVfxGlowLayerSpec("astd_test_glow", widthScale = 5.4f, alphaScale = 0.18f, blur = 34f, yOffset = -0.36f, colorMixTail = 0.52f, colorMixHead = 0.44f)),
                ),
                ASTDProjectileVfxComponentSpec.Body("body", trailId = "trail"),
                ASTDProjectileVfxComponentSpec.Ribbon(
                    id = "ribbon",
                    trailId = "trail",
                    ribbons = listOf(ASTDTrailRibbonDecorationSpec(frequency = 8f, amplitude = 6f)),
                ),
                ASTDProjectileVfxComponentSpec.Head(
                    id = "head",
                    trailId = "trail",
                    layers = listOf(
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
                ),
                ASTDProjectileVfxComponentSpec.Mist(
                    id = "mist",
                    trailId = "trail",
                    layers = listOf(ASTDProjectileVfxMistLayerSpec("astd_test_mist", blobCount = 52, lengthScale = 1f, widthScale = 1f, rxRange = ASTDFloatRangeSpec(2.4f, 7.2f), ryRange = ASTDFloatRangeSpec(0.45f, 1.8f), alphaRange = ASTDFloatRangeSpec(0.016f, 0.075f), noiseScale = 5.2f, driftSpeed = 0.32f, colorStart = ASTDColor(0f, 0f, 1f, 0.1f), colorEnd = ASTDColor(1f, 1f, 1f, 1f))),
                ),
                ASTDProjectileVfxComponentSpec.SideWisp(
                    id = "side",
                    trailId = "trail",
                    layers = listOf(ASTDProjectileVfxSideWispLayerSpec("astd_test_side", offsets = listOf(-2.1f, -1.36f, 1.28f, 2f), widthScale = 0.2f, alphaScale = 0.24f, blur = 10f, lengthStartRatio = 0.64f, lengthEndRatio = 0.28f, color = ASTDColor(0.45f, 0.7f, 1f, 0.72f))),
                ),
            ),
            samplingPolicy = ASTDProjectileVfxSamplingPolicy(historyFps = 60f, maxHistoryNodes = 96, minDistancePerNode = 2f, smoothingPasses = 1, distanceWindow = 260f),
            fadePolicy = ASTDProjectileVfxFadePolicy(fadeInSeconds = 0f, fadeOutSeconds = 0.16f, hitFadeOutSeconds = 0.10f, expireFadeOutSeconds = 0.20f),
            lifecycle = ASTDProjectileVfxLifecycleSpec(durationSeconds = 1.25f),
        )

        assertEquals("astd_test", preset.id)
        assertEquals(listOf("trail", "glow", "body", "ribbon", "head", "mist", "sideWisp"), preset.components.map { it.kind })
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
