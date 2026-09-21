package cn.kasuminova.astd.combat.affix

import cn.kasuminova.astd.combat.affix.AffixRegistry.EXCLUSIVE_PAIRS
import cn.kasuminova.astd.combat.affix.AffixRegistry.pickAffixes
import cn.kasuminova.astd.combat.affix.AffixRegistry.slotCounts
import cn.kasuminova.astd.internal.i18n.I18n
import java.util.Random
import kotlin.math.floor

/**
 * 词缀注册表（v3 定稿，对应 docs/design/bounty/affixes.md）。
 *
 * 词缀 = 菀星设计总局通用改装标准（改装件），17 条：
 * S 型×8（编目号 S-01~S-08）、M 型×6（M-09~M-14）、R 型×3（R-15~R-17）。
 *
 * 职责边界：
 * - 本注册表只维护词缀的静态编目数据与“按难度系数抽取”的纯逻辑；
 *   具体战斗效果由各词缀对应的隐藏 HullMod 实现（数值走轨一 [cn.kasuminova.astd.api.difficulty.DifficultyTuning]）。
 * - 抽取数量完全由难度系数搭配表决定（D20 定案）：S 2~4 + M 1~2 常驻；R 1~2 由调用方显式开关（allowR）。
 * - 互斥表 3 对与相位舰船约束 3 条在抽取时强制（见 [pickAffixes]）。
 */
object AffixRegistry {

    /**
     * 词缀类型（层级即稀有度：R >> M > S）。
     */
    enum class AffixType {
        S, M, R
    }

    /**
     * 单条词缀的编目定义。
     *
     * @property id 词缀 id（即规格书 ID，与隐藏 HullMod id 一致）
     * @property catalogNo 编目号（S-01 等；赏金文书"追加条款"栏的条款编号）
     * @property type 词缀类型（层级/稀有度）
     * @property hullModId 对应的隐藏 HullMod id（与 [id] 一致，单独保留以便 CSV/面板按 hullmod 反查）
     * @property phaseOnly 仅相位舰船可搭载（抽取时按舰队相位能力过滤，HullMod 侧再按舰体判定生效）
     */
    data class AffixDef(
        val id: String,
        val catalogNo: String,
        val type: AffixType,
        val hullModId: String,
        val phaseOnly: Boolean = false,
    ) {
        /** 词缀展示名（i18n，category 沿用 asteria_directorate_bounty）。 */
        fun displayName(): String = I18n[CATEGORY, "affix.$id.name"]

        /** 词缀效果简述（i18n）。 */
        fun description(): String = I18n[CATEGORY, "affix.$id.desc"]
    }

    const val CATEGORY: String = "asteria_directorate_bounty"

    // ─── S 型词缀（S-01 ~ S-08） ───

    const val ID_IRONCLAD_PLATING: String = "astd_affix_ironclad_plating"
    const val ID_CRYO_FLUX_NETWORK: String = "astd_affix_cryo_flux_network"
    const val ID_FLUX_COIL_EXPANSION: String = "astd_affix_flux_coil_expansion"
    const val ID_POLARIZED_SHIELD: String = "astd_affix_polarized_shield"
    const val ID_ENGINE_OVERCLOCK: String = "astd_affix_engine_overclock"
    const val ID_DIMENSIONAL_SPECIALTY: String = "astd_affix_dimensional_specialty"
    const val ID_PHASE_COIL_TUNING: String = "astd_affix_phase_coil_tuning"
    const val ID_PHASE_COIL_DETUNING: String = "astd_affix_phase_coil_detuning"

    // ─── M 型词缀（M-09 ~ M-14） ───

    const val ID_RECURSIVE_TARGETING: String = "astd_affix_recursive_targeting"
    const val ID_REACTIVE_FLUX_ARMOR: String = "astd_affix_reactive_flux_armor"
    const val ID_PSPACE_DIVER: String = "astd_affix_pspace_diver"
    const val ID_ENGINE_FLUX_ISOLATION: String = "astd_affix_engine_flux_isolation"
    const val ID_SWARM_COORDINATION: String = "astd_affix_swarm_coordination"
    const val ID_PLASMA_ARMOR_SHIELD: String = "astd_affix_plasma_armor_shield"

    // ─── R 型词缀（R-15 ~ R-17） ───

    const val ID_GRID_DEEPENING: String = "astd_affix_grid_deepening"
    const val ID_AGGRESSIVE_SWARM_NETWORK: String = "astd_affix_aggressive_swarm_network"
    const val ID_SINGULARITY_DRIVE: String = "astd_affix_singularity_drive"

    /** 全部词缀编目（按编目号排序）。 */
    val all: List<AffixDef> = listOf(
        AffixDef(ID_IRONCLAD_PLATING, "S-01", AffixType.S, ID_IRONCLAD_PLATING),
        AffixDef(ID_CRYO_FLUX_NETWORK, "S-02", AffixType.S, ID_CRYO_FLUX_NETWORK),
        AffixDef(ID_FLUX_COIL_EXPANSION, "S-03", AffixType.S, ID_FLUX_COIL_EXPANSION),
        AffixDef(ID_POLARIZED_SHIELD, "S-04", AffixType.S, ID_POLARIZED_SHIELD),
        AffixDef(ID_ENGINE_OVERCLOCK, "S-05", AffixType.S, ID_ENGINE_OVERCLOCK),
        AffixDef(ID_DIMENSIONAL_SPECIALTY, "S-06", AffixType.S, ID_DIMENSIONAL_SPECIALTY),
        AffixDef(ID_PHASE_COIL_TUNING, "S-07", AffixType.S, ID_PHASE_COIL_TUNING, phaseOnly = true),
        AffixDef(ID_PHASE_COIL_DETUNING, "S-08", AffixType.S, ID_PHASE_COIL_DETUNING, phaseOnly = true),
        AffixDef(ID_RECURSIVE_TARGETING, "M-09", AffixType.M, ID_RECURSIVE_TARGETING),
        AffixDef(ID_REACTIVE_FLUX_ARMOR, "M-10", AffixType.M, ID_REACTIVE_FLUX_ARMOR),
        AffixDef(ID_PSPACE_DIVER, "M-11", AffixType.M, ID_PSPACE_DIVER, phaseOnly = true),
        AffixDef(ID_ENGINE_FLUX_ISOLATION, "M-12", AffixType.M, ID_ENGINE_FLUX_ISOLATION),
        AffixDef(ID_SWARM_COORDINATION, "M-13", AffixType.M, ID_SWARM_COORDINATION),
        AffixDef(ID_PLASMA_ARMOR_SHIELD, "M-14", AffixType.M, ID_PLASMA_ARMOR_SHIELD),
        AffixDef(ID_GRID_DEEPENING, "R-15", AffixType.R, ID_GRID_DEEPENING),
        AffixDef(ID_AGGRESSIVE_SWARM_NETWORK, "R-16", AffixType.R, ID_AGGRESSIVE_SWARM_NETWORK),
        AffixDef(ID_SINGULARITY_DRIVE, "R-17", AffixType.R, ID_SINGULARITY_DRIVE),
    )

    /**
     * 互斥表（3 对，affixes.md 组合约束）：
     * - 六相冰辐能网络 ↔ 极限辐能线圈扩容；
     * - 相位线圈调谐 ↔ 相位线圈降频；
     * - 电网深化升级（捆绑六相冰效果）↔ 极限辐能线圈扩容。
     */
    val EXCLUSIVE_PAIRS: List<Set<String>> = listOf(
        setOf(ID_CRYO_FLUX_NETWORK, ID_FLUX_COIL_EXPANSION),
        setOf(ID_PHASE_COIL_TUNING, ID_PHASE_COIL_DETUNING),
        setOf(ID_GRID_DEEPENING, ID_FLUX_COIL_EXPANSION),
    )

    /**
     * 一次抽取的槽位数量（数量搭配表，D20 定案）。
     *
     * 数量完全由难度系数 k_s 决定：k_s=1 取区间下限、k_s=5 取上限、中间分段线性（半值进位）。
     *
     * @property sCount S 型数量，2~4，常驻
     * @property mCount M 型数量，1~2，常驻
     * @property rSlots R 型槽位数，1~2；仅在 allowR 时出现（槽位不代表必出，见 [pickAffixes] 的低权重落空）
     */
    data class AffixSlots(val sCount: Int, val mCount: Int, val rSlots: Int)

    fun getById(id: String): AffixDef? = all.firstOrNull { it.id == id }

    fun getByHullModId(hullModId: String): AffixDef? = all.firstOrNull { it.hullModId == hullModId }

    /**
     * 校验固定词缀表（主线工单按文书追加条款具名编目号声明的固定搭配，见 MainBounties.Stage.fixedAffixIds）。
     *
     * 固定表与随机抽取走同一套合法性规则：
     * - id 必须已编目且不重复；
     * - 不得违反互斥表 [EXCLUSIVE_PAIRS]；
     * - 不得包含相位限定词缀（固定表作用于整支编队，编成随缩放变化，无法保证相位舰在场）。
     *
     * @return 违规描述列表；空列表 = 合法。纯逻辑，不触碰 Global，可直接单测。
     */
    fun validateFixedTable(ids: List<String>): List<String> {
        val violations = ArrayList<String>(4)
        val seen = HashSet<String>(ids.size)
        for (id in ids) {
            val def = getById(id)
            if (def == null) {
                violations += "未知词缀 id：$id"
                continue
            }
            if (!seen.add(id)) {
                violations += "词缀重复：$id"
            }
            if (def.phaseOnly) {
                violations += "相位限定词缀不允许进入固定表：$id"
            }
        }
        for (pair in EXCLUSIVE_PAIRS) {
            val present = pair.filter { it in seen }
            if (present.size > 1) {
                violations += "互斥词缀共存：${present.joinToString(" + ")}"
            }
        }
        return violations
    }

    /**
     * 计算数量搭配表。
     *
     * @param kS 难度系数（轨一固有缩放系数，1.0~5.0；越界自动收敛）
     * @param allowR 是否允许 R 型出现（仅第三章赏金与结局后无限赏金，由调用方显式开关）
     */
    fun slotCounts(kS: Float, allowR: Boolean): AffixSlots {
        val t = normalizeKS(kS)
        return AffixSlots(
            sCount = roundHalfUp(2f + 2f * t),
            mCount = roundHalfUp(1f + t),
            rSlots = if (allowR) roundHalfUp(1f + t) else 0,
        )
    }

    /**
     * 按难度系数抽取词缀（纯逻辑，不触碰 Global，可直接单测）。
     *
     * 规则：
     * - 数量由 [slotCounts] 决定；同 ID 不叠加；互斥表 3 对在抽取时强制（后抽者跳过冲突项）；
     * - [allowPhase] 为 false 时，相位限定词缀（调谐/降频/P空间深潜器）从池剔除；
     * - R 型在允许出现时仍按高稀有度走低权重：每个 R 槽位只有
     *   `0.35 + 0.65 * t` 的概率被填充（k_s=1 时约三分之一，k_s=5 时必然填充）。
     *
     * @param kS 难度系数（1.0~5.0）
     * @param allowR R 型开关（调用方显式传入）
     * @param allowPhase 舰队是否具备相位舰船（相位限定词缀的总开关）
     * @param seed 随机种子（同种子结果确定）
     * @return 抽中的词缀，顺序为 S → M → R
     */
    fun pickAffixes(kS: Float, allowR: Boolean, allowPhase: Boolean, seed: Long): List<AffixDef> {
        val t = normalizeKS(kS)
        val slots = slotCounts(kS, allowR)
        val rnd = Random(seed)
        val picked = ArrayList<AffixDef>(slots.sCount + slots.mCount + slots.rSlots)

        draw(AffixType.S, slots.sCount, slotChance = 1f, allowPhase, rnd, picked)
        draw(AffixType.M, slots.mCount, slotChance = 1f, allowPhase, rnd, picked)
        draw(AffixType.R, slots.rSlots, slotChance = 0.35f + 0.65f * t, allowPhase, rnd, picked)

        return picked
    }

    /**
     * 无限赏金口径的抽取（docs/story/13：结局后无限赏金开放 R 全谱系）。
     *
     * 与 [pickAffixes] 的差异：R 型数量由调用方按危险等级显式给定（0→2 条），
     * 且必定填满——不走 allowR 槽位的低权重落空（无限赏金的 R 开放是文书明示条款，
     * 不是稀有度抽奖）；S/M 数量仍由难度系数搭配表决定。
     *
     * @param rCount R 型条数（收敛到 0~2）
     */
    fun pickAffixes(kS: Float, rCount: Int, allowPhase: Boolean, seed: Long): List<AffixDef> {
        val slots = slotCounts(kS, allowR = false)
        val rnd = Random(seed)
        val picked = ArrayList<AffixDef>(slots.sCount + slots.mCount + rCount.coerceAtLeast(0))

        draw(AffixType.S, slots.sCount, slotChance = 1f, allowPhase, rnd, picked)
        draw(AffixType.M, slots.mCount, slotChance = 1f, allowPhase, rnd, picked)
        draw(AffixType.R, rCount.coerceIn(0, 2), slotChance = 1f, allowPhase, rnd, picked)

        return picked
    }

    /** k_s → [0,1] 归一化插值参数（1.0 取下限、5.0 取上限）。 */
    fun normalizeKS(kS: Float): Float = (kS.coerceIn(1f, 5f) - 1f) / 4f

    private fun draw(
        type: AffixType,
        count: Int,
        slotChance: Float,
        allowPhase: Boolean,
        rnd: Random,
        picked: MutableList<AffixDef>,
    ) {
        if (count <= 0) return
        val pool = all.filter { it.type == type && (allowPhase || !it.phaseOnly) }
        if (pool.isEmpty()) return
        val candidates = pool.shuffled(rnd)
        var idx = 0
        repeat(count) {
            if (rnd.nextFloat() < slotChance) {
                while (idx < candidates.size && conflictsWithPicked(candidates[idx], picked)) idx++
                if (idx < candidates.size) {
                    picked += candidates[idx]
                    idx++
                }
            }
        }
    }

    private fun conflictsWithPicked(candidate: AffixDef, picked: List<AffixDef>): Boolean {
        if (picked.any { it.id == candidate.id }) return true
        return EXCLUSIVE_PAIRS.any { pair ->
            candidate.id in pair && picked.any { it.id in pair }
        }
    }

    private fun roundHalfUp(v: Float): Int = floor(v + 0.5f).toInt()
}
