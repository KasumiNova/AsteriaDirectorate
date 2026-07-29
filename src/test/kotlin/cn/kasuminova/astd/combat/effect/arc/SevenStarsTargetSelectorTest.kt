package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.combat.effect.arc.SevenStarsTargetSelector.Candidate
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 规格 07 §4.1 用例 7~10：目标排序（可摧毁优先/最近次优）、过滤矩阵、
 * 终结最近敌舰、护盾不预估的已知简化——手工构造候选值对象列表驱动
 * [SevenStarsTargetSelector] 纯函数重载，不 mock 引擎（实体桩仅作标签与属性载体）。
 */
class SevenStarsTargetSelectorTest {

    private val origin = Vector2f(0f, 0f)

    private fun stubMissile(owner: Int, x: Float, y: Float, hitpoints: Float, expired: Boolean = false): MissileAPI {
        val m = mock(MissileAPI::class.java)
        `when`(m.owner).thenReturn(owner)
        `when`(m.location).thenReturn(Vector2f(x, y))
        `when`(m.hitpoints).thenReturn(hitpoints)
        `when`(m.isExpired).thenReturn(expired)
        return m
    }

    private fun stubShip(
        owner: Int,
        x: Float,
        y: Float,
        hitpoints: Float = 1000f,
        fighter: Boolean = false,
        hulk: Boolean = false,
        alive: Boolean = true,
    ): ShipAPI {
        val s = mock(ShipAPI::class.java)
        `when`(s.owner).thenReturn(owner)
        `when`(s.location).thenReturn(Vector2f(x, y))
        `when`(s.hitpoints).thenReturn(hitpoints)
        `when`(s.isFighter).thenReturn(fighter)
        `when`(s.isHulk).thenReturn(hulk)
        `when`(s.isAlive).thenReturn(alive)
        return s
    }

    @Test
    fun `用例7 目标排序：可摧毁优先，同可摧毁时距离近者在前`() {
        // A 可摧毁但更远（300su），B 不可摧毁但更近（100su），aoeDamage=200 → A 在前。
        val a = Candidate(stubMissile(owner = 1, x = 300f, y = 0f, hitpoints = 100f), 100f, 300f * 300f)
        val b = Candidate(stubMissile(owner = 1, x = 100f, y = 0f, hitpoints = 500f), 500f, 100f * 100f)
        val sorted = SevenStarsTargetSelector.sortPdCandidates(listOf(b, a), aoeDamage = 200f)
        assertSame(a, sorted[0], "可摧毁优先于距离")
        assertSame(b, sorted[1])

        // 两者皆可摧毁 → 距离近者在前（对照场景）。
        val c = Candidate(stubMissile(owner = 1, x = 300f, y = 0f, hitpoints = 150f), 150f, 300f * 300f)
        val d = Candidate(stubMissile(owner = 1, x = 100f, y = 0f, hitpoints = 100f), 100f, 100f * 100f)
        val sorted2 = SevenStarsTargetSelector.sortPdCandidates(listOf(c, d), aoeDamage = 200f)
        assertSame(d, sorted2[0], "同可摧毁时距离近者在前")
        assertSame(c, sorted2[1])
    }

    @Test
    fun `用例8 过滤矩阵：归属、状态、类型、距离与 dist=0 全宫格`() {
        val jumpRange = 400f
        val friendlyMissile = stubMissile(owner = 0, x = 100f, y = 0f, hitpoints = 50f)
        val hulkFighter = stubShip(owner = 1, x = 100f, y = 0f, fighter = true, hulk = true)
        val expiredMissile = stubMissile(owner = 1, x = 100f, y = 0f, hitpoints = 50f, expired = true)
        val atRangeEdge = stubMissile(owner = 1, x = 400f, y = 0f, hitpoints = 50f)
        val beyondRange = stubMissile(owner = 1, x = 401f, y = 0f, hitpoints = 50f)
        val capitalShip = stubShip(owner = 1, x = 100f, y = 0f, fighter = false)
        val overlapMissile = stubMissile(owner = 1, x = 0f, y = 0f, hitpoints = 50f)
        val normalFighter = stubShip(owner = 1, x = 200f, y = 0f, hitpoints = 80f, fighter = true)

        val candidates = SevenStarsTargetSelector.collectPdCandidates(
            entities = listOf<CombatEntityAPI>(
                friendlyMissile, hulkFighter, expiredMissile, atRangeEdge,
                beyondRange, capitalShip, overlapMissile, normalFighter,
            ),
            from = origin,
            jumpRange = jumpRange,
            owner = 0,
            inPlay = { true },
        )
        val entities = candidates.map { it.entity }
        assertTrue(friendlyMissile !in entities, "自方导弹剔除")
        assertTrue(hulkFighter !in entities, "敌方 hulk 战机剔除")
        assertTrue(expiredMissile !in entities, "过期导弹剔除")
        assertTrue(atRangeEdge in entities, "距离恰等 jumpRange 纳入")
        assertTrue(beyondRange !in entities, "距离 +1su 剔除")
        assertTrue(capitalShip !in entities, "非战机舰船不出现在 PD 候选")
        assertTrue(overlapMissile in entities, "dist=0 重叠目标纳入")
        assertTrue(normalFighter in entities, "正常敌战机纳入")

        // dist=0 重叠目标经排序位于首位（无除零）。
        val sorted = SevenStarsTargetSelector.sortPdCandidates(candidates, aoeDamage = 10f)
        assertSame(overlapMissile, sorted[0].entity, "dist=0 且同不可摧毁时距离最近排首位")
    }

    @Test
    fun `用例9 终结最近敌舰：纯舰船取距离最小者，战机不参与，空集 null`() {
        val near = stubShip(owner = 1, x = 500f, y = 0f)
        val far = stubShip(owner = 1, x = 900f, y = 0f)
        val fighter = stubShip(owner = 1, x = 50f, y = 0f, fighter = true)
        val friendly = stubShip(owner = 0, x = 60f, y = 0f)
        val hulk = stubShip(owner = 1, x = 70f, y = 0f, hulk = true)

        val picked = SevenStarsTargetSelector.selectNearestShip(
            entities = listOf<CombatEntityAPI>(far, near, fighter, friendly, hulk),
            from = origin,
            owner = 0,
        )
        assertSame(near, picked, "最近敌舰（战机/友舰/hulk 均不参与）")

        assertNull(
            SevenStarsTargetSelector.selectNearestShip(
                entities = listOf<CombatEntityAPI>(fighter, friendly),
                from = origin,
                owner = 0,
            ),
            "无敌舰候选 → null（调用方走 DISSIPATE 路径）",
        )
    }

    @Test
    fun `用例10 护盾不预估的已知简化：低结构带盾战机仍判可摧毁`() {
        // 已知简化（规格 §2.2）：可摧毁预估只看剩余结构值，护盾吸收不预估——
        // 断言当前行为，防后续误改语义。
        val shieldedFighter = Candidate(
            stubShip(owner = 1, x = 300f, y = 0f, hitpoints = 50f, fighter = true),
            50f,
            300f * 300f,
        )
        val tougherButNearer = Candidate(
            stubMissile(owner = 1, x = 100f, y = 0f, hitpoints = 500f),
            500f,
            100f * 100f,
        )
        val sorted = SevenStarsTargetSelector.sortPdCandidates(
            listOf(tougherButNearer, shieldedFighter),
            aoeDamage = 200f,
        )
        assertSame(shieldedFighter, sorted[0], "带盾战机 hitpoints=50 <= 200 仍判可摧毁（护盾不预估）")
        assertEquals(1, sorted.indexOf(shieldedFighter) + 1, "排序位置断言稳定")
    }
}
