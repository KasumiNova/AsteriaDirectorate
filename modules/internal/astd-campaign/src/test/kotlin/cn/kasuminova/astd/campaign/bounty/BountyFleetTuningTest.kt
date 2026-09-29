package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.bounty.core.BountyPoolConfig
import cn.kasuminova.astd.campaign.bounty.core.PoolSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 赏金舰队后处理的纯逻辑校验：
 * - 护航排除判定（独特舰 + 逐电）；
 * - SMod 优先级预设的清单选择（专属覆盖 / 相位 / 航母 / 主力 / 通用）；
 * - 预设候选不与赏金旗舰 Bounty 变体已装配的普通船插重叠（保证 SMod 槽位有新候选可插）；
 * - 混编池配置路由、ASTD/余晖 1:1 分边、角色权重缩放与余晖池映射完整性。
 */
class BountyFleetTuningTest {

    private val presets = BountySmodPresetsImpl()

    @Test
    fun `独特舰与逐电被排除在护航位之外`() {
        // 四艘独特舰（含发布范围外的决明）一律排除
        assertTrue(BountyFleetTunerImpl.isEscortExcludedHull("astd_xc_001", astdUnique = true))
        assertTrue(BountyFleetTunerImpl.isEscortExcludedHull("astd_xc_002", astdUnique = true))
        assertTrue(BountyFleetTunerImpl.isEscortExcludedHull("astd_zw_001", astdUnique = true))
        assertTrue(BountyFleetTunerImpl.isEscortExcludedHull("astd_zw_002", astdUnique = true))
        // 逐电不是独特舰，按 id 显式排除
        assertTrue(BountyFleetTunerImpl.isEscortExcludedHull("astd_xc_104", astdUnique = false))
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
    fun `赏金旗舰命中专属覆盖清单且与变体普通船插不重叠`() {
        // 专属覆盖优先于任何通用清单
        assertEquals(
            BountySmodPresetsImpl.HULL_OVERRIDES.getValue("astd_zw_002"),
            presets.prioritiesFor("astd_zw_002", phase = true, carrier = false, capital = false),
        )
        // 三个旗舰变体已装配的普通船插（contents/data/variants 下 *_Bounty.variant 的 hullMods）
        val fittedByVariant = mapOf(
            "astd_xc_001" to setOf("targetingunit", "fluxdistributor", "hardenedshieldemitter", "armoredweapons"),
            "astd_xc_002" to setOf("expanded_deck_crew", "fluxdistributor", "turretgyros"),
            "astd_zw_002" to setOf("ex_phase_coils", "fluxdistributor", "fluxcoil"),
        )
        fittedByVariant.forEach { (hullId, fitted) ->
            val override = BountySmodPresetsImpl.HULL_OVERRIDES.getValue(hullId)
            assertTrue(override.none { it in fitted }, "$hullId 专属 SMod 候选与变体普通船插重叠")
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
        // 三个唯一舰赏金必须各自命中覆盖实例，且倍率方向与编队主题一致
        val xc001 = BountyPoolConfig.forBountyKey("astd_bounty_xc_001")
        assertTrue(xc001.carrierWeightMult < BountyPoolConfig.DEFAULT.carrierWeightMult, "坠星主控应压制航母权重")
        val zw002 = BountyPoolConfig.forBountyKey("astd_bounty_zw_002")
        assertTrue(zw002.phaseWeightMult > BountyPoolConfig.DEFAULT.phaseWeightMult, "密蒙主控应强化相位权重")
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
    fun `角色权重缩放只作用于航母与相位角色`() {
        val base = BountyFleetTunerImpl.ROLE_WEIGHTS
        val scaled = BountyPoolConfig.weightedRoles(base, BountyPoolConfig(carrierWeightMult = 0.25f, phaseWeightMult = 3f))
        base.zip(scaled).forEach { (baseRole, scaledRole) ->
            assertEquals(baseRole.first, scaledRole.first)
            when {
                baseRole.first.startsWith("carrier") ->
                    assertTrue(scaledRole.second < baseRole.second, "${baseRole.first} 应被压制")
                baseRole.first.startsWith("phase") ->
                    assertTrue(scaledRole.second > baseRole.second, "${baseRole.first} 应被强化")
                else -> assertEquals(baseRole.second, scaledRole.second, "${baseRole.first} 不应受影响")
            }
        }
    }

    @Test
    fun `全部抽取角色都能映射到非空余晖池`() {
        BountyFleetTunerImpl.ROLE_WEIGHTS.forEach { (role, _) ->
            val size = BountyPoolConfig.ROLE_TO_SIZE[role]
                ?: error("角色 $role 缺少舰级映射")
            assertTrue(BountyPoolConfig.REMNANT_POOLS.getValue(size).isNotEmpty(), "舰级 $size 的余晖池为空")
        }
    }

    @Test
    fun `余晖最高档过滤结果均为池内最大部署点`() {
        BountyPoolConfig.REMNANT_POOLS.forEach { (size, pool) ->
            val top = BountyPoolConfig.topFleetPointsPicks(pool)
            assertTrue(top.isNotEmpty(), "舰级 $size 最高档过滤结果为空")
            val max = pool.maxOf { it.fleetPoints }
            assertTrue(top.all { it.fleetPoints == max }, "舰级 $size 过滤结果混入了非最高部署点变体")
        }
    }

    @Test
    fun `难度档沿赏金链单调不减且覆盖全部登记赏金`() {
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
    fun `装舰核心表旗舰 O 档仅限唯一舰赏金`() {
        // 唯一舰赏金：T5+ 旗舰档即 O 档
        BountyPoolConfig.UNIQUE_FLAGSHIP_BOUNTIES.forEach { key ->
            val plan = BountyFleetTunerImpl.planBountyCores(key, 20, 42L)
            assertEquals(20, plan.size)
            assertEquals(StandardCores.Tier.O.commodityId, plan[0], "$key 旗舰应为 O 档")
        }
        // 量产旗舰赏金：任何难度档下旗舰不得为 O（O 封顶回 A）
        listOf(
            "astd_bounty_rogue_1", "astd_bounty_rogue_2", "astd_bounty_rogue_3",
            "astd_bounty_rogue_4", "astd_bounty_rogue_5", "astd_bounty_rogue_6",
        ).forEach { key ->
            val plan = BountyFleetTunerImpl.planBountyCores(key, 20, 42L)
            assertEquals(20, plan.size)
            assertNotEquals(StandardCores.Tier.O.commodityId, plan[0], "$key 旗舰不得为 O 档")
        }
        // 僚舰永不为 O；同种子方案确定
        val plan = BountyFleetTunerImpl.planBountyCores("astd_bounty_xc_001", 20, 42L)
        assertTrue(plan.drop(1).none { it == StandardCores.Tier.O.commodityId }, "僚舰不得出现 O 档")
        assertEquals(plan, BountyFleetTunerImpl.planBountyCores("astd_bounty_xc_001", 20, 42L), "同种子装舰表应确定")
    }

    @Test
    fun `装舰核心表各赏金档位构成符合难度档语义`() {
        val droppableIds = StandardCores.Tier.entries.filter { it.droppable }.map { it.commodityId }.toSet()
        BountyPoolConfig.THREAT_TIERS.keys.forEach { key ->
            val plan = BountyFleetTunerImpl.planBountyCores(key, 30, 7L)
            // 僚舰全部可打捞（掉落池 = 实际装舰的语义前提）
            assertTrue(plan.drop(1).all { it in droppableIds }, "$key 僚舰出现不可打捞核心")
            // 高档赏金僚舰应出现 B/A 档，低挡赏金僚舰全 G
            val escortTiers = plan.drop(1).mapNotNull { StandardCores.byCommodity(it) }.toSet()
            if (BountyPoolConfig.threatTierOf(key) <= 2) {
                assertEquals(setOf(StandardCores.Tier.G), escortTiers, "$key 低难度僚舰应全 G 档")
            } else {
                assertTrue(StandardCores.Tier.G !in escortTiers || BountyPoolConfig.threatTierOf(key) == 3,
                    "$key 高难度僚舰不应仍以 G 档为主")
            }
        }
    }
}
