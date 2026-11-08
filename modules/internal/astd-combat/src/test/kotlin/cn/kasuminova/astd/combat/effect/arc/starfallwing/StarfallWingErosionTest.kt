package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.buff.getBuff
import cn.kasuminova.astd.impl.buff.BuffInstall
import cn.kasuminova.astd.impl.buff.stubShip
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StarfallWingErosionTest {

    @BeforeTest
    fun installBuffBackend() {
        BuffInstall.install()
    }

    private fun erosionTarget(baseShieldTaken: Float = 0.8f): com.fs.starfarer.api.combat.ShipAPI {
        val ship = stubShip()
        `when`(ship.location).thenReturn(Vector2f(0f, 0f))
        val stats = mock(MutableShipStatsAPI::class.java)
        `when`(stats.shieldDamageTakenMult).thenReturn(MutableStat(baseShieldTaken))
        `when`(ship.mutableStats).thenReturn(stats)
        return ship
    }

    private fun moteHit(
        onHit: StarfallWingMoteOnHitEffect,
        ship: com.fs.starfarer.api.combat.ShipAPI,
        engine: CombatEngineAPI,
        shieldHit: Boolean,
    ) {
        val proj = mock(com.fs.starfarer.api.combat.DamagingProjectileAPI::class.java)
        val impact = mock(ApplyDamageResultAPI::class.java)
        onHit.onHit(proj, ship, Vector2f(50f, 0f), shieldHit, impact, engine)
    }

    @Test
    fun `星羽撞盾附加 1 层振频蚀穿 撞船体不附加`() {
        val engine = mock(CombatEngineAPI::class.java)
        val ship = erosionTarget()
        val onHit = StarfallWingMoteOnHitEffect()

        moteHit(onHit, ship, engine, shieldHit = false)
        assertNull(ship.getBuff(StarfallWingErosionStacks.BUFF_ID), "撞船体不附加振频蚀穿")

        moteHit(onHit, ship, engine, shieldHit = true)
        val buff = ship.getBuff(StarfallWingErosionStacks.BUFF_ID) as? StarfallWingErosionStacks
        assertEquals(1, buff?.stacks ?: -1, "撞盾附加 1 层")
        moteHit(onHit, ship, engine, shieldHit = true)
        assertEquals(2, buff?.stacks ?: -1, "两次撞盾累计 2 层")
    }

    @Test
    fun `振频蚀穿叠层劣化护盾承伤乘区 满 15 层后挤掉最早层`() {
        val engine = mock(CombatEngineAPI::class.java)
        val ship = erosionTarget(baseShieldTaken = 0.8f)
        repeat(StarfallWingTuning.EROSION_MAX_STACKS) {
            StarfallWingErosionStacks.attachLayer(ship, engine, null)
        }
        val buff = ship.getBuff(StarfallWingErosionStacks.BUFF_ID) as? StarfallWingErosionStacks
        assertNotNull(buff)
        assertEquals(StarfallWingTuning.EROSION_MAX_STACKS, buff.stacks)

        val expected = 0.8f * StarfallWingTuning.erosionShieldMult(0.8f, StarfallWingTuning.EROSION_MAX_STACKS)
        assertEquals(
            expected,
            ship.mutableStats.shieldDamageTakenMult.modifiedValue,
            1e-4f,
            "满层承伤乘区按 erosionShieldMult 劣化",
        )

        StarfallWingErosionStacks.attachLayer(ship, engine, null)
        assertEquals(
            StarfallWingTuning.EROSION_MAX_STACKS, buff.stacks,
            "第 16 层挤掉最早层，层数不超上限",
        )
    }

    @Test
    fun `振频蚀穿各层独立计时 到期逐层消退并移除增益`() {
        val engine = mock(CombatEngineAPI::class.java)
        val ship = erosionTarget()
        StarfallWingErosionStacks.attachLayer(ship, engine, null)
        val buff = ship.getBuff(StarfallWingErosionStacks.BUFF_ID) as? StarfallWingErosionStacks
        assertNotNull(buff)
        buff.advance(StarfallWingTuning.EROSION_DURATION_SECONDS - 0.5f)
        assertEquals(1, buff.stacks, "未满单层时长不消退")
        StarfallWingErosionStacks.attachLayer(ship, engine, null)
        assertEquals(2, buff.stacks)
        buff.advance(0.6f)
        assertEquals(1, buff.stacks, "最早一层先到期")
        buff.advance(StarfallWingTuning.EROSION_DURATION_SECONDS)
        assertNull(ship.getBuff(StarfallWingErosionStacks.BUFF_ID), "全部到期后增益移除")
        assertEquals(
            0.8f,
            ship.mutableStats.shieldDamageTakenMult.modifiedValue,
            1e-4f,
            "增益移除后承伤乘区还原",
        )
    }

    @Test
    fun `虚数充能池 入账消耗钳制与共鸣冷却闸门`() {
        val ship = stubShip()
        val pool = cn.kasuminova.astd.combat.hullmods.arc.ImaginaryCharge.of(ship)
        pool.add(150f)
        assertEquals(
            cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning.CHARGE_MAX,
            pool.charge, 1e-4f, "入账不超上限",
        )
        pool.consume(200f)
        assertEquals(0f, pool.charge, 1e-4f, "消耗不跌破 0")

        assertTrue(pool.tryHitGain(3f), "首次共鸣入账")
        assertEquals(3f, pool.charge, 1e-4f)
        assertTrue(!pool.tryHitGain(3f), "冷却窗内重复共鸣拒绝")
        assertEquals(3f, pool.charge, 1e-4f)
        pool.hitGainCooldown = 0f
        assertTrue(pool.tryHitGain(3f), "冷却结束后共鸣恢复")
        assertEquals(6f, pool.charge, 1e-4f)
    }
}
