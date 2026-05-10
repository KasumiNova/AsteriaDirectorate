package cn.kasuminova.astd.renderer.projectile

object ASTDProjectileVfxPresetCatalog {
    private val presets: Map<String, ASTDProjectileVfxPreset> = listOf(
        preset("aod7_shot", cyan(), 7f, 150f),
        preset("spc3_shot", violet(), 6f, 135f),
        preset("drv9_slug", amber(), 10f, 190f),
        preset("drv11", amber(), 12f, 230f, glowScale = 2.6f),
        preset("drv_omega_slug", omega(), 14f, 260f, glowScale = 3.0f),
        preset("slt3_pulse", blue(), 8f, 170f, ribbon = true),
        preset("slt4_burst", blue(), 9f, 190f, ribbon = true),
        preset("slt_omega_stream", omega(), 8f, 240f, ribbon = true),
        preset("vpd6_pulse", teal(), 8f, 180f),
        preset("vpd_omega_arc", omega(), 9f, 220f, ribbon = true),
        preset("rct6", rose(), 16f, 280f, head = true),
        preset("singularity_event_horizon_missile", singularity(), 18f, 310f, glowScale = 3.4f, head = true),
        preset("tsm_omega_missile", omega(), 18f, 330f, glowScale = 3.3f, head = true),
        preset("gsp12_rift", singularity(), 18f, 280f, glowScale = 3.1f, ribbon = true),
        preset("jmb2_beam", teal(), 12f, 260f, glowScale = 2.5f),
        preset("jmb9_beam", blue(), 13f, 280f, glowScale = 2.6f),
        preset("jmb_omega_beam", omega(), 15f, 330f, glowScale = 3.0f),
        preset("singularity_nova_missile", singularity(), 20f, 340f, glowScale = 3.6f, head = true),
        preset("fdp4_charge", amber(), 14f, 250f, glowScale = 2.6f, head = true),
        preset("ftb_omega_beam", omega(), 16f, 350f, glowScale = 3.2f),
        preset("mnl2_mine", teal(), 13f, 210f, glowScale = 2.4f, head = true),
        preset("mnl3_mine", blue(), 14f, 230f, glowScale = 2.5f, head = true),
        preset("mnl_omega_grid", omega(), 15f, 260f, glowScale = 3.0f, ribbon = true, head = true),
    ).associateBy { it.id }

    fun preset(id: String): ASTDProjectileVfxPreset? = presets[id]

    fun presetIds(): Set<String> = presets.keys

    private fun preset(
        id: String,
        color: ASTDColor,
        width: Float,
        length: Float,
        glowScale: Float = 2.2f,
        ribbon: Boolean = false,
        head: Boolean = false,
    ): ASTDProjectileVfxPreset {
        val layers = ArrayList<ASTDProjectileVfxLayer>()
        layers += ASTDProjectileVfxLayer.Trail(
            id = "${id}_trail",
            width = width,
            length = ASTDProjectileVfxLengthPolicy.Fixed(length),
            color = color,
        )
        layers += ASTDProjectileVfxLayer.Glow(
            id = "${id}_glow",
            width = width * glowScale,
            length = ASTDProjectileVfxLengthPolicy.Fixed(length * 0.82f),
            color = color.copy(alpha = (color.alpha * 0.55f).coerceIn(0.2f, 0.8f)),
        )
        if (ribbon) {
            layers += ASTDProjectileVfxLayer.Ribbon(
                id = "${id}_ribbon",
                width = width * 0.45f,
                length = ASTDProjectileVfxLengthPolicy.Fixed(length * 0.72f),
                color = color.copy(alpha = (color.alpha * 0.68f).coerceIn(0.25f, 0.85f)),
                frequency = 5.5f,
                amplitude = width * 0.42f,
            )
        }
        if (head) {
            layers += ASTDProjectileVfxLayer.HeadTrail(
                id = "${id}_head",
                width = width * 1.2f,
                length = ASTDProjectileVfxLengthPolicy.LifetimeWindow(0.08f),
                color = color.copy(alpha = 1f),
            )
        }
        return ASTDProjectileVfxPreset(
            id = id,
            layers = layers,
            samplingPolicy = ASTDProjectileVfxSamplingPolicy(
                historyFps = 60f,
                maxHistoryNodes = 96,
                minDistancePerNode = 2f,
                smoothingPasses = 1,
                distanceWindow = length,
            ),
            fadePolicy = ASTDProjectileVfxFadePolicy(
                fadeInSeconds = 0f,
                fadeOutSeconds = 0.18f,
                hitFadeOutSeconds = 0.1f,
                expireFadeOutSeconds = 0.22f,
            ),
        )
    }

    private fun cyan() = ASTDColor(0.25f, 0.82f, 1f, 0.92f)
    private fun violet() = ASTDColor(0.66f, 0.42f, 1f, 0.9f)
    private fun amber() = ASTDColor(1f, 0.62f, 0.18f, 0.95f)
    private fun omega() = ASTDColor(0.72f, 0.35f, 1f, 0.96f)
    private fun blue() = ASTDColor(0.2f, 0.55f, 1f, 0.92f)
    private fun teal() = ASTDColor(0.22f, 1f, 0.78f, 0.9f)
    private fun rose() = ASTDColor(1f, 0.34f, 0.42f, 0.94f)
    private fun singularity() = ASTDColor(0.78f, 0.92f, 1f, 0.96f)
}
