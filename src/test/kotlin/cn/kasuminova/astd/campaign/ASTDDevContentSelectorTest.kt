package cn.kasuminova.astd.campaign

import cn.kasuminova.astd.testutil.CsvTestUtil
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Path

internal class ASTDDevContentSelectorTest {

    @Test
    fun `dev ship storage includes hidden codex hulls but excludes modules and unavailable drones`() {
        val rows = CsvTestUtil.readRowsById(Path.of("contents/data/hulls/ship_data.csv"))

        assertTrue(ASTDDevContentSelector.isDevStorageShip(rows.getValue("astd_arc_flare").toShipRow()))
        assertTrue(ASTDDevContentSelector.isDevStorageShip(rows.getValue("astd_arc_jet").toShipRow()))
        assertTrue(ASTDDevContentSelector.isDevStorageShip(rows.getValue("astd_plasma_arch").toShipRow()))
        assertTrue(ASTDDevContentSelector.isDevStorageShip(rows.getValue("astd_radiation_belt").toShipRow()))

        val hideInCodexShip = ASTDDevContentSelector.ShipRow(
            id = "sample_hidden_real_ship",
            codexVariantId = "sample_hidden_real_ship_Standard",
            hints = setOf("HIDE_IN_CODEX"),
            logisticsNaReason = "",
        )
        assertTrue(
            ASTDDevContentSelector.isDevStorageShip(hideInCodexShip),
            "hide_in_codex is not enough to exclude a normally obtainable hull from dev storage.",
        )

        assertFalse(ASTDDevContentSelector.isDevStorageShip(rows.getValue("astd_conjugate_terminal").toShipRow()))
        assertFalse(
            ASTDDevContentSelector.isDevStorageShip(
                ASTDDevContentSelector.ShipRow(
                    id = "sample_module",
                    codexVariantId = "sample_module_Standard",
                    hints = setOf("MODULE", "UNDER_PARENT"),
                    logisticsNaReason = "",
                )
            )
        )

        val vanillaRows = CsvTestUtil.readRowsById(Path.of("/mnt/store/Games/Starsector098-linux/data/hulls/ship_data.csv"))
        assertTrue(ASTDDevContentSelector.isDevStorageShip(vanillaRows.getValue("wolf").toShipRow()))
        assertTrue(ASTDDevContentSelector.isDevStorageShip(vanillaRows.getValue("hammerhead").toShipRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageShip(vanillaRows.getValue("station1").toShipRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageShip(vanillaRows.getValue("module_bastion_pd1").toShipRow()))
    }

    @Test
    fun `dev weapon storage allows hidden weapons but excludes decorative and system-linked weapons`() {
        val rows = CsvTestUtil.readRowsById(Path.of("contents/data/weapons/weapon_data.csv"))

        assertTrue(ASTDDevContentSelector.isDevStorageWeapon(rows.getValue("astd_aod7").toWeaponRow()))
        assertTrue(ASTDDevContentSelector.isDevStorageWeapon(rows.getValue("astd_arc13").toWeaponRow()))
        assertTrue(ASTDDevContentSelector.isDevStorageWeapon(rows.getValue("astd_drv9").toWeaponRow()))
        assertTrue(ASTDDevContentSelector.isDevStorageWeapon(rows.getValue("astd_vpd6").toWeaponRow()))

        val hiddenRealWeapon = ASTDDevContentSelector.WeaponRow(
            id = "sample_hidden_real_weapon",
            type = "ENERGY",
            hints = emptySet(),
            tags = emptySet(),
            groupTag = "sample",
            primaryRoleStr = "压制",
            noDpsInTooltip = true,
        )
        assertTrue(
            ASTDDevContentSelector.isDevStorageWeapon(hiddenRealWeapon),
            "hidden/noDPSInTooltip is not enough to exclude a real weapon from dev storage.",
        )

        assertFalse(ASTDDevContentSelector.isDevStorageWeapon(rows.getValue("astd_arc_flare_lights").toWeaponRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageWeapon(rows.getValue("astd_arc_flare_lights_bloom").toWeaponRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageWeapon(rows.getValue("astd_arc_jet_bloom").toWeaponRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageWeapon(rows.getValue("astd_negentropy_edge_bloom").toWeaponRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageWeapon(rows.getValue("astd_plasma_arch_bloom").toWeaponRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageWeapon(rows.getValue("astd_radiation_belt_bloom").toWeaponRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageWeapon(rows.getValue("astd_virtual_particle_mote_launcher").toWeaponRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageWeapon(rows.getValue("astd_stasis_collapse_emitter").toWeaponRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageWeapon(rows.getValue("astd_stellar_jet_emitter").toWeaponRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageWeapon(rows.getValue("astd_stellar_jet_bolt_emitter").toWeaponRow()))

        val vanillaRows = CsvTestUtil.readRowsById(Path.of("/mnt/store/Games/Starsector098-linux/data/weapons/weapon_data.csv"))
        assertTrue(ASTDDevContentSelector.isDevStorageWeapon(vanillaRows.getValue("lightmg").toWeaponRow()))
        assertTrue(ASTDDevContentSelector.isDevStorageWeapon(vanillaRows.getValue("chaingun").toWeaponRow()))
        assertTrue(ASTDDevContentSelector.isDevStorageWeapon(vanillaRows.getValue("harpoon").toWeaponRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageWeapon(vanillaRows.getValue("swarmer_fighter").toWeaponRow()))
    }

    @Test
    fun `dev storage item filtering keeps cargo items but drops meta commodity buckets`() {
        val specialRows = CsvTestUtil.readRowsById(Path.of("contents/data/campaign/special_items.csv"))
        assertTrue(ASTDDevContentSelector.isDevStorageSpecialItem(specialRows.getValue("astd_echo_core").toSpecialItemRow()))

        val baseCommodityRows = CsvTestUtil.readRowsById(Path.of("/mnt/store/Games/Starsector098-linux/data/campaign/commodities.csv"))
        assertTrue(ASTDDevContentSelector.isDevStorageCommodity(baseCommodityRows.getValue("supplies").toCommodityRow()))
        assertTrue(ASTDDevContentSelector.isDevStorageCommodity(baseCommodityRows.getValue("food").toCommodityRow()))
        assertTrue(ASTDDevContentSelector.isDevStorageCommodity(baseCommodityRows.getValue("alpha_core").toCommodityRow()))
        assertTrue(ASTDDevContentSelector.isDevStorageCommodity(baseCommodityRows.getValue("omega_core").toCommodityRow()))

        assertFalse(ASTDDevContentSelector.isDevStorageCommodity(baseCommodityRows.getValue("ships").toCommodityRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageCommodity(baseCommodityRows.getValue("blueprints").toCommodityRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageCommodity(baseCommodityRows.getValue("ship_weapons").toCommodityRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageCommodity(baseCommodityRows.getValue("credits").toCommodityRow()))
    }

    @Test
    fun `dev special item storage drops parameterized template items without concrete params`() {
        val rows = CsvTestUtil.readRowsById(Path.of("/mnt/store/Games/Starsector098-linux/data/campaign/special_items.csv"))

        assertFalse(ASTDDevContentSelector.isDevStorageSpecialItem(rows.getValue("modspec").toSpecialItemRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageSpecialItem(rows.getValue("ship_bp").toSpecialItemRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageSpecialItem(rows.getValue("weapon_bp").toSpecialItemRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageSpecialItem(rows.getValue("fighter_bp").toSpecialItemRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageSpecialItem(rows.getValue("industry_bp").toSpecialItemRow()))

        assertTrue(ASTDDevContentSelector.isDevStorageSpecialItem(rows.getValue("low_tech_package").toSpecialItemRow()))
        assertTrue(ASTDDevContentSelector.isDevStorageSpecialItem(rows.getValue("topographic_data").toSpecialItemRow()))
    }

    @Test
    fun `dev special item storage drops stateful items that need concrete instance data`() {
        val rows = CsvTestUtil.readRowsById(Path.of("/mnt/store/Games/Starsector098-linux/data/campaign/special_items.csv"))

        assertFalse(
            ASTDDevContentSelector.isDevStorageSpecialItem(rows.getValue("wormhole_anchor").toSpecialItemRow()),
            "wormhole anchors require per-instance JSON data and crash the storage tooltip when added with null params.",
        )
    }

    @Test
    fun `dev special item storage drops hidden codex mod implementation items`() {
        val ratRows = CsvTestUtil.readRowsById(
            Path.of("/mnt/store/Games/Starsector098-linux/mods/Random-Assortment-of-Things/data/campaign/special_items.csv")
        )

        assertFalse(ASTDDevContentSelector.isDevStorageSpecialItem(ratRows.getValue("rat_ai_core_special").toSpecialItemRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageSpecialItem(ratRows.getValue("rat_alteration_install").toSpecialItemRow()))
        assertFalse(ASTDDevContentSelector.isDevStorageSpecialItem(ratRows.getValue("rat_artifact").toSpecialItemRow()))
    }

    @Test
    fun `dev runtime ship filtering rejects fighter hulls`() {
        val fighterHull = ASTDDevContentSelector.ShipSpecView(
            id = "sample_fighter_hull",
            hullSize = ShipAPI.HullSize.FIGHTER,
            hints = emptySet(),
            logisticsNaReason = "",
        )

        assertFalse(ASTDDevContentSelector.isDevStorageShip(fighterHull))
    }

    @Test
    fun `dev variant storage rejects variants with weapon slots missing from the active hull`() {
        val brokenVariant = ASTDDevContentSelector.VariantView(
            id = "astral1",
            hull = ASTDDevContentSelector.ShipSpecView(
                id = "astral",
                hullSize = ShipAPI.HullSize.CAPITAL_SHIP,
                hints = emptySet(),
                logisticsNaReason = "",
            ),
            availableWeaponSlotIds = setOf("WS 001", "WS 002", "WS 003"),
            fittedWeaponsBySlot = mapOf("WS 009" to "swarmer"),
            wingIds = emptyList(),
            hasStationModules = false,
        )

        assertFalse(
            ASTDDevContentSelector.validateDevStorageVariant(
                brokenVariant,
                weaponExists = { true },
                fighterWingExists = { true },
            ).accepted
        )
    }

    @Test
    fun `dev variant storage accepts variants when all fitted slots and fitted content exist`() {
        val validVariant = ASTDDevContentSelector.VariantView(
            id = "wolf_Assault",
            hull = ASTDDevContentSelector.ShipSpecView(
                id = "wolf",
                hullSize = ShipAPI.HullSize.FRIGATE,
                hints = emptySet(),
                logisticsNaReason = "",
            ),
            availableWeaponSlotIds = setOf("WS 001", "WS 002", "WS 003"),
            fittedWeaponsBySlot = mapOf("WS 001" to "lightag", "WS 002" to "sabot"),
            wingIds = emptyList(),
            hasStationModules = false,
        )

        assertTrue(
            ASTDDevContentSelector.validateDevStorageVariant(
                validVariant,
                weaponExists = { true },
                fighterWingExists = { true },
            ).accepted
        )
    }

    private fun Map<String, String>.toShipRow(): ASTDDevContentSelector.ShipRow {
        return ASTDDevContentSelector.ShipRow(
            id = getValue("id"),
            codexVariantId = getValue("codex variant id"),
            hints = splitCsvList(getValue("hints")),
            logisticsNaReason = getValue("logistics n/a reason"),
        )
    }

    private fun Map<String, String>.toWeaponRow(): ASTDDevContentSelector.WeaponRow {
        return ASTDDevContentSelector.WeaponRow(
            id = getValue("id"),
            type = getValue("type"),
            hints = splitCsvList(getValue("hints")),
            tags = splitCsvList(getValue("tags")),
            groupTag = getValue("groupTag"),
            primaryRoleStr = getValue("primaryRoleStr"),
            noDpsInTooltip = getValue("noDPSInTooltip").equals("true", ignoreCase = true),
        )
    }

    private fun Map<String, String>.toSpecialItemRow(): ASTDDevContentSelector.SpecialItemRow {
        return ASTDDevContentSelector.SpecialItemRow(
            id = getValue("id"),
            tags = splitCsvList(getValue("tags")),
            plugin = getValue("plugin"),
            params = getValue("plugin params"),
        )
    }

    private fun Map<String, String>.toCommodityRow(): ASTDDevContentSelector.CommodityRow {
        return ASTDDevContentSelector.CommodityRow(
            id = getValue("id"),
            tags = splitCsvList(getValue("tags")),
            nonEcon = splitCsvList(getValue("tags")).any { it.equals("nonecon", ignoreCase = true) },
            meta = splitCsvList(getValue("tags")).any { it.equals("meta", ignoreCase = true) },
        )
    }

    private fun splitCsvList(value: String): Set<String> {
        return value.split(',')
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() && it != "0" }
            .toSet()
    }
}
