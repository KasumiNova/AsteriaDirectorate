package cn.kasuminova.astd.campaign

import com.fs.starfarer.api.combat.ShipHullSpecAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.loading.WeaponGroupSpec
import com.fs.starfarer.api.loading.WeaponGroupType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

internal class ASTDVariantExporterTest {

    companion object {
        // 原版 HullVariantSpec(JSONObject) 加载器强制读取的字段（缺失即加载失败）。
        private val LOADER_REQUIRED_KEYS = setOf(
            "displayName", "hullId", "variantId", "fluxVents", "fluxCapacitors", "weaponGroups",
        )

        // 加载器可识别、导出器兼容比对的顶层字段全集；stock variant 不得出现超出此集合的字段。
        // 其中 suppressedMods 原版 toJSONObject 同样不写出，仅属加载器可识别字段。
        private val SUPPORTED_KEYS = setOf(
            "displayName", "hullId", "variantId", "fluxVents", "fluxCapacitors", "quality",
            "goalVariant", "weaponGroups", "modules", "wings",
            "hullMods", "permaMods", "sMods", "sModdedBuiltIns", "suppressedMods", "tags",
        )

        // 旧版本遗留字段，现行加载器静默忽略，导出器不写出。
        private val LEGACY_IGNORED_KEYS = setOf("mods")
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun mockHullSpec(
        hullId: String = "astd_zw_001",
        builtInMods: Set<String> = emptySet(),
        builtInWingCount: Int = 0,
    ): ShipHullSpecAPI {
        val spec = mock(ShipHullSpecAPI::class.java)
        `when`(spec.hullId).thenReturn(hullId)
        `when`(spec.isBuiltInMod(org.mockito.ArgumentMatchers.anyString())).thenAnswer { invocation ->
            builtInMods.contains(invocation.getArgument<String>(0))
        }
        `when`(spec.isBuiltInWing(org.mockito.ArgumentMatchers.anyInt())).thenAnswer { invocation ->
            invocation.getArgument<Int>(0) < builtInWingCount
        }
        return spec
    }

    private fun mockVariant(
        variantId: String = "astd_zw_001_Automated",
        displayName: String = "Automated",
        hullSpec: ShipHullSpecAPI = mockHullSpec(),
        vents: Int = 20,
        caps: Int = 20,
        goalVariant: Boolean = true,
        hullMods: List<String> = emptyList(),
        permaMods: Set<String> = emptySet(),
        sMods: Set<String> = emptySet(),
        sModdedBuiltIns: Set<String> = emptySet(),
        tags: Set<String> = emptySet(),
        weaponGroups: List<WeaponGroupSpec> = emptyList(),
        weaponsBySlot: Map<String, String> = emptyMap(),
        wings: List<String> = emptyList(),
        stationModules: Map<String, String> = emptyMap(),
        hasUnassignedWeapons: Boolean = false,
    ): ShipVariantAPI {
        val variant = mock(ShipVariantAPI::class.java)
        `when`(variant.hullVariantId).thenReturn(variantId)
        `when`(variant.displayName).thenReturn(displayName)
        `when`(variant.hullSpec).thenReturn(hullSpec)
        `when`(variant.numFluxVents).thenReturn(vents)
        `when`(variant.numFluxCapacitors).thenReturn(caps)
        `when`(variant.isGoalVariant).thenReturn(goalVariant)
        `when`(variant.hullMods).thenReturn(hullMods)
        `when`(variant.permaMods).thenReturn(permaMods)
        `when`(variant.sMods).thenReturn(linkedSetOf<String>().apply { addAll(sMods) })
        `when`(variant.sModdedBuiltIns).thenReturn(linkedSetOf<String>().apply { addAll(sModdedBuiltIns) })
        `when`(variant.tags).thenReturn(tags)
        `when`(variant.weaponGroups).thenReturn(weaponGroups)
        `when`(variant.stationModules).thenReturn(stationModules)
        `when`(variant.wings).thenReturn(wings)
        `when`(variant.fittedWings).thenReturn(wings.filter { it.isNotEmpty() })
        `when`(variant.hasUnassignedWeapons()).thenReturn(hasUnassignedWeapons)
        `when`(variant.getWeaponId(org.mockito.ArgumentMatchers.anyString())).thenAnswer { invocation ->
            weaponsBySlot[invocation.getArgument<String>(0)]
        }
        return variant
    }

    private fun group(
        type: WeaponGroupType,
        autofire: Boolean,
        vararg slots: String,
    ): WeaponGroupSpec {
        return WeaponGroupSpec(type).apply {
            isAutofireOnByDefault = autofire
            slots.forEach { addSlot(it) }
        }
    }

    @Test
    fun `exported json carries every loader-required field with group sub-structure`() {
        val variant = mockVariant(
            weaponGroups = listOf(group(WeaponGroupType.LINKED, false, "WS0001", "WS0003", "WS0009")),
            weaponsBySlot = mapOf("WS0001" to "hammer", "WS0003" to "pdlaser", "WS0009" to "pdlaser"),
            permaMods = setOf("astd_zw_001_mode_automated", "astd_zw_001_mode_next_automated", "automated"),
        )

        val json = ASTDVariantExporter.toJson(variant)

        assertEquals("Automated", json.getString("displayName"))
        assertEquals("astd_zw_001", json.getString("hullId"))
        assertEquals("astd_zw_001_Automated", json.getString("variantId"))
        assertEquals(20, json.getInt("fluxVents"))
        assertEquals(20, json.getInt("fluxCapacitors"))
        assertEquals(1.0, json.getDouble("quality"))
        assertTrue(json.getBoolean("goalVariant"))

        val groups = json.getJSONArray("weaponGroups")
        assertEquals(1, groups.length())
        val groupJson = groups.getJSONObject(0)
        assertEquals("LINKED", groupJson.getString("mode"))
        assertFalse(groupJson.getBoolean("autofire"))
        val weapons = groupJson.getJSONObject("weapons")
        assertEquals("hammer", weapons.getString("WS0001"))
        assertEquals("pdlaser", weapons.getString("WS0003"))
        assertEquals("pdlaser", weapons.getString("WS0009"))

        assertEquals(0, json.getJSONArray("hullMods").length())
        assertEquals(
            listOf("astd_zw_001_mode_automated", "astd_zw_001_mode_next_automated", "automated"),
            json.getJSONArray("permaMods").toStringList(),
        )
        assertEquals(0, json.getJSONArray("sMods").length())
    }

    @Test
    fun `every stock variant key set stays inside exporter-supported fields with loader-required keys present`() {
        val stockDir = File("contents/data/variants")
        val stockFiles = stockDir.listFiles { f -> f.isFile && f.extension == "variant" }!!.toList() +
                stockDir.listFiles { f -> f.isDirectory }!!
                    .flatMap { dir -> dir.listFiles { f -> f.isFile && f.extension == "variant" }!!.toList() }
        assertTrue(stockFiles.isNotEmpty(), "no stock variants found under contents/data/variants")

        for (file in stockFiles) {
            val json = JSONObject(file.readText())
            val keys = json.keysSet()
            assertTrue(
                keys.containsAll(LOADER_REQUIRED_KEYS),
                "${file.name} missing loader-required keys: ${LOADER_REQUIRED_KEYS - keys}",
            )
            val unsupported = keys - SUPPORTED_KEYS - LEGACY_IGNORED_KEYS
            assertTrue(unsupported.isEmpty(), "${file.name} has unsupported keys: $unsupported")
            for (i in 0 until json.getJSONArray("weaponGroups").length()) {
                assertTrue(
                    json.getJSONArray("weaponGroups").getJSONObject(i).has("weapons"),
                    "${file.name} weapon group $i missing weapons object",
                )
            }
        }
    }

    @Test
    fun `exported field structure covers the equivalent stock variant`() {
        val stock = JSONObject(File("contents/data/variants/astd_zw_001_Automated.variant").readText())
        val variant = mockVariant(
            weaponGroups = listOf(group(WeaponGroupType.LINKED, false, "WS0001", "WS0003", "WS0009")),
            weaponsBySlot = mapOf("WS0001" to "hammer", "WS0003" to "pdlaser", "WS0009" to "pdlaser"),
            permaMods = setOf("astd_zw_001_mode_automated", "astd_zw_001_mode_next_automated", "automated"),
        )

        val exported = ASTDVariantExporter.toJson(variant)

        val missing = stock.keysSet() - exported.keysSet()
        assertTrue(missing.isEmpty(), "exported json misses stock keys: $missing")
        for (key in stock.keysSet()) {
            if (key == "variantId" || key == "hullId" || key == "displayName" || key == "quality") continue
            assertEquals(
                stock.get(key).toString(), exported.get(key).toString(),
                "field $key diverges from stock variant structure",
            )
        }
    }

    @Test
    fun `minimal variant still satisfies loader-required fields and omits optional blocks`() {
        val variant = mockVariant(goalVariant = false, vents = 0, caps = 0)

        val json = ASTDVariantExporter.toJson(variant)

        assertTrue(json.keysSet().containsAll(LOADER_REQUIRED_KEYS))
        assertFalse(json.has("goalVariant"))
        assertFalse(json.has("wings"))
        assertFalse(json.has("modules"))
        assertFalse(json.has("tags"))
        assertFalse(json.has("sModdedBuiltIns"))
        assertEquals(0, json.getJSONArray("weaponGroups").length())
    }

    @Test
    fun `wings export skips built-in slots but keeps empty positional placeholders`() {
        val hullSpec = mockHullSpec(builtInWingCount = 1)
        val variant = mockVariant(
            hullSpec = hullSpec,
            wings = listOf("astd_builtin_wing", "", "talon_wing"),
        )

        val wings = ASTDVariantExporter.toJson(variant).getJSONArray("wings")

        assertEquals(listOf("", "talon_wing"), wings.toStringList())
    }

    @Test
    fun `built-in hullmods are excluded while perma and s mods stay in their own arrays`() {
        val hullSpec = mockHullSpec(builtInMods = setOf("astd_builtin_mod"))
        val variant = mockVariant(
            hullSpec = hullSpec,
            hullMods = listOf("astd_builtin_mod", "astd_zw_001_mode_automated", "hardened_shields"),
            permaMods = setOf("astd_zw_001_mode_automated"),
            sMods = setOf("astd_zw_001_mode_automated"),
            sModdedBuiltIns = setOf("astd_builtin_mod"),
        )

        val json = ASTDVariantExporter.toJson(variant)

        assertEquals(
            listOf("astd_zw_001_mode_automated", "hardened_shields"),
            json.getJSONArray("hullMods").toStringList(),
        )
        assertEquals(listOf("astd_zw_001_mode_automated"), json.getJSONArray("permaMods").toStringList())
        assertEquals(listOf("astd_zw_001_mode_automated"), json.getJSONArray("sMods").toStringList())
        assertEquals(listOf("astd_builtin_mod"), json.getJSONArray("sModdedBuiltIns").toStringList())
    }

    @Test
    fun `station modules export as single-key object array accepted by the loader`() {
        val variant = mockVariant(stationModules = linkedMapOf("MS0001" to "astd_module_Standard"))

        val modules = ASTDVariantExporter.toJson(variant).getJSONArray("modules")

        assertEquals(1, modules.length())
        assertEquals("astd_module_Standard", modules.getJSONObject(0).getString("MS0001"))
    }

    @Test
    fun `export writes timestamped variant file and never overwrites a previous export`() {
        val variant = mockVariant()
        val outputDir = tempFolder.newFolder("export")

        val first = ASTDVariantExporter.exportVariant(variant, outputDir = outputDir)
        val second = ASTDVariantExporter.exportVariant(variant, outputDir = outputDir)

        assertNotEquals(first.name, second.name)
        assertTrue(first.name.startsWith("astd_zw_001_Automated_"))
        assertTrue(first.name.endsWith(".variant"))
        val parsed = JSONObject(first.readText())
        assertTrue(parsed.keysSet().containsAll(LOADER_REQUIRED_KEYS))
        assertEquals("astd_zw_001_Automated", parsed.getString("variantId"))
    }

    @Test
    fun `export clones and assigns weapon groups when variant has unassigned weapons`() {
        val original = mockVariant(hasUnassignedWeapons = true)
        val prepared = mockVariant(
            weaponGroups = listOf(group(WeaponGroupType.LINKED, false, "WS0001")),
            weaponsBySlot = mapOf("WS0001" to "hammer"),
        )
        `when`(original.clone()).thenReturn(prepared)
        val outputDir = tempFolder.newFolder("export")

        val file = ASTDVariantExporter.exportVariant(original, outputDir = outputDir)

        verify(prepared).assignUnassignedWeapons()
        verify(original, never()).assignUnassignedWeapons()
        val groups = JSONObject(file.readText()).getJSONArray("weaponGroups")
        assertEquals(1, groups.length())
        assertEquals("hammer", groups.getJSONObject(0).getJSONObject("weapons").getString("WS0001"))
    }

    @Test
    fun `variant id unsafe for file names is sanitized in the output file name`() {
        val variant = mockVariant(variantId = "weird id/with:chars")
        val outputDir = tempFolder.newFolder("export")

        val file = ASTDVariantExporter.exportVariant(variant, outputDir = outputDir)

        assertTrue(file.name.startsWith("weird_id_with_chars_"), "unexpected file name: ${file.name}")
        assertTrue(file.parentFile == outputDir)
    }

    @Test
    fun `variant without hull spec fails loudly instead of writing a broken file`() {
        val variant = mockVariant()
        `when`(variant.hullSpec).thenReturn(null)

        assertFailsWith<IllegalArgumentException> {
            ASTDVariantExporter.toJson(variant)
        }
    }

    private fun JSONArray.toStringList(): List<String> {
        return (0 until length()).map { getString(it) }
    }

    private fun JSONObject.keysSet(): Set<String> {
        val keys = mutableSetOf<String>()
        val iterator = keys()
        while (iterator.hasNext()) {
            keys.add(iterator.next() as String)
        }
        return keys
    }
}
