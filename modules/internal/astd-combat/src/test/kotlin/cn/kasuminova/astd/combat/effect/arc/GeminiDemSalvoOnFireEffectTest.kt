package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.impl.buff.WarnCapture
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 规格 10 §4.1 用例 12~13：齐射流程——dummy 同帧移除、双弹 weaponId/批次号/TrackAI +
 * DEMScript 装配；生成失败路径——非 MissileAPI 记 ERROR 且另一枚不受影响。
 * fake 引擎记录 spawnProjectile 调用，demPluginFactory 注入记录桩断言装配。
 */
class GeminiDemSalvoOnFireEffectTest {

    private fun stubEngine(): CombatEngineAPI {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.customData).thenReturn(mutableMapOf())
        `when`(engine.getTotalElapsedTime(false)).thenReturn(12.5f)
        return engine
    }

    private fun stubShip(id: String, owner: Int, target: ShipAPI?): ShipAPI {
        val ship = mock(ShipAPI::class.java)
        `when`(ship.id).thenReturn(id)
        `when`(ship.owner).thenReturn(owner)
        `when`(ship.shipTarget).thenReturn(target)
        `when`(ship.velocity).thenReturn(Vector2f(10f, 0f))
        return ship
    }

    private fun stubTarget(id: String, owner: Int = 1): ShipAPI {
        val ship = mock(ShipAPI::class.java)
        `when`(ship.id).thenReturn(id)
        `when`(ship.owner).thenReturn(owner)
        `when`(ship.isAlive).thenReturn(true)
        `when`(ship.isHulk).thenReturn(false)
        return ship
    }

    private fun stubProjectile(): DamagingProjectileAPI {
        val projectile = mock(DamagingProjectileAPI::class.java)
        `when`(projectile.facing).thenReturn(0f)
        `when`(projectile.location).thenReturn(Vector2f(100f, 100f))
        return projectile
    }

    private fun stubWarheadMissile(): MissileAPI {
        val missile = mock(MissileAPI::class.java)
        `when`(missile.customData).thenReturn(mutableMapOf())
        return missile
    }

    @Test
    fun `用例12 齐射流程：dummy 移除、双弹 weaponId 正确、同一批次号、TrackAI 与 DEMScript 装配`() {
        val engine = stubEngine()
        val target = stubTarget("T1")
        val ship = stubShip("P1", 0, target)
        val weapon = mock(WeaponAPI::class.java)
        `when`(weapon.ship).thenReturn(ship)
        `when`(weapon.id).thenReturn("astd_gemini_dem_launcher")
        val projectile = stubProjectile()

        val kineticMissile = stubWarheadMissile()
        val heMissile = stubWarheadMissile()
        `when`(
            engine.spawnProjectile(
                org.mockito.ArgumentMatchers.same(ship),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(GeminiDemDifficulty.KINETIC_WEAPON_ID),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyFloat(), org.mockito.ArgumentMatchers.any(),
            ),
        ).thenReturn(kineticMissile)
        `when`(
            engine.spawnProjectile(
                org.mockito.ArgumentMatchers.same(ship),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(GeminiDemDifficulty.HE_WEAPON_ID),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyFloat(), org.mockito.ArgumentMatchers.any(),
            ),
        ).thenReturn(heMissile)

        val demAttached = mutableListOf<MissileAPI>()
        val effect = GeminiDemSalvoOnFireEffect(
            demPluginFactory = { missile, _, _ ->
                demAttached += missile
                BaseEveryFrameCombatPlugin()
            },
        )
        effect.onFire(projectile, weapon, engine)

        verify(engine).removeEntity(projectile)

        // 双弹装配：source / armingTime / TrackAI / 批次号 / DEMScript 插件
        for ((missile, expectTarget) in listOf(kineticMissile to target, heMissile to target)) {
            verify(missile).source = ship
            verify(missile).setArmingTime(GeminiDemDifficulty.WARHEAD_ARMING_TIME)
            val aiCaptor = ArgumentCaptor.forClass(com.fs.starfarer.api.combat.MissileAIPlugin::class.java)
            verify(missile).setMissileAI(aiCaptor.capture())
            val ai = aiCaptor.value
            assertIs<GeminiDemTrackAI>(ai, "装配的导弹 AI 必须是 GeminiDemTrackAI")
            assertSame(expectTarget, ai.target, "TrackAI 出生即持有齐射目标（GuidedMissileAI 供 DEM WAIT 段）")
        }
        val salvoK = kineticMissile.customData[GeminiDemDifficulty.SALVO_KEY] as? String
        val salvoH = heMissile.customData[GeminiDemDifficulty.SALVO_KEY] as? String
        assertNotNull(salvoK)
        assertEquals(salvoK, salvoH, "双弹写入同一齐射批次号")
        assertTrue(salvoK.startsWith("astd_gemini_salvo:P1:"))

        assertEquals(2, demAttached.size, "两枚弹头均装配 DEMScript 插件")
        assertSame(kineticMissile, demAttached[0])
        assertSame(heMissile, demAttached[1])
        verify(engine, times(2)).addPlugin(org.mockito.ArgumentMatchers.any(EveryFrameCombatPlugin::class.java))

        assertEquals(1, GeminiDemSalvoOnFireEffect.salvoCount(engine))
        assertEquals(2, GeminiDemSalvoOnFireEffect.warheadsSpawned(engine))
    }

    @Test
    fun `用例13 生成失败路径：spawnProjectile 返回非 MissileAPI 记 ERROR，另一枚不受影响`() {
        val engine = stubEngine()
        val target = stubTarget("T1")
        val ship = stubShip("P1", 0, target)
        val weapon = mock(WeaponAPI::class.java)
        `when`(weapon.ship).thenReturn(ship)
        val projectile = stubProjectile()

        val notAMissile = mock(CombatEntityAPI::class.java)
        val heMissile = stubWarheadMissile()
        `when`(
            engine.spawnProjectile(
                org.mockito.ArgumentMatchers.same(ship),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(GeminiDemDifficulty.KINETIC_WEAPON_ID),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyFloat(), org.mockito.ArgumentMatchers.any(),
            ),
        ).thenReturn(notAMissile)
        `when`(
            engine.spawnProjectile(
                org.mockito.ArgumentMatchers.same(ship),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(GeminiDemDifficulty.HE_WEAPON_ID),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyFloat(), org.mockito.ArgumentMatchers.any(),
            ),
        ).thenReturn(heMissile)

        val capture = WarnCapture(GeminiDemSalvoOnFireEffect::class.java)
        try {
            GeminiDemSalvoOnFireEffect { _, _, _ -> BaseEveryFrameCombatPlugin() }.onFire(projectile, weapon, engine)
        } finally {
            capture.detach()
        }

        assertTrue(
            capture.messages().any { it.contains("非 MissileAPI") },
            "ERROR 日志含生成失败说明（实际：${capture.messages()}）",
        )
        // 另一枚照常装配
        verify(heMissile).setArmingTime(GeminiDemDifficulty.WARHEAD_ARMING_TIME)
        verify(heMissile).setMissileAI(org.mockito.ArgumentMatchers.any(GeminiDemTrackAI::class.java))
        assertEquals(1, GeminiDemSalvoOnFireEffect.warheadsSpawned(engine), "失败枚不计入生成遥测")
        assertEquals(1, GeminiDemSalvoOnFireEffect.salvoCount(engine))
    }
}
