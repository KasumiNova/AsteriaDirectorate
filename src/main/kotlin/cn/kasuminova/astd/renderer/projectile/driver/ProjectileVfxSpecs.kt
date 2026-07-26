package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.impl.render.ASTDColor
import kotlin.math.roundToInt

/**
 * 已迁移到 RenderEntity 管线的弹体特效手写 DSL 构建函数库。
 * 每个 projectileSpecId 对应一个无状态构建函数，每次生成弹体都重新调用（不缓存），以支持调试期字面量热交换。
 *
 * 绝大多数弹体（[simpleProjectileVfx]）只需 6 个高层旋钮（色/宽/长/辉光倍率/是否飘带/是否弹头），
 * 逐字段复现旧 `preset()` 工厂公式；唯 hero（aod7）下探为逐层显式书写。
 */
object ProjectileVfxSpecs {

    /**
     * projectileSpecId → 构建函数。加入一个即接入本管线。
     *
     * 全部 24 个弹体 spec 均已接入。`astd_stellar_jet_bolt` 由 `StellarJetEmitterEveryFrameEffect` 每帧 spawn
     * （无 onFireEffect、绕过 dispatcher），发射时直接调 `ProjectileVfxDriverPlugin.track` 接本管线。
     */
    private val builders: Map<String, () -> ProjectileVfx> = mapOf(
        "astd_aod7_shot" to ::aod7Shot,
        "astd_spc3_shot" to { simpleProjectileVfx("astd_spc3_shot", violet(), width = 6f, length = 135f) },
        "astd_stellar_jet_bolt" to { simpleProjectileVfx("astd_stellar_jet_bolt", stellar(), width = 10f, length = 240f, glowScale = 2.4f) },
        "astd_drv9_slug" to { simpleProjectileVfx("astd_drv9_slug", amber(), width = 10f, length = 190f) },
        "astd_drv11_slug" to { simpleProjectileVfx("astd_drv11_slug", amber(), width = 12f, length = 230f, glowScale = 2.6f) },
        "astd_drv_omega_slug" to { simpleProjectileVfx("astd_drv_omega_slug", omega(), width = 14f, length = 260f, glowScale = 3.0f) },
        "astd_slt3_pulse" to { simpleProjectileVfx("astd_slt3_pulse", blue(), width = 8f, length = 170f, ribbon = true) },
        "astd_slt4_burst" to { simpleProjectileVfx("astd_slt4_burst", blue(), width = 9f, length = 190f, ribbon = true) },
        "astd_slt_omega_stream" to { simpleProjectileVfx("astd_slt_omega_stream", omega(), width = 8f, length = 240f, ribbon = true) },
        "astd_vpd6_pulse" to { simpleProjectileVfx("astd_vpd6_pulse", teal(), width = 8f, length = 180f) },
        "astd_vpd_omega_arc" to { simpleProjectileVfx("astd_vpd_omega_arc", omega(), width = 9f, length = 220f, ribbon = true) },
        "astd_rct6_torp" to { simpleProjectileVfx("astd_rct6_torp", rose(), width = 16f, length = 280f, head = true) },
        "astd_tsm2_missile" to { simpleProjectileVfx("astd_tsm2_missile", singularity(), width = 18f, length = 310f, glowScale = 3.4f, head = true) },
        "astd_tsm_omega_missile" to { simpleProjectileVfx("astd_tsm_omega_missile", omega(), width = 18f, length = 330f, glowScale = 3.3f, head = true) },
        "astd_gsp12_rift" to { simpleProjectileVfx("astd_gsp12_rift", singularity(), width = 18f, length = 280f, glowScale = 3.1f, ribbon = true) },
        "astd_jmb2_beam" to { simpleProjectileVfx("astd_jmb2_beam", teal(), width = 12f, length = 260f, glowScale = 2.5f) },
        "astd_jmb9_beam" to { simpleProjectileVfx("astd_jmb9_beam", blue(), width = 13f, length = 280f, glowScale = 2.6f) },
        "astd_jmb_omega_beam" to { simpleProjectileVfx("astd_jmb_omega_beam", omega(), width = 15f, length = 330f, glowScale = 3.0f) },
        "astd_sgl8_swarm" to { simpleProjectileVfx("astd_sgl8_swarm", singularity(), width = 20f, length = 340f, glowScale = 3.6f, head = true) },
        "astd_fdp4_charge" to { simpleProjectileVfx("astd_fdp4_charge", amber(), width = 14f, length = 250f, glowScale = 2.6f, head = true) },
        "astd_ftb_omega_beam" to { simpleProjectileVfx("astd_ftb_omega_beam", omega(), width = 16f, length = 350f, glowScale = 3.2f) },
        "astd_mnl2_mine" to { simpleProjectileVfx("astd_mnl2_mine", teal(), width = 13f, length = 210f, glowScale = 2.4f, head = true) },
        "astd_mnl3_mine" to { simpleProjectileVfx("astd_mnl3_mine", blue(), width = 14f, length = 230f, glowScale = 2.5f, head = true) },
        "astd_mnl_omega_grid" to { simpleProjectileVfx("astd_mnl_omega_grid", omega(), width = 15f, length = 260f, glowScale = 3.0f, ribbon = true, head = true) },
    )

    fun has(projectileSpecId: String): Boolean = projectileSpecId in builders

    /** 现构建一棵新树 + 策略；未迁移的 spec 返回 null（调用方回落旧管线）。 */
    fun build(projectileSpecId: String): ProjectileVfx? = builders[projectileSpecId]?.invoke()

    /**
     * 通用弹体特效：6 高层旋钮 → trail + glow + body（+ 可选 ribbon / head）。逐字段复现旧 `preset()` 工厂：
     * 起止色/emissive 由主色按固定 alpha 系数派生，glow 单层、body 承接拖尾，sampling/fade/lifecycle 用与旧工厂一致的默认。
     */
    private fun simpleProjectileVfx(
        id: String,
        color: ASTDColor,
        width: Float,
        length: Float,
        glowScale: Float = 2.2f,
        ribbon: Boolean = false,
        head: Boolean = false,
    ): ProjectileVfx = projectileVfx(id) {
        trail {
            width(width); widths(width, (width * 0.16f).coerceAtLeast(1f)); length(length)
            color(color.hex()); tail(color.a((color.alpha * 0.12f).coerceIn(0.04f, 0.2f)).hex())
            emissive(color.a(1f).hex(), color.a((color.alpha * 0.25f).coerceIn(0.08f, 0.3f)).hex())
            texture(96f, 0.9f); fill(0.84f, 0.03f, 0.02f, 0.12f); pausedMotion()
        }
        // lifecycle 全默认（duration 1.25 / dissolveAt 0.6 / headScale 1.5 / layoutRef 1280）——与旧 preset 的 ASTDProjectileVfxLifecycleSpec() 默认一致。
        sampling { fps(60f); maxNodes(96); minStep(2f); window(length) }
        fade { out(0.18f); hit(0.1f); expire(0.22f) }

        glow {
            layer(glowScale, (color.alpha * 0.35f).coerceIn(0.18f, 0.55f), width * 1.5f, mixTail = 0.52f, mixHead = 1f)
        }
        body()
        if (ribbon) {
            ribbon {
                frequency(5.5f); amplitude(width * 0.42f); thickness(0.45f)
                alpha((color.alpha * 0.68f).coerceIn(0.25f, 0.85f))
                colors(
                    start = color.hex(),
                    end = color.a((color.alpha * 0.18f).coerceIn(0.06f, 0.24f)).hex(),
                    main = color.a((color.alpha * 0.68f).coerceIn(0.25f, 0.85f)).hex(),
                )
            }
        }
        if (head) {
            head {
                length(length * 0.33f); width(width * 1.2f); shoulder(0.5f); rear(0.95f); blur(0.35f)
                shell(color.a(0.08f).hex(), color.a(0.46f).hex(), color.a(1f).hex())
            }
        }
    }

    /**
     * aod7 hero：七层（mist/glow/body/sideWisp/head/ribbon + body 承接拖尾）。
     * 数值逐字段对齐旧 preset，唯尺寸按目检反馈调整：
     * body/glow/mist/sideWisp 的横截宽都吃 widthBase=max(startWidth*0.075,3.5)，startWidth 40→96 使 widthBase 3.5→7.2（约 2×），
     * 令弹体核心与辉光随之放大；startWidth≤176 时 viewportTailCap 仍由 layoutRef 主导（=849），故可视长度不受影响。
     * 弹头 headSize = width×visible×headScale×headTrailScale(widthBase)，故 startWidth 翻倍时弹头也被动放大；
     * 目检反馈弹头偏大，headScale 1.9→1.14（≈上一版 60%）单独缩弹头，不动 body/glow。head 宽保持 34。
     */
    private fun aod7Shot(): ProjectileVfx = projectileVfx("astd_aod7_shot") {
        trail {
            width(96f); widths(96f, 8f); length(420f)
            color(0x478FEBEB); tail(0x0A24380F)
            emissive(0xF0F8FFFF, 0x0A347529)
            texture(96f, 0.9f)
            fill(0.84f, 0.03f, 0.02f, 0.12f)
            strip(); blend("additive"); flickerSync(17); pausedMotion()
        }
        lifecycle { duration(1.25f); dissolveAt(0.6f); headScale(1.14f); layoutRef(1846f) }
        sampling { fps(60f); maxNodes(96); minStep(2f); window(420f) }
        fade { out(0.15f) }

        mist {
            blobs(52); rx(2.4f, 7.2f); ry(0.45f, 1.8f)
            alpha(0.016f, 0.075f); noise(5.2f); drift(0.32f)
            colors(0x380A2E0F, 0xFFF2F9FF)
        }
        glow {
            layer(5.4f, 0.18f, 34f, y = -0.36f, mixTail = 0.52f, mixHead = 0.44f)
            layer(3.2f, 0.30f, 18f, y = 0.22f, mixTail = 0.52f, mixHead = 0.44f)
            layer(1.4f, 0.58f, 7f, y = -0.08f, mixTail = 0.22f, mixHead = 1f)
            layer(0.62f, 0.82f, 4f, y = 0f, mixTail = 0.48f, mixHead = 1f)
        }
        body()
        sideWisp {
            offsets(-2.1f, -1.36f, 1.28f, 2f)
            width(0.2f); alpha(0.24f); blur(10f)
            length(0.64f, 0.28f); color(0x73B3FFB8)
        }
        head {
            length(138f); width(34f); shoulder(0.5f); rear(0.95f)
            blur(0.35f); shell(0x380A2E14, 0xB8F0FF75, 0xFFFFFFFA)
        }
        ribbon {
            frequency(1.1f); amplitude(1.35f); wave("noise", scale = 4f)
            thickness(0.1f); alpha(0.28f); blur(9f)
            colors(0xE3EBEEEB, 0x0A1C380F, 0xFFFFFFEB)
        }
    }

    // 调色板：沿用旧管线数值（视觉已目检回归，不宜再动）。
    private fun violet() = ASTDColor(0.66f, 0.42f, 1f, 0.9f)
    private fun amber() = ASTDColor(1f, 0.62f, 0.18f, 0.95f)
    private fun omega() = ASTDColor(0.72f, 0.35f, 1f, 0.96f)
    private fun blue() = ASTDColor(0.2f, 0.55f, 1f, 0.92f)
    private fun teal() = ASTDColor(0.22f, 1f, 0.78f, 0.9f)
    private fun rose() = ASTDColor(1f, 0.34f, 0.42f, 0.94f)
    private fun singularity() = ASTDColor(0.78f, 0.92f, 1f, 0.96f)
    private fun stellar() = ASTDColor(1f, 0.92f, 0.74f, 0.92f)

    private fun ASTDColor.a(alpha: Float): ASTDColor = copy(alpha = alpha.coerceIn(0f, 1f))

    private fun ASTDColor.hex(): Long {
        fun ch(v: Float): Long = (v.coerceIn(0f, 1f) * 255f).roundToInt().toLong()
        return (ch(red) shl 24) or (ch(green) shl 16) or (ch(blue) shl 8) or ch(alpha)
    }
}
