package cn.kasuminova.astd.combat.effect.arc.starfallecho

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
import kotlin.math.round

/**
 * 单艘目标舰的结构谐振层数（坠星残响前 4 发命中机制的状态承载）。
 *
 * 动机：普通弹命中（护盾/船体均可，设计案未区分）叠 1 层，每层按难度查表提升目标
 * 装甲与结构易伤（`armorDamageTakenMult` / `hullDamageTakenMult` 乘区 +stacks × perStack），
 * 最多 [StarfallEchoTuning.RESONANCE_MAX_STACKS] 层。层数不随时间消散——
 * 只被第 5 发命中消耗（[consume]）或随宿主死亡回收。
 *
 * 生命周期：Ship 级 [BuffLifetime.HOST_BOUND]，经 `ShipAPI.buffHost()` 注册（id [BUFF_ID]）；
 * 宿主 hulk/死亡由 BuffTickPlugin 心跳回收，[onRemove] 恰一次 unmodify，无 stat 残留。
 *
 * 玩家可见反馈（机制可视化铁律）：
 * - 攻击方为玩家船时，左侧状态栏显示目标谐振层数与易伤 +％（negative=false）；
 * - 受击方为玩家船时，独立键显示本舰被附加的易伤 +％（negative=true）。
 */
class StarfallEchoResonanceStacks(
    /** 宿主舰（创建时捕获）。 */
    private val ship: ShipAPI,
    /** 战斗引擎（创建时捕获；HUD 与实体有效性查询）。 */
    private val engine: CombatEngineAPI,
    /** 所属 BuffHost（创建时捕获；层数被消耗归零时自行移除）。 */
    private val host: BuffHost,
) : StackableBuff {

    /** 每层易伤：由 OnHit 每次命中按难度覆写（多攻击者时后命中者口径覆盖，对齐彗星冲击波已文档化口径）。 */
    var perStack: Float = StarfallEchoTuning.RESONANCE_VULN_PER_STACK.v2

    /** 攻击方为玩家船时置 true：在玩家 HUD 显示目标谐振状态。 */
    var showOnPlayerHud: Boolean = false

    private var stacksInt: Int = 0

    override val id: String get() = BUFF_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND

    /** 层不随时间消散（消散语义不存在）；advance 仅做 HUD 维护。 */
    override val decayMode: StackDecayMode get() = StackDecayMode.CONTINUOUS

    override val stacks: Int get() = stacksInt
    override val maxStacks: Int get() = StarfallEchoTuning.RESONANCE_MAX_STACKS

    override fun addStacks(n: Int): Int {
        val before = stacksInt
        stacksInt = (stacksInt + n).coerceIn(0, maxStacks)
        if (stacksInt != before) refreshVulnerability()
        return stacksInt - before
    }

    /**
     * 第 5 发命中消耗：返回并清空全部层数（先伤害后消层的「消层」半步，由 OnHit 在爆炸结算后调用）；
     * 归零即自行移除（onRemove 幂等 unmodify，无 stat 残留）。
     */
    fun consume(): Int {
        val consumed = stacksInt
        if (consumed <= 0) return 0
        stacksInt = 0
        host.remove(this)
        return consumed
    }

    override fun advance(amount: Float) {
        if (stacksInt <= 0) return
        maintainHud()
    }

    override fun isHostValid(): Boolean = ship.isAlive && !ship.isHulk && engine.isEntityInPlay(ship)

    override fun onRemove() {
        ship.mutableStats.armorDamageTakenMult.unmodifyMult(BUFF_ID)
        ship.mutableStats.hullDamageTakenMult.unmodifyMult(BUFF_ID)
    }

    /** 易伤幂等刷新：modifierId 固定，先 unmodify 再按当前层数重写（无叠乘路径）。 */
    private fun refreshVulnerability() {
        val mult = 1f + stacksInt * perStack
        ship.mutableStats.armorDamageTakenMult.modifyMult(BUFF_ID, mult)
        ship.mutableStats.hullDamageTakenMult.modifyMult(BUFF_ID, mult)
    }

    /** HUD 双向维护：攻击方=玩家显示目标层数；受击方=玩家显示本舰被附加的易伤。 */
    private fun maintainHud() {
        val player = engine.playerShip
        val pctText = formatPercent(stacksInt * perStack * 100f)
        if (showOnPlayerHud && player != null) {
            feedback.maintainPlayerStatus(
                engine, HUD_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.starfall_echo.status.title"],
                I18n.t(I18n.Categories.MOD, "ui.starfall_echo.status.desc", "stacks" to stacksInt, "percent" to pctText),
                negative = false,
            )
        }
        if (ship == player) {
            feedback.maintainPlayerStatus(
                engine, HUD_VICTIM_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.starfall_echo.status.victim_title"],
                I18n.t(I18n.Categories.MOD, "ui.starfall_echo.status.victim_desc", "stacks" to stacksInt, "percent" to pctText),
                negative = true,
            )
        }
    }

    companion object {
        /** Ship 级 Buff 登记 id（同时充当 customData 键段与 stat modifierId）。 */
        const val BUFF_ID = "astd_starfall_echo_resonance"

        /** 攻击方视角 HUD 状态键。 */
        private const val HUD_KEY = "astd_starfall_echo_resonance_status"

        /** 受击方视角 HUD 状态键。 */
        private const val HUD_VICTIM_KEY = "astd_starfall_echo_resonance_victim_status"

        /** HUD 图标（复用现成美术，对齐彗星冲击波口径）。 */
        private const val HUD_ICON = "graphics/hullmods/astd_arc_loop_interface.png"

        /** HUD/浮字反馈通道（机制可视化铁律的统一落点）。 */
        private val feedback: CombatFeedback = CombatFeedbackImpl

        /** 百分比显示格式：整数去小数点，否则保留 1 位（如 10 / 12.5）。 */
        internal fun formatPercent(value: Float): String {
            val rounded = round(value * 10f) / 10f
            return if (rounded == floor(rounded)) rounded.toInt().toString() else rounded.toString()
        }
    }
}

/** 便捷扩展：取该船的结构谐振 Buff（不存在返回 null）。一行入口不沉淀进公共 API。 */
fun ShipAPI.starfallEchoResonanceStacks(): StarfallEchoResonanceStacks? =
    getBuff(StarfallEchoResonanceStacks.Companion.BUFF_ID) as? StarfallEchoResonanceStacks
