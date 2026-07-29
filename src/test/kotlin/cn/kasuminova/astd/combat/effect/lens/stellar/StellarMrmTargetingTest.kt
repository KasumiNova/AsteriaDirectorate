package cn.kasuminova.astd.combat.effect.lens.stellar

import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * 规格 08 §4.1 用例 7~10：目标选择矩阵——战机优先 / 无战机兜底（含无人机）/
 * 排除友军-hulk-不在场-导弹 / 射程门——全部真实调用 [StellarMrmTargetingImpl.select]。
 */
class StellarMrmTargetingTest {

    private val origin = Vector2f(0f, 0f)
    private val range = 2500f

    private fun stubShip(
        owner: Int,
        x: Float,
        y: Float,
        fighter: Boolean = false,
        drone: Boolean = false,
        hulk: Boolean = false,
        alive: Boolean = true,
    ): ShipAPI {
        val s = mock(ShipAPI::class.java)
        `when`(s.owner).thenReturn(owner)
        `when`(s.location).thenReturn(Vector2f(x, y))
        `when`(s.isFighter).thenReturn(fighter)
        `when`(s.isDrone).thenReturn(drone)
        `when`(s.isHulk).thenReturn(hulk)
        `when`(s.isAlive).thenReturn(alive)
        return s
    }

    private fun select(candidates: List<CombatEntityAPI>, inPlay: (CombatEntityAPI) -> Boolean = { true }): ShipAPI? =
        StellarMrmTargetingImpl.select(candidates, origin, owner = 0, acquireRange = range, inPlay = inPlay)

    @Test
    fun `用例7 战机优先：近处舰船与远处战机同场时选中战机`() {
        val nearShip = stubShip(owner = 1, x = 100f, y = 0f)
        val farFighter = stubShip(owner = 1, x = 2000f, y = 0f, fighter = true)
        assertSame(farFighter, select(listOf(nearShip, farFighter)), "战机优先于更近的舰船")
    }

    @Test
    fun `用例8 无战机兜底：仅舰船取最近者，无人机纳入兜底`() {
        val near = stubShip(owner = 1, x = 300f, y = 0f)
        val far = stubShip(owner = 1, x = 900f, y = 0f)
        assertSame(near, select(listOf(far, near)), "无战机时最近舰船兜底")

        val drone = stubShip(owner = 1, x = 150f, y = 0f, drone = true)
        assertSame(drone, select(listOf(near, drone)), "无人机（isDrone）与普通舰船同档纳入兜底")
    }

    @Test
    fun `用例9 排除矩阵：友军-hulk-不在场-导弹全排除，全空返回null`() {
        val friendly = stubShip(owner = 0, x = 50f, y = 0f, fighter = true)
        val hulk = stubShip(owner = 1, x = 60f, y = 0f, fighter = true, hulk = true)
        val dead = stubShip(owner = 1, x = 70f, y = 0f, alive = false)
        val offField = stubShip(owner = 1, x = 80f, y = 0f, fighter = true)
        val enemyMissile = mock(MissileAPI::class.java).also {
            `when`(it.owner).thenReturn(1)
            `when`(it.location).thenReturn(Vector2f(40f, 0f))
        }
        val valid = stubShip(owner = 1, x = 500f, y = 0f)

        val picked = select(
            listOf(friendly, hulk, dead, offField, enemyMissile, valid),
            inPlay = { it !== offField },
        )
        assertSame(valid, picked, "友军/hulk/已死/不在场/导弹全排除后只剩普通敌舰")

        assertNull(
            select(listOf(friendly, hulk, enemyMissile), inPlay = { true }),
            "输入全为排除项（含 MissileAPI 永不入选）→ null",
        )
        assertNull(select(emptyList()), "全空 → null")
    }

    @Test
    fun `用例10 射程门：战机在2500外不入选，落空或兜底近舰`() {
        val farFighter = stubShip(owner = 1, x = 2501f, y = 0f, fighter = true)
        val nearShip = stubShip(owner = 1, x = 100f, y = 0f)
        assertSame(nearShip, select(listOf(farFighter, nearShip)), "战机越射程 → 兜底近舰")
        assertNull(select(listOf(farFighter)), "唯一候选越射程 → null")

        val edgeFighter = stubShip(owner = 1, x = 2500f, y = 0f, fighter = true)
        assertSame(edgeFighter, select(listOf(edgeFighter)), "恰在 2500 边界内（含边界）入选")
    }
}
