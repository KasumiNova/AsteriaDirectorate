package cn.kasuminova.astd.campaign.ending

import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.bounty.PendingStrengthEffect
import cn.kasuminova.astd.campaign.ending.EndingProgression.activateDelayedEffects
import cn.kasuminova.astd.campaign.ending.EndingProgression.issueExecutor
import cn.kasuminova.astd.campaign.ending.EndingProgression.planSign
import cn.kasuminova.astd.campaign.ending.EndingProgression.sign
import java.util.Random
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 第五章「归档」结局流程纯逻辑层（docs/story/13 定稿口径；不触碰 Global，可直接单测）。
 *
 * 职责：
 * - 归档三选（公开/封存/交易）的效果计划与签署落账（[planSign] / [sign]）；
 * - 延迟生效条目的到期激活（封存全体延迟、交易其余势力延迟，[activateDelayedEffects]）；
 * - 「执行官」签发状态机（特化二选一、选定不可更改，[issueExecutor]）。
 *
 * 数值口径：三选幅度/报酬/关系变动均为 13 文档「提案」状态（文档未定案），
 * 常量在代码注释逐条标注「13 文档未定案，提案值」；封存延迟时长 30 标准日为
 * 「延迟数周期」的裁定（一个审计周期 = 30 标准日，与文书「审计周期」纪年口径一致）。
 *
 * 副作用（发钱、关系写入、市场 stat 挂载、物品发放）全部在游戏侧
 * [EndingEffects] 与分局终端后端装配层；本层只读写 [BountyState]。
 */
object EndingProgression {

    /** 归档三选（签署后不可反悔；签署前可退出终端再考虑，文书保持待签状态）。 */
    enum class Choice { PUBLISH, SEAL, TRADE }

    /** 「执行官」特化方向（签发时二选一，选定不可更改——「一经签发，不予退换」，85 §4.6）。 */
    enum class ExecutorSpec { COMBAT, ADMIN }

    // ─── 三选数值（全部为 13 文档未定案，提案值） ───

    /** 公开：全体势力强度 +25%，立即生效（13 文档未定案，提案值）。 */
    const val PUBLISH_ALL_PCT: Float = 0.25f

    /** 封存：全体势力强度 +12%，延迟生效（13 文档未定案，提案值）。 */
    const val SEAL_ALL_PCT: Float = 0.12f

    /** 交易：对象势力 +50% 立即（13 文档未定案，提案值）。 */
    const val TRADE_TARGET_PCT: Float = 0.50f

    /** 交易：其余势力 +10% 延迟（13 文档未定案，提案值）。 */
    const val TRADE_OTHERS_PCT: Float = 0.10f

    /** 交易：与对象势力关系 +0.3（13 文档未定案，提案值）。 */
    const val TRADE_REL_TARGET: Float = 0.3f

    /** 交易：其余势力关系 -0.05（13 文档未定案，提案值）。 */
    const val TRADE_REL_OTHERS: Float = -0.05f

    /** 交易：一次性报酬区间下限 500 万（13 文档未定案，提案值）。 */
    const val TRADE_REWARD_MIN: Int = 5_000_000

    /** 交易：一次性报酬区间上限 2500 万（13 文档未定案，提案值）。 */
    const val TRADE_REWARD_MAX: Int = 25_000_000

    /**
     * 延迟生效时长：30 标准日。
     * 裁定：13 文档「延迟数周期」未给具体天数；按文书纪年口径取一个审计周期 = 30 标准日。
     */
    const val DELAY_DAYS: Float = 30f

    /**
     * 交易候选对象（13 文档：霸主/辛达强权/速子科技/卢德教会；
     * 余晖除外——「不向非在编自动智能出售」，章程条款，不是价值判断）。
     */
    val TRADE_CANDIDATES: List<String> = listOf(
        "hegemony",
        "sindrian_diktat",
        "tritachyon",
        "luddic_church",
    )

    /**
     * 交易报价函：候选方报价 = 区间内按候选势力确定性取值 × 难度系数（封顶 5×，
     * 与主线报酬缩放同口径）。同一候选方报价恒定（报价函是两百年前的询价记录，13 定稿口径）。
     */
    fun tradeQuote(factionId: String, kS: Float): Int {
        val rnd = Random(factionId.hashCode().toLong() xor 0xB1D)
        val base = TRADE_REWARD_MIN + rnd.nextInt(TRADE_REWARD_MAX - TRADE_REWARD_MIN + 1)
        return (base * min(kS.coerceAtLeast(1f), 5f)).roundToInt()
    }

    /** 一条势力强度效果（目标势力 + 幅度 + 延迟天数；0=立即）。 */
    data class StrengthEffect(val factionId: String, val pct: Float, val delayDays: Float)

    /**
     * 签署计划（三选效果的完整展开；签署前供终端文书「效果说明」栏预览，签署时落账）。
     *
     * @property keepArchiveAccess 封存选：玩家保留档案室全部访问权（其余两选档案室转只读）
     */
    data class SignPlan(
        val choice: Choice,
        val tradeFactionId: String? = null,
        val effects: List<StrengthEffect> = emptyList(),
        val tradePayout: Int = 0,
        val relationDeltas: Map<String, Float> = emptyMap(),
        val keepArchiveAccess: Boolean = false,
    )

    /**
     * 展开三选效果计划。
     *
     * @param affectedFactionIds 受影响的全体势力（游戏侧装配：有市场的主要势力，玩家除外）
     * @param kS 难度系数（交易报价缩放）
     */
    fun planSign(
        choice: Choice,
        tradeFactionId: String?,
        affectedFactionIds: List<String>,
        kS: Float,
    ): SignPlan = when (choice) {
        // 公开：无差别扩散，全体立即（13：碎片人人有份）
        Choice.PUBLISH -> SignPlan(
            choice = choice,
            effects = affectedFactionIds.map { StrengthEffect(it, PUBLISH_ALL_PCT, 0f) },
        )
        // 封存：幅度减半、延迟一个周期；玩家保留档案室访问权
        Choice.SEAL -> SignPlan(
            choice = choice,
            effects = affectedFactionIds.map { StrengthEffect(it, SEAL_ALL_PCT, DELAY_DAYS) },
            keepArchiveAccess = true,
        )
        // 交易：对象立即最大幅度，其余延迟小幅度；附一次性报酬与关系变动
        Choice.TRADE -> {
            val target = tradeFactionId
                ?: throw IllegalArgumentException("交易选必须指定对象势力（候选见 TRADE_CANDIDATES）")
            require(target in TRADE_CANDIDATES) { "交易对象不在候选列表：$target" }
            SignPlan(
                choice = choice,
                tradeFactionId = target,
                effects = affectedFactionIds.map {
                    if (it == target) StrengthEffect(it, TRADE_TARGET_PCT, 0f)
                    else StrengthEffect(it, TRADE_OTHERS_PCT, DELAY_DAYS)
                },
                tradePayout = tradeQuote(target, kS),
                relationDeltas = affectedFactionIds.associateWith {
                    if (it == target) TRADE_REL_TARGET else TRADE_REL_OTHERS
                },
            )
        }
    }

    /**
     * 签署落账（终端「确认签署」的纯状态部分）。
     *
     * 写入：三选结果 / 交易对象与报酬 / 档案室只读标记 / 立即与延迟强度条目 /
     * 无限期承包商认证。签署后不可反悔（已签署直接拒绝）。
     *
     * 同一势力的多条强度效果不叠加：立即与延迟各保留一份，生效时同势力取最大幅度
     * （三选单选结构下本不会出现同势力双条目，此处为口径声明而非兜底）。
     *
     * @param nowTimestamp 战役时钟当前 timestamp（延迟条目的激活时刻基准）
     * @param secondsPerDay 战役时钟每天秒数（CampaignClockAPI.getSecondsPerDay）
     * @return 是否签署成功
     */
    fun sign(state: BountyState, plan: SignPlan, nowTimestamp: Long, secondsPerDay: Float): Boolean {
        if (!state.archivalPending) return false
        if (state.archivalChoice != null) return false

        state.archivalChoice = plan.choice.name
        state.tradeFactionId = plan.tradeFactionId
        state.tradePayout = plan.tradePayout
        state.archivesReadOnly = !plan.keepArchiveAccess

        val delaySeconds = (DELAY_DAYS * secondsPerDay).toLong()
        for (effect in plan.effects) {
            if (effect.delayDays <= 0f) {
                state.appliedStrengthPct[effect.factionId] = effect.pct
            } else {
                state.pendingStrengthEffects[effect.factionId] =
                    PendingStrengthEffect(effect.factionId, effect.pct, nowTimestamp + delaySeconds)
            }
        }

        // 《无限期承包合同》：清算序列完成后核销系统转入常设职能（13 定稿口径）
        state.indefiniteContractor = true
        return true
    }

    /**
     * 到期激活：把激活时刻已到的延迟条目转入已生效表。
     *
     * 势力灭国口径（D14）：到期时势力已不存在或名下无任何市场 → 条目直接作废
     * （移出待生效表且不入已生效表，不残留永不激活条目），由 [factionAlive] 判定。
     *
     * @param factionAlive 势力存续判定（默认恒真；游戏侧注入「势力存在且名下有市场」）
     * @return 本次实际激活的条目（空列表 = 无到期；游戏侧据此挂载市场 stat 与打印回执）
     */
    fun activateDelayedEffects(
        state: BountyState,
        nowTimestamp: Long,
        factionAlive: (String) -> Boolean = { true },
    ): List<PendingStrengthEffect> {
        val due = state.pendingStrengthEffects.values.filter { it.activateTimestamp <= nowTimestamp }
        val activated = ArrayList<PendingStrengthEffect>(due.size)
        for (effect in due) {
            state.pendingStrengthEffects.remove(effect.factionId)
            if (!factionAlive(effect.factionId)) continue // 势力灭国：到期作废，不残留
            // 同势力已有更大幅度生效条目时不覆盖（口径声明，见 sign）
            val existing = state.appliedStrengthPct[effect.factionId]
            if (existing == null || effect.pct > existing) {
                state.appliedStrengthPct[effect.factionId] = effect.pct
            }
            activated += effect
        }
        return activated
    }

    /**
     * 「执行官」签发状态机：归档签署完成后二选一，选定不可更改。
     *
     * 幂等与不可逆口径：
     * - 未签署归档 / 未解锁签发条件 → false；
     * - 已签发（[BountyState.executorIssued]）→ false（不予退换，85 §4.6）；
     * - 首次签发 → 写入特化与签发标记，返回 true（物品发放由游戏侧随后执行）。
     */
    fun issueExecutor(state: BountyState, spec: ExecutorSpec): Boolean {
        if (state.archivalChoice == null) return false
        if (state.executorIssued) return false
        state.executorSpec = spec.name
        state.executorIssued = true
        return true
    }

    /** 指挥舰指定前置校验（战斗特化限定）。事务口径：装配层先校验 → 游戏侧执行成功 → 再落账。 */
    fun canAssignCommandShip(state: BountyState): Boolean =
        state.executorSpec == ExecutorSpec.COMBAT.name

    /** 指挥舰指定落账（舰只离场/损毁后可重新指定，state 侧仅记录最新指定）。 */
    fun assignCommandShip(state: BountyState, memberId: String): Boolean {
        if (!canAssignCommandShip(state)) return false
        state.executorCommandShipId = memberId
        return true
    }

    /** 管理员任命前置校验（行政特化限定）。事务口径：装配层先校验 → 游戏侧执行成功 → 再落账。 */
    fun canAppointAdmin(state: BountyState): Boolean =
        state.executorSpec == ExecutorSpec.ADMIN.name

    /** 管理员任命落账（重复任命新市场时更换记录，旧市场清理由游戏侧处理）。 */
    fun appointAdmin(state: BountyState, marketId: String): Boolean {
        if (!canAppointAdmin(state)) return false
        state.executorAdminMarketId = marketId
        return true
    }

    /** 读取三选结果（null=未签署）。 */
    fun choiceOf(state: BountyState): Choice? = state.archivalChoice?.let { Choice.valueOf(it) }

    /** 读取执行官特化（null=未签发）。 */
    fun specOf(state: BountyState): ExecutorSpec? = state.executorSpec?.let { ExecutorSpec.valueOf(it) }
}
