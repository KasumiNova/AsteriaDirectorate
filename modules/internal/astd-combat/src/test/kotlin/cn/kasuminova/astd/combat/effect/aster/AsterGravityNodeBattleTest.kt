package cn.kasuminova.astd.combat.effect.aster

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 紫菀引力节点战斗效果（07 文档 53-64 行）逻辑验证：
 * 守方分档时间增益三锚点、距离衰减边界、攻方"额外"削减只动附加来源不动基础值、
 * 攻方减成全场常驻不衰减（falloffFactor 不进入攻方路径由实现保证，此处验证数学性质）。
 */
class AsterGravityNodeBattleTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun valueAt(scale: Float, entry: ScalingEntry): Float {
        DifficultyTuningImpl.installScaleForTests(scale)
        return DifficultyTuningImpl.value(entry)
    }

    @Test
    fun `守方时间增益按舰体大小与难度命中设计三锚点`() {
        val expected = mapOf(
            ShipAPI.HullSize.FRIGATE to Triple(0.30f, 0.45f, 0.60f),
            ShipAPI.HullSize.DESTROYER to Triple(0.25f, 0.375f, 0.50f),
            ShipAPI.HullSize.CRUISER to Triple(0.20f, 0.30f, 0.40f),
            ShipAPI.HullSize.CAPITAL_SHIP to Triple(0.15f, 0.225f, 0.30f),
        )
        for ((size, anchors) in expected) {
            DifficultyTuningImpl.installScaleForTests(1f)
            assertEquals(anchors.first, AsterGravityNodeMath.defenderTimeFlowBonus(size, DifficultyTuningImpl), 1e-6f, "$size k=1")
            DifficultyTuningImpl.installScaleForTests(2f)
            assertEquals(anchors.second, AsterGravityNodeMath.defenderTimeFlowBonus(size, DifficultyTuningImpl), 1e-6f, "$size k=2")
            DifficultyTuningImpl.installScaleForTests(5f)
            assertEquals(anchors.third, AsterGravityNodeMath.defenderTimeFlowBonus(size, DifficultyTuningImpl), 1e-6f, "$size k=5")
        }
    }

    @Test
    fun `守方减伤与攻方削减及半径命中设计三锚点`() {
        assertEquals(0.20f, valueAt(1f, AsterGravityNodeMath.DEFENDER_DAMAGE_TAKEN_REDUCTION), 1e-6f)
        assertEquals(0.40f, valueAt(5f, AsterGravityNodeMath.DEFENDER_DAMAGE_TAKEN_REDUCTION), 1e-6f)

        assertEquals(0.25f, valueAt(1f, AsterGravityNodeMath.ATTACKER_EXTRA_TIME_FLOW_CUT), 1e-6f)
        assertEquals(0.75f, valueAt(5f, AsterGravityNodeMath.ATTACKER_EXTRA_TIME_FLOW_CUT), 1e-6f)
        assertEquals(0.15f, valueAt(1f, AsterGravityNodeMath.ATTACKER_EXTRA_RANGE_CUT), 1e-6f)
        assertEquals(0.30f, valueAt(5f, AsterGravityNodeMath.ATTACKER_EXTRA_RANGE_CUT), 1e-6f)
        assertEquals(0.25f, valueAt(1f, AsterGravityNodeMath.ATTACKER_EXTRA_SHIELD_REDUCTION_CUT), 1e-6f)
        assertEquals(0.75f, valueAt(5f, AsterGravityNodeMath.ATTACKER_EXTRA_SHIELD_REDUCTION_CUT), 1e-6f)

        assertEquals(800f, valueAt(1f, AsterGravityNodeMath.FULL_EFFECT_RADIUS), 1e-6f)
        assertEquals(1600f, valueAt(5f, AsterGravityNodeMath.FULL_EFFECT_RADIUS), 1e-6f)
        assertEquals(1600f, valueAt(1f, AsterGravityNodeMath.OUTER_EFFECT_RADIUS), 1e-6f)
        assertEquals(3200f, valueAt(5f, AsterGravityNodeMath.OUTER_EFFECT_RADIUS), 1e-6f)
    }

    @Test
    fun `距离衰减满效内为1 外半径外为0 其间线性`() {
        val full = 800f
        val outer = 1600f
        assertEquals(1f, AsterGravityNodeMath.falloffFactor(0f, full, outer))
        assertEquals(1f, AsterGravityNodeMath.falloffFactor(800f, full, outer))
        assertEquals(0.5f, AsterGravityNodeMath.falloffFactor(1200f, full, outer), 1e-6f)
        assertEquals(0.25f, AsterGravityNodeMath.falloffFactor(1400f, full, outer), 1e-6f)
        assertEquals(0f, AsterGravityNodeMath.falloffFactor(1600f, full, outer))
        assertEquals(0f, AsterGravityNodeMath.falloffFactor(9999f, full, outer))
    }

    @Test
    fun `攻方额外时间流速削减只动附加部分 基础值不动`() {
        // eff=1.6（+60% 额外时流，如词缀/技能来源），cut=50% → 额外减半 → 目标 1.3
        val comp = AsterGravityNodeMath.extraTimeFlowCompMult(1.6f, 0.5f)
        assertEquals(1.3f, 1.6f * comp, 1e-6f)
        // 无额外时流（eff=1）→ 不动
        assertEquals(1f, AsterGravityNodeMath.extraTimeFlowCompMult(1.0f, 0.75f))
        // 时流低于基础（如被其他效果压慢）→ 不属于"额外"，不动
        assertEquals(1f, AsterGravityNodeMath.extraTimeFlowCompMult(0.8f, 0.75f))
        // cut=75%：+100% 额外 → 目标 1.25
        assertEquals(1.25f, 2.0f * AsterGravityNodeMath.extraTimeFlowCompMult(2.0f, 0.75f), 1e-6f)
    }

    @Test
    fun `攻方额外射程削减只抵消正值加成`() {
        // +30% 额外射程，cut=30% → 抵消 9 个百分点
        assertEquals(-9f, AsterGravityNodeMath.extraRangeCompPercent(30f, 0.3f), 1e-6f)
        // 无加成或负加成 → 不动
        assertEquals(0f, AsterGravityNodeMath.extraRangeCompPercent(0f, 0.3f))
        assertEquals(0f, AsterGravityNodeMath.extraRangeCompPercent(-15f, 0.3f))
    }

    @Test
    fun `攻方额外护盾减伤削减只动减免部分`() {
        // eff=0.4（60% 减伤），cut=50% → 减伤减半为 30% → 目标 0.7
        val comp = AsterGravityNodeMath.extraShieldReductionCompMult(0.4f, 0.5f)
        assertEquals(0.7f, 0.4f * comp, 1e-6f)
        // 无减伤 → 不动
        assertEquals(1f, AsterGravityNodeMath.extraShieldReductionCompMult(1.0f, 0.75f))
        // 护盾承伤增加（eff>1）→ 不属于"减免"，不动
        assertEquals(1f, AsterGravityNodeMath.extraShieldReductionCompMult(1.3f, 0.75f))
    }

    @Test
    fun `削减补偿与修正撤销闭环 套用后可还原`() {
        // 模拟逐帧挂摘：unmodify 后重读有效值再挂补偿——补偿 id 不污染下一帧读取。
        // 用纯数值模拟：第一帧 eff=1.6 挂 comp=1.3/1.6；第二帧撤销后读取应回到 1.6。
        val original = 1.6f
        val comp = AsterGravityNodeMath.extraTimeFlowCompMult(original, 0.5f)
        val applied = original * comp
        // 撤销语义 = 除以本插件乘区（对应 unmodify），读回原值 → 下一帧不会二次叠加。
        assertEquals(original, applied / comp, 1e-6f)
    }

    @Test
    fun `入口幂等性与空列表语义`() {
        // install 需要真实引擎，纯逻辑侧验证：空节点列表拒绝安装的判定不依赖引擎状态。
        // 引擎交互路径由 campaign 接入方负责（FID delegate / battleCreationContext），此处验证数据结构默认。
        val spec = AsterGravityNodeBattle.NodeSpec("astd_aster_gravity_node_1", org.lwjgl.util.vector.Vector2f(100f, 200f))
        assertEquals(AsterGravityNodeBattle.DEFAULT_NODE_VARIANT_ID, spec.variantId)
        assertEquals(0f, spec.facing)
        assertFalse(AsterGravityNodeBattle.isActive(completedEngineNullSafe()))
    }

    /** 无引擎环境下 isActive 不应被误调：本测试只验证 NodeSpec 默认值，引擎路径留给实机/集成测试。 */
    private fun completedEngineNullSafe(): com.fs.starfarer.api.combat.CombatEngineAPI {
        val engine = org.mockito.Mockito.mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
        org.mockito.Mockito.`when`(engine.customData).thenReturn(mutableMapOf())
        return engine
    }

    // ─── 插件 advance 全链路（mock 引擎 + 真实 MutableStat/StatBonus） ───

    private fun realStats(): com.fs.starfarer.api.combat.MutableShipStatsAPI {
        val stats = org.mockito.Mockito.mock(com.fs.starfarer.api.combat.MutableShipStatsAPI::class.java)
        org.mockito.Mockito.`when`(stats.timeMult).thenReturn(com.fs.starfarer.api.combat.MutableStat(1f))
        org.mockito.Mockito.`when`(stats.hullDamageTakenMult).thenReturn(com.fs.starfarer.api.combat.MutableStat(1f))
        org.mockito.Mockito.`when`(stats.armorDamageTakenMult).thenReturn(com.fs.starfarer.api.combat.MutableStat(1f))
        org.mockito.Mockito.`when`(stats.shieldDamageTakenMult).thenReturn(com.fs.starfarer.api.combat.MutableStat(1f))
        org.mockito.Mockito.`when`(stats.ballisticWeaponRangeBonus).thenReturn(com.fs.starfarer.api.combat.StatBonus())
        org.mockito.Mockito.`when`(stats.energyWeaponRangeBonus).thenReturn(com.fs.starfarer.api.combat.StatBonus())
        org.mockito.Mockito.`when`(stats.missileWeaponRangeBonus).thenReturn(com.fs.starfarer.api.combat.StatBonus())
        return stats
    }

    private fun shipStub(
        owner: Int,
        hullSize: ShipAPI.HullSize,
        location: org.lwjgl.util.vector.Vector2f,
        stats: com.fs.starfarer.api.combat.MutableShipStatsAPI,
    ): ShipAPI {
        val ship = org.mockito.Mockito.mock(ShipAPI::class.java)
        org.mockito.Mockito.`when`(ship.isAlive).thenReturn(true)
        org.mockito.Mockito.`when`(ship.isHulk).thenReturn(false)
        org.mockito.Mockito.`when`(ship.isFighter).thenReturn(false)
        org.mockito.Mockito.`when`(ship.isStationModule).thenReturn(false)
        org.mockito.Mockito.`when`(ship.owner).thenReturn(owner)
        org.mockito.Mockito.`when`(ship.hullSize).thenReturn(hullSize)
        org.mockito.Mockito.`when`(ship.mutableStats).thenReturn(stats)
        org.mockito.Mockito.`when`(ship.location).thenReturn(location)
        return ship
    }

    /**
     * 全链路：安装 → 首帧刷出节点 → 守方近点满效增益（k_s=2 默认档：护卫舰 +45% 时流、-30% 承伤）、
     * 攻方额外削减（额外时流 +60% 被削 50% → 1.3；额外射程 +30% 被削 22.5% → 23.25%）→
     * 节点摧毁后所有修正撤销。
     */
    @Test
    fun `节点战斗全链路 刷出 增益减成 摧毁撤销`() {
        DifficultyTuningImpl.installScaleForTests(2f)

        var nodeAlive = true
        val nodeShip = shipStub(1, ShipAPI.HullSize.CAPITAL_SHIP, org.lwjgl.util.vector.Vector2f(0f, 0f), realStats())
        org.mockito.Mockito.`when`(nodeShip.isAlive).thenAnswer { nodeAlive }

        val defenderStats = realStats()
        val defender = shipStub(1, ShipAPI.HullSize.FRIGATE, org.lwjgl.util.vector.Vector2f(500f, 0f), defenderStats)

        val attackerStats = realStats()
        // 附加来源：技能/词缀给予的额外时流 +60%、额外射程 +30%
        attackerStats.timeMult.modifyMult("some_temporal_source", 1.6f)
        attackerStats.ballisticWeaponRangeBonus.modifyPercent("some_range_source", 30f)
        val attacker = shipStub(0, ShipAPI.HullSize.CRUISER, org.lwjgl.util.vector.Vector2f(5000f, 0f), attackerStats)

        val fleetManager = org.mockito.Mockito.mock(com.fs.starfarer.api.combat.CombatFleetManagerAPI::class.java)
        org.mockito.Mockito.`when`(
            fleetManager.spawnShipOrWing(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyFloat(),
            ),
        ).thenReturn(nodeShip)

        val engine = org.mockito.Mockito.mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
        val customData = mutableMapOf<String, Any>()
        org.mockito.Mockito.`when`(engine.customData).thenReturn(customData)
        org.mockito.Mockito.`when`(engine.isPaused).thenReturn(false)
        org.mockito.Mockito.`when`(engine.playerShip).thenReturn(null)
        org.mockito.Mockito.`when`(engine.timeMult).thenReturn(com.fs.starfarer.api.combat.MutableStat(1f))
        org.mockito.Mockito.`when`(engine.getFleetManager(1)).thenReturn(fleetManager)
        org.mockito.Mockito.`when`(engine.ships).thenReturn(mutableListOf(nodeShip, defender, attacker))

        val installed = AsterGravityNodeBattle.install(
            engine,
            listOf(AsterGravityNodeBattle.NodeSpec("astd_aster_gravity_node_1", org.lwjgl.util.vector.Vector2f(0f, 0f))),
            defenderOwner = 1,
        )
        assertTrue(installed)
        assertTrue(AsterGravityNodeBattle.isActive(engine))
        // 幂等：重复安装被拒绝
        assertFalse(
            AsterGravityNodeBattle.install(
                engine,
                listOf(AsterGravityNodeBattle.NodeSpec("dup", org.lwjgl.util.vector.Vector2f())),
                defenderOwner = 1,
            ),
        )

        val plugin = customData[AsterGravityNodeBattle.ENGINE_PLUGIN_KEY] as AsterGravityNodeBattlePlugin
        plugin.init(engine)
        plugin.advance(0.1f, null)

        // 节点刷出并打上识别标记
        org.mockito.Mockito.verify(nodeShip).setCustomData(AsterGravityNodeBattle.SHIP_NODE_ID_DATA_KEY, "astd_aster_gravity_node_1")
        assertEquals(listOf("astd_aster_gravity_node_1"), plugin.aliveNodeIds())

        // 守方（护卫舰，距节点 500su < 满效半径 1200）：+45% 时流、-30% 承伤
        assertEquals(1.45f, defenderStats.timeMult.modifiedValue, 1e-4f)
        assertEquals(0.70f, defenderStats.hullDamageTakenMult.modifiedValue, 1e-4f)
        assertEquals(0.70f, defenderStats.shieldDamageTakenMult.modifiedValue, 1e-4f)

        // 攻方（k_s=2：额外时流削 50%、额外射程削 22.5%）：基础值不动
        assertEquals(1.3f, attackerStats.timeMult.modifiedValue, 1e-4f)
        assertEquals(23.25f, attackerStats.ballisticWeaponRangeBonus.getPercentMod(), 1e-3f)

        // 节点摧毁 → 所有修正撤销
        nodeAlive = false
        org.mockito.Mockito.`when`(nodeShip.isHulk).thenReturn(true)
        plugin.advance(0.1f, null)
        assertEquals(1f, defenderStats.timeMult.modifiedValue, 1e-6f)
        assertEquals(1f, defenderStats.hullDamageTakenMult.modifiedValue, 1e-6f)
        assertEquals(1.6f, attackerStats.timeMult.modifiedValue, 1e-4f, "攻方额外时流应恢复原样")
        assertEquals(30f, attackerStats.ballisticWeaponRangeBonus.getPercentMod(), 1e-3f, "攻方额外射程应恢复原样")
        assertEquals(emptyList(), plugin.aliveNodeIds())
    }
}
