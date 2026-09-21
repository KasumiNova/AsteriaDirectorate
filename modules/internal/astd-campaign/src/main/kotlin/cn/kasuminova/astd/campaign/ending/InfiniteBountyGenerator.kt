package cn.kasuminova.astd.campaign.ending

import cn.kasuminova.astd.campaign.bounty.BountyDef
import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.bounty.InfiniteSlotState
import cn.kasuminova.astd.campaign.ending.InfiniteBountyGenerator.SLOT_COUNT
import cn.kasuminova.astd.campaign.ending.InfiniteBountyGenerator.TARGET_FACTIONS
import cn.kasuminova.astd.campaign.ending.InfiniteBountyGenerator.ensureSlots
import cn.kasuminova.astd.campaign.ending.InfiniteBountyGenerator.keyOf
import cn.kasuminova.astd.campaign.ending.InfiniteBountyGenerator.regenerateSlot
import cn.kasuminova.astd.campaign.ending.InfiniteBountyGenerator.rollSlot
import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.impl.campaign.ids.Factions
import java.util.Random
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 《无限期承包合同》无限赏金生成纯逻辑层（docs/story/13 定稿口径；不触碰 Global，可直接单测）。
 *
 * 职责：
 * - 3 槽常驻工单的初始化与幂等补齐（[ensureSlots]）；
 * - 每代槽位的危险等级 / FP 档位 / R 型词缀开放数 / 报价锁定 / 目标势力抽取（[rollSlot]）；
 * - 核销换代（[regenerateSlot]：generation +1 全字段重滚，生命周期归待接取）。
 *
 * 数值口径：
 * - FP 档位 800~2800 按危险级线性（13 文档给区间未给分档，裁定：800 + (danger-1) × 500）；
 * - R 词缀开放数 0→2 条按危险级（13 文档只给「0→2 条」，裁定：1/2 级 0 条、3/4 级 1 条、5 级 2 条）；
 * - 报价 = FP × 每 FP 单价 × 难度系数（每 FP 单价 250~500 为 13 文档未定案，提案值）；
 * - 目标势力池 [TARGET_FACTIONS] 为裁定：13 文档列为待定项，按「辖区安全维护」文书口径
 *   取常规威胁势力（海盗/余晖残余/弃舰集群——后两者为自动智能威胁，不涉及三选的势力强化条款）。
 *
 * 词缀在生成时即按 [AffixRegistry.pickAffixes]（rCount 重载）抽取并锁定进
 * [InfiniteSlotState.affixIds]；固定表口径禁相位词缀（allowPhase = false，裁定：
 * 无限赏金属「常设辖区安全维护」，与主线固定表同口径）。
 */
object InfiniteBountyGenerator {

    /** 常驻槽位数（13 定稿：3 槽常驻）。 */
    const val SLOT_COUNT: Int = 3

    /** 无限赏金工单 key 前缀（[keyOf] 生成；BountyCampaignManager 据此分流）。 */
    const val KEY_PREFIX: String = "astd_infinite_"

    /** 账户分组 id（终端账户页「无限期承包」组）。 */
    const val GROUP_ID: String = "indefinite_contract"

    /** FP 档位下限（危险级 1；13 文档区间 800~2800 的下限）。 */
    const val FP_MIN: Int = 800

    /** FP 档位步进（裁定：区间未分档，按危险级线性）。 */
    const val FP_STEP: Int = 500

    /** 每 FP 报价下限（13 文档未定案，提案值）。 */
    const val REWARD_PER_FP_MIN: Int = 250

    /** 每 FP 报价上限（13 文档未定案，提案值）。 */
    const val REWARD_PER_FP_MAX: Int = 500

    /**
     * 目标势力池（裁定：13 文档待定项；按「辖区安全维护」条款取常规威胁势力）。
     * 候选人：海盗 / 余晖残余 / 弃舰集群。
     */
    val TARGET_FACTIONS: List<String> = listOf(Factions.PIRATES, Factions.REMNANTS, Factions.DERELICT)

    /** 危险级 → 预设 FP（800~2800 线性档位，裁定见类 KDoc）。 */
    fun fpForDanger(danger: Int): Int = FP_MIN + (danger.coerceIn(1, 5) - 1) * FP_STEP

    /** 危险级 → R 型词缀开放数（0→2 条，裁定见类 KDoc）。 */
    fun rCountForDanger(danger: Int): Int = when (danger.coerceIn(1, 5)) {
        1, 2 -> 0
        3, 4 -> 1
        else -> 2
    }

    /**
     * 报价锁定：FP × 每 FP 单价（区间内按种子确定性取值）× 难度系数（封顶 5×，
     * 与主线报酬缩放同口径）。生成时锁定，接取/执行期间不变（13：报价锁定）。
     */
    fun quoteReward(fp: Int, kS: Float, seed: Long): Int {
        val rnd = Random(seed xor 0x907E)
        val perFp = REWARD_PER_FP_MIN + rnd.nextInt(REWARD_PER_FP_MAX - REWARD_PER_FP_MIN + 1)
        return (fp * perFp * min(kS.coerceAtLeast(1f), 5f)).roundToInt()
    }

    /** 工单 key（槽位序号 + 换代序号；换代后旧 key 从 active/completed 摘除，新 key 重新挂出）。 */
    fun keyOf(slotIndex: Int, generation: Int): String = "$KEY_PREFIX${slotIndex}_$generation"

    /**
     * 槽位当前代的舰队组建定义（FleetComposer 输入；桥接挂出时的组建/打捞锁定与
     * 管理脚本的舰队重建 patch 共用本定义，保证两条路径同参数）。
     */
    fun toBountyDef(slot: InfiniteSlotState): BountyDef = BountyDef(
        key = keyOf(slot.index, slot.generation),
        title = serialOf(slot.index, slot.generation),
        shortDesc = "",
        threatTier = slot.danger,
        baselineFP = slot.fp,
        flagshipVariantId = slot.flagshipVariantId,
        requiredPreviousMainKey = null,
        isMain = false,
        allowRAffixes = true,
        allowAffixes = true,
        fixedAffixIds = slot.affixIds,
    )

    /** 文书编号（与主线 WG-c209 编号族同口径；%02d=槽位+1，%04d=换代序号）。 */
    fun serialOf(slotIndex: Int, generation: Int): String =
        "WG-c209-8%02d／清除-%04d".format(slotIndex + 1, generation)

    /**
     * 单槽位整代重滚：危险级 / FP / 报价 / 目标势力 / 词缀全部按种子确定性生成。
     *
     * @param index 槽位序号（0..[SLOT_COUNT]-1）
     * @param generation 换代序号（1 起）
     * @param seed 本代随机种子（调用方供给；同种子结果确定）
     * @param kS 难度系数（报价与词缀数量缩放）
     */
    fun rollSlot(index: Int, generation: Int, seed: Long, kS: Float): InfiniteSlotState {
        val rnd = Random(seed)
        val danger = 1 + rnd.nextInt(5)
        val fp = fpForDanger(danger)
        val targetFaction = TARGET_FACTIONS[rnd.nextInt(TARGET_FACTIONS.size)]
        val affixes = AffixRegistry.pickAffixes(kS, rCountForDanger(danger), allowPhase = false, seed = seed xor 0xAFF1)
        return InfiniteSlotState(
            index = index,
            generation = generation,
            danger = danger,
            fp = fp,
            seed = seed,
            quotedReward = quoteReward(fp, kS, seed),
            targetFactionId = targetFaction,
            affixIds = affixes.map { it.id },
        )
    }

    /**
     * 幂等补齐：无限期承包商认证后保证 [BountyState.infiniteSlots] 恒为 [SLOT_COUNT] 槽。
     *
     * 仅在签署后的初始化路径会真正生成（已有槽位直接保留，重复调用无副作用）。
     * 换代序号全局单调（[BountyState.infiniteGeneration]），首次初始化从 1 起。
     *
     * @return 本次新生成的槽位（空列表 = 已补齐，无副作用）
     */
    fun ensureSlots(state: BountyState, kS: Float, seedBase: Long): List<InfiniteSlotState> {
        if (!state.indefiniteContractor) return emptyList()
        if (state.infiniteSlots.size >= SLOT_COUNT) return emptyList()
        val created = ArrayList<InfiniteSlotState>(SLOT_COUNT - state.infiniteSlots.size)
        while (state.infiniteSlots.size < SLOT_COUNT) {
            val index = state.infiniteSlots.size
            val generation = ++state.infiniteGeneration
            val slot = rollSlot(index, generation, seedBase xor (0x5EEDL + index * 31L + generation), kS)
            state.infiniteSlots += slot
            created += slot
        }
        return created
    }

    /**
     * 核销换代：指定槽位 generation +1 全字段重滚，生命周期归待接取。
     *
     * @return 新一代槽位；槽位序号越界返回 null（调用方保证序号来自快照，属契约外输入）
     */
    fun regenerateSlot(state: BountyState, slotIndex: Int, kS: Float, seedBase: Long): InfiniteSlotState? {
        val pos = state.infiniteSlots.indexOfFirst { it.index == slotIndex }
        if (pos < 0) return null
        val generation = ++state.infiniteGeneration
        val slot = rollSlot(slotIndex, generation, seedBase xor (0x5EEDL + slotIndex * 31L + generation), kS)
        state.infiniteSlots[pos] = slot
        return slot
    }
}
