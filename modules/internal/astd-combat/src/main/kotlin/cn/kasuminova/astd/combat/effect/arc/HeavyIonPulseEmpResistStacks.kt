package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.api.buff.StackDecayMode
import cn.kasuminova.astd.api.buff.StackableBuff
import cn.kasuminova.astd.api.buff.getBuff
import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.math.floor

/**
 * 单艘目标舰的 EMP 抗性削减层数（彗星冲击波船体/装甲命中机制的状态承载，规格 02 §2.3 修订）。
 *
 * 动机：每次船体/装甲命中叠 1 层，每层按难度查表绝对降低目标 EMP 抗性
 * （`empDamageTakenMult` 绝对位移 +stacks × perStack）；最多 [HeavyIonPulseTuning.RESIST_MAX_STACKS] 层，
 * 每秒消散 [HeavyIonPulseTuning.RESIST_DECAY_PER_SECOND] 层（固定不缩放）。
 *
 * 隐藏机制（不在武器描述展示）：抗性降低值超出目标当前抗性部分自然转化为 EMP 易伤——
 * 位移语义下 `empDamageTakenMult` 可越过 1.0（如目标抗性 50% 即 mult 0.5，削减 100% 即 +1.0 位移，
 * 终值 1.5 = +50% EMP 承伤），无需特判分支。
 *
 * 0 值防线：目标 `empDamageTakenMult` ≤ 0（完全 EMP 免疫，0 乘区无法被乘算突破）时
 * 本机制不产生效果——层数照常累积/消散但不写 stat（与 EMP 贯穿 mult ≤ 0 整体跳过同一口径，文档化）。
 *
 * 生命周期：Ship 级 [BuffLifetime.HOST_BOUND]，经 `ShipAPI.buffHost()` 注册（id [BUFF_ID]）；
 * 宿主 hulk/死亡由 BuffTickPlugin 心跳回收，[onRemove] 恰一次 unmodify，无 stat 残留。
 *
 * 玩家可见反馈（机制可视化铁律）：
 * - 攻击方为玩家船时，左侧状态栏显示目标削减层数与抗性 −％（negative=false）；
 * - 受击方为玩家船时，独立键显示本舰被削减的抗性 −％（negative=true）。
 */
class HeavyIonPulseEmpResistStacks(
    /** 宿主舰（创建时捕获）。 */
    private val ship: ShipAPI,
    /** 战斗引擎（创建时捕获；HUD 与实体有效性查询）。 */
    private val engine: CombatEngineAPI,
    /** 所属 BuffHost（创建时捕获；层数归零时自行移除）。 */
    private val host: BuffHost,
) : StackableBuff {

    /** 每层 EMP 抗性削减：由 OnHit 每次命中按难度覆写（多攻击者时后命中者口径覆盖，对齐 01 已文档化口径）。 */
    var perStack: Float = HeavyIonPulseTuning.EMP_RESIST_PER_STACK.v2

    /** 攻击方为玩家船时置 true：在玩家 HUD 显示目标削减状态。 */
    var showOnPlayerHud: Boolean = false

    /** 浮点累加器：层数视图取 floor，亚层余量参与连续消散。 */
    private var stacksFloat: Float = 0f

    override val id: String get() = BUFF_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND
    override val decayMode: StackDecayMode get() = StackDecayMode.CONTINUOUS

    override val stacks: Int get() = floor(stacksFloat).toInt()
    override val maxStacks: Int get() = HeavyIonPulseTuning.RESIST_MAX_STACKS

    override fun addStacks(n: Int): Int {
        val before = stacksFloat
        stacksFloat = (stacksFloat + n).coerceIn(0f, maxStacks.toFloat())
        return (stacksFloat - before).toInt()
    }

    override fun advance(amount: Float) {
        stacksFloat = (stacksFloat - HeavyIonPulseTuning.RESIST_DECAY_PER_SECOND * amount).coerceAtLeast(0f)
        if (stacksFloat <= 0f) {
            host.remove(this)
            return
        }

        refreshEmpResist()
        maintainHud()
    }

    override fun isHostValid(): Boolean = ship.isAlive && !ship.isHulk && engine.isEntityInPlay(ship)

    override fun onRemove() {
        ship.mutableStats.empDamageTakenMult.unmodifyMult(BUFF_ID)
    }

    /**
     * EMP 抗性绝对位移幂等刷新：先 unmodify 读他源终值，再按 `+stacks × perStack` 反推乘区系数写回。
     * modifierId 固定，每帧重写不叠乘；他源终值 ≤ 0（完全免疫）时不写入（0 乘区不可突破）。
     */
    private fun refreshEmpResist() {
        val stat = ship.mutableStats.empDamageTakenMult
        stat.unmodifyMult(BUFF_ID)
        val current = stat.modifiedValue
        if (current <= 0f) return
        val shifted = current + stacks * perStack
        stat.modifyMult(BUFF_ID, shifted / current)
    }

    /** HUD 双向维护：攻击方=玩家显示目标层数；受击方=玩家显示本舰被削减的抗性。 */
    private fun maintainHud() {
        val player = engine.playerShip
        val pctText = formatPercent(stacks * perStack * 100f)
        if (showOnPlayerHud && player != null) {
            feedback.maintainPlayerStatus(
                engine, HUD_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.heavy_ion_pulse.status.title"],
                I18n.t(I18n.Categories.MOD, "ui.heavy_ion_pulse.status.desc", "stacks" to stacks, "percent" to pctText),
                negative = false,
            )
        }
        if (ship == player) {
            feedback.maintainPlayerStatus(
                engine, HUD_VICTIM_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.heavy_ion_pulse.status.victim_title"],
                I18n.t(I18n.Categories.MOD, "ui.heavy_ion_pulse.status.victim_desc", "stacks" to stacks, "percent" to pctText),
                negative = true,
            )
        }
    }

    companion object {
        /** Ship 级 Buff 登记 id（同时充当 customData 键段与 stat modifierId）。 */
        const val BUFF_ID = "astd_heavy_ion_pulse_emp_resist"

        /** 攻击方视角 HUD 状态键。 */
        private const val HUD_KEY = "astd_heavy_ion_pulse_emp_resist_status"

        /** 受击方视角 HUD 状态键。 */
        private const val HUD_VICTIM_KEY = "astd_heavy_ion_pulse_emp_resist_victim_status"

        /** HUD 图标（ARC 回路接口船插图，复用现成美术，对齐 01 电荷淤积口径）。 */
        private const val HUD_ICON = "graphics/hullmods/astd_arc_loop_interface.png"

        /** HUD/浮字反馈通道（机制可视化铁律的统一落点）。 */
        private val feedback: CombatFeedback = CombatFeedbackImpl

        /** 百分比显示格式：整数去小数点，否则保留 1 位（如 100 / 2.5）。 */
        internal fun formatPercent(value: Float): String {
            val rounded = kotlin.math.round(value * 10f) / 10f
            return if (rounded == floor(rounded)) rounded.toInt().toString() else rounded.toString()
        }
    }
}

/** 便捷扩展：取该船的 EMP 抗性削减 Buff（不存在返回 null）。一行入口不沉淀进公共 API（00 §1.3 约定）。 */
fun ShipAPI.heavyIonPulseEmpResistStacks(): HeavyIonPulseEmpResistStacks? =
    getBuff(HeavyIonPulseEmpResistStacks.BUFF_ID) as? HeavyIonPulseEmpResistStacks
