package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveDifficulty
import cn.kasuminova.astd.impl.buff.WarnCapture
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.apache.log4j.Level
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PositronShockwaveFuseTest {
    private var warnCapture: WarnCapture? = null

    @AfterTest
    fun tearDown() {
        warnCapture?.detach()
        warnCapture = null
    }

    @Test
    fun `用例5 reachedMaxRange 恰达射程边界含等号`() {
        assertTrue(
            PositronShockwaveDifficulty.reachedMaxRange(elapsed = 0.6667f, speed = 900f, range = 600f),
            "0.6667×900=600.03 ≥ 600 应判定已达",
        )
        assertFalse(
            PositronShockwaveDifficulty.reachedMaxRange(elapsed = 0.6666f, speed = 900f, range = 600f),
            "0.6666×900=599.94 < 600 不应引爆",
        )
    }

    @Test
    fun `用例6 reachedMaxRange 弹速为 0 立即引爆并报 ERROR 恰好一条`() {
        val capture = WarnCapture(PositronShockwaveDifficulty::class.java)
        warnCapture = capture

        val result = PositronShockwaveDifficulty.reachedMaxRange(elapsed = 0.1f, speed = 0f, range = 600f)

        assertTrue(result, "moveSpeed=0 应立即按当前位置引爆（不允许静默消散）")
        val errors = capture.events.filter { it.level == Level.ERROR }
        assertEquals(1, errors.size, "moveSpeed=0 应输出恰好一条 ERROR 日志")
    }

    @Test
    fun `用例7 isFuseTarget 目标类型六宫格矩阵`() {
        assertTrue(PositronShockwaveDifficulty.isFuseTarget(stubMissile(owner = 1), owner = 0))
        assertTrue(PositronShockwaveDifficulty.isFuseTarget(stubShip(owner = 1, fighter = true), owner = 0))
        assertTrue(PositronShockwaveDifficulty.isFuseTarget(stubShip(owner = 1, drone = true), owner = 0))
        assertFalse(PositronShockwaveDifficulty.isFuseTarget(stubShip(owner = 1), owner = 0))
        assertFalse(PositronShockwaveDifficulty.isFuseTarget(stubMissile(owner = 0), owner = 0))
        assertFalse(PositronShockwaveDifficulty.isFuseTarget(stubShip(owner = 1, fighter = true, hulk = true), owner = 0))
    }

    private fun stubMissile(owner: Int): MissileAPI {
        val missile = mock(MissileAPI::class.java)
        `when`(missile.owner).thenReturn(owner)
        return missile
    }

    private fun stubShip(owner: Int, fighter: Boolean = false, drone: Boolean = false, hulk: Boolean = false): ShipAPI {
        val ship = mock(ShipAPI::class.java)
        `when`(ship.owner).thenReturn(owner)
        `when`(ship.isFighter).thenReturn(fighter)
        `when`(ship.isDrone).thenReturn(drone)
        `when`(ship.isHulk).thenReturn(hulk)
        return ship
    }
}
