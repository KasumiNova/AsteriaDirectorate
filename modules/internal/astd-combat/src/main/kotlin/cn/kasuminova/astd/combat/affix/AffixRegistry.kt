package cn.kasuminova.astd.combat.affix

import cn.kasuminova.astd.internal.i18n.I18n
import java.util.Random

/**
 * 词缀注册表（affixes.md v3.0 / D20 定案）：
 * - 词缀 = 菀星设计总局通用改装标准（改装件），稀有度层级 R > M > S；
 * - 仅维护"解锁与选择"的数据；具体效果由隐藏 HullMod 实现（hullModId 与 csv 中隐藏 hullmod 一一对应）。
 *
 * 抽取规则（数量完全由难度系数 k 决定，章节数量挂钩表已作废）：
 * - S 型 2~4 条、M 型 1~2 条，始终出现；
 * - R 型 1~2 条，仅第三章赏金与结局后的无限赏金开放（调用方以 `rTierAllowed` 声明），
 *   开放章节内仍按高稀有度走低权重；
 * - 同 ID 不叠加；互斥表见 [isMutuallyExclusive]；互斥冲突时低稀有度让位（R 先抽）。
 */
object AffixRegistry {

    private const val I18N_CATEGORY = "asteria_directorate_bounty"

    /**
     * 词缀类型（稀有度层级）。
     *
     * @property tier 供 UI 着色/标签使用的层级序号（S=1, M=2, R=3），沿用旧 `tier` 口径。
     */
    enum class AffixType(val tier: Int) {
        S(1),
        M(2),
        R(3),
    }

    data class AffixDef(
        val id: String,
        /** 改装件编目号（S-01~S-08 / M-09~M-14 / R-15~R-17），即赏金文书"追加条款"栏的条款编号。 */
        val catalog: String,
        val type: AffixType,
        val nameKey: String,
        val hullModId: String,
        val unlockAfterMain: Int,
        /** 仅相位舰船可搭载（相位线圈调谐 / 相位线圈降频 / P空间深潜器）；落舰约束由 HullMod 适用性判定执行。 */
        val phaseOnly: Boolean = false,
    ) {
        /** 层级序号（等价于 [AffixType.tier]），供既有 UI 面板读取。 */
        val tier: Int get() = type.tier

        fun displayName(): String = I18n[I18N_CATEGORY, nameKey]
        fun description(): String = I18n[I18N_CATEGORY, "affix.$id.desc"]
    }

    // 约定：hullModId = "astd_affix_" + id，与 csv 中隐藏 hullmod 一一对应。
    private fun def(id: String, catalog: String, type: AffixType, phaseOnly: Boolean = false) =
        AffixDef(
            id = id,
            catalog = catalog,
            type = type,
            nameKey = "affix.$id.name",
            hullModId = "astd_affix_$id",
            unlockAfterMain = 0,
            phaseOnly = phaseOnly,
        )

    private val defs: List<AffixDef> = listOf(
        // S 型（8 条）
        def("ironclad_plating", "S-01", AffixType.S),
        def("cryo_flux_network", "S-02", AffixType.S),
        def("flux_coil_expansion", "S-03", AffixType.S),
        def("polarized_shield", "S-04", AffixType.S),
        def("engine_overclock", "S-05", AffixType.S),
        def("dimensional_specialty", "S-06", AffixType.S),
        def("phase_coil_tuning", "S-07", AffixType.S, phaseOnly = true),
        def("phase_coil_detuning", "S-08", AffixType.S, phaseOnly = true),
        // M 型（6 条）
        def("recursive_targeting", "M-09", AffixType.M),
        def("reactive_flux_armor", "M-10", AffixType.M),
        def("pspace_diver", "M-11", AffixType.M, phaseOnly = true),
        def("engine_flux_isolation", "M-12", AffixType.M),
        def("swarm_coordination", "M-13", AffixType.M),
        def("plasma_armor_shield", "M-14", AffixType.M),
        // R 型（3 条）
        def("grid_deepening", "R-15", AffixType.R),
        def("aggressive_swarm_network", "R-16", AffixType.R),
        def("singularity_drive", "R-17", AffixType.R),
    )

    /**
     * 互斥表（affixes.md v3.0）：
     * - 六相冰辐能网络 ↔ 极限辐能线圈扩容；
     * - 相位线圈调谐 ↔ 相位线圈降频；
     * - 电网深化升级（含六相冰捆绑效果）↔ 极限辐能线圈扩容。
     */
    private val MUTEX_PAIRS: Set<Set<String>> = setOf(
        setOf("cryo_flux_network", "flux_coil_expansion"),
        setOf("phase_coil_tuning", "phase_coil_detuning"),
        setOf("grid_deepening", "flux_coil_expansion"),
    )

    // R 型在开放章节内的低权重口径：首条基础概率 25%（+45%·k），第二条基础 5%（+35%·k）。
    private const val R_FIRST_BASE_CHANCE = 0.25f
    private const val R_FIRST_K_CHANCE = 0.45f
    private const val R_SECOND_BASE_CHANCE = 0.05f
    private const val R_SECOND_K_CHANCE = 0.35f

    fun all(): List<AffixDef> = defs

    fun initialUnlock(): Set<String> = defs.filter { it.unlockAfterMain <= 0 }.map { it.id }.toSet()

    fun unlockForMainCompleted(mainCompleted: Int): Set<String> =
        defs.filter { it.unlockAfterMain <= mainCompleted }.map { it.id }.toSet()

    fun getById(id: String): AffixDef? = defs.firstOrNull { it.id == id }

    fun getByHullModId(hullModId: String): AffixDef? = defs.firstOrNull { it.hullModId == hullModId }

    /** 两个词缀是否互斥（对称关系）。 */
    fun isMutuallyExclusive(firstId: String, secondId: String): Boolean =
        firstId != secondId && MUTEX_PAIRS.any { firstId in it && secondId in it }

    /**
     * 按难度系数抽取词缀组合。
     *
     * @param unlockedIds 已解锁词缀 id（仅这些进入抽取池）。
     * @param mainCompleted 已完成主线数量。v3 起数量不再与章节挂钩，仅为兼容既有调用方保留。
     * @param threatTier 威胁档位。同上，仅为兼容保留。
     * @param k 进程动态系数 k_p（0..1，超界自动钳制）；决定 S/M 数量与 R 权重。
     * @param seed 随机种子；相同入参必得相同结果。
     * @param rTierAllowed R 型开放口径：仅第三章赏金与结局后的无限赏金传 `true`。
     */
    @Suppress("UNUSED_PARAMETER")
    fun pickAffixes(
        unlockedIds: Set<String>,
        mainCompleted: Int,
        threatTier: Int,
        k: Float,
        seed: Long,
        rTierAllowed: Boolean = false,
    ): List<AffixDef> {
        val kc = k.coerceIn(0f, 1f)
        val sTarget = (2 + kc * 2f).toInt().coerceIn(2, 4)
        val mTarget = (1 + kc).toInt().coerceIn(1, 2)

        val rnd = Random(seed)
        // java.util.Random 线性同余未充分混合：相邻种子的首次抽取强相关（实测 seeds 0..499 首次
        // nextFloat 全部 ≈0.731），直接用作 R 权重首判会导致整段种子区间结果趋同，先丢弃一次。
        rnd.nextLong()
        var rTarget = 0
        if (rTierAllowed) {
            if (rnd.nextFloat() < R_FIRST_BASE_CHANCE + R_FIRST_K_CHANCE * kc) rTarget = 1
            if (rTarget == 1 && rnd.nextFloat() < R_SECOND_BASE_CHANCE + R_SECOND_K_CHANCE * kc) rTarget = 2
        }

        val picked = LinkedHashMap<String, AffixDef>()

        fun conflictsWithPicked(def: AffixDef): Boolean =
            picked.values.any { isMutuallyExclusive(it.id, def.id) }

        fun pickType(type: AffixType, target: Int) {
            if (target <= 0) return
            val pool = defs.filter { it.type == type && it.id in unlockedIds }.shuffled(rnd)
            var added = 0
            for (def in pool) {
                if (added >= target) break
                if (conflictsWithPicked(def)) continue
                picked[def.id] = def
                added++
            }
        }

        // 稀有度高的先抽：互斥冲突时低稀有度让位。
        pickType(AffixType.R, rTarget)
        pickType(AffixType.S, sTarget)
        pickType(AffixType.M, mTarget)
        return picked.values.toList()
    }
}
