package cn.kasuminova.astd.combat.effect.arc.qiongjue

import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.api.buff.StackDecayMode
import cn.kasuminova.astd.api.buff.StackableBuff
import cn.kasuminova.astd.api.buff.getBuff
import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI

/**
 * 全舰“穷距”相位轨道炮共享的持续演算叠层状态（规格 05 §2.1，2026-09 修订：Weapon 级 → Ship 级共享）。
 *
 * 动机：连续命中同一目标每层 +x% 伤害与射速（上限 10 层），异目标命中按保留比例折算，
 * 3s 未命中后按 z 层/s 流失。层数/目标全舰同型武器共享一份（多门穷距共同积累、共同受益）；
 * 状态挂 Ship 级 [BuffLifetime.HOST_BOUND]，由基建 `BuffTickPlugin` 心跳驱动 [advance]。
 *
 * 加成作用域裁定（2026-09 用户裁定）：伤害与射速只作用于**同型武器实例**——
 * 伤害走 [QiongjueDamageDealtModifier] 逐命中通道（已按 weaponId 过滤），
 * 射速由本 Buff 逐武器实例压缩冷却（见下）；其他武器不受影响。
 *
 * 射速修正（规格 05 §2.4 spike 结论）：**`WeaponAPI.setRemainingCooldownTo` 冷却扣减方案**——
 * 每门穷距各自的开火周期起点（`cooldownRemaining` 上跳沿）一次性把本周期冷却压缩为 `cd / mult`，
 * 逐槽位跟踪上帧冷却读数（[lastCooldownBySlot]），精确作用于本武器、无同舰其他实弹武器射速同步变化的副作用。
 * 周期中途叠层变化不追溯当前周期，下一周期生效（已文档化）。
 *
 * 玩家可见反馈（机制可视化铁律）：玩家船且层数 > 0 时每帧维持左侧状态栏
 * 「持续演算 层数 x/10 · 伤害 +a% · 射速 +b%」（negative=false）。
 */
class QiongjueCalcStacks(
    /** 载具舰（创建时捕获；HUD 归属与 stat 查询）。 */
    private val ship: ShipAPI,
    /** 战斗引擎（创建时捕获；时刻读取/实体有效性/HUD 通道）。 */
    private val engine: CombatEngineAPI,
    /** 所属 BuffHost（创建时捕获；层数归零时自行移除）。 */
    private val host: BuffHost,
    /** 每层加成锚点（默认定稿常量；测试注入异常配置驱动 0 值防线分支）。 */
    private val perStackEntry: ScalingEntry = QiongjuePhaseRailgunDifficulty.PER_STACK_BONUS,
    /** 衰减速率锚点（默认定稿常量；测试注入异常配置驱动 0 值防线分支）。 */
    private val decayRateEntry: ScalingEntry = QiongjuePhaseRailgunDifficulty.DECAY_RATE,
) : StackableBuff {

    /** 当前演算目标（由 OnHit 命中时更新；失效目标在下次命中判定时视为无旧目标不折算）。 */
    var target: ShipAPI? = null

    /** 最近一次命中战斗时刻（`engine.getTotalElapsedTime(false)` 口径；由 OnHit 刷新）。 */
    var lastHitTime: Float = 0f

    /** 当前层数视图（读写统一走 [addStacks] clamp 路径）。 */
    private var stacksField: Int = 0

    /** 亚层衰减累加器（窗口后按速率累积，每满 1 扣 1 层）。 */
    private var pendingDecay: Float = 0f

    /** 本帧解析出的每层加成（HUD 与乘区刷新共用）。 */
    private var currentPerStack: Float = QiongjuePhaseRailgunDifficulty.PER_STACK_BONUS.v2

    /** 逐槽位上帧冷却读数（每门穷距各自的开火周期起点跳沿检测；槽位换装后读数随新周期自愈）。 */
    private val lastCooldownBySlot = mutableMapOf<String, Float>()

    /** decayRate ≤ 0 异常分支的「一次/实例」日志闸（配置异常必须可见，不刷屏）。 */
    private var warnedZeroDecayRate: Boolean = false

    override val id: String get() = BUFF_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND
    override val decayMode: StackDecayMode get() = StackDecayMode.WINDOWED
    override val stacks: Int get() = stacksField
    override val maxStacks: Int get() = QiongjuePhaseRailgunDifficulty.MAX_STACKS

    override fun addStacks(n: Int): Int {
        val before = stacksField
        stacksField = (stacksField + n).coerceIn(0, maxStacks)
        return stacksField - before
    }

    /** 当前演算目标是否仍可结算（hulk/离场即失效——下次命中视为无旧目标，不吃切换折算）。 */
    fun isTargetAlive(): Boolean {
        val t = target ?: return false
        return !t.isHulk && engine.isEntityInPlay(t)
    }

    override fun advance(amount: Float) {
        val now = engine.getTotalElapsedTime(false)
        // 难度取值每帧重取（玩家 owner==0 恒 v2；LunaLib 热变更即时生效，成本可忽略）。
        val perStack = QiongjueStackMath.resolve(DifficultyTuningImpl, perStackEntry, ship.owner)
        val decayRate = QiongjueStackMath.resolve(DifficultyTuningImpl, decayRateEntry, ship.owner)
        currentPerStack = perStack

        // 1. 窗口衰减（WINDOWED 语义由 QiongjueStackMath.decayAdvance 落实）。
        if (!QiongjueStackMath.isDecayRateValid(decayRate) && !warnedZeroDecayRate) {
            warnedZeroDecayRate = true
            log.warn("穷距演算衰减速率非法（$decayRate），难度配置异常，层数不衰减继续运行: ship=${ship.id}")
        }
        val step = QiongjueStackMath.decayAdvance(stacks, pendingDecay, now - lastHitTime, amount, decayRate)
        if (step.stacks != stacks) addStacks(step.stacks - stacks)
        pendingDecay = step.pendingDecay

        // 层数归零即自行移除（下次命中重建），无残留心跳。
        if (stacks <= 0) {
            host.remove(this)
            return
        }

        // 2. 伤害乘区不在此落地：逐武器伤害乘区由 QiongjueDamageDealtModifier 逐命中写入（本 Buff 层数为其唯一数据源）。
        val mult = QiongjueStackMath.mult(stacks, perStack)

        // 3. 射速 spike：逐门穷距各自检测开火周期起点（冷却上跳沿），一次性压缩本周期冷却为 cd / mult。
        for (w in ship.allWeapons) {
            if (w?.spec?.weaponId != QiongjuePhaseRailgunDifficulty.WEAPON_ID) continue
            val slotId = w.slot?.id ?: continue
            val cooldown = w.cooldownRemaining
            val last = lastCooldownBySlot[slotId] ?: 0f
            if (cooldown - last > REFIRE_EDGE_EPS) {
                w.setRemainingCooldownTo(cooldown / mult)
                engine.customData[TELEMETRY_SPIKE_APPLIED] = (engine.customData[TELEMETRY_SPIKE_APPLIED] as? Int ?: 0) + 1
            }
            lastCooldownBySlot[slotId] = cooldown
        }

        // 4. 玩家 HUD（仅玩家船且层数 > 0；归零即停止维持，状态条目自然消失）。
        if (stacks > 0 && ship === engine.playerShip) {
            val pct = QiongjueStackMath.formatPercent((mult - 1f) * 100f)
            feedback.maintainPlayerStatus(
                engine,
                HUD_KEY,
                HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.qiongjue.status.calc"],
                I18n.t(
                    I18n.Categories.MOD,
                    "ui.qiongjue.status.stacks",
                    "stacks" to stacks,
                    "dmg" to pct,
                    "rof" to pct,
                ),
                negative = false,
            )
            engine.customData[TELEMETRY_HUD_FRAMES] = (engine.customData[TELEMETRY_HUD_FRAMES] as? Int ?: 0) + 1
        }
    }

    override fun isHostValid(): Boolean =
        ship.isAlive && !ship.isHulk &&
            ship.allWeapons.any { it?.spec?.weaponId == QiongjuePhaseRailgunDifficulty.WEAPON_ID }

    companion object {
        /** Ship 级 Buff 登记 id（同时充当 customData 键段）。 */
        const val BUFF_ID = "astd_qiongjue_stacks"

        /** HUD 状态条目键。 */
        private const val HUD_KEY = "astd_qiongjue_status"

        /** HUD 图标（ARC 回路接口船插图，复用现成美术；武器贴图到位后替换为 graphics/weapons/astd_qiongjue_base.png）。 */
        private const val HUD_ICON = "graphics/hullmods/astd_arc_loop_interface.png"

        /** 开火周期起点判定阈值（冷却读数上跳超过该值视为新周期；烟测实证 cooldownRemaining 含充能段，
         * 2.5s 全周期下跳沿约 +2.5，见 acceptance-log qjRefireMinAtFull=1.2376s ≈ 2/1.625 旧口径证据）。 */
        private const val REFIRE_EDGE_EPS = 0.5f

        /** 射速 spike 应用次数遥测键（dev 自动化烟测读取）。 */
        const val TELEMETRY_SPIKE_APPLIED = "astd_qiongjue_spike_applied"

        /** HUD 维持帧数遥测键（dev 自动化烟测读取）。 */
        const val TELEMETRY_HUD_FRAMES = "astd_qiongjue_hud_frames"

        /** HUD/浮字反馈通道（机制可视化铁律的统一落点）。 */
        private val feedback: CombatFeedback = CombatFeedbackImpl

        private val log = Global.getLogger(QiongjueCalcStacks::class.java)
    }
}

/**
 * 便捷扩展：取该船的穷距演算共享 Buff（不存在返回 null）。
 * 一行入口不沉淀进公共 API（00-共享基建 §1.3 约定）；被 OnHit / DamageDealtModifier 与 dev 自动化烟测脚本调用。
 */
fun ShipAPI.qiongjueCalcStacks(): QiongjueCalcStacks? = getBuff(QiongjueCalcStacks.BUFF_ID) as? QiongjueCalcStacks
