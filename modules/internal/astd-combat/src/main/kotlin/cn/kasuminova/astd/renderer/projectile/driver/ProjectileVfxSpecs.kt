package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.combat.effect.arc.piercinglance.PiercingLanceVfx
import cn.kasuminova.astd.impl.render.ASTDColor
import cn.kasuminova.astd.impl.render.BoxFlareStyle
import cn.kasuminova.astd.impl.render.ConeImpactVfx
import cn.kasuminova.astd.impl.render.ConeImpactVfxSpec
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxSpecs.build
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxSpecs.has
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxSpecs.simpleProjectileVfx
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 弹体特效手写 DSL 构建函数库（Static Trail + Box 螺栓管线）。
 * 每个 projectileSpecId 对应一个无状态构建函数，每次生成弹体都重新调用（不缓存），以支持调试期字面量热交换。
 * 注意：Static Trail 拖尾层的 StaticTrailData（vRAM 池配置）按 BoxUtil 约束为常量并缓存，热交换对拖尾层不生效。
 *
 * 绝大多数弹体（[simpleProjectileVfx]）只需 4 个高层旋钮（主色/宽/长/体型档），拖尾主体为固定四层贴图混合
 * （twin 外带 + smooth 核心 + zappy 装饰 ×2，参数见文件底部常量与纯函数）；弹头由 Box 螺栓组件承担
 * （SpriteEntity 双趟烘焙彗形贴图，默认开启、染近白单色系），原版螺栓视觉由 .proj 屏蔽
 * （ss-csv 侧 `ProjectileProjSpec.boxBolt`）。
 */
object ProjectileVfxSpecs {

    /**
     * projectileSpecId → 构建函数（参数 = 武器面板射程 su，null 时用 spec 固定带长）。加入一个即接入本管线。
     *
     * 当前接入：aod7（hero，双层）+ 12 个 simpleProjectileVfx spec（四层惯例）。
     */
    private val builders: Map<String, (Float?) -> ProjectileVfx> = mapOf(
        "astd_aod7_shot" to ::aod7Shot,
        "astd_spc3_shot" to { simpleProjectileVfx("astd_spc3_shot", violet(), width = 6f, length = 135f) },
        // 电荷针刺族：固定短拖尾 180、无 zappy 装饰层（去随机扭转抖动）、宽度 −75%。
        "astd_charge_needle_shot" to {
            simpleProjectileVfx(
                "astd_charge_needle_shot",
                chargeNeedleColor(),
                width = 6f,
                length = 180f,
                trailWidthScale = 0.5f,
                decorTrail = false
            )
        },
        "astd_heavy_charge_needle_shot" to {
            simpleProjectileVfx(
                "astd_heavy_charge_needle_shot",
                chargeNeedleColor(),
                width = 6f,
                length = 180f,
                trailWidthScale = 0.5f,
                decorTrail = false
            )
        },
        // 电驱加速炮：黄色射弹（美术裁定），trail 长 = 射程×25%、宽 −50%，尾色金→红渐变。
        "astd_electric_drive_accelerator_shot" to { range ->
            simpleProjectileVfx(
                "astd_electric_drive_accelerator_shot",
                electricYellow(),
                width = 9f,
                length = 500f,
                range = range,
                rangeRatio = 0.25f,
                trailWidthScale = 0.5f,
                tailColor = emberRedTail(),
                decorTrail = false
            )
        },
        // 穷距相位轨道炮：ARC 冷蓝白弹体 + 长距离明亮拖尾（蓝→淡绿渐变尾），trail 长 = 射程×50%、宽 −35%、
        // 装饰层关闭（大弹体主角光环留给外带/核心）；弹头补 SMOOTH 光斑强化弹体发光，开火附带炮口锥面碎片。
        "astd_qiongjue_phase_railgun_shot" to { range ->
            simpleProjectileVfx(
                "astd_qiongjue_phase_railgun_shot",
                qiongjueBlue(),
                width = 16f,
                length = 300f,
                range = range,
                rangeRatio = 0.5f,
                trailWidthScale = 0.65f,
                trailGlow = 0.8f,
                tailColor = paleGreenTail(),
                boltFlare = 48f,
                muzzleBurst = true,
                decorTrail = false
            )
        },
        // 正电子冲击波：小型 PD 弹体克制处理（width 5 / length 90 短拖尾，不抢主炮视觉——设计案特效节）。
        "astd_positron_shockwave_shot" to {
            simpleProjectileVfx("astd_positron_shockwave_shot", positronWhiteBlue(), width = 5f, length = 90f)
        },
        // 重型离子脉冲：trail 长 = 射程×25%、宽 −25%；弹头光斑强化弹体发光，开火附带炮口锥面碎片。
        "astd_heavy_ion_pulse_shot" to { range ->
            simpleProjectileVfx(
                "astd_heavy_ion_pulse_shot",
                heavyIonPulseColor(),
                width = 12f,
                length = 220f,
                range = range,
                rangeRatio = 0.25f,
                trailWidthScale = 0.75f,
                trailGlow = 0.7f,
                boltFlare = 36f,
                muzzleBurst = true
            )
        },
        // 辉星 MRM（规格 08 §3.1）：LENS 紫辉星弹体/拖尾（爆炸为裂隙组件蓝色族），trail 长 = 射程×50%、recede 0；
        // width=10 表达 1.5× 弹体体量（介于 spc3 中型 6 与穷距大型 12 之间）。两 spec 值完全一致属刻意（同一弹头两种发射器）。
        "astd_stellar_mrm_launcher_shot" to { range -> stellarMrmShot("astd_stellar_mrm_launcher_shot", range) },
        "astd_stellar_mrm_pod_shot" to { range -> stellarMrmShot("astd_stellar_mrm_pod_shot", range) },
        // 星尘光尘（联制线内置导弹）：小型环绕弹体（width 4 / length 200，装饰层关闭保持环绕群可读性），
        // ARC 蓝 / LENS 紫双线配色（设计案 20-joint.md §武器特效）。带长固定，不随射程折算。
        "astd_lh_stardust_mote_arc" to {
            simpleProjectileVfx("astd_lh_stardust_mote_arc", qiongjueBlue(), width = 4f, length = 200f, decorTrail = false)
        },
        "astd_lh_stardust_mote_lens" to {
            simpleProjectileVfx("astd_lh_stardust_mote_lens", violet(), width = 4f, length = 200f, decorTrail = false)
        },
        // 贯星之矛（规格 09 §3.1）：width=36 大圆形弹体 + glowScale 4.0 放大带宽（公式派生）。
        "astd_piercing_lance_shot" to ::piercingLanceShot,
        // 摧锋鱼雷（blue/30-superlative.md §特效）：ARC 冷蓝白，trail 长 = 射程×50%、recede 0（带体亮头直抵弹头），
        // 弹头处 SMOOTH 光斑补鱼雷本体贴图之外的辉光（辉星同款口径）。
        "astd_cuifeng_torpedo_shot" to { range -> cuifengTorpedoShot("astd_cuifeng_torpedo_shot", range) },
        // 源生冰晶 MIRV 母弹（purple/30-superlative.md §特效）：冰蓝白，trail 长 = 射程×50%、recede 0。
        "astd_ice_shard_mirv_shot" to { range -> iceShardMirvShot("astd_ice_shard_mirv_shot", range) },
        // 源生冰晶子射弹：15 枚小冰晶成群，克制处理（width 4 / 固定短拖尾 120、装饰层关闭保持冰晶群可读性，
        // 星尘光尘同款口径）；弹体本体由 spriteBody 接管（BoxUtil SpriteEntity 逐帧跟随，normal alpha 对齐原版
        // 导弹贴图语义），原版贴图渲染由 .proj 的 sprite=BUtil_NONE.png 屏蔽，bolt 显式关闭（本体贴图取代螺栓）。
        "astd_ice_shard_sub_msl" to {
            simpleProjectileVfx("astd_ice_shard_sub_msl", iceBlue(), width = 6f, length = 120f, decorTrail = false, recede = -10f) {
                bolt { off() }
                spriteBody("graphics/fx/astd_ice_shard.png", width = 40f, height = 40f) {
                    glow(0.5f)
                }
            }
        },
    )

    private val installed = AtomicBoolean(false)

    /**
     * 把全部武器特效构建函数注册进 [ProjectileVfxRegistry]（幂等）。
     * 战斗装配点（CombatVfxBootstrap）显式调用一次；[has]/[build] 也会兜底触发，
     * 保证渲染驱动侧查表前注册已完成。
     */
    fun install() {
        if (installed.compareAndSet(false, true)) {
            builders.forEach { (id, builder) -> ProjectileVfxRegistry.register(id, builder) }
        }
    }

    fun has(projectileSpecId: String): Boolean {
        install()
        return ProjectileVfxRegistry.has(projectileSpecId)
    }

    /** 现构建一份新蓝图 + 策略；未迁移的 spec 返回 null（调用方回落旧管线）。 */
    fun build(projectileSpecId: String, weaponRangeSu: Float? = null): ProjectileVfx? {
        install()
        return ProjectileVfxRegistry.build(projectileSpecId, weaponRangeSu)
    }

    /**
     * 通用弹体特效：4 高层旋钮 → 四层贴图拖尾混合（Static Trail）。
     *
     * 四层构图（美术裁定，全弹体统一）：
     * - twin 外带（layer1 垫底）：全宽 [bandWidth]×[BAND_WIDTH_MULT]×[trailWidthScale]；
     * - smooth 核心（layer2）：宽度 −50%（[CORE_WIDTH_RATIO]）；
     * - zappy 装饰 ×2（layer3，[decorTrail]=false 时省略）：[arcWidth]（0.6×外带），带尾端自旋/漂移抖动。
     * 各层两段上色 alpha 不再按层缩放（统一 [brightness] 乘算），bloom 由 [trailGlow] 全层统一供给。
     *
     * 弹头为 Box 螺栓（近白单色系染色，见 `bolt{}` 与 [boltColor]）；带体两段上色（亮头 → 暗尾）。
     * 派生公式全部为内部纯函数（[bandWidth] 等），登记行只填差异；目检微调优先改公式常量。
     *
     * @param length 固定带长（su）；[rangeRatio] 非空且 [range] 可用时被射程折算取代（作为射程缺失时的基线）。
     * @param range 武器面板射程（su，构建期传入）。
     * @param rangeRatio 拖尾带长 = 射程 × 本比例（非空启用；长度折算后按 5su 取整）。
     * @param trailWidthScale 拖尾宽度倍率（各层带体统一乘算）。
     * @param brightness 带体亮度倍率（各层两段上色 alpha 统一乘算，钳 0..1）。
     * @param trailGlow 拖尾层 bloom 发光强度（0..1，全层统一乘算；热交换对拖尾层不生效需重启）。
     * @param tailColor 拖尾尾色（自选 RGB，null = 同色压暗淡化；非空 = 头色 → 尾色两段渐变，rgb 直取、
     *   alpha 乘层透明度，如蓝→淡绿、金→红）。
     * @param boltFlare 弹头 SMOOTH 光斑尺寸（su，null = 不加；组件层，支持字面量热交换）。
     * @param muzzleBurst 开火瞬间在炮口附加一发小型锥面冲击（三角碎片 + 刺束 + 顶点闪光，ConeImpactVfx 一发即走）。
     * @param decorTrail 是否带 zappy 电弧装饰层 ×2（关闭同时去掉其随机扭转/漂移抖动）。
     * @param recede 带体退距显式值（null = 自动取弹体长度 ×0.2，tracker 运行期解析）。
     */
    private fun simpleProjectileVfx(
        id: String,
        color: ASTDColor,
        width: Float,
        length: Float,
        glowScale: Float = 2.2f,
        range: Float? = null,
        rangeRatio: Float? = null,
        trailWidthScale: Float = 1f,
        brightness: Float = 0.9f,
        trailGlow: Float = 0.7f,
        tailColor: ASTDColor? = null,
        boltFlare: Float? = null,
        muzzleBurst: Boolean = false,
        decorTrail: Boolean = true,
        recede: Float? = null,
        extra: ProjectileVfxScope.() -> Unit = {},
    ): ProjectileVfx = projectileVfx(id) {
        fade { out(0.18f); hit(0.1f); expire(0.22f) }

        bolt { color(boltColor(color).hex()) }

        val bandLen = if (rangeRatio != null && range != null) round5(range * rangeRatio) else length
        val bandW = bandWidth(width, glowScale) * BAND_WIDTH_MULT * trailWidthScale
        staticTrail("twin", TEX_TWIN) {
            layer(1); width(bandW); length(bandLen)
            colors(bandHeadColor(color, brightness).hex(), bandTailColor(color, brightness, tailColor).hex())
            tile(mainTile(bandLen), mainScroll(bandLen))
            recede?.let { recede(it) }
            glow(trailGlow)
        }
        staticTrail("core", TEX_SMOOTH) {
            layer(2); width(round05(bandW * CORE_WIDTH_RATIO)); length(bandLen)
            colors(bandHeadColor(color, brightness).hex(), bandTailColor(color, brightness, tailColor).hex())
            tile(mainTile(bandLen), mainScroll(bandLen))
            recede?.let { recede(it) }
            glow(trailGlow)
        }
        if (decorTrail) {
            // 两条 zappy 装饰层共用 layer 3（renderOrder 相同，绘制先后由实体树声明序稳定保证）；
            // 层名 zappy_0/zappy_1 必须互异——StaticTrailData 池按「树 id + 层名」缓存，同名会键冲突。
            repeat(2) { count ->
                staticTrail("zappy_$count", TEX_ZAPPY) {
                    layer(3); width(arcWidth(bandW)); length(bandLen)
                    colors(bandHeadColor(color, brightness).hex(), bandTailColor(color, brightness, tailColor).hex())
                    tile(arcTile(bandLen), arcScroll(bandLen))
                    recede?.let { recede(it) }
                    // 尾端漂移卷曲：angular 必须配合非零 velocity 才生效（自旋旋转的是漂移偏移矢量）
                    angularOut()
                    velocityOut(-12f, -6f, 12f, 6f)
                    glow(trailGlow)
                }
            }
        }
        if (boltFlare != null) {
            boxFlare("boltGlow") {
                style(BoxFlareStyle.SMOOTH)
                size(boltFlare, boltFlare)
                colors(mixWhite(color, 0.85f).a(0.5f).hex(), color.a(0.15f).hex())
                glow(0.2f, 4f)
                noise(0f)
                offset(-boltFlare / 2)
            }
        }
        if (muzzleBurst) {
            onFire { engine, projectile ->
                ConeImpactVfx.spawn(
                    engine,
                    ConeImpactVfxSpec(
                        origin = Vector2f(projectile.location),
                        facingDeg = projectile.facing,
                        halfAngleDeg = 20f,
                        length = 120f,
                        coreColor = awt(mixWhite(color, 0.7f)),
                        fringeColor = awt(color),
                        duration = 0.35f,
                    ),
                )
            }
        }
        extra()
    }

    /**
     * aod7 hero：两条贴图拖尾为拖尾主体（复刻参考模组 zappy+twin 叠加构图）；
     * 弹头 = Box 螺栓（染 aod7 冷蓝白近白色）。
     * 拖尾吃 astd_trails 贴图（twin 脆丝垫底 layer1、zappy 电弧 layer2，宽比 twin=1.25×zappy）。
     * 带长 = 武器面板射程 ×75%（range 缺失时 420 基线）；headLead/recede 均缺省
     * （锚点压弹体前端，退距 = 弹体长度 ×0.2，tracker 运行期解析）。
     * zappy 不做尾端自旋（实机观测：新旧段拼接处扭曲跳变主要来自 angular 随机自旋）。
     */
    private fun aod7Shot(range: Float?): ProjectileVfx = projectileVfx("astd_aod7_shot") {
        fade { out(0.15f) }

        bolt { color(0xE4F2FFC8) }

        val bandLen = if (range != null) round5(range * 0.75f) else 420f
        staticTrail("twin", TEX_TWIN) {
            layer(1); width(30f); length(bandLen)
            colors(0xCFE8FF90, 0x0A1C3810)
            tile(round5(bandLen / 3f), round5(bandLen * 0.12f))
        }
        staticTrail("zappy", TEX_ZAPPY) {
            layer(2); width(24f); length(bandLen)
            colors(0xF0F8FFB4, 0x0A1C3812)
            tile(round5(bandLen * 0.476f), round5(bandLen * 0.215f))
            glow(0.5f)
        }
    }

    /**
     * 辉星 MRM：simpleProjectileVfx 四层惯例 + 带长 = 射程 ×50% + recede 0（带体亮头直抵弹头）+
     * 弹头处 SMOOTH 光斑（light：柔边球光，补导弹本体贴图之外的辉光）。
     */
    private fun stellarMrmShot(id: String, range: Float?): ProjectileVfx = simpleProjectileVfx(
        id,
        violet(),
        width = 10f,
        length = 420f,
        range = range,
        rangeRatio = 0.5f,
        recede = 0f,
    ) {
        boxFlare("light") {
            style(BoxFlareStyle.SMOOTH)
            colors(0xA046F4c1, 0x9f6ed3c1)
            size(30f, 30f)
            glow(2.0f, 4f)
            flicker(0.2f)
            noise(0.1f)
        }
    }

    // 贯星之矛（规格 09 §3.1）：冷蓝白 ARC 主色内联字面量；width 36 / glowScale 4.0 大圆形弹体观感；
    // 带长 = 射程 ×85%（基线 260），recede 0（带体亮头直抵弹头），亮度 +25%。
    // 追加：BoxUtil 水平光斑 core（锚在弹体前端 = 螺栓头部）+ 弹头处 SMOOTH 光斑 light（50su 柔边球光）+
    // 原版 EMP 锚点电弧（发射点固定 → 弹体头部拉伸）+ 发射瞬间发射点扭曲（PiercingLanceVfx.spawnMuzzleDistortion）。
    private fun piercingLanceShot(range: Float?): ProjectileVfx = simpleProjectileVfx(
        "astd_piercing_lance_shot",
        ASTDColor(0.55f, 0.78f, 1f, 0.95f),
        width = 36f,
        length = 260f,
        glowScale = 4.0f,
        range = range,
        rangeRatio = 0.85f,
        brightness = 1.25f,
        recede = 0f,
    ) {
        boxFlare("core") {
            size(200f, 6f)
            colors(0xD0E8FFFF, 0x64B4FFBE)
            glow(1.6f, 4f)
            style(BoxFlareStyle.SMOOTH_DISC)
            fixedFacing(0f)
//            flicker(1.3f)
//            noise(0.4f)
        }
        boxFlare("light") {
            size(40f, 40f)
            colors(0xD0E8FFFF, 0x64B4FFBE)
            glow(0.2f, 4f)
            style(BoxFlareStyle.SMOOTH)
            fixedFacing(0f)
//            flicker(1.3f)
//            noise(0.4f)
        }
        anchorArc("arc") {
            thickness(10f)
            colors(fringe = 0x78BEFFC0L, core = 0xF0F8FFF0L)
        }
        onFire(ProjectileVfxOnFireHook { engine, projectile ->
            PiercingLanceVfx.spawnMuzzleDistortion(engine, Vector2f(projectile.location))
        })
    }

    // 摧锋鱼雷：simpleProjectileVfx 四层惯例 + 带长 = 射程 ×50% + recede 0（带体亮头直抵弹头）+
    // 弹头处 SMOOTH 光斑（light：柔边球光，补鱼雷本体贴图之外的辉光，ARC 冷蓝白调色）。
    private fun cuifengTorpedoShot(id: String, range: Float?): ProjectileVfx = simpleProjectileVfx(
        id,
        qiongjueBlue(),
        width = 10f,
        length = 420f,
        range = range,
        rangeRatio = 0.5f,
        recede = 0f,
    ) {
        boxFlare("light") {
            style(BoxFlareStyle.SMOOTH)
            colors(ASTDColor(0xD0E8FF).a(0.5f).hex(), ASTDColor(0x78BEFF).a(0.5f).hex())
            size(30f, 30f)
            glow(0.1f, 4f)
            noise(0.1f)
        }
    }

    // 源生冰晶 MIRV 母弹：simpleProjectileVfx 四层惯例（同辉星/摧锋口径），冰蓝白调色。
    private fun iceShardMirvShot(id: String, range: Float?): ProjectileVfx = simpleProjectileVfx(
        id,
        iceBlue(),
        width = 8f,
        length = 420f,
        range = range,
        rangeRatio = 0.5f,
        recede = 0f,
    ) {
        boxFlare("light") {
            style(BoxFlareStyle.SMOOTH)
            colors(ASTDColor(0xE0F4FFFF).a(0.5f).hex(), ASTDColor(0x9CD8FFc1).a(0.5f).hex())
            size(30f, 30f)
            glow(0.1f, 4f)
            noise(0.1f)
        }
    }

    // 源生冰晶族：冰蓝白（LENS 紫线中的冰晶冷色，全局美术约定新调色板由收口人添加）。
    private fun iceBlue() = ASTDColor(0.72f, 0.9f, 1f, 1f)

    // 正电子冲击波：冷蓝白系（全局美术约定「正电子用白色弹体与明亮拖尾」），分支内内联字面量。
    private fun positronWhiteBlue() = ASTDColor(0.62f, 0.82f, 1f, 1f)

    // 电驱加速炮：黄色弹体（美术裁定），分支内内联字面量。
    private fun electricYellow() = ASTDColor(1f, 0.82f, 0.25f, 1f)

    // 电驱加速炮拖尾尾色（自选 RGB）：金→红渐变的余烬红端（alpha 乘层透明度）。
    private fun emberRedTail() = ASTDColor(0.35f, 0.1f, 0.04f, 0.6f)

    // 穷距相位轨道炮：ARC 冷蓝白（与重离子脉冲同族；弹体弹头仍近白，拖尾读蓝色相）。
    private fun qiongjueBlue() = ASTDColor(0.55f, 0.78f, 1f, 1f)

    // 穷距拖尾尾色（自选 RGB）：蓝→淡绿渐变的淡绿端（alpha 乘层透明度）。
    private fun paleGreenTail() = ASTDColor(0.19f, 0.35f, 0.25f, 0.6f)

    // 调色板：颜色沿用旧管线数值（视觉已目检回归，不宜再动）。
    private fun violet() = ASTDColor(0.66f, 0.42f, 1f, 1f)

    // 重型离子脉冲：ARC 冷蓝白（全局美术约定，与电荷针刺同色系）。新共享调色板只允许收口人添加（00 §3），本组内联私有函数。
    private fun heavyIonPulseColor() = ASTDColor(0.55f, 0.78f, 1f, 1f)

    // 电荷针刺：ARC 冷蓝白（全局美术约定）。新共享调色板只允许收口人添加（00 §3），本组内联私有函数。
    private fun chargeNeedleColor() = ASTDColor(0.55f, 0.78f, 1f, 1f)

    private fun ASTDColor.a(alpha: Float): ASTDColor = copy(alpha = alpha.coerceIn(0f, 1f))

    private fun ASTDColor.hex(): Long {
        fun ch(v: Float): Long = (v.coerceIn(0f, 1f) * 255f).roundToInt().toLong()
        return (ch(red) shl 24) or (ch(green) shl 16) or (ch(blue) shl 8) or ch(alpha)
    }
}

internal const val TEX_TWIN = "graphics/fx/astd_trails_twin.png"
internal const val TEX_SMOOTH = "graphics/fx/astd_trails_smooth.png"
internal const val TEX_ZAPPY = "graphics/fx/astd_trails_zappy.png"

// —— 拖尾混合常量（美术裁定，公式守护测试锚点）——

/** 带宽总倍率（宽度翻倍裁定）：外带全宽 = [bandWidth] × 本值。 */
internal const val BAND_WIDTH_MULT = 2f

/** 核心层（smooth）宽度比：外带 ×0.5。 */
internal const val CORE_WIDTH_RATIO = 0.5f

// —— 观感翻译公式（内部纯函数，供 simpleProjectileVfx 与公式守护测试共用）——

/** 0.5 粒度取整。 */
internal fun round05(v: Float): Float = (v * 2f).roundToInt() / 2f

/** 5 粒度取整。 */
internal fun round5(v: Float): Float = (v / 5f).roundToInt() * 5f

/** 贴图拖尾外带基准全宽：0.35×旧宽 锚 aod7 的 96→30；3.15×glowScale 锚旧 glow 视觉全宽（widthBase 3.5 × g）。 */
internal fun bandWidth(width: Float, glowScale: Float): Float = round05(max(0.35f * width, 3.15f * glowScale))

/** zappy 装饰带宽度：0.6×外带。 */
internal fun arcWidth(bandW: Float): Float = round05(bandW * 0.6f)

/** 两段上色的头部色：mix(主色, 白, 0.45)，alpha = 主色 alpha×0.78×[alphaScale]（保持主色饱和——0.78 白混合会把琥珀等暖色洗成近白，与弹头光晕产生色差接缝）。 */
internal fun bandHeadColor(color: ASTDColor, alphaScale: Float = 1f): ASTDColor =
    mixWhite(color, 0.45f).copy(alpha = (color.alpha * 0.78f * alphaScale).coerceIn(0f, 1f))

/** 两段上色的尾部色：[tail] 缺省 = 主色 rgb×0.16、alpha 0.07×[alphaScale]（对标 0x0A1C3810）；
 * [tail] 非空 = 自选尾色直取（rgb 原样，alpha = 尾色 alpha × [alphaScale]），头色 → 尾色两段渐变。 */
internal fun bandTailColor(color: ASTDColor, alphaScale: Float = 1f, tail: ASTDColor? = null): ASTDColor {
    if (tail != null) {
        return ASTDColor(tail.red, tail.green, tail.blue, (tail.alpha * alphaScale).coerceIn(0f, 1f))
    }
    return ASTDColor(
        color.red * 0.16f, color.green * 0.16f, color.blue * 0.16f, (0.07f * alphaScale).coerceIn(0f, 1f),
    )
}

/** 外带/核心平铺周期：L/2.4（aod7 的 420→140~200 区间居中）。 */
internal fun mainTile(length: Float): Float = round5(length / 2.4f)

/** 外带/核心滚动速度：L/6。 */
internal fun mainScroll(length: Float): Float = round5(length / 6f)

/** 装饰带平铺周期：L/2（更快更密的电弧）。 */
internal fun arcTile(length: Float): Float = round5(length / 2f)

/** 装饰带滚动速度：L/4.5。 */
internal fun arcScroll(length: Float): Float = round5(length / 4.5f)

/** Box 螺栓弹头染色：mix(主色, 白, 0.7)，alpha 0.78（原版 coreColor 近白口径；弹头只染单色系，
 * 武器主色由拖尾带体承担——原版 fringeColor 属 projtrail 外带语义）。 */
internal fun boltColor(color: ASTDColor): ASTDColor = mixWhite(color, 0.7f).copy(alpha = 0.78f)

private fun mixWhite(color: ASTDColor, t: Float): ASTDColor = ASTDColor(
    color.red + (1f - color.red) * t,
    color.green + (1f - color.green) * t,
    color.blue + (1f - color.blue) * t,
    color.alpha,
)

/** ASTDColor → java.awt.Color（ConeImpactVfxSpec 等 awt 调色入口）。 */
private fun awt(color: ASTDColor): Color = Color(
    color.red.coerceIn(0f, 1f),
    color.green.coerceIn(0f, 1f),
    color.blue.coerceIn(0f, 1f),
    color.alpha.coerceIn(0f, 1f),
)
