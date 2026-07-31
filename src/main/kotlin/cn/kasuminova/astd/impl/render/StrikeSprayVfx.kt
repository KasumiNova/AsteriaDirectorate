package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderEntity
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 冲击刺束喷散特效入口（计划 00-锥面冲击特效重做计划 §10.9 v4.1：吞并 `ImpactStrikeFx` 的
 * spray/smoke 职能，渲染本体收敛为 [StrikeSprayComponent]——可挂在锥面树内作子节点，
 * 也可经 [spawnSpray] 单独建一棵一次性 RenderEntity 树）。
 *
 * 与原 `ImpactStrikeFx` 的差异（v4.1 架构层，参数逐值平移 v2.2 不改一个数字）：
 * - EveryFrameCombatPlugin 驱动层弃用：针位置由组件 advanceSelf 显式积分 `pos += vel×dt`，
 *   vel ∥ facing 由构造保证（治「概率侧飞」——旧两层驱动：插件推 loc 与 BoxUtil 自管理状态互撞）；
 * - 三级兜底链（TrailEntity→SpriteEntity→vanilla 粒子）整体退役：只留 TrailEntity 主路径，
 *   BoxUtil 不可用记 WARN 缺席本层（全局规范禁兜底）；
 * - intensityMult 折叠：两个真实调用方（锥面/aod7）恒传 1f，vis 派生全部按 1 化简。
 */
object StrikeSprayVfx {
    private val log = Global.getLogger(StrikeSprayVfx::class.java)

    /** 驱动 TTL 下限（秒）：针最长寿命 introRamp + full 上限 + fadeOut 上限 + 收尾余量。 */
    private const val MIN_DRIVER_TTL = 0.70f

    /** 树收尾余量（秒）：最迟激活的针也要能被驱到寿命尽头。 */
    private const val DRIVER_TAIL_MARGIN = 0.15f

    /**
     * 冲击喷散朝向（自 `ImpactStrikeFx.ImpactFacingMode` 迁移）：
     * - [OUTWARD]：朝「远离敌人/命中点外侧」喷散，约定为来袭方向 +180°；
     * - [INWARD]：朝「指向敌人/命中点内侧」喷散，约定为沿来袭方向。
     */
    enum class FacingMode {
        OUTWARD,
        INWARD,
    }

    /** 冲击烟雾参数组（自 `ImpactStrikeFx.ImpactSmokeStyle` 逐字迁移；vanilla 星云粒子自管理）。 */
    data class SmokeStyle(
        val puffCountBase: Int = 6,
        val puffCountExtra: Int = 4,
        val spreadArc: Float = 28f,
        val sizeMin: Float = 60f,
        val sizeMax: Float = 120f,
        val speedMin: Float = 80f,
        val speedMax: Float = 180f,
        val durationMin: Float = 0.45f,
        val durationMax: Float = 0.85f,
        val endSizeMult: Float = 1.35f,
    )

    /**
     * 刺束显式参数组（自 `ImpactStrikeFx.ImpactSprayStyle` 逐字迁移，默认值为原重量级档）：
     * 针长/宽/速度直接取随机区间（×impactScale ×内部塑形系数）；锥面 v2.2 档与 aod7 命中轻量档
     * 均经此组逐字传参。
     */
    data class SprayStyle(
        val baseRaysMin: Int = 22,
        val baseRaysExtra: Int = 9,
        val arc: Float = 65f,
        val lengthMin: Float = 140f,
        val lengthMax: Float = 310f,
        val widthMin: Float = 12.0f,
        val widthMax: Float = 24.0f,
        val fullMin: Float = 0.06f,
        val fullMax: Float = 0.12f,
        val fadeOutMin: Float = 0.44f,
        val fadeOutMax: Float = 0.64f,
        val speedMin: Float = 240f,
        val speedMax: Float = 560f,
        val impactScale: Float = 1f,
        val introRampSeconds: Float = 0.07f,
        /**
         * 精确针数（§10.9 v4.4 光锥数量动态化）：非空时优先于 [baseRaysMin]/[baseRaysExtra]
         * 固定域——由调用方按张角推导好总数传入（锥面冲击：每 10° 张角 2~3 根）；null 走原
         * 固定域随机（aod7 显式档零影响）。仅数量动态化，渲染细节零改动。
         */
        val exactRays: Int? = null,
    )

    /** 刺束喷散规格：针参数全部取自 [style] 随机区间。 */
    data class StrikeSpraySpec(
        /** 喷散原点（世界坐标，su）。 */
        val origin: Vector2f,

        /** 喷散中轴朝向（度，世界坐标系）。 */
        val facingDeg: Float,

        /** 喷散张角（度）：针朝向 = facingDeg ± arcDeg/2 随机。 */
        val arcDeg: Float,

        /** 针核心色（diffuse 着色）。 */
        val coreColor: Color,

        /** 针辉光色（emissive 着色，接原生泛光）。 */
        val fringeColor: Color,

        /** 显式参数组（v2.2 锥面档 / aod7 轻量档均经此逐字传参）。 */
        val style: SprayStyle,
    )

    /**
     * 只发刺束喷散（无烟）：建一棵一次性 RenderEntity 树（[StrikeSprayComponent] 为根），
     * 交 [OneShotVfxPlugin] 逐帧推进。入参非法记 WARN 返回 null，不产出半成品特效。
     */
    fun spawnSpray(engine: CombatEngineAPI, spec: StrikeSpraySpec): OneShotVfxPlugin? {
        if (spec.arcDeg.isNaN() || spec.arcDeg <= 0f) {
            log.warn("刺束喷散 arcDeg 非正（${spec.arcDeg}），属配置错误，本次不生成特效")
            return null
        }
        val style = spec.style
        if (style.lengthMax <= 0f || style.lengthMin <= 0f || style.widthMin <= 0f || style.speedMin <= 0f) {
            log.warn("刺束喷散显式参数组存在非正区间端点（$style），属配置错误，本次不生成特效")
            return null
        }

        val tree: RenderEntity = StrikeSprayComponent(
            id = "strike_spray@" + System.identityHashCode(spec),
            spec = spec,
        )
        val host = PointHost(
            hostId = "spray@" + System.identityHashCode(tree),
            origin = Vector2f(spec.origin),
            facingDeg = spec.facingDeg,
        )
        // 驱动寿命 = 最迟激活时刻 + 针最长寿命 + 收尾余量：针由组件逐帧积分，树提前收尾会把还在飞的针整批掐掉。
        val ttl = (style.introRampSeconds + style.fullMax + style.fadeOutMax + DRIVER_TAIL_MARGIN).coerceAtLeast(MIN_DRIVER_TTL)
        val plugin = OneShotVfxPlugin(engine, host, tree, ttl)
        engine.addPlugin(plugin)
        return plugin
    }

    /**
     * 命中烟雾（自 `ImpactStrikeFx.spawnImpactSmoke` 移植；intensityMult 两调用方恒 1f 已折叠）：
     * 同色星云粒子沿冲击方向喷出，vanilla 粒子自管理，不依赖任何树存活。
     */
    fun spawnSmoke(
        engine: CombatEngineAPI,
        point: Vector2f,
        facingDeg: Float,
        smokeColor: Color,
        style: SmokeStyle = SmokeStyle(),
    ) {
        val puffs = (style.puffCountBase + MathUtils.getRandomNumberInRange(0, style.puffCountExtra)).coerceAtLeast(1)
        for (i in 0 until puffs) {
            val ang = facingDeg + MathUtils.getRandomNumberInRange(-style.spreadArc * 0.5f, style.spreadArc * 0.5f)
            val v = MathUtils.getPointOnCircumference(null, MathUtils.getRandomNumberInRange(style.speedMin, style.speedMax), ang)
            val loc = MathUtils.getRandomPointInCircle(point, 8f)
            engine.addNebulaParticle(
                loc,
                v,
                MathUtils.getRandomNumberInRange(style.sizeMin, style.sizeMax),
                style.endSizeMult,
                0.08f,
                0.28f,
                MathUtils.getRandomNumberInRange(style.durationMin, style.durationMax),
                smokeColor,
                true,
            )
        }
    }

    /**
     * 通用冲击反馈（自 `ImpactStrikeFx.spawnImpactFx` 移植；intensityMult 恒 1f 已折叠）：
     * 同轨迹烟雾 + 刺束喷散。
     *
     * @param towardTargetFacing 建议传弹体朝向/速度朝向（即「来袭方向/朝向敌人的方向」）。
     * @param facingMode 选择喷散方向（朝向敌人 [FacingMode.INWARD] / 远离敌人 [FacingMode.OUTWARD]）。
     */
    fun spawnImpactFx(
        engine: CombatEngineAPI,
        point: Vector2f,
        towardTargetFacing: Float,
        facingMode: FacingMode,
        smokeColor: Color,
        coreColor: Color,
        fringeColor: Color,
        sprayStyle: SprayStyle = SprayStyle(),
        smokeStyle: SmokeStyle = SmokeStyle(),
    ): OneShotVfxPlugin? {
        val facing = computeImpactFxFacing(towardTargetFacing, facingMode)
        spawnSmoke(engine, point, facing, smokeColor, smokeStyle)
        return spawnSpray(
            engine,
            StrikeSpraySpec(
                origin = Vector2f(point),
                facingDeg = facing,
                arcDeg = sprayStyle.arc,
                coreColor = coreColor,
                fringeColor = fringeColor,
                style = sprayStyle,
            ),
        )
    }

    /** 喷散朝向结算（自 `ImpactStrikeFx.computeImpactFxFacing` 逐字迁移）。 */
    private fun computeImpactFxFacing(towardTargetFacing: Float, mode: FacingMode): Float {
        val f = ((towardTargetFacing % 360f) + 360f) % 360f
        return when (mode) {
            FacingMode.OUTWARD -> (f + 180f) % 360f
            FacingMode.INWARD -> f
        }
    }
}
