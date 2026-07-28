package cn.kasuminova.astd.combat.effect.arc

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 电荷针刺的命中/泄放视觉静态入口（规格 01 §3.2，对齐 `ImpactStrikeFx` 惯例）。
 *
 * - 护盾命中：少量冷蓝白 hitParticle（克制量级，每发最多 2 粒，20 发/s 不糊屏）；
 * - 电荷泄放：`spawnEmpArc` 真实电弧（伤害/视觉/结算一体），冷蓝白双色参数化。
 *
 * 不新增 RenderEntity 组件；弹体拖尾走 `ProjectileVfxSpecs` texTrail 管线（与本类无关）。
 *
 * 设计取舍登记（规格 01 §2.4）：泄放**不加浮字**——v2 40% × 20 发/s ≈ 8 次/s，浮字必然糊屏，
 * 电弧本身即最强反馈。泄放落点由原版 `spawnEmpArc` 在目标舰上自行选取
 * （API 无落点指定入口，`EmpArcParams` 亦不含落点字段，jar 已核实）；
 * 「打击武器与引擎」由 EMP 结算自身的瘫痪机制表达。
 */
object ChargeNeedleVfx {

    /** 护盾命中粒子色（冷蓝白，与弹体调色板同族）。 */
    private val SHIELD_HIT_COLOR = Color(140, 200, 255, 195)

    /** 泄放电弧外缘色（冷蓝）。 */
    private val ARC_FRINGE = Color(140, 200, 255)

    /** 泄放电弧核心色（蓝白）。 */
    private val ARC_CORE = Color(225, 242, 255)

    /** 泄放电弧音效（速射 EMP 武器口径；`spawnEmpArc` 的 soundId = null 时原版不播音效，已核实改为显式指定）。 */
    private const val ARC_SOUND_ID = "shock_repeater_emp_impact"

    /** 泄放计数在 engine.customData 上的遥测键（automation 场景读取）。 */
    const val TELEMETRY_DISCHARGE_COUNT = "astd_charge_needle_discharge_count"

    /**
     * 护盾命中轻粒子：一发 hitParticle + 一发 smoothParticle（量级对齐 HighFluxShieldPressure 克制档）。
     */
    fun shieldHitParticles(engine: CombatEngineAPI, point: Vector2f, ship: ShipAPI) {
        val vel = ship.velocity?.let { Vector2f(it) } ?: Vector2f()
        engine.addHitParticle(point, vel, 35f, 1f, 0.18f, SHIELD_HIT_COLOR)
        engine.addSmoothParticle(point, vel, 55f, 0.65f, 0.25f, SHIELD_HIT_COLOR)
    }

    /**
     * 电荷泄放：从命中点向目标舰释放真实 EMP 电弧（伤害 0、EMP 按难度倍率折算）。
     * 锚定实体为目标舰本体，保证电弧追踪；[source] 允许 null（游离弹由原版兜底归功）。
     */
    fun discharge(engine: CombatEngineAPI, source: ShipAPI?, from: Vector2f, target: ShipAPI, emp: Float) {
        engine.spawnEmpArc(
            source,
            from,
            target,
            target,
            DamageType.ENERGY,
            0f,
            emp,
            1000f,
            ARC_SOUND_ID,
            20f,
            ARC_FRINGE,
            ARC_CORE,
        )
        engine.customData[TELEMETRY_DISCHARGE_COUNT] = dischargeCount(engine) + 1
    }

    /** 本场战斗累计泄放次数（dev 自动化烟测证据计数，对齐 ASTDArcProductionVfx 遥测先例）。 */
    fun dischargeCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_DISCHARGE_COUNT] as? Int ?: 0
}
