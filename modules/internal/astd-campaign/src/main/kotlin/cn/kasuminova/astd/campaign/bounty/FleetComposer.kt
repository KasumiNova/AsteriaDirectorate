package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
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
        /**
         * 旗舰专属词缀 HullMod（编队词缀之外仅施加于旗舰，如 R-17 奇点驱动）。
         */
        val flagshipAffixHullMods: List<String> = emptyList(),
        /**
         * 舰载 AI 核心 commodity id 表（与 [pickedVariantIds] 同下标对齐，索引 0 = 旗舰）；
         * 赏金目标舰队全舰船一律配备制式核心军官（85 文档量产核心口径，分档映射见 [StandardCores]）。
         */
        val officerCoreIds: List<String> = emptyList(),
        val k: Float,
        val totalMult: Float,
    )

    fun buildComposition(
        def: BountyDef,
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

        // 3) 词缀选择：固定词缀表（文书追加条款具名编目号）优先；无固定表才随机抽取
        //    （v3 随机口径：数量由难度系数搭配表决定；R 型由 def 显式开关；相位限定词缀按舰队相位能力过滤）
        val affixes = when {
            // 序章与第一章批一不挂词缀（05/03 文档口径）
            !def.allowAffixes -> emptyList()
            def.fixedAffixIds != null -> resolveFixedAffixes(def, def.fixedAffixIds)
            else -> {
                val allowPhase = picked.any { VariantPools.isPhaseVariant(it) }
                val drawn = AffixRegistry.pickAffixes(
                    kS = DifficultyTuningImpl.fixedScale,
                    allowR = def.allowRAffixes,
                    allowPhase = allowPhase,
                    seed = seed xor 0x5EED5EED,
                )
                enforceMinRAffixes(drawn, def.minRAffixes, seed)
            }
        }

        // 旗舰专属词缀（如四章中军旗舰的 R-17 奇点驱动；未知 id 记日志并跳过）
        val flagshipAffixHullMods = def.flagshipAffixIds.mapNotNull { id ->
            val affix = AffixRegistry.getById(id)
            if (affix == null) {
                log.warn("[FleetComposer] 未知旗舰词缀 id=$id (bounty=${def.key})，已跳过")
            }
            affix?.hullModId
        }

        val affixHullMods = affixes.map { it.hullModId }
        // 舰载核心配置：全舰船一律制式核心军官，档位随威胁等级（分档映射见 StandardCores 类 KDoc）
        val officerCoreIds = StandardCores.planFleetCores(picked.size, def.threatTier, seed xor 0xC0E5L)
        return Composition(
            pickedVariantIds = picked,
            affixHullMods = affixHullMods,
            flagshipAffixHullMods = flagshipAffixHullMods,
            officerCoreIds = officerCoreIds,
            k = scale.k,
            totalMult = scale.totalMult,
        )
    }

    /**
     * 固定词缀表解析：按文书追加条款具名编目号挂载，合法性（互斥/相位/编目有效性）
     * 与随机抽取同一套规则——违规属注册表编写错误，逐条记错误日志并剔除违规项后应用其余条目
     * （注册表口径由 MainBountiesTest 全量断言，运行期违规意味着存档/注册表被外部改动）。
     */
    private fun resolveFixedAffixes(def: BountyDef, fixedIds: List<String>): List<AffixRegistry.AffixDef> {
        val violations = AffixRegistry.validateFixedTable(fixedIds)
        for (v in violations) {
            log.error("[FleetComposer] 固定词缀表违规（bounty=${def.key}）：$v")
        }
        if (violations.isEmpty()) {
            return fixedIds.mapNotNull { AffixRegistry.getById(it) }
        }
        // 剔除：未知 id、重复项、相位限定词缀，以及互斥对中后出现的一方
        val result = ArrayList<AffixRegistry.AffixDef>(fixedIds.size)
        for (id in fixedIds) {
            val affix = AffixRegistry.getById(id) ?: continue
            if (affix.phaseOnly) continue
            if (result.any { it.id == id }) continue
            val conflicts = AffixRegistry.EXCLUSIVE_PAIRS.any { pair ->
                id in pair && result.any { it.id in pair }
            }
            if (conflicts) continue
            result.add(affix)
        }
        return result
    }

    /**
     * 固定 R 型补足（三章单 3「固定至少 1 条 R」口径）：
     * 抽取结果中 R 不足时，按种子从 R 池中确定性补足；与已抽结果冲突的条目跳过。
     */
    private fun enforceMinRAffixes(
        drawn: List<AffixRegistry.AffixDef>,
        minRAffixes: Int,
        seed: Long,
    ): List<AffixRegistry.AffixDef> {
        val rCount = drawn.count { it.type == AffixRegistry.AffixType.R }
        if (rCount >= minRAffixes) return drawn

        val result = drawn.toMutableList()
        val rnd = Random(seed xor 0xAFF1E5L)
        val pool = AffixRegistry.all.filter { it.type == AffixRegistry.AffixType.R }.shuffled(rnd)
        for (candidate in pool) {
            if (result.count { it.type == AffixRegistry.AffixType.R } >= minRAffixes) break
            if (result.any { it.id == candidate.id }) continue
            val conflicts = AffixRegistry.EXCLUSIVE_PAIRS.any { pair ->
                candidate.id in pair && result.any { it.id in pair }
            }
            if (conflicts) continue
            result.add(candidate)
        }
        if (result.count { it.type == AffixRegistry.AffixType.R } < minRAffixes) {
            log.warn("[FleetComposer] R 型补足不足：目标 $minRAffixes 条，实际 ${result.count { it.type == AffixRegistry.AffixType.R }} 条")
        }
        return result
    }

    fun rebuildFleetMembers(
        bountyKey: String,
        fleetMembers: List<String>,
        k: Float,
        totalMult: Float,
        affixHullMods: List<String>,
        flagship: FleetMemberAPI,
        flagshipAffixHullMods: List<String> = emptyList(),
        officerCoreIds: List<String> = emptyList(),
        factionId: String? = null,
    ): List<FleetMemberAPI> {
        val factory = Global.getFactory()
        val created = ArrayList<FleetMemberAPI>(fleetMembers.size)

        if (officerCoreIds.size != fleetMembers.size) {
            log.warn(
                "[FleetComposer] 核心配置与舰队成员数不齐（bounty=$bountyKey）：" +
                        "cores=${officerCoreIds.size} members=${fleetMembers.size}，按下标对齐截断",
            )
        }

        // 旗舰保持原对象，但补上缩放/词缀（含旗舰专属词缀）与制式核心舰长。
        applyBountyHullModsAndMemory(flagship, k, totalMult, affixHullMods + flagshipAffixHullMods)
        assignCoreCaptain(flagship, officerCoreIds.getOrNull(0), factionId, bountyKey)
        created.add(flagship)

        for ((index, vid) in fleetMembers.withIndex()) {
            if (index == 0) continue

            val member = try {
                factory.createFleetMember(FleetMemberType.SHIP, vid)
            } catch (t: Throwable) {
                log.warn("[FleetComposer] Failed to create fleet member for variant=$vid (bounty=$bountyKey): ${t.message}")
                null
            } ?: continue

            applyBountyHullModsAndMemory(member, k, totalMult, affixHullMods)
            assignCoreCaptain(member, officerCoreIds.getOrNull(index), factionId, bountyKey)
            created.add(member)
        }
        return created
    }

    /** 舰载制式核心舰长上任（85 文档量产核心口径；无配置时保留 MagicBounty 原舰长）。 */
    private fun assignCoreCaptain(member: FleetMemberAPI, coreId: String?, factionId: String?, bountyKey: String) {
        if (coreId == null) return
        val tier = StandardCores.byCommodity(coreId)
        if (tier == null) {
            log.error("[FleetComposer] 未知制式核心 id=$coreId（bounty=$bountyKey，member=${member.id}），跳过舰长配置")
            return
        }
        member.captain = StandardCores.createOfficerPerson(tier, factionId)
    }

    private fun applyBountyHullModsAndMemory(member: FleetMemberAPI, k: Float, totalMult: Float, affixHullMods: List<String>) {
        try {
            // 基础小幅数值缩放（难度系数）
            member.variant?.addPermaMod("astd_bounty_scaling")
            // 词缀 hullmods
            for (hm in affixHullMods) {
                member.variant?.addPermaMod(hm)
            }
        } catch (t: Throwable) {
            // 某些 VariantAPI 实现可能不允许改；记日志后至少保留 memory 标记。
            log.warn("[FleetComposer] 应用词缀/缩放失败（member=${member.id}）：${t.message}")
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

        /** 变体是否为相位舰船（词缀相位约束的舰队级判定；未知变体按非相位处理）。 */
        fun isPhaseVariant(variantId: String): Boolean =
            Global.getSettings().getVariant(variantId)?.hullSpec?.isPhase == true
    }
}
