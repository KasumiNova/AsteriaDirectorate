package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveFireControl
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 正电子冲击波自动开火协同认领（2026-10 裁定）的纯逻辑验证：
 * - [PositronShockwaveFireControl.neededWeapons]：导弹按 ceil(耐久 ÷ 面板 150) 分配管数，战机恒 1；
 * - [PositronShockwaveFireControl.freshClaimCount]：同舰 + 未过期 + 非自身三条件同时满足才计入；
 * - [PositronShockwaveFireControl.purgeStale]：超时条目回收。
 */
class PositronShockwaveFireControlTest {

    @Test
    fun `导弹认领上限按耐久折算，战机恒 1`() {
        assertEquals(1, PositronShockwaveFireControl.neededWeapons(isMissile = true, hitpoints = 0f))
        assertEquals(1, PositronShockwaveFireControl.neededWeapons(isMissile = true, hitpoints = 50f))
        assertEquals(1, PositronShockwaveFireControl.neededWeapons(isMissile = true, hitpoints = 150f))
        assertEquals(2, PositronShockwaveFireControl.neededWeapons(isMissile = true, hitpoints = 151f))
        assertEquals(4, PositronShockwaveFireControl.neededWeapons(isMissile = true, hitpoints = 500f), "500 耐久 → 4 管")
        assertEquals(5, PositronShockwaveFireControl.neededWeapons(isMissile = true, hitpoints = 750f), "750 耐久（军官加成）→ 5 管")
        assertEquals(1, PositronShockwaveFireControl.neededWeapons(isMissile = false, hitpoints = 9999f), "战机不参与集火分配")
    }

    @Test
    fun `认领计数只计同舰未过期的他武器认领`() {
        val shipA = mock(ShipAPI::class.java)
        val shipB = mock(ShipAPI::class.java)
        val target = mock(CombatEntityAPI::class.java)
        val self = weaponOn(shipA)
        val sameShipOther = weaponOn(shipA)
        val otherShip = weaponOn(shipB)

        val claims = mutableMapOf<WeaponAPI, PositronShockwaveFireControl.Claim>(
            self to PositronShockwaveFireControl.Claim(target, time = 10f),
            sameShipOther to PositronShockwaveFireControl.Claim(target, time = 10f),
            otherShip to PositronShockwaveFireControl.Claim(target, time = 10f),
        )

        assertEquals(
            1,
            PositronShockwaveFireControl.freshClaimCount(claims, target, shipA, self, now = 10.1f),
            "仅同舰他武器的新鲜认领计入（自身与异舰排除）",
        )
        assertEquals(
            0,
            PositronShockwaveFireControl.freshClaimCount(claims, target, shipA, self, now = 11f),
            "超过 0.5s 窗口的认领失效",
        )
    }

    @Test
    fun `过期认领被回收，新鲜认领保留`() {
        val ship = mock(ShipAPI::class.java)
        val target = mock(CombatEntityAPI::class.java)
        val fresh = weaponOn(ship)
        val stale = weaponOn(ship)
        val claims = mutableMapOf<WeaponAPI, PositronShockwaveFireControl.Claim>(
            fresh to PositronShockwaveFireControl.Claim(target, time = 10f),
            stale to PositronShockwaveFireControl.Claim(target, time = 8f),
        )

        PositronShockwaveFireControl.purgeStale(claims, now = 10.2f)

        assertTrue(claims.containsKey(fresh), "0.2s 未超窗口，应保留")
        assertFalse(claims.containsKey(stale), "2.2s 已超窗口，应回收")
    }

    private fun weaponOn(ship: ShipAPI): WeaponAPI {
        val weapon = mock(WeaponAPI::class.java)
        `when`(weapon.ship).thenReturn(ship)
        return weapon
    }
}
