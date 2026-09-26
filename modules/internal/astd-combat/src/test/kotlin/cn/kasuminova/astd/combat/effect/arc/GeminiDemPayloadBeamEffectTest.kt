package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemDifficulty
import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemPayloadBeamEffect
import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemSyncHandler
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.WeaponSpecAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 规格 10 §4.1 用例 9~10（2026-09 机制重做：EMP 电弧改照射期 0.1s 节律，单道 EMP = 面板 ×10%）：
 * 首伤帧一次性登记（防照射期重复）、动能节律 EMP 电弧、高爆无电弧、停火后状态移除可重新触发、
 * hulk/战机目标不登记不触发。
 */
class GeminiDemPayloadBeamEffectTest {

    /** 单道 EMP 电弧伤害 = 面板 × 比例。 */
    private val empPerArc = GeminiDemDifficulty.WARHEAD_PANEL_DAMAGE * GeminiDemDifficulty.EMP_ARC_EMP_FRACTION

    private fun stubEngine(now: Float = 10f): CombatEngineAPI {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.customData).thenReturn(mutableMapOf())
        `when`(engine.getTotalElapsedTime(false)).thenReturn(now)
        `when`(engine.isEntityInPlay(org.mockito.ArgumentMatchers.any())).thenReturn(true)
        return engine
    }

    private fun stubShip(id: String, owner: Int, hulk: Boolean = false, fighter: Boolean = false): ShipAPI {
        val ship = mock(ShipAPI::class.java)
        `when`(ship.id).thenReturn(id)
        `when`(ship.owner).thenReturn(owner)
        `when`(ship.isHulk).thenReturn(hulk)
        `when`(ship.isFighter).thenReturn(fighter)
        // 同步触发会对双弹头 demDrone stats 施加乘区（applySyncMult）并写实体 customData（markSyncVisual），必须可触达
        val stats = mock(MutableShipStatsAPI::class.java)
        `when`(stats.energyWeaponDamageMult).thenReturn(mock(MutableStat::class.java))
        `when`(ship.mutableStats).thenReturn(stats)
        `when`(ship.customData).thenReturn(mutableMapOf())
        return ship
    }

    private fun stubBeam(
        payloadWeaponId: String,
        target: ShipAPI,
        source: ShipAPI,
        firing: Boolean = true,
        damaging: Boolean = true,
    ): BeamAPI {
        val spec = mock(WeaponSpecAPI::class.java)
        `when`(spec.weaponId).thenReturn(payloadWeaponId)
        val weapon = mock(WeaponAPI::class.java)
        `when`(weapon.spec).thenReturn(spec)
        `when`(weapon.isFiring).thenReturn(firing)
        val beam = mock(BeamAPI::class.java)
        `when`(beam.weapon).thenReturn(weapon)
        `when`(beam.didDamageThisFrame()).thenReturn(damaging)
        `when`(beam.damageTarget).thenReturn(target)
        `when`(beam.to).thenReturn(Vector2f(50f, 60f))
        `when`(beam.source).thenReturn(source)
        return beam
    }

    @Test
    fun `用例10 动能光束照射期每 0_1s 一道 EMP 电弧（0_3s 共 3 道），高爆光束 0 次`() {
        val engine = stubEngine()
        val effect = GeminiDemPayloadBeamEffect()
        val target = stubShip("T1", 1)
        val source = stubShip("P1", 0)

        val kinetic = stubBeam(GeminiDemDifficulty.KINETIC_PAYLOAD_ID, target, source)
        repeat(5) { effect.advance(0.06f, engine, kinetic) }
        verify(engine, times(2)).spawnEmpArc(
            source, kinetic.to, target, target, DamageType.ENERGY,
            0f, empPerArc, 10_000f,
            "tachyon_lance_emp_impact", 20f,
            java.awt.Color(140, 200, 255), java.awt.Color(225, 242, 255),
        )
        assertEquals(2, GeminiDemPayloadBeamEffect.empArcCount(engine))
        assertEquals(1, GeminiDemPayloadBeamEffect.kineticHitCount(engine), "首伤帧登记一次性（5 帧伤害只记 1 次）")

        val he = stubBeam(GeminiDemDifficulty.HE_PAYLOAD_ID, target, source)
        repeat(5) { effect.advance(0.06f, engine, he) }
        // 电弧总数不变（高爆无 EMP）；同步共振已被动能+高爆异种配对触发一次
        assertEquals(2, GeminiDemPayloadBeamEffect.empArcCount(engine))
        assertEquals(1, GeminiDemPayloadBeamEffect.heHitCount(engine))
        assertEquals(1, GeminiDemSyncHandler.syncTriggerCount(engine), "动能+高爆 1s 窗内配对触发同步")
    }

    @Test
    fun `用例10b 停火后状态移除，再次伤害帧可重新触发（新一轮打击）`() {
        val engine = stubEngine()
        val effect = GeminiDemPayloadBeamEffect()
        val target = stubShip("T1", 1)
        val source = stubShip("P1", 0)

        val spec = mock(WeaponSpecAPI::class.java)
        `when`(spec.weaponId).thenReturn(GeminiDemDifficulty.KINETIC_PAYLOAD_ID)
        val weapon = mock(WeaponAPI::class.java)
        `when`(weapon.spec).thenReturn(spec)
        val beam = mock(BeamAPI::class.java)
        `when`(beam.weapon).thenReturn(weapon)
        `when`(beam.damageTarget).thenReturn(target)
        `when`(beam.to).thenReturn(Vector2f(50f, 60f))
        `when`(beam.source).thenReturn(source)

        // 第一轮：开火 + 伤害帧 → 首伤帧登记（0.016s 不足一道电弧）
        `when`(weapon.isFiring).thenReturn(true)
        `when`(beam.didDamageThisFrame()).thenReturn(true)
        effect.advance(0.016f, engine, beam)
        assertEquals(1, GeminiDemPayloadBeamEffect.kineticHitCount(engine))
        assertEquals(0, GeminiDemPayloadBeamEffect.empArcCount(engine))

        // 停火：状态移除
        `when`(beam.didDamageThisFrame()).thenReturn(false)
        `when`(weapon.isFiring).thenReturn(false)
        effect.advance(0.016f, engine, beam)

        // 第二轮：再次开火 + 0.3s 伤害帧 → 再登记一次 + 2 道电弧
        `when`(weapon.isFiring).thenReturn(true)
        `when`(beam.didDamageThisFrame()).thenReturn(true)
        `when`(engine.getTotalElapsedTime(false)).thenReturn(20f)
        repeat(5) { effect.advance(0.06f, engine, beam) }
        assertEquals(2, GeminiDemPayloadBeamEffect.empArcCount(engine))
        assertEquals(2, GeminiDemPayloadBeamEffect.kineticHitCount(engine))
    }

    @Test
    fun `用例9 hulk 与战机目标不登记不触发（光束伤害照常走原版）`() {
        val engine = stubEngine()
        val effect = GeminiDemPayloadBeamEffect()
        val source = stubShip("P1", 0)

        val hulk = stubShip("H1", 1, hulk = true)
        val fighter = stubShip("F1", 1, fighter = true)
        effect.advance(0.016f, engine, stubBeam(GeminiDemDifficulty.KINETIC_PAYLOAD_ID, hulk, source))
        effect.advance(0.016f, engine, stubBeam(GeminiDemDifficulty.KINETIC_PAYLOAD_ID, fighter, source))

        verify(engine, never()).spawnEmpArc(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyFloat(),
            org.mockito.ArgumentMatchers.anyFloat(), org.mockito.ArgumentMatchers.anyFloat(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyFloat(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        )
        assertEquals(0, GeminiDemPayloadBeamEffect.empArcCount(engine))
        assertEquals(0, GeminiDemPayloadBeamEffect.kineticHitCount(engine))
        assertEquals(0, GeminiDemSyncHandler.hitRegisteredCount(engine), "hulk/战机不写入同步登记")
        assertTrue(GeminiDemSyncHandler.registryOf(engine).isEmpty())
    }
}
