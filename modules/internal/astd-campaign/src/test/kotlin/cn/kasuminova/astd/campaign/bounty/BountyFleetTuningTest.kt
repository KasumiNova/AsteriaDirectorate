package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.bounty.core.BountyOfficerSkills
import cn.kasuminova.astd.campaign.bounty.core.BountyPoolConfig
import cn.kasuminova.astd.campaign.bounty.core.PoolSide
import com.fs.starfarer.api.combat.ShipAPI
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 赏金舰队后处理的纯逻辑校验：
 * - 护航排除判定（独特舰 + 发布范围外显式清单）；
 * - SMod 优先级预设的清单选择与旗舰导入装配的不重叠性；
 * - 混编池配置路由、ASTD/余晖 1:1 分边、双池舰级覆盖与技能表引用完整性；
 * - 核心方案（唯一舰 O 档特判 / rogue 旗舰固定 Alpha + 僚舰池）与难度档映射；
 * - 核心打捞扩展（原版/SMS 核心可掉、omega 不可掉）。
 */
class BountyFleetTuningTest {

    private val presets = BountySmodPresetsImpl()

    @Test
    fun `独特舰与发布范围外舰体被排除在护航位之外`() {
        // 四艘独特舰（astd_unique tag 标于 ship_data.csv）一律排除
        assertTrue(BountyFleetTunerImpl.isEscortExcludedHull("astd_xc_001", astdUnique = true))
        assertTrue(BountyFleetTunerImpl.isEscortExcludedHull("astd_xc_002", astdUnique = true))
        assertTrue(BountyFleetTunerImpl.isEscortExcludedHull("astd_zw_001", astdUnique = true))
        assertTrue(BountyFleetTunerImpl.isEscortExcludedHull("astd_zw_002", astdUnique = true))
        // 逐电与决明在显式排除清单内：即使 astd_unique tag 漏标（false）也硬排除
        assertTrue(BountyFleetTunerImpl.isEscortExcludedHull("astd_xc_104", astdUnique = false))
        assertTrue(BountyFleetTunerImpl.isEscortExcludedHull("astd_zw_001", astdUnique = false))
        // 量产舰正常放行
        assertFalse(BountyFleetTunerImpl.isEscortExcludedHull("astd_xc_101", astdUnique = false))
        assertFalse(BountyFleetTunerImpl.isEscortExcludedHull("astd_xc_102", astdUnique = false))
        assertFalse(BountyFleetTunerImpl.isEscortExcludedHull("astd_xc_103", astdUnique = false))
        assertFalse(BountyFleetTunerImpl.isEscortExcludedHull("astd_zw_101", astdUnique = false))
        assertFalse(BountyFleetTunerImpl.isEscortExcludedHull("astd_zw_102", astdUnique = false))
        assertFalse(BountyFleetTunerImpl.isEscortExcludedHull("astd_zw_103", astdUnique = false))
        assertFalse(BountyFleetTunerImpl.isEscortExcludedHull("astd_lh_001", astdUnique = false))
        assertFalse(BountyFleetTunerImpl.isEscortExcludedHull("astd_lh_002", astdUnique = false))
    }

    @Test
    fun `SMod 预设按舰型特征选择清单`() {
        assertEquals(
            BountySmodPresetsImpl.PHASE_PRIORITY,
            presets.prioritiesFor("astd_zw_101", phase = true, carrier = false, capital = false),
        )
        assertEquals(
            BountySmodPresetsImpl.CARRIER_PRIORITY,
            presets.prioritiesFor("astd_zw_102", phase = false, carrier = true, capital = true),
        )
        assertEquals(
            BountySmodPresetsImpl.CAPITAL_PRIORITY,
            presets.prioritiesFor("astd_xc_102", phase = false, carrier = false, capital = true),
        )
        assertEquals(
            BountySmodPresetsImpl.GENERIC_PRIORITY,
            presets.prioritiesFor("astd_xc_103", phase = false, carrier = false, capital = false),
        )
    }

    @Test
    fun `赏金旗舰命中专属覆盖清单且与导入装配普通船插不重叠`() {
        // 专属覆盖优先于任何通用清单
        assertEquals(
            BountySmodPresetsImpl.HULL_OVERRIDES.getValue("astd_zw_002"),
            presets.prioritiesFor("astd_zw_002", phase = true, carrier = false, capital = false),
        )
        // 三个唯一舰旗舰的导入装配普通船插（contents/data/variants 下旗舰装配的 hullMods）
        val fittedByVariant = mapOf(
            "astd_xc_001" to setOf(
                "astd_dual_mode_switcher", "astd_mode_automated", "astd_mode_next_automated",
                "automated", "frontemitter", "hardenedshieldemitter",
                "magazines", "stabilizedshieldemitter", "targetingunit",
            ),
            "astd_xc_002" to setOf(
                "astd_dual_mode_switcher", "astd_mode_automated", "astd_mode_next_automated",
                "automated", "fluxbreakers", "frontemitter",
                "hardenedshieldemitter", "stabilizedshieldemitter", "targetingunit",
            ),
            "astd_zw_002" to setOf(
                "astd_dual_mode_switcher", "astd_mode_automated", "astd_mode_next_automated",
                "automated", "phase_anchor", "targetingunit",
            ),
        )
        fittedByVariant.forEach { (hullId, fitted) ->
            val override = BountySmodPresetsImpl.HULL_OVERRIDES.getValue(hullId)
            assertTrue(override.none { it in fitted }, "$hullId 专属 SMod 候选与导入装配普通船插重叠")
        }
    }

    @Test
    fun `不同特征清单之间确实存在差异`() {
        assertNotEquals(BountySmodPresetsImpl.PHASE_PRIORITY, BountySmodPresetsImpl.GENERIC_PRIORITY)
        assertNotEquals(BountySmodPresetsImpl.CARRIER_PRIORITY, BountySmodPresetsImpl.GENERIC_PRIORITY)
        assertNotEquals(BountySmodPresetsImpl.CAPITAL_PRIORITY, BountySmodPresetsImpl.GENERIC_PRIORITY)
    }

    @Test
    fun `池配置按赏金键路由且未知键回退默认`() {
        assertSame(BountyPoolConfig.DEFAULT, BountyPoolConfig.forBountyKey(null))
        assertSame(BountyPoolConfig.DEFAULT, BountyPoolConfig.forBountyKey("astd_bounty_rogue_1"))
        assertSame(BountyPoolConfig.DEFAULT, BountyPoolConfig.forBountyKey("some_other_bounty"))
        val xc002 = BountyPoolConfig.forBountyKey("astd_bounty_xc_002")
        assertTrue(xc002.destroyerBestOf > BountyPoolConfig.DEFAULT.destroyerBestOf, "星翼主控应启用驱逐舰优选")
    }

    @Test
    fun `混编分边严格一比一交替且 ASTD 先出`() {
        assertEquals(PoolSide.ASTD, BountyPoolConfig.pickPoolSide(0, 0))
        assertEquals(PoolSide.REMNANT, BountyPoolConfig.pickPoolSide(1, 0))
        assertEquals(PoolSide.ASTD, BountyPoolConfig.pickPoolSide(1, 1))
        assertEquals(PoolSide.REMNANT, BountyPoolConfig.pickPoolSide(2, 1))
        // 任意时刻两侧计数差不超过 1
        var astd = 0
        var remnant = 0
        repeat(50) {
            when (BountyPoolConfig.pickPoolSide(astd, remnant)) {
                PoolSide.ASTD -> astd++
                PoolSide.REMNANT -> remnant++
            }
            assertTrue(kotlin.math.abs(astd - remnant) <= 1, "分边计数失衡：astd=$astd remnant=$remnant")
        }
    }

    @Test
    fun `双侧混编池覆盖全部作战舰级且 ASTD 池变体均有技能表`() {
        val combatSizes = setOf(
            ShipAPI.HullSize.CAPITAL_SHIP,
            ShipAPI.HullSize.CRUISER,
            ShipAPI.HullSize.DESTROYER,
            ShipAPI.HullSize.FRIGATE,
        )
        combatSizes.forEach { size ->
            assertTrue(BountyPoolConfig.ASTD_POOLS.getValue(size).isNotEmpty(), "ASTD 池缺少舰级 $size")
            assertTrue(BountyPoolConfig.REMNANT_POOLS.getValue(size).isNotEmpty(), "余晖池缺少舰级 $size")
        }
        // 随机池变体必须在素材技能表中有登记（键为正式 stock variant id）
        BountyPoolConfig.ASTD_POOLS.values.flatten().forEach { variantId ->
            assertTrue(BountyOfficerSkills.forVariant(variantId) != null, "混编池变体 $variantId 缺少技能表登记")
        }
        // 发布范围外舰体与唯一舰不得进入随机池（唯一舰导入装配仅作旗舰引用）
        val poolIds = BountyPoolConfig.ASTD_POOLS.values.flatten()
        assertTrue(poolIds.none { it.startsWith("astd_zw_001") || it.startsWith("astd_xc_104") }, "发布范围外舰体混入随机池")
        assertTrue(poolIds.none { it.startsWith("astd_xc_001_") || it.startsWith("astd_xc_002_") || it.startsWith("astd_zw_002_") }, "唯一舰装配混入随机池")
    }

    @Test
    fun `余晖混编池发现结果优先且按舰级回退兜底清单`() {
        // 发现结果在该舰级非空：以 doctrine 发现为准，兜底清单不参与
        val discovered = mapOf(ShipAPI.HullSize.CRUISER to listOf("some_mod_cruiser_Standard"))
        assertEquals(
            listOf("some_mod_cruiser_Standard"),
            BountyFleetTunerImpl.resolveRemnantPool(discovered, ShipAPI.HullSize.CRUISER),
        )
        // 发现结果缺失该舰级（含空表）：退回硬编码兜底清单
        assertEquals(
            BountyPoolConfig.REMNANT_POOLS.getValue(ShipAPI.HullSize.DESTROYER),
            BountyFleetTunerImpl.resolveRemnantPool(discovered, ShipAPI.HullSize.DESTROYER),
        )
        assertEquals(
            BountyPoolConfig.REMNANT_POOLS.getValue(ShipAPI.HullSize.CRUISER),
            BountyFleetTunerImpl.resolveRemnantPool(
                mapOf(ShipAPI.HullSize.CRUISER to emptyList()),
                ShipAPI.HullSize.CRUISER,
            ),
        )
    }

    @Test
    fun `难度档沿赏金链单调不减且唯一舰处于 T5 以上`() {
        val chain = listOf(
            "astd_bounty_rogue_1", "astd_bounty_rogue_2", "astd_bounty_rogue_3",
            "astd_bounty_rogue_4", "astd_bounty_rogue_5", "astd_bounty_rogue_6",
            "astd_bounty_xc_002", "astd_bounty_zw_002", "astd_bounty_xc_001",
        )
        chain.forEach { key ->
            assertTrue(BountyPoolConfig.THREAT_TIERS.containsKey(key), "赏金 $key 缺少难度档登记")
        }
        chain.zipWithNext().forEach { (lower, higher) ->
            assertTrue(
                BountyPoolConfig.threatTierOf(higher) >= BountyPoolConfig.threatTierOf(lower),
                "难度档在 $lower -> $higher 间倒退",
            )
        }
        assertTrue(
            BountyPoolConfig.UNIQUE_FLAGSHIP_BOUNTIES.all { BountyPoolConfig.threatTierOf(it) >= 5 },
            "唯一舰赏金必须处于 T5+ 才能产出 O 档旗舰",
        )
    }

    @Test
    fun `唯一舰装舰核心表旗舰 O 档且僚舰可打捞`() {
        BountyPoolConfig.UNIQUE_FLAGSHIP_BOUNTIES.forEach { key ->
            val plan = BountyFleetTunerImpl.planBountyCores(key, 20, 42L)
            assertEquals(20, plan.size)
            assertEquals(StandardCores.Tier.O.commodityId, plan[0], "$key 旗舰应为 O 档")
        }
        val plan = BountyFleetTunerImpl.planBountyCores("astd_bounty_xc_001", 20, 42L)
        val droppableIds = StandardCores.Tier.entries.filter { it.droppable }.map { it.commodityId }.toSet()
        assertTrue(plan.drop(1).all { it in droppableIds }, "僚舰出现不可打捞核心")
        assertEquals(plan, BountyFleetTunerImpl.planBountyCores("astd_bounty_xc_001", 20, 42L), "同种子装舰表应确定")
    }

    @Test
    fun `rogue 装舰核心表旗舰固定 Alpha 且僚舰出自核心池`() {
        val pool = BountyPoolConfig.ROGUE_ESCORT_CORE_POOL
        val plan = BountyFleetTunerImpl.planRogueCores(20, pool, Random(42L))
        assertEquals(20, plan.size)
        assertEquals(BountyPoolConfig.ROGUE_FLAGSHIP_CORE, plan[0], "rogue 旗舰应固定原版 Alpha 核心")
        val poolIds = pool.map { it.first }.toSet()
        assertTrue(plan.drop(1).all { it in poolIds }, "僚舰核心越出核心池：$plan")
        // SMS 软联动：过滤掉 SMS 条目后方案仍然成立（模拟未安装 SMS）
        val noSms = pool.filterNot { it.first.startsWith("sms_") }
        val planNoSms = BountyFleetTunerImpl.planRogueCores(20, noSms, Random(42L))
        assertTrue(planNoSms.drop(1).none { it.startsWith("sms_") }, "软过滤后僚舰不应出现 SMS 核心")
        // 空池与零规模返回空表（调用方记日志跳过）
        assertEquals(emptyList(), BountyFleetTunerImpl.planRogueCores(20, emptyList(), Random(1L)))
        assertEquals(emptyList(), BountyFleetTunerImpl.planRogueCores(0, pool, Random(1L)))
        // 同种子确定
        assertEquals(plan, BountyFleetTunerImpl.planRogueCores(20, pool, Random(42L)))
    }

    @Test
    fun `核心打捞扩展覆盖原版与 SMS 核心且 omega 不可掉`() {
        assertTrue(StandardCores.isCoreDroppable("alpha_core"))
        assertTrue(StandardCores.isCoreDroppable("beta_core"))
        assertTrue(StandardCores.isCoreDroppable("gamma_core"))
        assertTrue(StandardCores.isCoreDroppable("sms_alpha_pseudocore"))
        assertTrue(StandardCores.isCoreDroppable("sms_fractured_gamma_core"))
        assertFalse(StandardCores.isCoreDroppable("omega_core"))
        assertFalse(StandardCores.isCoreDroppable(StandardCores.Tier.O.commodityId))

        // 混合装舰表：原版旗舰保底必掉，omega 与 astd O 永不入池
        val installed = listOf(
            "alpha_core", "astd_ai_core_b", "sms_gamma_pseudocore", "omega_core",
        )
        for (seed in 0L until 64L) {
            val loot = StandardCores.rollCoreLoot(installed, seed)
            assertTrue("omega_core" !in loot.keys, "omega 不得入打捞池：$loot")
            assertEquals("alpha_core", loot.keys.first(), "旗舰核心保底必掉：$loot")
            assertTrue(loot.keys.all { it in installed }, "掉落越出实际装舰：$loot")
            assertTrue(loot.values.sum() >= 1, "至少一枚承诺被破坏：$loot")
        }
    }

    @Test
    fun `舰级排序档位单调且护卫舰垫底`() {
        assertTrue(
            BountyFleetTunerImpl.sizeRank(ShipAPI.HullSize.CAPITAL_SHIP) <
                BountyFleetTunerImpl.sizeRank(ShipAPI.HullSize.CRUISER),
        )
        assertTrue(
            BountyFleetTunerImpl.sizeRank(ShipAPI.HullSize.CRUISER) <
                BountyFleetTunerImpl.sizeRank(ShipAPI.HullSize.DESTROYER),
        )
        assertTrue(
            BountyFleetTunerImpl.sizeRank(ShipAPI.HullSize.DESTROYER) <
                BountyFleetTunerImpl.sizeRank(ShipAPI.HullSize.FRIGATE),
        )
    }
}
