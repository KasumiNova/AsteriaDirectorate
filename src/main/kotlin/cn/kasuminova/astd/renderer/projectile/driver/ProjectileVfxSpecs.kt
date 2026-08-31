package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.combat.effect.arc.piercinglance.PiercingLanceVfx
import cn.kasuminova.astd.impl.render.ASTDColor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 已迁移到 RenderEntity 管线的弹体特效手写 DSL 构建函数库。
 * 每个 projectileSpecId 对应一个无状态构建函数，每次生成弹体都重新调用（不缓存），以支持调试期字面量热交换。
 *
 * 绝大多数弹体（[simpleProjectileVfx]）只需 4 个高层旋钮（主色/宽/长/体型档），拖尾主体为固定三层贴图混合
 * （twin 外带 + smooth 核心 + zappy 装饰，参数见文件底部常量与纯函数）；弹头由原版弹体渲染（.proj 不再隐藏
 * bulletSprite，走 projbody/projtrail 螺栓），代码弹头（head{} DSL）保留给 hero（aod7）逐层显式书写。
 */
object ProjectileVfxSpecs {

    /**
     * projectileSpecId → 构建函数。加入一个即接入本管线。
     *
     * 当前接入：aod7（hero）+ 10 个简单 spec。aod7 豁免三层混合改版（保持既有 hero 观感）。
     */
    private val builders: Map<String, () -> ProjectileVfx> = mapOf(
        "astd_aod7_shot" to ::aod7Shot,
        "astd_spc3_shot" to { simpleProjectileVfx("astd_spc3_shot", violet(), width = 6f, length = 135f) },
        "astd_charge_needle_shot" to { simpleProjectileVfx("astd_charge_needle_shot", chargeNeedleColor(), width = 6f, length = 135f) },
        "astd_heavy_charge_needle_shot" to { simpleProjectileVfx("astd_heavy_charge_needle_shot", chargeNeedleColor(), width = 9f, length = 165f) },
        // 电驱加速炮：白色射弹（美术裁定），粗细 = 电荷针刺箭弹 6f × 1.5，长 trail 拖尾 500su。
        "astd_electric_drive_accelerator_shot" to {
            simpleProjectileVfx("astd_electric_drive_accelerator_shot", ASTDColor(1f, 1f, 1f, 0.9f), width = 9f, length = 500f)
        },
        // 穷距相位轨道炮：高亮白色细长射弹 + 长距离明亮拖尾（美术主色口径），大型主炮体量 12/300。
        "astd_qiongjue_phase_railgun_shot" to {
            simpleProjectileVfx("astd_qiongjue_phase_railgun_shot", ASTDColor(0.92f, 0.95f, 1f, 1f), width = 12f, length = 300f)
        },
        // 正电子冲击波：小型 PD 弹体克制处理（width 5 / length 90 短拖尾，不抢主炮视觉——设计案特效节）。
        "astd_positron_shockwave_shot" to {
            simpleProjectileVfx("astd_positron_shockwave_shot", positronWhiteBlue(), width = 5f, length = 90f)
        },
        // 重型离子脉冲：ARC 冷蓝白大槽体量 12/220（90 计划 §2.4）。
        "astd_heavy_ion_pulse_shot" to {
            simpleProjectileVfx("astd_heavy_ion_pulse_shot", heavyIonPulseColor(), width = 12f, length = 220f)
        },
        // 辉星 MRM（规格 08 §3.1）：LENS 紫辉星弹体/拖尾（爆炸为裂隙组件蓝色族），很长拖尾（length=420 对齐 aod7 hero，2500 射程长航迹）；
        // width=10 表达 1.5× 弹体体量（介于 spc3 中型 6 与穷距大型 12 之间）。两 spec 值完全一致属刻意（同一弹头两种发射器）。
        "astd_stellar_mrm_launcher_shot" to {
            simpleProjectileVfx("astd_stellar_mrm_launcher_shot", violet(), width = 10f, length = 420f)
        },
        "astd_stellar_mrm_pod_shot" to {
            simpleProjectileVfx("astd_stellar_mrm_pod_shot", violet(), width = 10f, length = 420f)
        },
        // 贯星之矛（规格 09 §3.1）：width=36 大圆形弹体 + glowScale 4.0 放大带宽（公式派生）。
        "astd_piercing_lance_shot" to ::piercingLanceShot,
    )

    fun has(projectileSpecId: String): Boolean = projectileSpecId in builders

    /** 现构建一棵新树 + 策略；未迁移的 spec 返回 null（调用方回落旧管线）。 */
    fun build(projectileSpecId: String): ProjectileVfx? = builders[projectileSpecId]?.invoke()

    /**
     * 通用弹体特效：4 高层旋钮 → trail 风格声明（驱动锚点）+ 三层贴图拖尾混合。
     *
     * 三层构图（美术裁定，全弹体统一；aod7 hero 豁免）：
     * - twin 外带（layer1 垫底）：全宽 [bandWidth]×[BAND_WIDTH_MULT]，alpha [ALPHA_OUTER]，每段 ±[TWIST_OUTER_DEG] 随机扭转；
     * - smooth 核心（layer2）：宽度 −50%，alpha [ALPHA_CORE]，每段 ±[TWIST_INNER_DEG] 随机扭转；
     * - zappy 装饰（layer3）：[arcWidth]（0.8×外带），alpha [ALPHA_DECOR]，每段 ±[TWIST_INNER_DEG] 随机扭转。
     *
     * 弹头不再由代码网格承担（head{} 保留给 aod7）：原版弹体渲染（projbody/projtrail 螺栓）经 .proj 启用。
     * 派生公式全部为内部纯函数（[bandWidth] 等），登记行只填差异；目检微调优先改公式常量。
     */
    private fun simpleProjectileVfx(
        id: String,
        color: ASTDColor,
        width: Float,
        length: Float,
        glowScale: Float = 2.2f,
        extra: ProjectileVfxScope.() -> Unit = {},
    ): ProjectileVfx = projectileVfx(id) {
        trail {
            width(width); length(length)
            color(color.hex()); tail(color.a((color.alpha * 0.12f).coerceIn(0.04f, 0.2f)).hex())
            emissive(color.a(1f).hex())
        }
        // lifecycle 全默认（duration 1.25 / dissolveAt 0.6 / headScale 1.5 / layoutRef 1280）——与旧 preset 的 ASTDProjectileVfxLifecycleSpec() 默认一致。
        sampling { fps(60f); maxNodes(96); minStep(2f); window(length) }
        fade { out(0.18f); hit(0.1f); expire(0.22f) }

        val bandW = bandWidth(width, glowScale) * BAND_WIDTH_MULT
        val recedeBy = headRecede(length)
        val nodeCount = trailNodes(length)
        texTrail("twin", TEX_TWIN) {
            layer(1); width(bandW)
            colors(bandHeadColor(color, ALPHA_OUTER).hex(), bandMidColor(color, ALPHA_OUTER).hex(), bandTailColor(color, ALPHA_OUTER).hex(), midAt = 0.25f)
            nodes(nodeCount); tile(mainTile(length), mainScroll(length))
            recede(recedeBy)
            twist(TWIST_OUTER_DEG)
        }
        texTrail("core", TEX_SMOOTH) {
            layer(2); width(round05(bandW * CORE_WIDTH_RATIO))
            colors(bandHeadColor(color, ALPHA_CORE).hex(), bandMidColor(color, ALPHA_CORE).hex(), bandTailColor(color, ALPHA_CORE).hex(), midAt = 0.25f)
            nodes(nodeCount); tile(mainTile(length), mainScroll(length))
            recede(recedeBy)
            twist(TWIST_INNER_DEG)
        }
        texTrail("zappy", TEX_ZAPPY) {
            layer(3); width(arcWidth(bandW))
            colors(bandHeadColor(color, ALPHA_DECOR).hex(), bandMidColor(color, ALPHA_DECOR).hex(), bandTailColor(color, ALPHA_DECOR).hex(), midAt = 0.25f)
            nodes(nodeCount); tile(arcTile(length), arcScroll(length))
            recede(recedeBy)
            twist(TWIST_INNER_DEG)
        }
        extra()
    }

    /**
     * aod7 hero：两条贴图拖尾为拖尾主体 + 代码网格弹头。
     * 拖尾复刻 MagicTrail 语义吃 astd_trails 贴图（twin 脆丝垫底 layer1、zappy 电弧 layer2，宽比 twin=1.25×zappy），
     * 对标参考模组 zappy+twin 叠加构图；弹头用旧 `head{}` 网格（贴图弹头壳路线经目检否定已删，连同 headShell 特性）。
     * `trail{}` 仅作拖尾风格声明（弹头网格的基宽/基色来源 + 驱动锚点）。
     *
     * 豁免三层混合改版（无 twist、宽度不翻倍、保留 head{} 代码弹头与隐藏原版弹体）。
     * headLead(0f)：原版弹体已隐藏、代码弹头网格锚在弹体中心，禁用自动前移避免整体视觉前移半颗弹。
     */
    private fun aod7Shot(): ProjectileVfx = projectileVfx("astd_aod7_shot") {
        trail {
            width(96f); length(420f)
            color(0x478FEBEB); tail(0x0A24380F)
            emissive(0xF0F8FFFF)
        }
        lifecycle { duration(1.25f); dissolveAt(0.6f); headScale(1.14f); layoutRef(1846f); headLead(0f) }
        sampling { fps(60f); maxNodes(96); minStep(2f); window(420f) }
        fade { out(0.15f) }

        head {
            length(138f); width(34f); shoulder(0.5f); rear(0.95f)
            blur(0.35f)
            alpha(0.7f)  // 并入 bloom 管线后吃提取遍 emissive 增益 + 合成叠加，整体压暗防溢出
            shell(0x380A2E14, 0xDCEEFFEE, 0xF0F8FFFA)  // 蓝白族对齐带体头部色（zappy 头 0xF0F8FF），小缩放下不露色相接缝
        }
        texTrail("twin", "graphics/fx/astd_trails_twin.png") {
            layer(1); width(30f); recede(40f)
            colors(0xCFE8FF90, 0x6FB4FF60, 0x0A1C3810, midAt = 0.25f)
            nodes(24); tile(140f, 50f)
        }
        texTrail("zappy", "graphics/fx/astd_trails_zappy.png") {
            layer(2); width(24f); recede(40f)
            colors(0xF0F8FFB4, 0x6FB4FF6C, 0x0A1C3812, midAt = 0.25f)
            nodes(24); tile(200f, 90f)
            // dispersion 验证案例：振幅 5（< 带宽 1/4=6）；波长 110 ≈ 带长 420 摆三个多波段；
            // 慢爬行 30 su/s 与贴图快爬行（90/200≈0.45 整图/秒）错出快慢两层动感；相位 0.8 避免与 twin 层头部同相
            wobble(5f, 110f, 30f, 0.8f)
        }
    }

    // 贯星之矛（规格 09 §3.1）：冷蓝白 ARC 主色内联字面量；width 36 / length 260 / glowScale 4.0 大圆形弹体观感。
    // 追加：BoxUtil 水平光斑（锚回弹体中心：offset = -headLead = -36/2）+ 发射点锚定电弧（首次泛用组件接入）
    // + 发射瞬间发射点扭曲（PiercingLanceVfx.spawnMuzzleDistortion）。
    private fun piercingLanceShot(): ProjectileVfx = simpleProjectileVfx(
        "astd_piercing_lance_shot",
        ASTDColor(0.55f, 0.78f, 1f, 0.95f),
        width = 36f,
        length = 260f,
        glowScale = 4.0f,
    ) {
        boxFlare("core") {
            size(150f, 14f)
            colors(0xF4FBFFFF, 0x8CD2FFBE)
            glow(1f, 4f)
            flicker(1.3f)
            offset(-18f)
        }
        arcTrail("arc", TEX_ZAPPY) {
            layer(4); width(7f)
            colors(0xE8F6FFCCL, 0x78BEFF30L)
            nodes(26); tile(180f, 260f)
            jag(14f, 200f, 9f)
            flicker(0.2f)
        }
        onFire(ProjectileVfxOnFireHook { engine, projectile ->
            PiercingLanceVfx.spawnMuzzleDistortion(engine, org.lwjgl.util.vector.Vector2f(projectile.location))
        })
    }

    // 正电子冲击波：冷蓝白系（全局美术约定「正电子用白色弹体与明亮拖尾」），分支内内联字面量。
    private fun positronWhiteBlue() = ASTDColor(0.62f, 0.82f, 1f, 0.85f)

    // 调色板：颜色沿用旧管线数值（视觉已目检回归，不宜再动）。
    private fun violet() = ASTDColor(0.66f, 0.42f, 1f, 0.9f)

    // 重型离子脉冲：ARC 冷蓝白（全局美术约定，与电荷针刺同色系）。新共享调色板只允许收口人添加（00 §3），本组内联私有函数。
    private fun heavyIonPulseColor() = ASTDColor(0.55f, 0.78f, 1f, 0.9f)

    // 电荷针刺：ARC 冷蓝白（全局美术约定）。新共享调色板只允许收口人添加（00 §3），本组内联私有函数。
    private fun chargeNeedleColor() = ASTDColor(0.55f, 0.78f, 1f, 0.9f)

    private fun ASTDColor.a(alpha: Float): ASTDColor = copy(alpha = alpha.coerceIn(0f, 1f))

    private fun ASTDColor.hex(): Long {
        fun ch(v: Float): Long = (v.coerceIn(0f, 1f) * 255f).roundToInt().toLong()
        return (ch(red) shl 24) or (ch(green) shl 16) or (ch(blue) shl 8) or ch(alpha)
    }
}

internal const val TEX_TWIN = "graphics/fx/astd_trails_twin.png"
internal const val TEX_SMOOTH = "graphics/fx/astd_trails_smooth.png"
internal const val TEX_ZAPPY = "graphics/fx/astd_trails_zappy.png"

// —— 三层混合常量（美术裁定，公式守护测试锚点）——

/** 带宽总倍率（宽度翻倍裁定）：外带全宽 = [bandWidth] × 本值。 */
internal const val BAND_WIDTH_MULT = 2f

/** 核心层（smooth）宽度比：外带 ×0.5。 */
internal const val CORE_WIDTH_RATIO = 0.5f

/** 三层 alpha：twin 外带 0.45 / smooth 核心 0.6 / zappy 装饰 0.45（乘进三段渐变各节点 alpha）。
 * 初版 0.6/0.8/0.6 经烟测目检过曝（多层加色叠加 + 同走廊多发弹体拖尾重叠），×0.75 压暗，保持 3:4:3 比例。 */
internal const val ALPHA_OUTER = 0.45f
internal const val ALPHA_CORE = 0.6f
internal const val ALPHA_DECOR = 0.45f

/** 逐段随机扭转幅度（度）：twin 外带 ±90°，smooth 核心 / zappy 装饰 ±30°（前后段沿路径插值自动衔接）。 */
internal const val TWIST_OUTER_DEG = 90f
internal const val TWIST_INNER_DEG = 30f

// —— P2 观感翻译公式（内部纯函数，供 simpleProjectileVfx 与公式守护测试共用）——

/** 0.5 粒度取整。 */
internal fun round05(v: Float): Float = (v * 2f).roundToInt() / 2f

/** 5 粒度取整。 */
internal fun round5(v: Float): Float = (v / 5f).roundToInt() * 5f

/** 贴图拖尾外带基准全宽：0.35×旧宽 锚 aod7 的 96→30；3.15×glowScale 锚旧 glow 视觉全宽（widthBase 3.5 × g）。 */
internal fun bandWidth(width: Float, glowScale: Float): Float = round05(max(0.35f * width, 3.15f * glowScale))

/** zappy 装饰带宽度：0.8×外带（对标 aod7 zappy/twin=0.8）。 */
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

/** 外带/核心平铺周期：L/2.4（aod7 的 420→140~200 区间居中）。 */
internal fun mainTile(length: Float): Float = round5(length / 2.4f)

/** 外带/核心滚动速度：L/6。 */
internal fun mainScroll(length: Float): Float = round5(length / 6f)

/** 装饰带平铺周期：L/2（更快更密的电弧）。 */
internal fun arcTile(length: Float): Float = round5(length / 2f)

/** 装饰带滚动速度：L/4.5。 */
internal fun arcScroll(length: Float): Float = round5(length / 4.5f)

/** 节点数：L/16，下限 16（速射短弹的弯道平滑保底）上限 24（aod7）。 */
internal fun trailNodes(length: Float): Int = (length / 16f).roundToInt().coerceIn(16, 24)

/** 带体头部退距：L×0.08（aod7 40/420≈0.095），带体亮端后移让原版螺栓弹头在带体前露出（禁 forward 偏移）。 */
internal fun headRecede(length: Float): Float = round5(length * 0.08f)

private fun mixWhite(color: ASTDColor, t: Float): ASTDColor = ASTDColor(
    color.red + (1f - color.red) * t,
    color.green + (1f - color.green) * t,
    color.blue + (1f - color.blue) * t,
    color.alpha,
)
