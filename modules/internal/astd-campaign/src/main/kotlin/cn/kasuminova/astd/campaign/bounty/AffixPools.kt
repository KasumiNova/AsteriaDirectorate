package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.combat.affix.AffixRegistry.AffixType
import java.util.Random

/**
 * 词缀池抽取：按 [AffixRule]（主线定义侧传递的搭配规则）从 [AffixRegistry]（v3 定案池）取词缀。
 *
 * 与注册表的分工：
 * - 池内容、S/M/R 分类、互斥表、相位约束标记均以 [AffixRegistry] 为唯一真相来源；
 * - 本层负责“规则传递”：把 [BountyDef.affixRule] 转成实际抽取数量——S/M 按难度系数取档，
 *   R 型按章节口径定量（第三章单 3 起固定至少 1 条；第四章阶段三固定 2 条打满搭配表），
 *   R 先抽、互斥冲突时低稀有度让位（与注册表口径一致）。
 */
object AffixPools {

    /** 抽取结果：hullModId 列表 + 其中仅相位舰船可搭载的子集（落舰过滤见 [FleetComposer]）。 */
    data class Pick(
        val affixHullMods: List<String>,
        val phaseOnlyHullMods: List<String>,
    )

    /**
     * 按规则抽取词缀。
     *
     * @param pool 候选池；默认取 [AffixRegistry.all]，测试可注入合成池
     */
    fun pick(
        rule: AffixRule,
        k: Float,
        seed: Long,
        pool: List<AffixRegistry.AffixDef> = AffixRegistry.all(),
    ): Pick {
        val counts = rule.counts(k)
        if (counts.total <= 0) return Pick(emptyList(), emptyList())

        val rnd = Random(seed)
        val picked = LinkedHashMap<String, AffixRegistry.AffixDef>()

        fun conflictsWithPicked(def: AffixRegistry.AffixDef): Boolean =
            picked.values.any { AffixRegistry.isMutuallyExclusive(it.id, def.id) }

        fun pickType(type: AffixType, target: Int) {
            if (target <= 0) return
            val candidates = pool.filter { it.type == type }.shuffled(rnd)
            var added = 0
            for (def in candidates) {
                if (added >= target) break
                if (def.id in picked) continue
                if (conflictsWithPicked(def)) continue
                picked[def.id] = def
                added++
            }
        }

        // R 型高稀有度：先定量抽 R，再补 S/M（互斥冲突时低稀有度让位）。
        pickType(AffixType.R, counts.r)
        pickType(AffixType.S, counts.s)
        pickType(AffixType.M, counts.m)

        return Pick(
            affixHullMods = picked.values.map { it.hullModId },
            phaseOnlyHullMods = picked.values.filter { it.phaseOnly }.map { it.hullModId },
        )
    }
}
