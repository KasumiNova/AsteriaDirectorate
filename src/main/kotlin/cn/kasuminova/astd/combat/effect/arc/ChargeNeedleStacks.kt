package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.buff.Buff
import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.api.buff.StackDecayMode
import cn.kasuminova.astd.api.buff.StackableBuff
import cn.kasuminova.astd.api.buff.getBuff
import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.impl.combat.CombatRandom
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import kotlin.math.floor

/**
 * 单艘目标舰的电荷淤积层数（电荷针刺护盾命中机制的状态承载，规格 01 §2.3）。
 *
 * 动机：命中护盾后在目标舰累积淤积层，每层抬高其护盾维持辐能（`shieldUpkeepMult` 乘区），
 * 停火后按 10 层/s 连续流失；层数上限由耗散安全闸（[ChargeNeedleTuning.dissipationCapStacks]）
 * 动态 clamp——追加维持量不得超过目标当前耗散的 50%，耗散被压制时闸门收紧、超出部分直接裁层。
 *
 * 生命周期：Ship 级 [BuffLifetime.HOST_BOUND]，经 `ShipAPI.buffHost()` 注册（id [BUFF_ID]）；
 * 宿主 hulk/死亡由 BuffTickPlugin 心跳回收，[onRemove] 恰一次 unmodify，无 stat 残留。
 *
 * 玩家可见反馈（机制可视化铁律）：
 * - 攻击方为玩家船时，左侧状态栏显示目标层数与维持 +％（negative=false）；
 * - 受击方为玩家船时，独立键显示本舰被抬升的维持 +％（negative=true）。
 */
class ChargeNeedleStacks(
    /** 淤积宿主舰（创建时捕获）。 */
    private val ship: ShipAPI,
    /** 战斗引擎（创建时捕获；HUD 与实体有效性查询）。 */
    private val engine: CombatEngineAPI,
    /** 所属 BuffHost（创建时捕获；层数归零时自行移除）。 */
    private val host: BuffHost,
) : StackableBuff {

    /** 每层护盾维持加成：由 OnHit 每次命中按难度覆写（多攻击者时后命中者口径覆盖，已文档化）。 */
    var perStack: Float = ChargeNeedleTuning.PER_STACK.v2

    /** 攻击方为玩家船时置 true：在玩家 HUD 显示目标淤积状态。 */
    var showOnPlayerHud: Boolean = false

    /** 浮点累加器：层数视图取 floor，亚层余量参与连续衰减。 */
    private var stacksFloat: Float = 0f

    // —— 异常分支「一次/船」日志闸（纯函数只定返回值语义，日志由本类按实例去重承担）——
    private var warnedZeroDissipation = false
    private var erroredZeroPerStack = false

    override val id: String get() = BUFF_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND
    override val decayMode: StackDecayMode get() = StackDecayMode.CONTINUOUS

    override val stacks: Int get() = floor(stacksFloat).toInt()

    /** 动态闸：每次读取按目标当前耗散终值与基础维持重算（耗散被压制/船插移除时闸门收紧）。 */
    override val maxStacks: Int
        get() = ChargeNeedleTuning.dissipationCapStacks(
            dissipation = ship.mutableStats.fluxDissipation.modifiedValue,
            baseUpkeep = ship.hullSpec.shieldSpec?.upkeepCost ?: 0f,
            perStack = perStack,
        )

    override fun addStacks(n: Int): Int {
        val before = stacksFloat
        stacksFloat = (stacksFloat + n).coerceIn(0f, maxStacks.toFloat())
        return (stacksFloat - before).toInt()
    }

    override fun advance(amount: Float) {
        stacksFloat = (stacksFloat - ChargeNeedleTuning.DECAY_PER_SECOND * amount).coerceAtLeast(0f)
        if (stacksFloat <= 0f) {
            host.remove(this)
            return
        }

        warnOnAbnormalBranches()

        // 闸动态收紧（目标耗散被压制/船插移除）时超上限部分直接裁层，不做缓降。
        val cap = maxStacks
        if (stacksFloat > cap) stacksFloat = cap.toFloat()
        if (stacksFloat <= 0f) {
            host.remove(this)
            return
        }

        refreshUpkeep(ship.mutableStats.shieldUpkeepMult, stacks, perStack)
        maintainHud()
    }

    override fun isHostValid(): Boolean = ship.isAlive && !ship.isHulk && engine.isEntityInPlay(ship)

    override fun onRemove() {
        clearUpkeep(ship.mutableStats.shieldUpkeepMult)
    }

    /** 安全闸异常分支日志（一次/实例 ≈ 一次/船）：配置/状态异常不静默。 */
    private fun warnOnAbnormalBranches() {
        if (perStack <= 0f && !erroredZeroPerStack) {
            erroredZeroPerStack = true
            log.error("电荷淤积 perStack ≤ 0（$perStack），难度配置错误，安全闸失效但机制不退化: ship=${ship.id}, hull=${ship.hullSpec?.hullId}")
        }
        val dissipation = ship.mutableStats.fluxDissipation.modifiedValue
        if (dissipation <= 0f && !warnedZeroDissipation) {
            warnedZeroDissipation = true
            log.warn("电荷淤积目标耗散 ≤ 0（$dissipation），异常态层数立即裁到 0: ship=${ship.id}, hull=${ship.hullSpec?.hullId}")
        }
    }

    /** HUD 双向维护：攻击方=玩家显示目标层数；受击方=玩家显示本舰被抬升的维持。 */
    private fun maintainHud() {
        val player = engine.playerShip
        val pctText = formatPercent(stacks * perStack * 100f)
        if (showOnPlayerHud && player != null) {
            feedback.maintainPlayerStatus(
                engine, HUD_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.charge_needle.status.title"],
                I18n.t(I18n.Categories.MOD, "ui.charge_needle.status.desc", "stacks" to stacks, "percent" to pctText),
                negative = false,
            )
        }
        if (ship == player) {
            feedback.maintainPlayerStatus(
                engine, HUD_VICTIM_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.charge_needle.status.victim_title"],
                I18n.t(I18n.Categories.MOD, "ui.charge_needle.status.victim_desc", "stacks" to stacks, "percent" to pctText),
                negative = true,
            )
        }
    }

    companion object {
        /** Ship 级 Buff 登记 id（同时充当 customData 键段与 stat modifierId）。 */
        const val BUFF_ID = "astd_charge_needle_stacks"

        /** 攻击方视角 HUD 状态键。 */
        private const val HUD_KEY = "astd_charge_needle_stacks_status"

        /** 受击方视角 HUD 状态键。 */
        private const val HUD_VICTIM_KEY = "astd_charge_needle_stacks_victim_status"

        /** HUD 图标（ARC 回路接口船插图，复用现成美术）。 */
        private const val HUD_ICON = "graphics/hullmods/astd_arc_loop_interface.png"

        /** HUD/浮字反馈通道（机制可视化铁律的统一落点）。 */
        private val feedback: CombatFeedback = CombatFeedbackImpl

        private val log = Global.getLogger(ChargeNeedleStacks::class.java)

        /**
         * 护盾维持乘区幂等刷新：`stat.modifyMult(modifierId, 1 + stacks × perStack)`，
         * modifierId 固定，重复刷新不叠乘。
         */
        internal fun refreshUpkeep(stat: MutableStat, stacks: Int, perStack: Float) {
            stat.modifyMult(BUFF_ID, 1f + stacks * perStack)
        }

        /** 回收时恰一次 unmodify（与 [refreshUpkeep] 同 modifierId），无 stat 残留。 */
        internal fun clearUpkeep(stat: MutableStat) {
            stat.unmodifyMult(BUFF_ID)
        }

        /** 百分比显示格式：整数去小数点，否则保留 1 位（如 100 / 2.5）。 */
        internal fun formatPercent(value: Float): String {
            val rounded = kotlin.math.round(value * 10f) / 10f
            return if (rounded == floor(rounded)) rounded.toInt().toString() else rounded.toString()
        }
    }
}

/**
 * 泄放概率结算随机的 Weapon 级状态位（纯标记 Buff，规格 01 §2.1）。
 *
 * 动机：`WeaponAPI` 无 customData（jar 已核实），泄放随机的调用序 callIndex 只能挂舰船侧复合键；
 * 每武器实例一个确定性序列（seed 派生 `ship.id × 31 + slot.id`，战斗内稳定），
 * 保证同帧同事件不二次取值、同事件重放结果一致。
 *
 * 生命周期：Weapon 级复合键登记；槽位换装/空槽由 BuffTickPlugin 自动回收（weaponMatches 判定），
 * 本类无需自管理。
 */
class ChargeNeedleShots(
    /** 确定性序列种子（由 [CombatRandom.seedOf] 派生）。 */
    val seed: Long,
) : Buff {

    /** 泄放结算随机调用序：每次判定取值后自增。 */
    var callIndex: Int = 0

    constructor(source: ShipAPI, weapon: WeaponAPI) : this(CombatRandom.seedOf(source.id, weapon.slot.id))

    override val id: String get() = SHOTS_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND

    /** 武器级回收由 BuffTickPlugin 的换装/空槽判定承担，宿主舰有效性同理，恒 true。 */
    override fun isHostValid(): Boolean = true

    companion object {
        /** Weapon 级 Buff 登记 id（复合键 `astd_buff:weapon:<id>:<slotId>` 的键段）。 */
        const val SHOTS_ID = "astd_charge_needle_shots"
    }
}

/** 便捷扩展：取该船的电荷淤积 Buff（不存在返回 null）。一行入口不沉淀进公共 API（00 §1.3 约定）。 */
fun ShipAPI.chargeNeedleStacks(): ChargeNeedleStacks? = getBuff(ChargeNeedleStacks.BUFF_ID) as? ChargeNeedleStacks
