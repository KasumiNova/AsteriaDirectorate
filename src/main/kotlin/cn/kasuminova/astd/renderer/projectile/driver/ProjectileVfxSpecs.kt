package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.impl.render.ASTDColor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 已迁移到 RenderEntity 管线的弹体特效手写 DSL 构建函数库。
 * 每个 projectileSpecId 对应一个无状态构建函数，每次生成弹体都重新调用（不缓存），以支持调试期字面量热交换。
 *
 * 绝大多数弹体（[simpleProjectileVfx]）只需 5 个高层旋钮（调色板/宽/长/体型档/是否电弧副带），
 * 拖尾主体由 texTrail 贴图拖尾承担（P2 观感翻译公式见文件底部纯函数）；唯 hero（aod7）下探为逐层显式书写。
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
        "astd_rct6_torp" to { simpleProjectileVfx("astd_rct6_torp", rose(), width = 16f, length = 280f) },
        "astd_tsm2_missile" to { simpleProjectileVfx("astd_tsm2_missile", singularity(), width = 18f, length = 310f, glowScale = 3.4f) },
        "astd_tsm_omega_missile" to { simpleProjectileVfx("astd_tsm_omega_missile", omega(), width = 18f, length = 330f, glowScale = 3.3f) },
        "astd_gsp12_rift" to { simpleProjectileVfx("astd_gsp12_rift", singularity(), width = 18f, length = 280f, glowScale = 3.1f, ribbon = true) },
        "astd_jmb2_beam" to { simpleProjectileVfx("astd_jmb2_beam", teal(), width = 12f, length = 260f, glowScale = 2.5f) },
        "astd_jmb9_beam" to { simpleProjectileVfx("astd_jmb9_beam", blue(), width = 13f, length = 280f, glowScale = 2.6f) },
        "astd_jmb_omega_beam" to { simpleProjectileVfx("astd_jmb_omega_beam", omega(), width = 15f, length = 330f, glowScale = 3.0f) },
        "astd_sgl8_swarm" to { simpleProjectileVfx("astd_sgl8_swarm", singularity(), width = 20f, length = 340f, glowScale = 3.6f) },
        "astd_fdp4_charge" to { simpleProjectileVfx("astd_fdp4_charge", amber(), width = 14f, length = 250f, glowScale = 2.6f) },
        "astd_ftb_omega_beam" to { simpleProjectileVfx("astd_ftb_omega_beam", omega(), width = 16f, length = 350f, glowScale = 3.2f) },
        "astd_mnl2_mine" to { simpleProjectileVfx("astd_mnl2_mine", teal(), width = 13f, length = 210f, glowScale = 2.4f) },
        "astd_mnl3_mine" to { simpleProjectileVfx("astd_mnl3_mine", blue(), width = 14f, length = 230f, glowScale = 2.5f) },
        "astd_mnl_omega_grid" to { simpleProjectileVfx("astd_mnl_omega_grid", omega(), width = 15f, length = 260f, glowScale = 3.0f, ribbon = true) },
    )

    fun has(projectileSpecId: String): Boolean = projectileSpecId in builders

    /** 现构建一棵新树 + 策略；未迁移的 spec 返回 null（调用方回落旧管线）。 */
    fun build(projectileSpecId: String): ProjectileVfx? = builders[projectileSpecId]?.invoke()

    /**
     * 通用弹体特效：5 高层旋钮 → trail 风格声明 + texTrail 贴图拖尾主体 + bloom 网格弹头（+ 可选电弧副带）。
     *
     * P2 观感翻译（aod7 hero 形态推广）：glow/body/ribbon 网格层退役，拖尾主体由 texTrail 承担、
     * 光晕由 bloom 管线统一提供；弹头恒在（目检结论：无弹头的射弹头部观感过平）。
     * 派生公式全部为内部纯函数（[bandWidth] 等），登记行只填差异；
     * 目检微调优先改公式常量，个别例外才用具名参数覆盖。
     *
     * 注意：trail.startWidth 喂 widthBase=max(w*0.075, 3.5) 与驱动锚点，超过 46.7 会使 widthBase
     * 脱离 3.5 下限并放大弹头（head{} 尺寸乘 headSizeScale × headTrailScale(widthBase)，简单 spec
     * 系数 0.875 vs aod7 1.368，故 head 字面量必须经公式换算，禁照抄 aod7）。
     */
    private fun simpleProjectileVfx(
        id: String,
        palette: VfxPalette,
        width: Float,
        length: Float,
        glowScale: Float = 2.2f,
        ribbon: Boolean = false,
    ): ProjectileVfx = projectileVfx(id) {
        val color = palette.color
        trail {
            width(width); widths(width, (width * 0.16f).coerceAtLeast(1f)); length(length)
            color(color.hex()); tail(color.a((color.alpha * 0.12f).coerceIn(0.04f, 0.2f)).hex())
            emissive(color.a(1f).hex(), color.a((color.alpha * 0.25f).coerceIn(0.08f, 0.3f)).hex())
            texture(96f, 0.9f); fill(0.84f, 0.03f, 0.02f, 0.12f); pausedMotion()
        }
        // lifecycle 全默认（duration 1.25 / dissolveAt 0.6 / headScale 1.5 / layoutRef 1280）——与旧 preset 的 ASTDProjectileVfxLifecycleSpec() 默认一致。
        sampling { fps(60f); maxNodes(96); minStep(2f); window(length) }
        fade { out(0.18f); hit(0.1f); expire(0.22f) }

        val bandW = bandWidth(width, glowScale)
        // 双带（ribbon）时主带垫底、整体 alpha 压到 0.8（对标 aod7 twin 垫底 0x90/0x60），副带全 alpha
        val mainAlpha = if (ribbon) 0.8f else 1f
        val recedeBy = headRecede(length)
        texTrail("main", palette.texture) {
            layer(1); width(bandW)
            colors(bandHeadColor(color, mainAlpha).hex(), bandMidColor(color, mainAlpha).hex(), bandTailColor(color, mainAlpha).hex(), midAt = 0.25f)
            nodes(trailNodes(length)); tile(mainTile(length), mainScroll(length))
            recede(recedeBy)
        }
        if (ribbon) {
            // 电弧副带顶替旧 ribbon 网格的「缠绕飘带」观感：更窄、平铺/滚动更快
            texTrail("arc", palette.arcTexture) {
                layer(2); width(arcWidth(bandW))
                colors(bandHeadColor(color).hex(), bandMidColor(color).hex(), bandTailColor(color).hex(), midAt = 0.25f)
                nodes(trailNodes(length)); tile(arcTile(length), arcScroll(length))
                recede(recedeBy)
            }
        }
        head {
            length(headLength(length)); width(headWidth(bandW)); shoulder(0.5f); rear(0.95f); blur(0.35f)
            alpha(0.7f)  // 并入 bloom 管线后吃提取遍 emissive 增益 + 合成叠加，整体压暗防溢出
            shell(shellStartColor(color).hex(), shellMidColor(color).hex(), shellEndColor(color).hex())
        }
    }

    /**
     * aod7 hero：两条贴图拖尾为拖尾主体 + 代码网格弹头。
     * 拖尾复刻 MagicTrail 语义直接吃 gr_trails 原图（twin 脆丝垫底 layer1、zappy 电弧 layer2，宽比 twin=1.25×zappy），
     * 对标参考模组 zappy+twin 叠加构图；弹头用旧 `head{}` 网格（贴图弹头壳路线经目检否定已删，连同 headShell 特性）。
     * `trail{}` 仅作拖尾风格声明（基宽/基色/中线来源 + 驱动锚点），有 texTrail 时不落 BoxUtil 直线拖尾兜底。
     */
    private fun aod7Shot(): ProjectileVfx = projectileVfx("astd_aod7_shot") {
        trail {
            width(96f); widths(96f, 8f); length(420f)
            color(0x478FEBEB); tail(0x0A24380F)
            emissive(0xF0F8FFFF, 0x0A347529)
            texture(96f, 0.9f); fill(0.84f, 0.03f, 0.02f, 0.12f)
            strip(); blend("additive"); flickerSync(17); pausedMotion()
        }
        lifecycle { duration(1.25f); dissolveAt(0.6f); headScale(1.14f); layoutRef(1846f) }
        sampling { fps(60f); maxNodes(96); minStep(2f); window(420f) }
        fade { out(0.15f) }

        head {
            length(138f); width(34f); shoulder(0.5f); rear(0.95f)
            blur(0.35f)
            alpha(0.7f)  // 并入 bloom 管线后吃提取遍 emissive 增益 + 合成叠加，整体压暗防溢出
            shell(0x380A2E14, 0xDCEEFFEE, 0xF0F8FFFA)  // 蓝白族对齐带体头部色（zappy 头 0xF0F8FF），小缩放下不露色相接缝
        }
        texTrail("twin", "graphics/fx/gr_trails_twin.png") {
            layer(1); width(30f); recede(40f)
            colors(0xCFE8FF90, 0x6FB4FF60, 0x0A1C3810, midAt = 0.25f)
            nodes(24); tile(140f, 50f)
        }
        texTrail("zappy", "graphics/fx/gr_trails_zappy.png") {
            layer(2); width(24f); recede(40f)
            colors(0xF0F8FFB4, 0x6FB4FF6C, 0x0A1C3812, midAt = 0.25f)
            nodes(24); tile(200f, 90f)
        }
    }

    // 调色板：颜色沿用旧管线数值（视觉已目检回归，不宜再动）；贴图按色系语义分配（P2 观感翻译）。
    private fun violet() = VfxPalette(ASTDColor(0.66f, 0.42f, 1f, 0.9f), TEX_SMOOTH, TEX_ZAPPY)
    private fun amber() = VfxPalette(ASTDColor(1f, 0.62f, 0.18f, 0.95f), TEX_TWIN, TEX_ZAPPY)
    private fun omega() = VfxPalette(ASTDColor(0.72f, 0.35f, 1f, 0.96f), TEX_LIGHTNING, TEX_LIGHTNING)
    private fun blue() = VfxPalette(ASTDColor(0.2f, 0.55f, 1f, 0.92f), TEX_ZAPPY, TEX_ZAPPY)
    private fun teal() = VfxPalette(ASTDColor(0.22f, 1f, 0.78f, 0.9f), TEX_CLEAN, TEX_ZAPPY)
    private fun rose() = VfxPalette(ASTDColor(1f, 0.34f, 0.42f, 0.94f), TEX_CONTRAIL, TEX_ZAPPY)
    private fun singularity() = VfxPalette(ASTDColor(0.78f, 0.92f, 1f, 0.96f), TEX_CIRCLE, TEX_ZAPPY)
    private fun stellar() = VfxPalette(ASTDColor(1f, 0.92f, 0.74f, 0.92f), TEX_CONTRAIL, TEX_ZAPPY)

    private fun ASTDColor.a(alpha: Float): ASTDColor = copy(alpha = alpha.coerceIn(0f, 1f))

    private fun ASTDColor.hex(): Long {
        fun ch(v: Float): Long = (v.coerceIn(0f, 1f) * 255f).roundToInt().toLong()
        return (ch(red) shl 24) or (ch(green) shl 16) or (ch(blue) shl 8) or ch(alpha)
    }
}

/** 简单 spec 的贴图拖尾调色板：主色 + 主带贴图 + 电弧副带贴图（ribbon=true 时启用）。 */
internal data class VfxPalette(val color: ASTDColor, val texture: String, val arcTexture: String)

internal const val TEX_CIRCLE = "graphics/fx/gr_trails_circle.png"
internal const val TEX_CLEAN = "graphics/fx/gr_trails_clean.png"
internal const val TEX_CONTRAIL = "graphics/fx/gr_trails_contrail.png"
internal const val TEX_LIGHTNING = "graphics/fx/gr_trails_lightning.png"
internal const val TEX_SMOOTH = "graphics/fx/gr_trails_smooth.png"
internal const val TEX_TWIN = "graphics/fx/gr_trails_twin.png"
internal const val TEX_ZAPPY = "graphics/fx/gr_trails_zappy.png"

// —— P2 观感翻译公式（内部纯函数，供 simpleProjectileVfx 与公式守护测试共用）——

/** 0.5 粒度取整。 */
internal fun round05(v: Float): Float = (v * 2f).roundToInt() / 2f

/** 5 粒度取整。 */
internal fun round5(v: Float): Float = (v / 5f).roundToInt() * 5f

/** 贴图拖尾主带全宽：0.35×旧宽 锚 aod7 的 96→30；3.15×glowScale 锚旧 glow 视觉全宽（widthBase 3.5 × g）。 */
internal fun bandWidth(width: Float, glowScale: Float): Float = round05(max(0.35f * width, 3.15f * glowScale))

/** 电弧副带宽度：0.8×主带（对标 aod7 zappy/twin=0.8）。 */
internal fun arcWidth(bandW: Float): Float = round05(bandW * 0.8f)

/** 三段上色的头部色：mix(主色, 白, 0.45)，alpha = 主色 alpha×0.78×[alphaScale]（保持主色饱和——0.78 白混合会把琥珀等暖色洗成近白，与弹头光晕产生色差接缝）。 */
internal fun bandHeadColor(color: ASTDColor, alphaScale: Float = 1f): ASTDColor =
    mixWhite(color, 0.45f).copy(alpha = (color.alpha * 0.78f * alphaScale).coerceIn(0f, 1f))

/** 三段上色的中段色：主色，alpha×0.55×[alphaScale]（带体全段能量与弹头光晕衔接，过低保留着色但观感断档）。 */
internal fun bandMidColor(color: ASTDColor, alphaScale: Float = 1f): ASTDColor =
    color.copy(alpha = (color.alpha * 0.55f * alphaScale).coerceIn(0f, 1f))

/** 三段上色的尾部色：主色 rgb×0.16，alpha 0.07×[alphaScale]（对标 0x0A1C3810）。 */
internal fun bandTailColor(color: ASTDColor, alphaScale: Float = 1f): ASTDColor = ASTDColor(
    color.red * 0.16f, color.green * 0.16f, color.blue * 0.16f, (0.07f * alphaScale).coerceIn(0f, 1f),
)

/** 主带平铺周期：L/2.4（aod7 的 420→140~200 区间居中）。 */
internal fun mainTile(length: Float): Float = round5(length / 2.4f)

/** 主带滚动速度：L/6。 */
internal fun mainScroll(length: Float): Float = round5(length / 6f)

/** 副带平铺周期：L/2（更快更密的电弧）。 */
internal fun arcTile(length: Float): Float = round5(length / 2f)

/** 副带滚动速度：L/4.5。 */
internal fun arcScroll(length: Float): Float = round5(length / 4.5f)

/** 节点数：L/16，下限 16（速射短弹的弯道平滑保底）上限 24（aod7）。 */
internal fun trailNodes(length: Float): Int = (length / 16f).roundToInt().coerceIn(16, 24)

/** 弹头露出退距：L×0.08（aod7 40/420≈0.095），带体亮端后移让弹头尖在带体前露出（禁 forward 偏移）。 */
internal fun headRecede(length: Float): Float = round5(length * 0.08f)

/** 弹头长：L×0.33（沿用旧工厂）。 */
internal fun headLength(length: Float): Float = length * 0.33f

/** 弹头宽：bandW×1.77——aod7 视觉比（弹头视觉宽/带宽 = 34×1.368/30 ≈ 1.55）除以简单 spec 的 0.875 缩放系数。 */
internal fun headWidth(bandW: Float): Float = round05(bandW * 1.77f)

/** 弹头壳内圈色：主色 rgb×0.25 @ alpha 0.08。 */
internal fun shellStartColor(color: ASTDColor): ASTDColor =
    ASTDColor(color.red * 0.25f, color.green * 0.25f, color.blue * 0.25f, 0.08f)

/** 弹头壳中圈色：mix(主色, 白, 0.55) @ alpha 0.93。 */
internal fun shellMidColor(color: ASTDColor): ASTDColor = mixWhite(color, 0.55f).copy(alpha = 0.93f)

/** 弹头壳外圈色：mix(主色, 白, 0.85) @ alpha 0.98（与带体头色同族，小缩放接缝双保险）。 */
internal fun shellEndColor(color: ASTDColor): ASTDColor = mixWhite(color, 0.85f).copy(alpha = 0.98f)

private fun mixWhite(color: ASTDColor, t: Float): ASTDColor = ASTDColor(
    color.red + (1f - color.red) * t,
    color.green + (1f - color.green) * t,
    color.blue + (1f - color.blue) * t,
    color.alpha,
)
