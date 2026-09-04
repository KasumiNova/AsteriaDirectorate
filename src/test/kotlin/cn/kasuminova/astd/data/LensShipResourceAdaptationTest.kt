package cn.kasuminova.astd.data

import cn.kasuminova.astd.testutil.CsvTestUtil
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LensShipResourceAdaptationTest {

    @Test
    fun `gravitational lens has complete bloom overlay resource chain`() {
        val hullId = "astd_gravitational_lens"
        val weaponId = "astd_gravitational_lens_bloom"
        val slotId = "WS0002"

        assertTrue(
            Files.exists(Path.of("contents/graphics/ships/$weaponId.png")),
            "$hullId bloom texture must exist beside the ship sprite.",
        )

        val hull = JSONObject(Files.readString(Path.of("contents/data/hulls/$hullId.ship")))
        assertEquals(hullId, hull.getString("hullId"), "ship spec must use the real gravitational lens id.")
        val builtInWeapons = hull.optJSONObject("builtInWeapons") ?: fail("$hullId must declare builtInWeapons.")
        assertEquals(
            weaponId,
            builtInWeapons.getString(slotId),
            "$hullId must mount the bloom overlay weapon on $slotId.",
        )

        val bloomSlot = hull.getJSONArray("weaponSlots")
            .asObjects()
            .firstOrNull { slot -> slot.getString("id") == slotId }
        assertNotNull(bloomSlot, "$hullId must keep decorative slot $slotId.")
        assertEquals("DECORATIVE", bloomSlot.getString("type"), "$slotId must be a decorative slot.")
        assertEquals("LARGE", bloomSlot.getString("size"), "$slotId must match the LARGE bloom weapon size.")
        assertEquals(3, bloomSlot.getInt("renderOrderMod"), "$slotId must render the bloom overlay at order mod 3.")

        val weaponSpecPath = Path.of("contents/data/weapons/$weaponId.wpn")
        assertTrue(Files.exists(weaponSpecPath), "$weaponId weapon spec must exist.")
        val weaponSpec = JSONObject(Files.readString(weaponSpecPath))
        assertEquals(weaponId, weaponSpec.getString("id"), "$weaponId.wpn id must match the weapon id.")
        assertEquals("beam", weaponSpec.getString("specClass"), "$weaponId.wpn must use the decorative beam spec.")
        assertEquals("DECORATIVE", weaponSpec.getString("type"), "$weaponId.wpn must be decorative.")
        assertEquals("LARGE", weaponSpec.getString("size"), "$weaponId.wpn must fit the $slotId slot.")
        assertEquals(
            "cn.kasuminova.astd.renderer.effect.system.ArcFlareDecorativeLightsEffect",
            weaponSpec.getString("everyFrameEffect"),
            "$weaponId.wpn must use the established decorative lights effect.",
        )
        assertTrue(
            weaponSpec.getJSONArray("renderHints").asStrings().contains("RENDER_ADDITIVE"),
            "$weaponId.wpn must render additively.",
        )
        assertEquals(
            "graphics/ships/$weaponId.png",
            weaponSpec.getString("turretSprite"),
            "$weaponId.wpn turret sprite must point at the bloom ship texture.",
        )
        assertEquals(
            "graphics/ships/$weaponId.png",
            weaponSpec.getString("hardpointSprite"),
            "$weaponId.wpn hardpoint sprite must point at the bloom ship texture.",
        )

        val weaponRow = CsvTestUtil.readRowsById(Path.of("contents/data/weapons/weapon_data.csv"))[weaponId]
        assertNotNull(weaponRow, "weapon_data.csv must contain $weaponId.")
        assertEquals("引力透镜辉光", weaponRow.getValue("name"))
        assertEquals("5", weaponRow.getValue("tier"))
        assertEquals("0", weaponRow.getValue("base value"))
        assertEquals("0", weaponRow.getValue("range"))
        assertEquals("0", weaponRow.getValue("turn rate"))
        assertEquals("OTHER", weaponRow.getValue("type"))
        assertEquals("no_drop, no_drop_salvage", weaponRow.getValue("tags"))
        assertEquals("菀星设计局-紫菀", weaponRow.getValue("tech/manufacturer"))
        assertEquals("TRUE", weaponRow.getValue("noDPSInTooltip"))
    }

    @Test
    fun `gravitational lens standard variant uses current weapon slot ids`() {
        val hullId = "astd_gravitational_lens"
        val hull = JSONObject(Files.readString(Path.of("contents/data/hulls/$hullId.ship")))
        val activeSlots = hull.getJSONArray("weaponSlots")
            .asObjects()
            .filterNot { slot -> slot.getString("type") == "DECORATIVE" }
            .map { slot -> slot.getString("id") }
            .toSet()

        val variant = JSONObject(Files.readString(Path.of("contents/data/variants/${hullId}_Standard.variant")))
        assertEquals(hullId, variant.getString("hullId"))

        val weapons = variant.getJSONArray("weaponGroups")
            .asObjects()
            .flatMap { group ->
                val groupWeapons = group.getJSONObject("weapons")
                groupWeapons.keys().asSequence().map { slotId ->
                    slotId.toString() to groupWeapons.getString(slotId.toString())
                }.toList()
            }
            .toMap()

        assertEquals(
            // D9 废弃武器拆除后，空出的槽位以原版武器过渡（P 后续阶段重新设计）
            mapOf(
                "WS0001" to "hammer",
                "WS0003" to "pdlaser",
                "WS0009" to "pdlaser",
            ),
            weapons,
        )
        assertTrue(weapons.keys.all { slotId -> slotId in activeSlots }, "standard variant must only mount weapons on active hull slots.")
    }

    private fun org.json.JSONArray.asObjects(): List<JSONObject> =
        (0 until length()).map { idx -> getJSONObject(idx) }

    private fun org.json.JSONArray.asStrings(): List<String> =
        (0 until length()).map { idx -> getString(idx) }
}
