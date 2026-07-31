package cn.kasuminova.astd.data

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class ArcShipBloomDecorationTest {

    @Test
    fun `new arc hulls have built in bloom decoration resources`() {
        val hulls = mapOf(
            "astd_radiation_belt" to "WS0009",
            "astd_plasma_arch" to "WS0011",
        )

        val weaponData = read("contents/data/weapons/weapon_data.csv")
        for ((hullId, slotId) in hulls) {
            val weaponId = "${hullId}_bloom"
            val ship = read("contents/data/hulls/$hullId.ship")

            assertTrue(ship.contains("\"builtInWeapons\""), "$hullId 缺少 builtInWeapons")
            assertTrue(ship.contains("\"$slotId\": \"$weaponId\""), "$hullId 未把 $weaponId 挂到 $slotId")
            assertTrue(ship.contains("\"renderOrderMod\": 3"), "$hullId bloom 槽缺少 renderOrderMod=3")

            val wpnPath = Path.of("contents", "data", "weapons", "$weaponId.wpn")
            assertTrue(Files.exists(wpnPath), "缺少 bloom weapon spec: $wpnPath")
            val wpn = Files.readString(wpnPath)
            assertTrue(wpn.contains("\"id\": \"$weaponId\""), "$weaponId.wpn id 不匹配")
            assertTrue(wpn.contains("\"turretSprite\": \"graphics/ships/$weaponId.png\""), "$weaponId.wpn turretSprite 不匹配")

            assertTrue(weaponData.contains(",$weaponId,"), "weapon_data.csv 缺少 $weaponId")
        }
    }

    private fun read(path: String): String = Files.readString(Path.of(path))
}
