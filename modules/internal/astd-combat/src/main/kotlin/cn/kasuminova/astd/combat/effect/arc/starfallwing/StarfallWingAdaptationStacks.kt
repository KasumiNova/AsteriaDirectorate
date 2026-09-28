package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.buff.Buff
import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.api.buff.getBuff
import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.math.floor
import kotlin.math.round

/**
 * 单艘目标舰的「振频适应」层数（坠星残翼命中护盾机制的状态承载，舰船侧全局叠层）。
 *
 * 动机：主弹命中护盾叠 1 层、子射弹命中叠 0.5 层（故层数为 Float，不走整层
 * StackableBuff）；每层削弱目标 10% 最终护盾承伤效率——承伤比口径：
 * `shieldDamageTakenMult` 目标值 = min(base + 0.1×层数, 1.0)（[StarfallWingTuning.adaptationShieldMult]），
 * 层数每秒流失 1 层，归零即自行移除。层数 >10 时坠星残翼主弹穿透护盾
 * （判定在 StarfallWingWeaponEffect，本类只承载层数与承伤修饰）。
 *
 * 生命周期：Ship 级 [BuffLifetime.HOST_BOUND]，经 `ShipAPI.buffHost()` 注册（id [BUFF_ID]）；
 * 宿主 hulk/死亡由 BuffTickPlugin 心跳回收，[onRemove] 恰一次 unmodify，无 stat 残留。
 *
 * 玩家可见反馈（机制可视化铁律）：
 * - 攻击方为玩家船时，左侧状态栏显示目标适应层数与承伤削弱 −％（negative=false）；
 * - 受击方为玩家船时，独立键显示本舰被附加的承伤削弱 −％（negative=true）。
 */
class StarfallWingAdaptationStacks(
    /** 宿主舰（创建时捕获）。 */
    private val ship: ShipAPI,
    /** 战斗引擎（创建时捕获；HUD 与实体有效性查询）。 */
    private val engine: CombatEngineAPI,
    /** 所属 BuffHost（创建时捕获；层数流失归零时自行移除）。 */
    private val host: BuffHost,
) : Buff {

    /** 攻击方为玩家船时置 true：在玩家 HUD 显示目标适应状态。 */
    var showOnPlayerHud: Boolean = false

    /** 当前层数（Float：子射弹 0.5 层粒度）；读取侧经 [stacks] 访问。 */
    var stacks: Float = 0f
        private set

    override val id: String get() = BUFF_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND

    /** 叠加 [n] 层（无上限——穿盾门槛与承伤比上限各自封顶语义）并刷新承伤修饰。 */
    fun addStacks(n: Float) {
        if (n <= 0f) return
        stacks += n
        refreshShieldMult()
    }

    /** 每秒流失 [StarfallWingTuning.ADAPTATION_DECAY_PER_SECOND] 层；归零自行移除。 */
    override fun advance(amount: Float) {
        if (stacks <= 0f) return
        stacks -= StarfallWingTuning.ADAPTATION_DECAY_PER_SECOND * amount
        if (stacks <= 0f) {
            stacks = 0f
            host.remove(this)
            return
        }
        refreshShieldMult()
        maintainHud()
    }

    override fun isHostValid(): Boolean = ship.isAlive && !ship.isHulk && engine.isEntityInPlay(ship)

    override fun onRemove() {
        ship.mutableStats.shieldDamageTakenMult.unmodifyMult(BUFF_ID)
    }

    /** 承伤修饰幂等刷新：modifierId 固定，按当前层数重写（base 取未修饰基础值，无叠乘路径）。 */
    private fun refreshShieldMult() {
        val base = ship.mutableStats.shieldDamageTakenMult.baseValue
        ship.mutableStats.shieldDamageTakenMult.modifyMult(
            BUFF_ID, StarfallWingTuning.adaptationShieldMult(base, stacks),
        )
    }

    /** HUD 双向维护：攻击方=玩家显示目标层数；受击方=玩家显示本舰被削弱。 */
    private fun maintainHud() {
        val player = engine.playerShip
        val weakened = stacks * StarfallWingTuning.ADAPTATION_TAKEN_PER_STACK
        val pctText = formatPercent(weakened * 100f)
        val stacksText = formatPercent(stacks)
        if (showOnPlayerHud && player != null) {
            feedback.maintainPlayerStatus(
                engine, HUD_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.starfall_wing.status.title"],
                I18n.t(I18n.Categories.MOD, "ui.starfall_wing.status.desc", "stacks" to stacksText, "percent" to pctText),
                negative = false,
            )
        }
        if (ship == player) {
            feedback.maintainPlayerStatus(
                engine, HUD_VICTIM_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.starfall_wing.status.victim_title"],
                I18n.t(I18n.Categories.MOD, "ui.starfall_wing.status.victim_desc", "stacks" to stacksText, "percent" to pctText),
                negative = true,
            )
        }
    }

    companion object {
        /** Ship 级 Buff 登记 id（同时充当 customData 键段与 stat modifierId）。 */
        const val BUFF_ID = "astd_starfall_wing_adaptation"

        /** 攻击方视角 HUD 状态键。 */
        private const val HUD_KEY = "astd_starfall_wing_adaptation_status"

        /** 受击方视角 HUD 状态键。 */
        private const val HUD_VICTIM_KEY = "astd_starfall_wing_adaptation_victim_status"

        /** HUD 图标（复用现成美术，对齐结构谐振口径）。 */
        private const val HUD_ICON = "graphics/hullmods/astd_arc_loop_interface.png"

        /** HUD/浮字反馈通道（机制可视化铁律的统一落点）。 */
        private val feedback: CombatFeedback = CombatFeedbackImpl

        /** 数值显示格式：整数去小数点，否则保留 1 位（如 10 / 12.5）；结构谐振同款。 */
        internal fun formatPercent(value: Float): String {
            val rounded = round(value * 10f) / 10f
            return if (rounded == floor(rounded)) rounded.toInt().toString() else rounded.toString()
        }
    }
}

/** 便捷扩展：取该船的振频适应 Buff（不存在返回 null）。一行入口不沉淀进公共 API。 */
fun ShipAPI.starfallWingAdaptationStacks(): StarfallWingAdaptationStacks? =
    getBuff(StarfallWingAdaptationStacks.BUFF_ID) as? StarfallWingAdaptationStacks
