package cn.kasuminova.astd.combat.affix

import cn.kasuminova.astd.internal.i18n.I18n

/**
 * 词缀注册表：
 * - 仅维护“解锁与选择”的数据；具体效果由隐藏 HullMod 实现。
 */
object AffixRegistry {

    data class AffixDef(
        val id: String,
        val nameKey: String,
        val tier: Int,
        val hullModId: String,
        val unlockAfterMain: Int,
    ) {
        fun displayName(): String = I18n["asteria_directorate_bounty", nameKey]
        fun description(): String = I18n["asteria_directorate_bounty", "affix.$id.description"]
    }

    // 约定：hullModId 与 csv 中隐藏 hullmod 一一对应。
    private val defs: List<AffixDef> = listOf(
        AffixDef("overclocked_coils", "affix.overclocked_coils.name", 1, "astd_affix_overclocked_coils", 0),
        AffixDef("jamming_nodes", "affix.jamming_nodes.name", 1, "astd_affix_jamming_nodes", 0),
        AffixDef("entropy_shields", "affix.entropy_shields.name", 2, "astd_affix_entropy_shields", 2),
        AffixDef("reckless_drive", "affix.reckless_drive.name", 2, "astd_affix_reckless_drive", 3),
        AffixDef("phase_instability", "affix.phase_instability.name", 3, "astd_affix_phase_instability", 5),
        AffixDef("zero_margin", "affix.zero_margin.name", 3, "astd_affix_zero_margin", 6),
    )

    fun initialUnlock(): Set<String> = defs.filter { it.unlockAfterMain <= 0 }.map { it.id }.toSet()

    fun unlockForMainCompleted(mainCompleted: Int): Set<String> =
        defs.filter { it.unlockAfterMain <= mainCompleted }.map { it.id }.toSet()

    fun getById(id: String): AffixDef? = defs.firstOrNull { it.id == id }

    fun getByHullModId(hullModId: String): AffixDef? = defs.firstOrNull { it.hullModId == hullModId }

    fun pickAffixes(
        unlockedIds: Set<String>,
        mainCompleted: Int,
        threatTier: Int,
        // 0..1
        k: Float,
        seed: Long,
    ): List<AffixDef> {
        // 随主线推进与威胁等级提高“词缀数量上限”。
        val maxCount = (1 + (mainCompleted / 3)).coerceIn(1, 6)
        // 难度越高，允许抽更高 tier。
        val tierCap = (1 + (k * 3.0f)).toInt().coerceIn(1, 3).coerceAtMost(threatTier.coerceIn(1, 5))
        val pool = defs.filter { it.id in unlockedIds && it.tier <= tierCap }
        if (pool.isEmpty()) return emptyList()

        val rnd = java.util.Random(seed)
        val picked = LinkedHashSet<AffixDef>()

        // 轻度偏好高 tier：k 越高，越倾向抽到 tierCap。
        val weighted = pool.flatMap { def ->
            val w = when (def.tier) {
                1 -> 1
                2 -> if (k > 0.35f) 2 else 1
                else -> if (k > 0.65f) 3 else 1
            }
            List(w) { def }
        }

        val targetCount = (1 + (k * (maxCount - 1)).toInt()).coerceIn(1, maxCount)
        var guard = 200
        while (picked.size < targetCount && guard-- > 0) {
            picked.add(weighted[rnd.nextInt(weighted.size)])
        }
        return picked.toList()
    }
}
