package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemDifficulty
import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemPayloadBeamEffect
import cn.kasuminova.astd.combat.effect.arc.geminidem.GeminiDemSyncHandler
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
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

/**
 * 规格 10 §4.1 用例 9~10（EMP 电弧口径：每轮打击预算 5 道 × 0.2s 节律，
 * 单道 EMP = 面板 1000 × 总量难度倍率 × 20%——玩家恒 v2 单道 400）：
 * 首伤帧一次性登记（防照射期重复）、动能节律 EMP 电弧打满预算即止、高爆无电弧、
 * 停火后状态移除可重新触发、hulk/战机目标不登记不触发、
 * 战机版 payload 按 weaponId 识别（数据驱动削弱链），EMP 单道乘算 ×0.75。
 */
class GeminiDemPayloadBeamEffectTest {

    /** 玩家来源（owner=0）恒 v2：单道 EMP = 面板 × v2 总量 × 单道占比。 */
    private val empPerArcPlayerV2 =
        GeminiDemDifficulty.WARHEAD_PANEL_DAMAGE * GeminiDemDifficulty.EMP_TOTAL_FRACTION.v2 * GeminiDemDifficulty.EMP_ARC_SHARE_OF_TOTAL

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

    /** 持续伤害帧驱动 beam 推进 [frames] × [frameSeconds] 秒。 */
    private fun burn(effect: GeminiDemPayloadBeamEffect, engine: CombatEngineAPI, beam: BeamAPI, frames: Int, frameSeconds: Float = 0.05f) {
        repeat(frames) { effect.advance(frameSeconds, engine, beam) }
    }

    @Test
    fun `用例10 动能光束每轮打击预算 5 道 EMP 电弧（0_2s 节律，打满即止），高爆光束 0 次`() {
        val engine = stubEngine()
        val effect = GeminiDemPayloadBeamEffect()
        val target = stubShip("T1", 1)
        val source = stubShip("P1", 0)

        val kinetic = stubBeam(GeminiDemDifficulty.KINETIC_PAYLOAD_ID, target, source)
        // 1.5s 照射（30 × 0.05s）：首道随首伤帧立即打出，其后于累积 0.2/0.4/0.6/0.8s 各一道，预算 5 道打满后不再产生
        burn(effect, engine, kinetic, 30)
        verify(engine, times(5)).spawnEmpArc(
            source, kinetic.to, target, target, DamageType.ENERGY,
            0f, empPerArcPlayerV2, 10_000f,
            "tachyon_lance_emp_impact", 20f,
            java.awt.Color(140, 200, 255), java.awt.Color(225, 242, 255),
        )
        assertEquals(5, GeminiDemPayloadBeamEffect.empArcCount(engine))
        assertEquals(1, GeminiDemPayloadBeamEffect.kineticHitCount(engine), "首伤帧登记一次性（30 帧伤害只记 1 次）")

        val he = stubBeam(GeminiDemDifficulty.HE_PAYLOAD_ID, target, source)
        burn(effect, engine, he, 30)
        // 电弧总数不变（高爆无 EMP）；同步共振已被动能+高爆异种配对触发一次
        assertEquals(5, GeminiDemPayloadBeamEffect.empArcCount(engine))
        assertEquals(1, GeminiDemPayloadBeamEffect.heHitCount(engine))
        assertEquals(1, GeminiDemSyncHandler.syncTriggerCount(engine), "动能+高爆 1s 窗内配对触发同步")
    }

    @Test
    fun `用例10b 停火后状态移除，再次伤害帧可重新触发（新一轮打击重置 5 道预算）`() {
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

        // 第一轮：开火 + 伤害帧 → 首伤帧登记 + 首道电弧随首伤帧立即打出
        `when`(weapon.isFiring).thenReturn(true)
        `when`(beam.didDamageThisFrame()).thenReturn(true)
        effect.advance(0.016f, engine, beam)
        assertEquals(1, GeminiDemPayloadBeamEffect.kineticHitCount(engine))
        assertEquals(1, GeminiDemPayloadBeamEffect.empArcCount(engine))

        // 停火：状态移除
        `when`(beam.didDamageThisFrame()).thenReturn(false)
        `when`(weapon.isFiring).thenReturn(false)
        effect.advance(0.016f, engine, beam)

        // 第二轮：再次开火 + 1.5s 伤害帧 → 再登记一次 + 打满新一轮 5 道预算
        `when`(weapon.isFiring).thenReturn(true)
        `when`(beam.didDamageThisFrame()).thenReturn(true)
        `when`(engine.getTotalElapsedTime(false)).thenReturn(20f)
        burn(effect, engine, beam, 30)
        assertEquals(6, GeminiDemPayloadBeamEffect.empArcCount(engine))
        assertEquals(2, GeminiDemPayloadBeamEffect.kineticHitCount(engine))
    }

    @Test
    fun `用例10c 敌版来源按轨一 k_s 缩放 EMP 总量（k_s=5 单道 1000），玩家侧不受注入影响`() {
        val engine = stubEngine()
        val effect = GeminiDemPayloadBeamEffect()
        val target = stubShip("T1", 0)
        val enemy = stubShip("E1", 1)

        DifficultyTuningImpl.installScaleForTests(5f)
        try {
            val beam = stubBeam(GeminiDemDifficulty.KINETIC_PAYLOAD_ID, target, enemy)
            burn(effect, engine, beam, 30)
            val empV5 =
                GeminiDemDifficulty.WARHEAD_PANEL_DAMAGE * GeminiDemDifficulty.EMP_TOTAL_FRACTION.v5 * GeminiDemDifficulty.EMP_ARC_SHARE_OF_TOTAL
            verify(engine, times(5)).spawnEmpArc(
                org.mockito.ArgumentMatchers.same(enemy), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.same(target), org.mockito.ArgumentMatchers.same(target),
                org.mockito.ArgumentMatchers.same(DamageType.ENERGY),
                org.mockito.ArgumentMatchers.eq(0f), org.mockito.ArgumentMatchers.eq(empV5),
                org.mockito.ArgumentMatchers.anyFloat(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyFloat(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            )
        } finally {
            DifficultyTuningImpl.installScaleForTests(null)
        }
    }

    @Test
    fun `战机版 payload：按 payload weaponId 识别，EMP 单道乘算 x0_75（玩家 v2 单道 300），命中登记口径不变`() {
        val engine = stubEngine()
        val effect = GeminiDemPayloadBeamEffect()
        val target = stubShip("T1", 1)
        val source = stubShip("P1", 0)

        // 数据驱动削弱链：光束伤害由战机版 spec dps 折算（引擎侧），脚本侧只剩 EMP 单道乘算；
        // 战机版动能 payload 同样按动能命中登记（同步配对口径与舰装一致）
        val beam = stubBeam(GeminiDemDifficulty.KINETIC_PAYLOAD_FIGHTER_ID, target, source)
        burn(effect, engine, beam, 30)

        assertEquals(1, GeminiDemPayloadBeamEffect.kineticHitCount(engine))
        val empFighter = empPerArcPlayerV2 * GeminiDemDifficulty.FIGHTER_DAMAGE_MULT
        verify(engine, times(5)).spawnEmpArc(
            org.mockito.ArgumentMatchers.same(source), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.same(target), org.mockito.ArgumentMatchers.same(target),
            org.mockito.ArgumentMatchers.same(DamageType.ENERGY),
            org.mockito.ArgumentMatchers.eq(0f), org.mockito.ArgumentMatchers.eq(empFighter),
            org.mockito.ArgumentMatchers.anyFloat(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyFloat(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        )
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
        kotlin.test.assertTrue(GeminiDemSyncHandler.registryOf(engine).isEmpty())
    }
}
