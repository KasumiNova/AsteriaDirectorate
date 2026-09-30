package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.campaign.bounty.core.BountyFitRules
import cn.kasuminova.astd.campaign.bounty.core.BountyOfficerSkills
import cn.kasuminova.astd.campaign.bounty.core.BountyPoolConfig
import cn.kasuminova.astd.campaign.bounty.core.PoolSide
import com.fs.starfarer.api.combat.ShipAPI
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 赏金舰队后处理的纯逻辑校验：
 * - 护航排除判定（独特舰 + 发布范围外显式清单）；
 * - 核心军官技能组成（变体技能表按全局优先级取舍、未登记变体走 fallback）；
 * - 模组核心（SMS 拟核）技能适配：保留 sms_ 特殊技能，原版技能按变体表等量替换；
 * - 额外装配规则：SMod 适用性等效判定、SMod 与已装 SHU 的冲突过滤、OP 回收预算与拆除方案、SHU 权重调整；
 * - 混编池配置路由、ASTD/余晖 1:1 分边、双池舰级覆盖与兜底解析；
 * - 核心方案（唯一舰 O 档特判 / rogue 旗舰固定 Alpha + 僚舰池）与难度档映射；
 * - 核心打捞扩展（原版/SMS 核心可掉、omega 不可掉）。
 */
class BountyFleetTuningTest {

    private val fitRules: BountyFitRules = BountyFitRulesImpl()

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
    fun `核心军官技能按统一优先级顺序取前 N 个`() {
        val priority = BountyFleetTunerImpl.BOUNTY_OFFICER_SKILL_PRIORITY
        // N = 军官等级：每个等级的技能集都是优先级表的有序前缀
        for (level in 1..priority.size) {
            assertEquals(priority.take(level), BountyFleetTunerImpl.bountyOfficerSkills(level))
        }
        // 零与负等级不产出技能，超出表长按表长截断不溢出
        assertEquals(emptyList(), BountyFleetTunerImpl.bountyOfficerSkills(0))
        assertEquals(emptyList(), BountyFleetTunerImpl.bountyOfficerSkills(-3))
        assertEquals(priority, BountyFleetTunerImpl.bountyOfficerSkills(priority.size + 10))
        // 既有核心档位等级（G3/B5/A7/O9 与余晖 4/5/6）都在表长覆盖内
        (StandardCores.Tier.entries.map { it.officerLevel } + BountyFleetTunerImpl.AI_CORE_LEVELS.values).forEach {
            assertTrue(it <= priority.size, "核心档等级 $it 超出技能优先级表覆盖")
        }
    }

    @Test
    fun `变体技能表按全局优先级取舍且未登记变体走 fallback`() {
        // 表长 > N：表内技能按全局优先级排序取前 N，不在优先级表中的技能排最后
        val mixed = listOf("combat_endurance", "custom_skill_x", "helmsmanship", "custom_skill_y")
        assertEquals(
            listOf("helmsmanship", "combat_endurance"),
            BountyFleetTunerImpl.resolveOfficerSkills(mixed, 2),
        )
        // 非优先级表技能保持原相对顺序
        val customs = listOf("custom_skill_a", "helmsmanship", "custom_skill_b")
        assertEquals(
            listOf("helmsmanship", "custom_skill_a"),
            BountyFleetTunerImpl.resolveOfficerSkills(customs, 2),
        )
        // 表长 <= N：装满全表，原样返回
        val table = listOf("combat_endurance", "helmsmanship", "point_defense")
        assertEquals(table, BountyFleetTunerImpl.resolveOfficerSkills(table, 3))
        assertEquals(table, BountyFleetTunerImpl.resolveOfficerSkills(table, 20))
        // 未登记变体（null 表）：退回全局优先级表顺序取前 N
        assertEquals(
            BountyFleetTunerImpl.bountyOfficerSkills(5),
            BountyFleetTunerImpl.resolveOfficerSkills(null, 5),
        )
        // 真实登记变体（余晖侧不出现）在高位核心档下装满全表
        BountyOfficerSkills.TABLES.forEach { (variantId, skills) ->
            assertEquals(skills, BountyFleetTunerImpl.resolveOfficerSkills(skills, 9), "$variantId 高位核心档应装满全表")
        }
    }

    @Test
    fun `stock 变体回溯优先取 originalVariant`() {
        // 充气重洗后的 REFIT 变体：真实 stock id 在 originalVariant
        assertEquals("astd_xc_101_Standard", BountyFleetTunerImpl.stockVariantIdOf("astd_xc_101_Standard", "fleet_123_0"))
        // 未重洗变体：originalVariant 为空，直接用 hullVariantId
        assertEquals("astd_zw_101_Standard", BountyFleetTunerImpl.stockVariantIdOf(null, "astd_zw_101_Standard"))
    }

    @Test
    fun `模组核心技能适配保留特殊技能并按变体技能表等量替换`() {
        // SMS Alpha 拟核组成（7 技能，1 特殊）：特殊技能保留在最前，其余 6 位按变体表取舍，总量不变
        val alphaLike = listOf(
            "sms_shared_knowledge", "helmsmanship", "target_analysis", "impact_mitigation",
            "field_modulation", "gunnery_implants", "combat_endurance",
        )
        val table = BountyOfficerSkills.forVariant("astd_xc_002_Standard")!!
        val planned = BountyFleetTunerImpl.planModuleCoreSkills(alphaLike, table)
        assertEquals(alphaLike.size, planned.size)
        assertEquals(listOf("sms_shared_knowledge"), planned.take(1))
        assertEquals(6, planned.drop(1).size)
        planned.drop(1).forEach { assertTrue(it in table, "$it 应来自变体技能表") }
        assertEquals(planned.distinct().size, planned.size, "技能不得重复")

        // SMS 无定形拟核组成（9 技能，3 特殊，未登记变体走 fallback）：3 特殊全保留，6 位走全局优先级表
        val amorphousLike = listOf(
            "sms_amorphous_knowledge", "sms_dimensional_tether", "sms_shared_knowledge",
            "helmsmanship", "target_analysis", "impact_mitigation",
            "field_modulation", "gunnery_implants", "combat_endurance",
        )
        val amorphousPlan = BountyFleetTunerImpl.planModuleCoreSkills(amorphousLike, null)
        assertEquals(amorphousLike.size, amorphousPlan.size)
        assertEquals(amorphousLike.take(3), amorphousPlan.take(3))
        assertEquals(
            BountyFleetTunerImpl.BOUNTY_OFFICER_SKILL_PRIORITY.take(6),
            amorphousPlan.drop(3),
        )

        // SMS 破损 Gamma 核心组成（1 技能，无特殊）：整体替换为 1 个变体表/优先级技能，不扩位
        val fracturedPlan = BountyFleetTunerImpl.planModuleCoreSkills(listOf("helmsmanship"), null)
        assertEquals(listOf("helmsmanship"), fracturedPlan)

        // 变体表技能数不足技能位：优先级表补齐差量（不重复），总量仍与原组成一致
        val shortTable = BountyFleetTunerImpl.planModuleCoreSkills(alphaLike, listOf("combat_endurance", "helmsmanship"))
        assertEquals(alphaLike.size, shortTable.size)
        assertEquals(listOf("sms_shared_knowledge", "combat_endurance", "helmsmanship"), shortTable.take(3))
        assertEquals(shortTable.distinct().size, shortTable.size)

        // 全特殊技能（理论边界）：原样保留，不产生替换
        val allSpecial = listOf("sms_a", "sms_b")
        assertEquals(allSpecial, BountyFleetTunerImpl.planModuleCoreSkills(allSpecial, table))
    }

    @Test
    fun `SMod 适用性等效判定按船体特征过滤候选`() {
        // 相位线圈仅相位舰可用；强化护盾须有护盾；扩展弹舱须具备导弹搭载能力
        assertTrue(fitRules.isSmodApplicable("adaptive_coils", phase = true, hasShield = false, missileCapable = false))
        assertFalse(fitRules.isSmodApplicable("adaptive_coils", phase = false, hasShield = true, missileCapable = true))
        assertTrue(fitRules.isSmodApplicable("hardenedshieldemitter", phase = false, hasShield = true, missileCapable = false))
        assertFalse(fitRules.isSmodApplicable("hardenedshieldemitter", phase = true, hasShield = false, missileCapable = false))
        assertTrue(fitRules.isSmodApplicable("magazines", phase = false, hasShield = true, missileCapable = true))
        assertFalse(fitRules.isSmodApplicable("magazines", phase = false, hasShield = true, missileCapable = false))
        // 其余候选全舰种通用
        listOf("targetingunit", "heavyarmor").forEach {
            assertTrue(fitRules.isSmodApplicable(it, phase = true, hasShield = false, missileCapable = false))
            assertTrue(fitRules.isSmodApplicable(it, phase = false, hasShield = true, missileCapable = true))
        }
    }

    @Test
    fun `OP 回收方案从存量较多侧交替拆除且不超预算`() {
        // 平手先拆寄存器，随后交替
        assertEquals(2 to 2, fitRules.planOpReclaim(caps = 5, vents = 5, opPerUnit = 1, neededOp = 4, budget = 14))
        assertEquals(1 to 0, fitRules.planOpReclaim(caps = 2, vents = 2, opPerUnit = 1, neededOp = 1, budget = 14))
        // 存量较多侧优先：寄存器远多于耗散通道时连续从寄存器侧拆
        assertEquals(3 to 0, fitRules.planOpReclaim(caps = 20, vents = 2, opPerUnit = 1, neededOp = 3, budget = 14))
        // 按缺口拆除不过量：缺口 8（4/点）拆 2 点即停，不多拆
        assertEquals(1 to 1, fitRules.planOpReclaim(caps = 5, vents = 5, opPerUnit = 4, neededOp = 8, budget = 14))
        assertEquals(2 to 1, fitRules.planOpReclaim(caps = 5, vents = 5, opPerUnit = 2, neededOp = 6, budget = 14))
        // 预算硬约束：回收总量不超过预算（4/点、预算 14 → 至多 3 点 = 12）
        val (caps, vents) = fitRules.planOpReclaim(caps = 10, vents = 10, opPerUnit = 4, neededOp = 99, budget = 14)
        assertTrue((caps + vents) * 4 <= 14, "回收量越出预算")
        assertEquals(12, (caps + vents) * 4)
        // 存量耗尽即停
        assertEquals(1 to 0, fitRules.planOpReclaim(caps = 1, vents = 0, opPerUnit = 1, neededOp = 99, budget = 14))
        // 需求为零或预算装不下单点时不拆
        assertEquals(0 to 0, fitRules.planOpReclaim(caps = 5, vents = 5, opPerUnit = 1, neededOp = 0, budget = 14))
        assertEquals(0 to 0, fitRules.planOpReclaim(caps = 5, vents = 5, opPerUnit = 4, neededOp = 4, budget = 3))
    }

    @Test
    fun `SMod 候选与已装 SHU 冲突时跳过取下一个`() {
        // 已装等离子充能护盾：强化护盾被冲突过滤，其余候选不受影响
        assertTrue(fitRules.isSmodBlockedByShu("hardenedshieldemitter", BountyFitRules.SHU_PLASMA_DYNAMO))
        assertFalse(fitRules.isSmodBlockedByShu("heavyarmor", BountyFitRules.SHU_PLASMA_DYNAMO))
        assertFalse(fitRules.isSmodBlockedByShu("targetingunit", BountyFitRules.SHU_PLASMA_DYNAMO))
        // 等离子互斥表覆盖 SHU 源码四件护盾系船插（硬化/稳定/扩展/分流）
        listOf("hardenedshieldemitter", "stabilizedshieldemitter", "extendedshieldemitter", "shield_shunt").forEach {
            assertTrue(fitRules.isSmodBlockedByShu(it, BountyFitRules.SHU_PLASMA_DYNAMO), "等离子互斥表缺少 $it")
        }
        // 未装 SHU 不过滤任何候选；超分流器冲突表独立于等离子
        assertFalse(fitRules.isSmodBlockedByShu("hardenedshieldemitter", null))
        assertTrue(fitRules.isSmodBlockedByShu("fluxbreakers", BountyFitRules.SHU_HYPERSHUNT))
        assertFalse(fitRules.isSmodBlockedByShu("hardenedshieldemitter", BountyFitRules.SHU_HYPERSHUNT))
    }

    @Test
    fun `SHU 候选权重按舰级与航母特征调整`() {
        val capital = fitRules.shuCandidates(ShipAPI.HullSize.CAPITAL_SHIP, carrier = false)
        val frigate = fitRules.shuCandidates(ShipAPI.HullSize.FRIGATE, carrier = false)
        // 超分流器仅主力舰入池；战机工厂仅航母入池
        assertTrue(capital.any { it.first == BountyFitRules.SHU_HYPERSHUNT })
        assertTrue(frigate.none { it.first == BountyFitRules.SHU_HYPERSHUNT })
        assertTrue(fitRules.shuCandidates(ShipAPI.HullSize.CRUISER, carrier = true).any { it.first == BountyFitRules.SHU_DRONE_REPLICATOR })
        assertTrue(capital.none { it.first == BountyFitRules.SHU_DRONE_REPLICATOR })
        // 纳米蜂群与聚变电容为舰体级安装 id（SHU 安装表 _upgrades 口径）
        assertTrue(frigate.any { it.first == "specialsphmod_soilnanites_upgrades" })
        assertTrue(frigate.any { it.first == "specialsphmod_fusionlampreactor_upgrades" })
        // 战术中继驱逐舰权重高于其他舰级
        val destroyerWeight = fitRules.shuCandidates(ShipAPI.HullSize.DESTROYER, carrier = false)
            .first { it.first == BountyFitRules.SHU_DEALMAKER }.second
        val cruiserWeight = fitRules.shuCandidates(ShipAPI.HullSize.CRUISER, carrier = false)
            .first { it.first == BountyFitRules.SHU_DEALMAKER }.second
        assertTrue(destroyerWeight > cruiserWeight, "战术中继驱逐舰权重应上调")
        // 候选表只产出正权重条目（权重 0 即不入池）
        ShipAPI.HullSize.entries.forEach { size ->
            assertTrue(fitRules.shuCandidates(size, carrier = false).all { it.second > 0f })
        }
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
    fun `双侧混编池覆盖全部作战舰级且池内变体均有技能表登记`() {
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
        // 随机池变体必须在变体技能表中有登记（技能组成的真相来源）
        val poolIds = BountyPoolConfig.ASTD_POOLS.values.flatten()
        poolIds.forEach { variantId ->
            assertTrue(BountyOfficerSkills.forVariant(variantId) != null, "混编池变体 $variantId 缺少技能表登记")
        }
        // 发布范围外舰体与唯一舰不得进入随机池（唯一舰导入装配仅作旗舰引用）
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
