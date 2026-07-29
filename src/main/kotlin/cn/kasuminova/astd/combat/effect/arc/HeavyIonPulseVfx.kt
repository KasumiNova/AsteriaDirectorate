package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 重型离子脉冲的泄放/贯穿视觉与结算静态入口（规格 02 §2.3 / §3.2，对齐 `ImpactStrikeFx` 惯例）。
 *
 * - 泄放：`spawnEmpArc` 真实电弧（伤害/视觉/结算一体），冷蓝白双色参数化，
 *   thickness 24f（略粗于电荷针刺的 20f，匹配大槽体量）；
 *   电弧起点选取目标舰随机一件非装饰武器的位置（Misc.random——纯视觉选取，00 §4.1 允许），
 *   无武器时退引擎挂载点，再无退命中点/舰体中心——呈现「电弧打击武器/引擎部位」的设计案性格
 *   （01 已核实 API 无落点指定入口，电弧终点仍由原版在目标舰上自行选取）。
 * - 贯穿补伤：`applyDamage`（不触发 onHitEffect，无二次 onHit 回环）+ 伤害浮字 + 克制火花 1 粒。
 *
 * 不新增 RenderEntity 组件；弹体拖尾走 `ProjectileVfxSpecs` texTrail 管线（与本类无关）。
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
    const val TELEMETRY_PIERCE_LAST_MULT = "astd_hip_pierce_last_mult"
    const val TELEMETRY_PIERCE_LAST_BASE_EMP = "astd_hip_pierce_last_base_emp"
    const val TELEMETRY_PIERCE_LAST_ARC_EMP = "astd_hip_pierce_last_arc_emp"
    const val TELEMETRY_PIERCE_SUM_EXTRA = "astd_hip_pierce_sum_extra"

    /**
     * 泄放：从目标舰随机武器/引擎部位向目标舰释放真实 EMP 电弧（伤害 0、EMP 按难度倍率折算）。
     * [from] 为弹体命中点（落点选取的最终兜底）；[source] 允许 null（游离弹由原版兜底归功）。
     */
    fun discharge(engine: CombatEngineAPI, source: ShipAPI?, from: Vector2f, target: ShipAPI, emp: Float) {
        val arcOrigin = selectArcOrigin(target, from)
        engine.spawnEmpArc(
            source,
            arcOrigin,
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
     * EMP 贯穿补伤：对高 EMP 抗性目标追加 [extra] 等额 EMP（走 `applyDamage`，无二次 onHit 回环），
     * 同帧触发伤害浮字 + 克制火花（00 §4.2 反馈铁律落点）。
     */
    fun pierce(engine: CombatEngineAPI, ship: ShipAPI, point: Vector2f, extra: Float, source: ShipAPI?, mult: Float, baseEmp: Float, arcEmp: Float) {
        engine.applyDamage(ship, point, 0f, DamageType.ENERGY, extra, false, false, source)
        feedback.floatingDamage(engine, point, extra, ARC_CORE, ship, source)
        engine.addHitParticle(point, Vector2f(), 30f, 1f, 0.2f, ARC_CORE)

        val playerCaused = source?.owner == 0
        increment(engine, if (playerCaused) TELEMETRY_PIERCE_PLAYER else TELEMETRY_PIERCE_OTHER)
        engine.customData[TELEMETRY_PIERCE_LAST_EXTRA] = extra
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

    /** 泄放电弧起点选取：随机一件非装饰武器的位置（纯视觉选取，Misc.random 一次性随机）；
     * 无武器退引擎挂载点，再无退命中点，最终退舰体中心。 */
    private fun selectArcOrigin(target: ShipAPI, hitPoint: Vector2f): Vector2f {
        val weapons = target.allWeapons?.filter { !it.isDecorative } ?: emptyList()
        if (weapons.isNotEmpty()) return Vector2f(weapons[Misc.random.nextInt(weapons.size)].location)

        val engines = target.engineController?.shipEngines?.filter { !it.isPermanentlyDisabled } ?: emptyList()
        if (engines.isNotEmpty()) return Vector2f(engines[Misc.random.nextInt(engines.size)].location)

        return Vector2f(target.location ?: hitPoint)
    }

    private fun increment(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }
}
