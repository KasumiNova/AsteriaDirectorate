package cn.kasuminova.astd.campaign.bounty

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.fleet.FleetMemberType
import org.apache.log4j.Logger
import java.util.Random

/**
 * 将“缩放模型 + 舰船池 + 词缀规则”落到具体 fleet 成员列表。
 *
 * - 舰队规模：预设 FP × [DifficultyModel] 总缩放，以实际生成舰船的 FP 核算预算。
 *   大编队作为战斗增援进入，不在配置的舰队大小处静默丢弃剩余预算。
 * - 词缀：由 [BountyDef.affixRule] 驱动（affixes.md v3 搭配表：S 2~4 + M 1~2 + R 按章解禁），
 *   候选池见 [AffixPools]；相位约束词缀只搭载到相位舰。
 */
object FleetComposer {

    private val log: Logger = Global.getLogger(FleetComposer::class.java)

    data class Composition(
        val pickedVariantIds: List<String>,
        val affixHullMods: List<String>,
        /** 词缀中仅相位舰船可搭载的子集（[AffixPools] 口径）。 */
        val phaseOnlyHullMods: List<String>,
        val k: Float,
        val totalMult: Float,
    )

    fun buildComposition(
        def: BountyDef,
        seed: Long,
    ): Composition {
        val scale = DifficultyModel.compute(def.baselineFP)
        val desiredFP = (def.baselineFP.toFloat() * scale.totalMult).toInt().coerceAtLeast(def.baselineFP)

        val rnd = Random(seed)
        val modPool = VariantPools.modStandardVariants().filter { id ->
            val variant = Global.getSettings().getVariant(id)
            if (def.chapter == 0) variant.hullSize < com.fs.starfarer.api.combat.ShipAPI.HullSize.CRUISER
            else true
        }
        val randomPool = VariantPools.randomPresetVariants().filter { id ->
            val variant = Global.getSettings().getVariant(id)
            if (def.chapter == 0) variant.hullSize <= com.fs.starfarer.api.combat.ShipAPI.HullSize.CRUISER
            else true
        }

        val picked = ArrayList<String>(64)
        picked.add(def.flagshipVariantId)
        picked.addAll(def.coreVariantIds)

        val pickedFp = picked.sumOf { id ->
            requireNotNull(VariantPools.variantFpCostOrNull(id)) { "Invalid bounty flagship/core variant: $id" }
        }
        val remainingFP = (desiredFP - pickedFp).coerceAtLeast(0)

        val budgets: List<Pair<List<String>, Int>> = if (def.modOnlyComposition) {
            listOf(modPool to remainingFP)
        } else {
            val modBudget = (remainingFP * if (def.chapter == 0) 0.15f else 0.50f).toInt()
            listOf(modPool to modBudget, randomPool to remainingFP - modBudget)
        }

        for ((pool, budget) in budgets) {
            val costs = pool.associateWith { id ->
                requireNotNull(VariantPools.variantFpCostOrNull(id)) { "Invalid bounty variant: $id" }
            }.filterValues { it > 0 }
            picked += fillBudget(costs, budget, rnd)
        }

        // 3) 词缀选择：固有难度取档，玩家超模评估不改变搭配数量。
        val affixPick = AffixPools.pick(def.affixRule, scale.k, seed xor 0x5EED5EED)

        return Composition(
            pickedVariantIds = picked,
            affixHullMods = affixPick.affixHullMods,
            phaseOnlyHullMods = affixPick.phaseOnlyHullMods,
            k = scale.k,
            totalMult = scale.totalMult,
        )
    }

    /**
     * 按实际 FP 填充编成，不以船数或随机重试次数截断预算。
     * 优先选能装入剩余预算的预设；不足最小舰船成本时以最小舰船补齐，误差小于一艘舰。
     * 排序池与二分上界避免大规模赏金逐次扫描全部预设。
     */
    internal fun fillBudget(costs: Map<String, Int>, budget: Int, random: Random): List<String> {
        require(budget >= 0)
        if (budget == 0) return emptyList()
        require(costs.isNotEmpty() && costs.values.all { it > 0 }) { "Bounty fleet pool has no valid positive-FP variants" }
        val pool = costs.entries.sortedWith(compareBy({ it.value }, { it.key }))
        val result = ArrayList<String>()
        var remaining = budget
        while (remaining > 0) {
            var low = 0
            var high = pool.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (pool[middle].value <= remaining) low = middle + 1 else high = middle
            }
            val pick = pool[if (low == 0) 0 else random.nextInt(low)]
            result += pick.key
            remaining -= pick.value
        }
        return result
    }

    fun rebuildFleetMembers(
        bountyKey: String,
        fleetMembers: List<String>,
        affixHullMods: List<String>,
        phaseOnlyHullMods: List<String>,
        flagship: FleetMemberAPI,
        flagshipDMods: Int,
        seed: Long,
    ): List<FleetMemberAPI> {
        val factory = Global.getFactory()
        val created = ArrayList<FleetMemberAPI>(fleetMembers.size)

        // 旗舰保持原对象，但补上缩放/词缀/D-mod。
        applyBountyHullMods(flagship, affixHullMods, phaseOnlyHullMods)
        if (flagshipDMods > 0) {
            applyRandomDMods(flagship, flagshipDMods, seed xor 0xD40D)
        }
        created.add(flagship)

        for (vid in fleetMembers.drop(1)) {

            val member = try {
                factory.createFleetMember(FleetMemberType.SHIP, vid)
            } catch (t: Throwable) {
                log.warn("[FleetComposer] Failed to create fleet member for variant=$vid (bounty=$bountyKey): ${t.message}")
                null
            } ?: continue

            applyBountyHullMods(member, affixHullMods, phaseOnlyHullMods)
            created.add(member)
        }
        return created
    }

    private fun applyBountyHullMods(
        member: FleetMemberAPI,
        affixHullMods: List<String>,
        phaseOnlyHullMods: List<String>,
    ) {
        // 不能在共享 stock variant 上加词缀，否则后续生成的普通舰船也会被永久污染。
        val variant = requireNotNull(member.variant).clone()
        variant.setSource(com.fs.starfarer.api.loading.VariantSource.REFIT)
        variant.originalVariant = null
        val isPhase = member.isPhaseShip
        variant.addPermaMod("astd_bounty_scaling")
        for (hm in affixHullMods) {
            if (hm in phaseOnlyHullMods && !isPhase) continue
            variant.addPermaMod(hm)
        }
        member.setVariant(variant, false, false)
        member.repairTracker.cr = 0.7f
    }

    /** 给目标旗舰添加可适用的随机 D-mod，由原版筛选互斥与舰体约束。 */
    private fun applyRandomDMods(member: FleetMemberAPI, count: Int, seed: Long) {
        com.fs.starfarer.api.impl.campaign.DModManager.addDMods(member, true, count, Random(seed))
    }

    private object VariantPools {
        private val cachedFpCost: MutableMap<String, Int> = HashMap()

        private fun combatVariants(): List<String> = Global.getSettings().allVariantIds.filter { id ->
            val variant = Global.getSettings().getVariant(id)
            variant.isCombat && !variant.isEmptyHullVariant && !variant.isFighter && !variant.isStation &&
                !variant.isCivilian && variant.hullSpec.fleetPoints > 0 &&
                !variant.hints.contains(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.MODULE)
        }

        fun modStandardVariants(): List<String> =
            combatVariants().filter { it.startsWith("astd_") && it.endsWith("_Standard") }

        fun randomPresetVariants(): List<String> =
            combatVariants().filter { !it.startsWith("astd_") }

        fun variantFpCostOrNull(variantId: String): Int? = cachedFpCost[variantId] ?: run {
            val variant = Global.getSettings().getVariant(variantId) ?: return null
            variant.hullSpec.fleetPoints.also { cachedFpCost[variantId] = it }
        }
    }
}
