package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.buff.Buff
import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.api.buff.StackDecayMode
import cn.kasuminova.astd.api.buff.StackableBuff
import cn.kasuminova.astd.api.buff.getBuff
import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.combat.effect.arc.ChargeNeedleStacks.Companion.BUFF_ID
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.impl.combat.CombatRandom
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import kotlin.math.floor

/**
 * 单艘目标舰的电荷淤积层数（电荷针刺护盾命中机制的状态承载，规格 01 §2.3，2026-09 机制修订）。
 *
 * 动机：命中护盾后在目标舰累积淤积层，每层产出两份护盾维持压力——
 * 1. 乘区：`shieldUpkeepMult` 最终乘区 +stacks × perStack（难度查表 1%~5%）；
 * 2. 固定软辐能：按目标舰体型与难度查表（0.5/1/1.5/2 ~ 2.5/5/7.5/10 su/s 每层），
 *    **仅在目标护盾开启期间**逐帧 `increaseFlux(soft)` 直写（关盾不产出）；
 * 两项之和按 [ChargeNeedleTuning.dissipationCapFactor] 折算，最高不超过目标最终耗散的 200%（不受难度影响）。
 *
 * 消散：连续流失，速率 = 当前层数 × 4%/s（护盾关闭时翻倍 8%/s）、下限 2 层/s（固定不缩放，不受难度影响）。
 *
 * 生命周期：Ship 级 [BuffLifetime.HOST_BOUND]，经 `ShipAPI.buffHost()` 注册（id [BUFF_ID]）；
 * 宿主 hulk/死亡由 BuffTickPlugin 心跳回收，[onRemove] 恰一次 unmodify，无 stat 残留
 * （固定软辐能为逐帧直写，无持久状态）。
 *
 * 玩家可见反馈（机制可视化铁律）：
 * - 攻击方为玩家船时，左侧状态栏显示目标层数、维持 +％ 与固定软辐能读数（negative=false）；
 * - 受击方为玩家船时，独立键显示本舰被抬升的维持与固定软辐能（negative=true）。
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

    /** 每层固定软辐能（su/s）：由 OnHit 按目标体型 + 难度覆写（同 perStack 覆盖口径）。 */
    var flatPerStack: Float = ChargeNeedleTuning.FLAT_FLUX_FRIGATE.v2

    /** 攻击方为玩家船时置 true：在玩家 HUD 显示目标淤积状态。 */
    var showOnPlayerHud: Boolean = false

    /** 浮点累加器：层数视图取 floor，亚层余量参与连续消散。 */
    private var stacksFloat: Float = 0f

    /** 本帧 200% 耗散折算系数（HUD 读数与实际产出同口径，advance 内刷新）。 */
    private var lastFactor: Float = 1f

    /** 本帧固定软辐能实际产出（护盾关闭时为 0；HUD 读数与实际产出同口径，advance 内刷新）。 */
    private var lastFlatPerSec: Float = 0f

    // —— 异常分支「一次/船」日志闸（纯函数只定返回值语义，日志由本类按实例去重承担）——
    private var warnedZeroDissipation = false
    private var erroredZeroPerStack = false

    override val id: String get() = BUFF_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND
    override val decayMode: StackDecayMode get() = StackDecayMode.CONTINUOUS

    override val stacks: Int get() = floor(stacksFloat).toInt()

    /** 层数上限为绝对上限；产出压力由 200% 耗散折算承担（不再按层数裁闸）。 */
    override val maxStacks: Int get() = ChargeNeedleTuning.ABSOLUTE_MAX_STACKS

    override fun addStacks(n: Int): Int {
        val before = stacksFloat
        stacksFloat = (stacksFloat + n).coerceIn(0f, maxStacks.toFloat())
        return (stacksFloat - before).toInt()
    }

    override fun advance(amount: Float) {
        // 护盾状态门控：固定软辐能仅护盾开启期间产出；关盾时消散速率翻倍。
        val shieldOn = ship.shield?.isOn == true

        stacksFloat = (stacksFloat - ChargeNeedleTuning.decayPerSecond(stacksFloat, shieldOn) * amount).coerceAtLeast(0f)
        if (stacksFloat <= 0f) {
            host.remove(this)
            return
        }

        warnOnAbnormalBranches()

        // 200% 耗散上限折算：维持乘区额外量与固定软辐能之和超限按比例同步压缩。
        val dissipation = ship.mutableStats.fluxDissipation.modifiedValue
        val baseUpkeep = ship.hullSpec.shieldSpec?.upkeepCost ?: 0f
        val upkeepExtra = baseUpkeep * stacks * perStack
        val flatPerSec = if (shieldOn) stacks * flatPerStack else 0f
        val factor = ChargeNeedleTuning.dissipationCapFactor(dissipation, upkeepExtra, flatPerSec)
        lastFactor = factor
        lastFlatPerSec = flatPerSec

        ship.mutableStats.shieldUpkeepMult.modifyMult(BUFF_ID, 1f + stacks * perStack * factor)
        if (flatPerSec * factor > 0f) {
            ship.fluxTracker.increaseFlux(flatPerSec * factor * amount, false)
        }

        maintainHud()
    }

    override fun isHostValid(): Boolean = ship.isAlive && !ship.isHulk && engine.isEntityInPlay(ship)

    override fun onRemove() {
        ship.mutableStats.shieldUpkeepMult.unmodifyMult(BUFF_ID)
    }

    /** 异常分支日志（一次/实例 ≈ 一次/船）：配置/状态异常不静默。 */
    private fun warnOnAbnormalBranches() {
        if (perStack <= 0f && !erroredZeroPerStack) {
            erroredZeroPerStack = true
            log.error("电荷淤积 perStack ≤ 0（$perStack），难度配置错误: ship=${ship.id}, hull=${ship.hullSpec?.hullId}")
        }
        val dissipation = ship.mutableStats.fluxDissipation.modifiedValue
        if (dissipation <= 0f && !warnedZeroDissipation) {
            warnedZeroDissipation = true
            log.warn("电荷淤积目标耗散 ≤ 0（$dissipation），异常态产出压没: ship=${ship.id}, hull=${ship.hullSpec?.hullId}")
        }
    }

    /** HUD 双向维护：攻击方=玩家显示目标层数；受击方=玩家显示本舰被抬升的维持与固定软辐能。 */
    private fun maintainHud() {
        val player = engine.playerShip
        // HUD 读数与实际产出同口径：维持乘区按本帧折算系数；固定软辐能按本帧实际产出（关盾为 0）。
        val pctText = formatPercent(stacks * perStack * lastFactor * 100f)
        val flatText = formatPercent(lastFlatPerSec * lastFactor)
        if (showOnPlayerHud && player != null) {
            feedback.maintainPlayerStatus(
                engine, HUD_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.charge_needle.status.title"],
                I18n.t(
                    I18n.Categories.MOD,
                    "ui.charge_needle.status.desc",
                    "stacks" to stacks, "percent" to pctText, "flat" to flatText,
                ),
                negative = false,
            )
        }
        if (ship == player) {
            feedback.maintainPlayerStatus(
                engine, HUD_VICTIM_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.charge_needle.status.victim_title"],
                I18n.t(
                    I18n.Categories.MOD,
                    "ui.charge_needle.status.victim_desc",
                    "stacks" to stacks, "percent" to pctText, "flat" to flatText,
                ),
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

        /** 百分比/数值显示格式：整数去小数点，否则保留 1 位（如 100 / 2.5）。 */
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
fun ShipAPI.chargeNeedleStacks(): ChargeNeedleStacks? = getBuff(BUFF_ID) as? ChargeNeedleStacks
