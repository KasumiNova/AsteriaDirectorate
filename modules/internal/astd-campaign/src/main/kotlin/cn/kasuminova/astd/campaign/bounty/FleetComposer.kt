package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.fleet.FleetMemberType
import org.apache.log4j.Logger
import java.util.Random

/**
 * 将“缩放模型 + 舰船池 + 词缀”落到具体 fleet 成员列表。
 */
object FleetComposer {

    private val log: Logger = Global.getLogger(FleetComposer::class.java)

    data class Composition(
        val pickedVariantIds: List<String>,
        val affixHullMods: List<String>,
        val k: Float,
        val totalMult: Float,
    )

    fun buildComposition(
        def: BountyDef,
        state: BountyState,
        seed: Long,
    ): Composition {
        val scale = DifficultyModel.compute(def.threatTier, def.baselineFP)
        val desiredFP = (def.baselineFP.toFloat() * scale.totalMult).toInt().coerceAtLeast(def.baselineFP)

        val rnd = Random(seed)
        val modPool = VariantPools.modStandardVariants()
        val randomPool = VariantPools.randomPresetVariants()
        val omegaPool = VariantPools.omegaLikeVariants()

        val picked = ArrayList<String>(64)
        // 1) 先塞一个旗舰（强制）
        picked.add(def.flagshipVariantId)
        // 2) 再塞固定“核心编成”（用于还原案子基础阵容）
        if (def.coreVariantIds.isNotEmpty()) {
            picked.addAll(def.coreVariantIds)
        }

        // 核心编成可能已经消耗了不少预算：后续按“剩余预算”去填池。
        val pickedFp = picked.sumOf { VariantPools.variantFpCostOrNull(it) ?: 0 }
        val remainingFP = (desiredFP - pickedFp).coerceAtLeast(0)

        val modBudget = (remainingFP * 0.50f).toInt()
        val randomBudget = (remainingFP * 0.40f).toInt()
        val omegaBudget = remainingFP - modBudget - randomBudget

        fun fill(pool: List<String>, budget: Int) {
            var remaining = budget
            var guard = 600
            while (remaining > 0 && guard-- > 0 && pool.isNotEmpty()) {
                val vid = pool[rnd.nextInt(pool.size)]
                val fp = VariantPools.variantFpCostOrNull(vid) ?: continue
                if (fp <= 0) continue
                if (fp > remaining && remaining > 10) continue
                picked.add(vid)
                remaining -= fp
            }
        }

        // 3) 分池填充（注意：旗舰/核心编成已提前写入；这里仅填“剩余部分”）
        fill(modPool, modBudget)
        fill(randomPool, randomBudget)
        if (omegaPool.isNotEmpty()) {
            fill(omegaPool, omegaBudget)
        } else {
            // Omega 池缺失：把预算回流给 random。
            fill(randomPool, omegaBudget)
        }

        // 3) 词缀选择（只依赖 state，不依赖 config 文件）
        val unlocked = state.unlockedAffixIds
        val affixes = AffixRegistry.pickAffixes(
            unlockedIds = unlocked,
            mainCompleted = state.mainCompleted,
            threatTier = def.threatTier,
            k = scale.k,
            seed = seed xor 0x5EED5EED,
        )

        val affixHullMods = affixes.mapNotNull { it.hullModId }
        return Composition(
            pickedVariantIds = picked,
            affixHullMods = affixHullMods,
            k = scale.k,
            totalMult = scale.totalMult,
        )
    }

    fun rebuildFleetMembers(
        bountyKey: String,
        fleetMembers: List<String>,
        k: Float,
        totalMult: Float,
        affixHullMods: List<String>,
        flagship: FleetMemberAPI,
    ): List<FleetMemberAPI> {
        val factory = Global.getFactory()
        val created = ArrayList<FleetMemberAPI>(fleetMembers.size)

        // 旗舰保持原对象，但补上缩放/词缀。
        applyBountyHullModsAndMemory(flagship, k, totalMult, affixHullMods)
        created.add(flagship)

        for (vid in fleetMembers.drop(1)) {

            val member = try {
                factory.createFleetMember(FleetMemberType.SHIP, vid)
            } catch (t: Throwable) {
                log.warn("[FleetComposer] Failed to create fleet member for variant=$vid (bounty=$bountyKey): ${t.message}")
                null
            } ?: continue

            applyBountyHullModsAndMemory(member, k, totalMult, affixHullMods)
            created.add(member)
        }
        return created
    }

    private fun applyBountyHullModsAndMemory(member: FleetMemberAPI, k: Float, totalMult: Float, affixHullMods: List<String>) {
        try {
            // 基础小幅数值缩放（难度系数）
            member.variant?.addPermaMod("astd_bounty_scaling")
            // 词缀 hullmods
            for (hm in affixHullMods) {
                member.variant?.addPermaMod(hm)
            }
        } catch (_: Throwable) {
            // 某些 VariantAPI 实现可能不允许改；忽略，至少记 memory。
        }
    }

    private object VariantPools {
        private var cachedAllVariantIds: List<String>? = null
        private var cachedFpCost: MutableMap<String, Int> = HashMap()

        private fun allVariantIds(): List<String> {
            val cached = cachedAllVariantIds
            if (cached != null) return cached
            val ids = Global.getSettings().allVariantIds ?: emptyList()
            cachedAllVariantIds = ids
            return ids
        }

        fun modStandardVariants(): List<String> =
            allVariantIds().filter { it.startsWith("astd_") && it.endsWith("_Standard") }

        fun randomPresetVariants(): List<String> =
            allVariantIds().filter { id ->
                // 排除本模组、排除一些明显不是“预设战斗舰”的东西
                if (id.startsWith("astd_")) return@filter false
                if (id.contains("_Hull")) return@filter false
                true
            }

        fun omegaLikeVariants(): List<String> {
            // 尽力从现有 Variant ID 推断“Omega 风格单位”。若不存在则返回空。
            val candidates = allVariantIds().filter { id ->
                val s = id.lowercase()
                s.contains("tesseract") || s.contains("omega") || s.contains("dorito")
            }
            return candidates
        }

        fun variantFpCostOrNull(variantId: String): Int? {
            val cached = cachedFpCost[variantId]
            if (cached != null) return cached
            return try {
                val v = Global.getSettings().getVariant(variantId) ?: return null
                val fp = v.hullSpec.fleetPoints
                cachedFpCost[variantId] = fp
                fp
            } catch (_: Throwable) {
                null
            }
        }
    }
}
