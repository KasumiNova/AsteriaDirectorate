package cn.kasuminova.astd.combat.effect.arc.heavyionpulse

import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 彗星冲击波（原重型离子脉冲）的泄放/贯穿视觉与结算静态入口（规格 02 §2.3 / §3.2，对齐 `StrikeSprayVfx` 惯例）。
 *
 * - 泄放：`spawnEmpArc` 真实电弧（伤害/视觉/结算一体），冷蓝白双色参数化，
 *   thickness 24f（略粗于电荷针刺的 20f，匹配大槽体量）；
 *   电弧起点 = 弹体命中点（2026-09 用户裁定：与电针/原版离子脉冲口径一致；
 *   原「随机武器/引擎部位作起点」方案因电弧与弹着点脱节被废止）。
 * - 贯穿补伤：`applyDamage`（不触发 onHitEffect，无二次 onHit 回环）+ 伤害浮字 + 克制火花 1 粒。
 *
 * 不新增 RenderEntity 组件；弹体拖尾走 `ProjectileVfxSpecs` Static Trail 管线（与本类无关）。
 *
 * 设计取舍登记（规格 02 §2.4）：泄放**不加浮字**——v2 31.25% × 2.67 发/s ≈ 0.8 次/s，
 * 电弧本身即最强反馈（沿用 01 取舍口径）；贯穿补伤频率天然极低（破晓敌版限定、仅对高 EMP
 * 抗性目标触发），浮字+火花满足「不得有机制无反馈」。
 */
object HeavyIonPulseVfx {

    /** 泄放/贯穿色（冷蓝白，与弹体调色板同族）。 */
    private val ARC_FRINGE = Color(140, 200, 255)
    private val ARC_CORE = Color(225, 242, 255)

    /** 泄放电弧音效（`spawnEmpArc` 的 soundId = null 时原版不播音效——01 已核实，显式指定同口径）。 */
    private const val ARC_SOUND_ID = "shock_repeater_emp_impact"

    /** HUD/浮字反馈通道（机制可视化铁律的统一落点）。 */
    private val feedback: CombatFeedback = CombatFeedbackImpl

    // —— dev 自动化烟测遥测键（engine.customData，对齐 ChargeNeedleVfx/EDA 先例）——

    /** 船体/装甲命中计数（攻击方=玩家 / 其他）。 */
    const val TELEMETRY_HULL_HITS_PLAYER = "astd_hip_hull_hits_player"
    const val TELEMETRY_HULL_HITS_OTHER = "astd_hip_hull_hits_other"

    /** 泄放计数（攻击方=玩家 / 其他）。 */
    const val TELEMETRY_DISCHARGE_PLAYER = "astd_hip_discharge_count_player"
    const val TELEMETRY_DISCHARGE_OTHER = "astd_hip_discharge_count_other"

    /** 贯穿补伤计数（攻击方=玩家 / 其他）与最近一次结算参数（§2.5 待验证项证据）。 */
    const val TELEMETRY_PIERCE_PLAYER = "astd_hip_pierce_count_player"
    const val TELEMETRY_PIERCE_OTHER = "astd_hip_pierce_count_other"
    const val TELEMETRY_PIERCE_LAST_EXTRA = "astd_hip_pierce_last_extra"
    const val TELEMETRY_PIERCE_LAST_APPLIED = "astd_hip_pierce_last_applied"
    const val TELEMETRY_PIERCE_LAST_MULT = "astd_hip_pierce_last_mult"
    const val TELEMETRY_PIERCE_LAST_BASE_EMP = "astd_hip_pierce_last_base_emp"
    const val TELEMETRY_PIERCE_LAST_ARC_EMP = "astd_hip_pierce_last_arc_emp"
    const val TELEMETRY_PIERCE_SUM_EXTRA = "astd_hip_pierce_sum_extra"

    /**
     * 泄放：以弹体命中点为电弧起点向目标舰释放真实 EMP 电弧（伤害 0、EMP 按难度倍率折算）。
     * [source] 允许 null（游离弹由原版兜底归功）。
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
            24f,
            ARC_FRINGE,
            ARC_CORE,
        )
        increment(engine, if (source?.owner == 0) TELEMETRY_DISCHARGE_PLAYER else TELEMETRY_DISCHARGE_OTHER)
    }

    /**
     * EMP 贯穿补伤：对高 EMP 抗性目标追加 EMP（走 `applyDamage`，无二次 onHit 回环），
     * 同帧触发伤害浮字 + 克制火花（00 §4.2 反馈铁律落点）。
     *
     * A9 裁定方案 a（2026-07-29）：`applyDamage` 的 empDamage 会被目标 `empDamageTakenMult`
     * 再乘一次，故 [applied] 为折算补偿量（extra/max(mult, 0.01)），引擎二次乘算后实际结算
     * 回补到 [extra]；浮字显示 [extra]——显示值 = 实际结算量（mult ≥ 0.01 时精确）。
     */
    fun pierce(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        point: Vector2f,
        extra: Float,
        applied: Float,
        source: ShipAPI?,
        mult: Float,
        baseEmp: Float,
        arcEmp: Float
    ) {
        engine.applyDamage(ship, point, 0f, DamageType.ENERGY, applied, false, false, source)
        feedback.floatingDamage(engine, point, extra, ARC_CORE, ship, source)
        engine.addHitParticle(point, Vector2f(), 30f, 1f, 0.2f, ARC_CORE)

        val playerCaused = source?.owner == 0
        increment(engine, if (playerCaused) TELEMETRY_PIERCE_PLAYER else TELEMETRY_PIERCE_OTHER)
        engine.customData[TELEMETRY_PIERCE_LAST_EXTRA] = extra
        engine.customData[TELEMETRY_PIERCE_LAST_APPLIED] = applied
        engine.customData[TELEMETRY_PIERCE_LAST_MULT] = mult
        engine.customData[TELEMETRY_PIERCE_LAST_BASE_EMP] = baseEmp
        engine.customData[TELEMETRY_PIERCE_LAST_ARC_EMP] = arcEmp
        engine.customData[TELEMETRY_PIERCE_SUM_EXTRA] = ((engine.customData[TELEMETRY_PIERCE_SUM_EXTRA] as? Float) ?: 0f) + extra
    }

    /** 船体/装甲命中遥测（dev 自动化烟测证据计数，按攻击方归属分键）。 */
    fun recordHullHit(engine: CombatEngineAPI, source: ShipAPI?) {
        increment(engine, if (source?.owner == 0) TELEMETRY_HULL_HITS_PLAYER else TELEMETRY_HULL_HITS_OTHER)
    }

    /** 读取遥测计数（缺省 0）。 */
    fun telemetryCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

    /** 读取遥测浮点（缺省 0）。 */
    fun telemetryFloat(engine: CombatEngineAPI, key: String): Float = engine.customData[key] as? Float ?: 0f

    private fun increment(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }
}
