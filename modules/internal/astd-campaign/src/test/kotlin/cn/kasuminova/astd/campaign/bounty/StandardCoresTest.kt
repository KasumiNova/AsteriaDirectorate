package cn.kasuminova.astd.campaign.bounty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 制式核心（85 文档量产级 G/B/A/O）：分档表原版对齐、舰队核心配置分档映射与确定性、
 * 核心打捞区间与旗舰保底、接取时锁定语义。
 */
class StandardCoresTest {

    @Test
    fun `分档表与原版同档核心逐项对齐`() {
        val expect = mapOf(
            StandardCores.Tier.G to Triple("astd_ai_core_g", 3, 3),
            StandardCores.Tier.B to Triple("astd_ai_core_b", 5, 5),
            StandardCores.Tier.A to Triple("astd_ai_core_a", 7, 7),
            StandardCores.Tier.O to Triple("astd_ai_core_o", 9, 10),
        )
        for ((tier, e) in expect) {
            assertEquals(e.first, tier.commodityId, "$tier commodity id")
            assertEquals(e.second, tier.officerLevel, "$tier 军官等级")
            assertEquals(e.third, tier.officerSkills.size, "$tier 技能数")
            assertTrue(tier.officerSkills.toSet().size == tier.officerSkills.size, "$tier 技能表不得有重复")
            assertEquals("graphics/portraits/astd_portrait_core_${tier.name.lowercase()}.png", tier.portrait)
        }
        // 原版 autoPointsMult：GAMMA/BETA/ALPHA/OMEGA = 2/3/4/5
        assertEquals(2f, StandardCores.Tier.G.autoPointsMult)
        assertEquals(3f, StandardCores.Tier.B.autoPointsMult)
        assertEquals(4f, StandardCores.Tier.A.autoPointsMult)
        assertEquals(5f, StandardCores.Tier.O.autoPointsMult)
        // 85 文档：O 级不作为可获取物品存在
        assertEquals(
            mapOf(
                StandardCores.Tier.G to true,
                StandardCores.Tier.B to true,
                StandardCores.Tier.A to true,
                StandardCores.Tier.O to false,
            ),
            StandardCores.Tier.entries.associateWith { it.droppable },
        )
        assertEquals(StandardCores.Tier.A, StandardCores.byCommodity("astd_ai_core_a"))
        assertEquals(null, StandardCores.byCommodity("alpha_core"))
    }

    @Test
    fun `旗舰档位按威胁等级映射且 O 档仅 T5 以上旗舰`() {
        assertEquals(StandardCores.Tier.G, StandardCores.flagshipTier(1))
        assertEquals(StandardCores.Tier.B, StandardCores.flagshipTier(2))
        assertEquals(StandardCores.Tier.B, StandardCores.flagshipTier(3))
        assertEquals(StandardCores.Tier.A, StandardCores.flagshipTier(4))
        assertEquals(StandardCores.Tier.O, StandardCores.flagshipTier(5))
        assertEquals(StandardCores.Tier.O, StandardCores.flagshipTier(6))
    }

    @Test
    fun `核心配置方案随威胁等级分档且同种子确定`() {
        val allowed = mapOf(
            1 to setOf(StandardCores.Tier.G),
            2 to setOf(StandardCores.Tier.G),
            3 to setOf(StandardCores.Tier.G, StandardCores.Tier.B),
            4 to setOf(StandardCores.Tier.B, StandardCores.Tier.A),
            5 to setOf(StandardCores.Tier.B, StandardCores.Tier.A),
        )
        for ((tier, escortAllowed) in allowed) {
            val plan = StandardCores.planFleetCores(24, tier, 42L)
            assertEquals(24, plan.size)
            assertEquals(StandardCores.flagshipTier(tier).commodityId, plan[0], "T$tier 旗舰档位")
            for (id in plan.drop(1)) {
                val escort = StandardCores.byCommodity(id)
                assertTrue(escort in escortAllowed, "T$tier 僚舰档位 $escort 超出允许集 $escortAllowed")
                assertNotEquals(StandardCores.Tier.O, escort, "O 档仅旗舰使用")
            }
            // 同种子确定
            assertEquals(plan, StandardCores.planFleetCores(24, tier, 42L), "T$tier 同种子方案应确定")
        }
        assertEquals(emptyList(), StandardCores.planFleetCores(0, 3, 42L))
    }

    @Test
    fun `打捞表同种子确定且不同种子有变化`() {
        val installed = StandardCores.planFleetCores(16, 3, 7L)
        val a = StandardCores.rollCoreLoot(installed, 99L)
        assertEquals(a, StandardCores.rollCoreLoot(installed, 99L), "同种子打捞应确定")
        val distinct = (0L until 32L).mapTo(HashSet()) { StandardCores.rollCoreLoot(installed, it) }
        assertTrue(distinct.size > 1, "僚舰 50% 独立判定应在多种子下产生不同打捞结果")
    }

    @Test
    fun `打捞只含已装可打捞核心且旗舰保底与至少一枚`() {
        val installed = listOf(
            StandardCores.Tier.G.commodityId,
            StandardCores.Tier.O.commodityId,
            StandardCores.Tier.B.commodityId,
            StandardCores.Tier.G.commodityId,
        )
        for (seed in 0L until 64L) {
            val loot = StandardCores.rollCoreLoot(installed, seed)
            // 掉落池 = 实际装舰的可打捞核心（O 永不掉落）
            assertTrue(loot.keys.all { it in setOf(installed[0], installed[2]) }, "掉落越出已装可打捞池：$loot")
            // 旗舰（G）保底必掉
            assertEquals(installed[0], loot.keys.first(), "旗舰核心保底必掉：$loot")
            // 单 id 数量不超过装舰数；总量在 [1, 可打捞装舰数=3]
            assertTrue((loot[installed[0]] ?: 0) <= 2)
            assertTrue((loot[installed[2]] ?: 0) <= 1)
            assertTrue(loot.values.sum() in 1..3, "打捞总量越界：$loot")
        }
        // 全 O 舰队（理论边界）：无可打捞核心 → 空表
        assertEquals(
            emptyMap(),
            StandardCores.rollCoreLoot(listOf(StandardCores.Tier.O.commodityId, StandardCores.Tier.O.commodityId), 1L),
        )
        // 旗舰 O + 僚舰可打捞：僚舰判定全空时按「至少一枚」强制掉落索引最小可打捞者
        val oFlag = listOf(
            StandardCores.Tier.O.commodityId,
            StandardCores.Tier.A.commodityId,
            StandardCores.Tier.B.commodityId,
        )
        val forced = StandardCores.rollCoreLoot(oFlag, findAllMissSeed())
        assertEquals(mapOf(StandardCores.Tier.A.commodityId to 1), forced)
    }

    /** 找一个两枚僚舰 50% 判定全部落空的种子（按判定的确定性消费顺序正演；找不到即环境异常）。 */
    private fun findAllMissSeed(): Long {
        for (seed in 0L until 10000L) {
            val rnd = java.util.Random(seed)
            if (rnd.nextFloat() >= StandardCores.ESCORT_DROP_CHANCE &&
                rnd.nextFloat() >= StandardCores.ESCORT_DROP_CHANCE
            ) {
                return seed
            }
        }
        error("未找到僚舰全落空的种子")
    }

    @Test
    fun `接取时锁定后重复构建沿用首次结果`() {
        val locks = HashMap<String, LockedFleetPlan>()
        val lockKey = "astd_main_test#0"
        val first = StandardCores.lockFleetPlan(locks, lockKey) {
            LockedFleetPlan(comp("v_flag", listOf("astd_ai_core_g", "astd_ai_core_b")), mapOf("astd_ai_core_g" to 2))
        }
        val second = StandardCores.lockFleetPlan(locks, lockKey) {
            LockedFleetPlan(comp("v_other", listOf("astd_ai_core_a")), mapOf("astd_ai_core_a" to 9))
        }
        assertSame(first, second, "同一 lockKey 重复构建应沿用首次锁定")
        assertEquals(mapOf("astd_ai_core_g" to 2), locks.getValue(lockKey).coreLoot)
        assertEquals(listOf("astd_ai_core_g", "astd_ai_core_b"), locks.getValue(lockKey).officerCoreIds)
        // 换 lockKey（阶段推进/无限赏金换代）重新锁定
        val nextStage = StandardCores.lockFleetPlan(locks, "astd_main_test#1") {
            LockedFleetPlan(comp("v_flag", listOf("astd_ai_core_a")), mapOf("astd_ai_core_a" to 1))
        }
        assertEquals(mapOf("astd_ai_core_a" to 1), nextStage.coreLoot)
        assertEquals(2, locks.size)
    }

    @Test
    fun `锁定快照与组建结果双向转换不丢字段`() {
        val comp = comp("v_flag", listOf("astd_ai_core_g", "astd_ai_core_b"))
        val restored = LockedFleetPlan(comp, mapOf("astd_ai_core_g" to 1)).toComposition()
        assertEquals(comp.pickedVariantIds, restored.pickedVariantIds)
        assertEquals(comp.affixHullMods, restored.affixHullMods)
        assertEquals(comp.flagshipAffixHullMods, restored.flagshipAffixHullMods)
        assertEquals(comp.officerCoreIds, restored.officerCoreIds)
        assertEquals(comp.k, restored.k)
        assertEquals(comp.totalMult, restored.totalMult)
    }

    private fun comp(flagship: String, cores: List<String>) = FleetComposer.Composition(
        pickedVariantIds = listOf(flagship) + List(cores.size - 1) { "v_escort_$it" },
        affixHullMods = listOf("hm_a"),
        flagshipAffixHullMods = listOf("hm_flag"),
        officerCoreIds = cores,
        k = 0.5f,
        totalMult = 2f,
    )
}
