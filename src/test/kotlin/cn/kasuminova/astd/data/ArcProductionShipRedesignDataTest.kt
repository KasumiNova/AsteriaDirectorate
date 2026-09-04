package cn.kasuminova.astd.data

import cn.kasuminova.astd.testutil.CsvTestUtil
import cn.kasuminova.astd.testutil.RepoLayout
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArcProductionShipRedesignDataTest {

    @Test
    fun `production arc ships match redesign combat stats and systems`() {
        val rows = readCsvRows(Path.of("contents/data/hulls/ship_data.csv"))

        assertShipStats(
            rows.getValue("astd_arc_jet"),
            ExpectedShipStats(
                fleetPts = 55,
                hitpoints = 27500,
                armorRating = 1800,
                maxFlux = 25000,
                fluxDissipation = 1400,
                ordnancePoints = 350,
                maxSpeed = 40,
                acceleration = 15,
                deceleration = 15,
                maxTurnRate = 20,
                turnAcceleration = 40,
                mass = 4200,
                shieldType = "OMNI",
                shieldArc = 240,
                shieldEfficiency = 0.70,
                peakCrSec = 720,
                systemId = "astd_arc_shared_flux_network",
            ),
        )
        assertShipStats(
            rows.getValue("astd_plasma_arch"),
            ExpectedShipStats(
                fleetPts = 32,
                hitpoints = 13000,
                armorRating = 1600,
                maxFlux = 14000,
                fluxDissipation = 750,
                ordnancePoints = 185,
                maxSpeed = 45,
                acceleration = 15,
                deceleration = 15,
                maxTurnRate = 10,
                turnAcceleration = 20,
                mass = 2250,
                shieldType = "FRONT",
                shieldArc = 360,
                shieldEfficiency = 1.00,
                peakCrSec = 600,
                systemId = "astd_plasma_armor_shield_boost",
            ),
        )
        assertShipStats(
            rows.getValue("astd_radiation_belt"),
            ExpectedShipStats(
                fleetPts = 14,
                hitpoints = 5500,
                armorRating = 650,
                maxFlux = 6500,
                fluxDissipation = 500,
                maxSpeed = 95,
                shieldType = "OMNI",
                shieldArc = 200,
                shieldEfficiency = 0.70,
                peakCrSec = 360,
                systemId = "astd_limit_temporal_thruster",
            ),
        )
    }

    @Test
    fun `production arc system data matches design roles and toggle behavior`() {
        val rows = readCsvRows(Path.of("contents/data/shipsystems/ship_systems.csv"))

        assertSystemRow(
            row = rows.getValue("astd_arc_shared_flux_network"),
            expected = ExpectedSystemRow(
                chargeUp = "0.5",
                active = "10",
                down = "0.5",
                cooldown = "20",
                toggle = "FALSE",
                noFiring = "FALSE",
                hardFlux = "FALSE",
            ),
        )
        listOf(
            "astd_arc_flare_overdrive",
            "astd_arc_flare_overdrive_crewed",
            "astd_arc_flare_overdrive_automated",
        ).forEach { id ->
            assertSystemRow(
                row = rows.getValue(id),
                expected = ExpectedSystemRow(
                    chargeUp = "1",
                    active = "8",
                    down = "1",
                    cooldown = "20",
                    toggle = "FALSE",
                    noFiring = "FALSE",
                    hardFlux = "FALSE",
                ),
            )
        }
        assertSystemRow(
            row = rows.getValue("astd_plasma_armor_shield_boost"),
            expected = ExpectedSystemRow(
                chargeUp = "0.5",
                active = "",
                down = "0.5",
                cooldown = "0",
                toggle = "TRUE",
                noFiring = "TRUE",
                hardFlux = "TRUE",
                fluxPerSecondBaseCap = "0.02",
                tags = "defensive",
            ),
        )
        assertSystemRow(
            row = rows.getValue("astd_limit_temporal_thruster"),
            expected = ExpectedSystemRow(
                maxUses = "3",
                regen = "0.1",
                chargeUp = "0.2",
                active = "2",
                down = "0.2",
                cooldown = "1.5",
                toggle = "FALSE",
                noFiring = "FALSE",
                hardFlux = "FALSE",
            ),
        )

        assertSystemFile(
            path = Path.of("contents/data/shipsystems/astd_arc_shared_flux_network.system"),
            id = "astd_arc_shared_flux_network",
            type = "STAT_MOD",
            statsScript = "cn.kasuminova.astd.combat.shipsystems.ASTDArcSharedFluxNetworkSystemStats",
            aiType = "CUSTOM",
            aiScript = "cn.kasuminova.astd.combat.shipsystems.ASTDArcSharedFluxNetworkSystemAI",
        )
        assertSystemFile(
            path = Path.of("contents/data/shipsystems/astd_plasma_armor_shield_boost.system"),
            id = "astd_plasma_armor_shield_boost",
            type = "SHIELD_MOD",
            statsScript = "cn.kasuminova.astd.combat.shipsystems.ASTDPlasmaArmorShieldBoostSystemStats",
            aiType = "FORTRESS_SHIELD",
            aiScript = null,
            useSound = null,
            loopSound = "system_fortress_shield_loop",
            outOfUsesSound = "gun_out_of_ammo",
        )
        assertSystemFile(
            path = Path.of("contents/data/shipsystems/astd_limit_temporal_thruster.system"),
            id = "astd_limit_temporal_thruster",
            type = "ENGINE_MOD",
            statsScript = "cn.kasuminova.astd.combat.shipsystems.ASTDLimitTemporalThrusterSystemStats",
            aiType = "CUSTOM",
            aiScript = "cn.kasuminova.astd.combat.shipsystems.ASTDLimitTemporalThrusterSystemAI",
            useSound = "system_burn_drive_activate",
        )
    }

    @Test
    fun `production arc design document stays synchronized with latest plasma and radiation changes`() {
        val design = Files.readString(Path.of("docs/design/ships/blue/20-production.md"))

        listOf(
            "正前方 **60°**",
            "左右侧前方 **60°~180°**",
            "后方 **180°~360°**",
            "系统启动期间，辐能转换量与软辐能生成同步翻倍",
            "激活期间护盾受到的单次伤害大于舰船最大辐能的 **5%** 时，超出的伤害部分降低 **50%**",
            "触发时播放 EMP 发生器打击音效",
            "触发概率不再受到受击位置和伤害类型修正",
            "光束伤害会降低 **90%** 触发概率",
            "以舰船最大辐能的 **2%** 为基准",
            "最终倍率限制在 **0.1x~3x**",
            "护盾边缘出现整根原版 EMP 电弧",
            "护盾电弧更倾向于出现在近期受击方向",
            "端点使用小型 MagicLensFlare 光斑",
            "系统开启期间电弧生成频率翻倍",
            "正前方 **60°** | 最终装甲值的 30%",
            "左右侧前方 **60°~180°** | 最终装甲值的 20%~30%",
            "后方 **180°~360°** | 最终装甲值的 10%~20%",
            "护盾分流与强化护盾",
            "固定降低 **50%** 最大装甲值",
            "软辐能生成 | 与被转换硬辐能等额",
            "装配点 | 185",
            "部署点 | 32",
            "额外时间流速 | +200%",
            "残影刷新频率为电弧闪级战术系统的 2 倍",
            "舰船自身非导弹非点防武器射程降低 **20%**",
        ).forEach { required ->
            assertTrue(design.contains(required), "design document missing current requirement: $required")
        }

        listOf(
            "虚拟装甲",
            "护盾被拆分为 12",
            "0.5% 的硬辐能",
            "最大辐能的 **0.5%**",
            "护盾受击：动能伤害 **+75%**",
            "装甲受击：动能伤害 **-50%**",
            "光束伤害会在以上规则基础上额外 **-75%**",
            "最终装甲值的 5%~10%",
            "最终装甲值的 10%~15%，越靠近正前方越高",
            "只能享受 **50%** 装甲提升效果",
            "只能享受 **66%** 装甲提升效果",
            "被转换硬辐能的 2 倍",
            "被转换硬辐能的 1.5 倍",
            "护盾会根据受损比例受到额外伤害",
            "额外时间流速 | +100%",
            "舰船自身武器射程降低 **20%**",
        ).forEach { stale ->
            assertFalse(design.contains(stale), "design document still contains stale requirement: $stale")
        }
    }

    @Test
    fun `production arc standard variants carry vanilla and unique built-ins`() {
        assertHullBuiltInMods(
            hullPath = Path.of("contents/data/hulls/astd_arc_jet.ship"),
            variantPath = Path.of("contents/data/variants/astd_arc_jet_Standard.variant"),
            required = listOf(
                "advancedcore",
                "armoredweapons",
                "astd_arc_advanced_fire_control",
                "astd_arc_shared_tactical_network",
            ),
        )
        assertVariantWeapons(
            path = Path.of("contents/data/variants/astd_arc_jet_Standard.variant"),
            // D9 废弃武器拆除后，空出的槽位以原版武器过渡（P 后续阶段重新设计）
            required = mapOf(
                "WS0001" to "gauss",
                "WS0002" to "gauss",
                "WS0003" to "tachyonlance",
                "WS0004" to "tachyonlance",
                "WS0015" to "heavyneedler",
                "WS0016" to "heavyneedler",
            ),
        )
        assertHullBuiltInMods(
            hullPath = Path.of("contents/data/hulls/astd_plasma_arch.ship"),
            variantPath = Path.of("contents/data/variants/astd_plasma_arch_Standard.variant"),
            required = listOf(
                "stabilizedshieldemitter",
                "missleracks",
                "astd_plasma_armor_shield",
                "astd_ionized_recoil_accumulator",
            ),
        )
        assertVariantWeapons(
            path = Path.of("contents/data/variants/astd_plasma_arch_Standard.variant"),
            required = mapOf(
                "WS0001" to "gauss",
                "WS0002" to "gauss",
                "WS0003" to "heavyneedler",
                "WS0004" to "heavyneedler",
                "WS0012" to "harpoon",
                "WS0013" to "harpoon",
            ),
        )
        val radiationBeltBuiltIns = assertHullBuiltInMods(
            hullPath = Path.of("contents/data/hulls/astd_radiation_belt.ship"),
            variantPath = Path.of("contents/data/variants/astd_radiation_belt_Standard.variant"),
            required = listOf(
                "magazines",
                "auxiliarythrusters",
                "astd_arc_advanced_targeting_system",
                "astd_distributed_pursuit_network",
            ),
        )
        assertFalse("expanded_magazines" in radiationBeltBuiltIns, "Starsector canonical expanded magazines id is magazines")
        assertVariantWeapons(
            path = Path.of("contents/data/variants/astd_radiation_belt_Standard.variant"),
            required = mapOf(
                "WS0001" to "pdlaser",
                "WS0002" to "astd_spc3",
                "WS0003" to "astd_spc3",
                "WS0007" to "harpoon",
                "WS0008" to "harpoon",
            ),
        )
    }

    @Test
    fun `production arc ship systems are registered with expected runtime scripts`() {
        val rows = readCsvRows(Path.of("contents/data/shipsystems/ship_systems.csv"))
        listOf(
            "astd_arc_shared_flux_network",
            "astd_plasma_armor_shield_boost",
            "astd_limit_temporal_thruster",
        ).forEach { id ->
            assertTrue(rows.containsKey(id), "missing ship system csv row: $id")
        }

        assertSystemFile(
            path = Path.of("contents/data/shipsystems/astd_arc_shared_flux_network.system"),
            id = "astd_arc_shared_flux_network",
            statsScript = "cn.kasuminova.astd.combat.shipsystems.ASTDArcSharedFluxNetworkSystemStats",
            aiType = "CUSTOM",
            aiScript = "cn.kasuminova.astd.combat.shipsystems.ASTDArcSharedFluxNetworkSystemAI",
        )
        assertSystemFile(
            path = Path.of("contents/data/shipsystems/astd_plasma_armor_shield_boost.system"),
            id = "astd_plasma_armor_shield_boost",
            type = "SHIELD_MOD",
            statsScript = "cn.kasuminova.astd.combat.shipsystems.ASTDPlasmaArmorShieldBoostSystemStats",
            aiType = "FORTRESS_SHIELD",
            aiScript = null,
            useSound = null,
            loopSound = "system_fortress_shield_loop",
            outOfUsesSound = "gun_out_of_ammo",
        )
        assertSystemFile(
            path = Path.of("contents/data/shipsystems/astd_limit_temporal_thruster.system"),
            id = "astd_limit_temporal_thruster",
            type = "ENGINE_MOD",
            statsScript = "cn.kasuminova.astd.combat.shipsystems.ASTDLimitTemporalThrusterSystemStats",
            aiType = "CUSTOM",
            aiScript = "cn.kasuminova.astd.combat.shipsystems.ASTDLimitTemporalThrusterSystemAI",
            useSound = "system_burn_drive_activate",
        )
    }

    @Test
    fun `plasma armor shield boost exposes native fortress ai cost without duplicate script flux`() {
        val rows = readCsvRows(Path.of("contents/data/shipsystems/ship_systems.csv"))
        val row = rows.getValue("astd_plasma_armor_shield_boost")
        assertEquals("0.02", row.getValue("f/s (base cap)"), "system hard flux upkeep should be the design 2% max-flux cost")
        assertEquals("TRUE", row.getValue("hardFlux"), "system upkeep must be generated as hard flux by the native ship-system spec")
        assertEquals("defensive", row.getValue("tags"), "vanilla FORTRESS_SHIELD AI must see a defensive system tag")

        val statsSource = Files.readString(RepoLayout.mainSourceFile("combat/shipsystems/ASTDPlasmaArmorShieldBoostSystemStats.kt")!!)
        assertFalse(statsSource.contains("increaseFlux("), "stats script must not add a second hard-flux upkeep on top of ship_systems.csv")
        assertFalse(statsSource.contains("HARD_FLUX_PER_SECOND"), "hard-flux upkeep belongs in ship_systems.csv so vanilla AI and runtime cost see the same value")
    }

    @Test
    fun `plasma armor shield boost stats exposes shield reduction to vanilla fortress ai estimator`() {
        val statsSource = Files.readString(RepoLayout.mainSourceFile("combat/shipsystems/ASTDPlasmaArmorShieldBoostSystemStats.kt")!!)
        val applyBody = statsSource
            .substringAfter("override fun apply(stats: MutableShipStatsAPI")
            .substringBefore("override fun unapply")

        assertTrue(
            applyBody.contains("applyStatModifiers(stats, id, level)"),
            "system stats must apply shield and armor modifiers through a pure helper visible to vanilla FORTRESS_SHIELD AI's offline estimator",
        )
        assertTrue(
            statsSource.contains("private fun applyStatModifiers(stats: MutableShipStatsAPI, id: String, level: Float)"),
            "shield reduction calculation should not depend on combat engine or live ship state",
        )
        assertTrue(
            applyBody.indexOf("applyStatModifiers(stats, id, level)") < applyBody.indexOf("val engine = Global.getCombatEngine() ?: return"),
            "vanilla FORTRESS_SHIELD AI constructs an estimated stats object without a combat engine; stat modifiers must be applied before combat-only effects return",
        )
        assertTrue(
            applyBody.indexOf("applyStatModifiers(stats, id, level)") < applyBody.indexOf("val ship = stats.entity as? ShipAPI ?: return"),
            "vanilla FORTRESS_SHIELD AI's estimated stats object has no live ShipAPI entity; shield reduction must not require stats.entity",
        )
    }

    private fun assertShipStats(row: Map<String, String>, expected: ExpectedShipStats) {
        assertEquals(expected.fleetPts.toString(), row.getValue("fleet pts"), "fleet pts mismatch for ${row.getValue("id")}")
        assertEquals(expected.hitpoints.toString(), row.getValue("hitpoints"), "hitpoints mismatch for ${row.getValue("id")}")
        assertEquals(expected.armorRating.toString(), row.getValue("armor rating"), "armor rating mismatch for ${row.getValue("id")}")
        assertEquals(expected.maxFlux.toString(), row.getValue("max flux"), "max flux mismatch for ${row.getValue("id")}")
        assertEquals(expected.fluxDissipation.toString(), row.getValue("flux dissipation"), "flux dissipation mismatch for ${row.getValue("id")}")
        expected.ordnancePoints?.let {
            assertEquals(it.toString(), row.getValue("ordnance points"), "ordnance points mismatch for ${row.getValue("id")}")
        }
        assertEquals(expected.maxSpeed.toString(), row.getValue("max speed"), "max speed mismatch for ${row.getValue("id")}")
        expected.acceleration?.let {
            assertEquals(it.toString(), row.getValue("acceleration"), "acceleration mismatch for ${row.getValue("id")}")
        }
        expected.deceleration?.let {
            assertEquals(it.toString(), row.getValue("deceleration"), "deceleration mismatch for ${row.getValue("id")}")
        }
        expected.maxTurnRate?.let {
            assertEquals(it.toString(), row.getValue("max turn rate"), "max turn rate mismatch for ${row.getValue("id")}")
        }
        expected.turnAcceleration?.let {
            assertEquals(it.toString(), row.getValue("turn acceleration"), "turn acceleration mismatch for ${row.getValue("id")}")
        }
        expected.mass?.let {
            assertEquals(it.toString(), row.getValue("mass"), "mass mismatch for ${row.getValue("id")}")
        }
        expected.shieldType?.let {
            assertEquals(it, row.getValue("shield type"), "shield type mismatch for ${row.getValue("id")}")
        }
        expected.shieldArc?.let {
            assertEquals(it.toString(), row.getValue("shield arc"), "shield arc mismatch for ${row.getValue("id")}")
        }
        assertEquals(formatNumber(expected.shieldEfficiency), row.getValue("shield efficiency"), "shield efficiency mismatch for ${row.getValue("id")}")
        assertEquals(expected.peakCrSec.toString(), row.getValue("peak CR sec"), "peak CR sec mismatch for ${row.getValue("id")}")
        assertEquals(expected.systemId, row.getValue("system id"), "system id mismatch for ${row.getValue("id")}")
    }

    private fun assertSystemRow(row: Map<String, String>, expected: ExpectedSystemRow) {
        val id = row.getValue("id")
        assertEquals(expected.maxUses, row.getValue("max uses"), "max uses mismatch for $id")
        assertEquals(expected.regen, row.getValue("regen"), "regen mismatch for $id")
        assertEquals(expected.chargeUp, row.getValue("charge up"), "charge up mismatch for $id")
        assertEquals(expected.active, row.getValue("active"), "active mismatch for $id")
        assertEquals(expected.down, row.getValue("down"), "down mismatch for $id")
        assertEquals(expected.cooldown, row.getValue("cooldown"), "cooldown mismatch for $id")
        assertEquals(expected.toggle, row.getValue("toggle"), "toggle mismatch for $id")
        assertEquals(expected.noFiring, row.getValue("noFiring"), "noFiring mismatch for $id")
        assertEquals(expected.hardFlux, row.getValue("hardFlux"), "hardFlux mismatch for $id")
        assertEquals(expected.fluxPerSecondBaseCap, row.getValue("f/s (base cap)"), "f/s (base cap) mismatch for $id")
        assertEquals(expected.tags, row.getValue("tags"), "tags mismatch for $id")
    }

    private fun readCsvRows(path: Path): Map<String, Map<String, String>> {
        return CsvTestUtil.readRowsById(path)
    }

    private fun assertHullBuiltInMods(hullPath: Path, variantPath: Path, required: List<String>): Set<String> {
        val builtIns = hullBuiltInMods(hullPath)
        required.forEach { id ->
            assertTrue(id in builtIns, "${hullPath.fileName} missing builtInMod: $id")
        }
        val variantPermaMods = variantPermaMods(variantPath)
        required.forEach { id ->
            assertFalse(id in variantPermaMods, "${variantPath.fileName} must not keep built-in hullmod as removable permaMod: $id")
        }
        return builtIns
    }

    private fun assertVariantWeapons(path: Path, required: Map<String, String>) {
        val groups = JSONObject(Files.readString(path)).getJSONArray("weaponGroups")
        assertTrue(groups.length() > 0, "${path.fileName} must define a production weapon loadout")
        assertValidFluxAllocation(path)
        val weapons = linkedMapOf<String, String>()
        for (groupIndex in 0 until groups.length()) {
            val groupWeapons = groups.getJSONObject(groupIndex).getJSONObject("weapons")
            val keys = groupWeapons.keys()
            while (keys.hasNext()) {
                val slot = keys.next() as String
                weapons[slot] = groupWeapons.getString(slot)
            }
        }
        required.forEach { (slot, weaponId) ->
            assertEquals(weaponId, weapons[slot], "${path.fileName} weapon mismatch for slot $slot")
        }
    }

    private fun assertValidFluxAllocation(path: Path) {
        val variant = JSONObject(Files.readString(path))
        val vents = variant.getInt("fluxVents")
        val capacitors = variant.getInt("fluxCapacitors")
        assertTrue(vents >= 0, "${path.fileName} fluxVents must not be negative")
        assertTrue(capacitors >= 0, "${path.fileName} fluxCapacitors must not be negative")
        assertTrue(vents + capacitors <= 50, "${path.fileName} exceeds Starsector flux vent/capacitor limit")
    }

    private fun variantPermaMods(path: Path): Set<String> {
        val json = JSONObject(Files.readString(path))
        if (!json.has("permaMods")) return emptySet()
        val array = json.getJSONArray("permaMods")
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }

    private fun hullBuiltInMods(path: Path): Set<String> {
        val json = JSONObject(Files.readString(path))
        if (!json.has("builtInMods")) return emptySet()
        val array = json.getJSONArray("builtInMods")
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }

    private fun assertSystemFile(
        path: Path,
        id: String,
        type: String = "STAT_MOD",
        statsScript: String,
        aiType: String,
        aiScript: String?,
        useSound: String? = "system_ammo_feeder",
        loopSound: String? = null,
        outOfUsesSound: String? = null,
    ) {
        val values = readJsonStringValues(path)
        assertEquals(id, values["id"], "system id mismatch for ${path.fileName}")
        assertEquals(type, values["type"], "system type mismatch for $id")
        assertEquals(statsScript, values["statsScript"], "statsScript mismatch for $id")
        assertEquals(aiType, values["aiType"], "aiType mismatch for $id")
        if (aiScript == null) {
            assertFalse(values.containsKey("aiScript"), "system should not define aiScript: $id")
        } else {
            assertEquals(aiScript, values["aiScript"], "aiScript mismatch for $id")
        }
        if (useSound == null) {
            assertFalse(values.containsKey("useSound"), "system should omit useSound for $id")
        } else {
            assertEquals(useSound, values["useSound"], "useSound mismatch for $id")
        }
        loopSound?.let { assertEquals(it, values["loopSound"], "loopSound mismatch for $id") }
        outOfUsesSound?.let { assertEquals(it, values["outOfUsesSound"], "outOfUsesSound mismatch for $id") }
    }

    private fun readJsonStringValues(path: Path): Map<String, String> =
        JSONObject(Files.readString(path)).let { json ->
            val values = linkedMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next() as String
                values[key] = json.getString(key)
            }
            values
        }

    private fun formatNumber(value: Double): String {
        val longValue = value.toLong()
        return if (value == longValue.toDouble()) longValue.toString() else value.toString()
    }

    private data class ExpectedShipStats(
        val fleetPts: Int,
        val hitpoints: Int,
        val armorRating: Int,
        val maxFlux: Int,
        val fluxDissipation: Int,
        val ordnancePoints: Int? = null,
        val maxSpeed: Int,
        val acceleration: Int? = null,
        val deceleration: Int? = null,
        val maxTurnRate: Int? = null,
        val turnAcceleration: Int? = null,
        val mass: Int? = null,
        val shieldType: String? = null,
        val shieldArc: Int? = null,
        val shieldEfficiency: Double,
        val peakCrSec: Int,
        val systemId: String,
    )

    private data class ExpectedSystemRow(
        val maxUses: String = "",
        val regen: String = "",
        val chargeUp: String,
        val active: String,
        val down: String,
        val cooldown: String,
        val toggle: String,
        val noFiring: String,
        val hardFlux: String,
        val fluxPerSecondBaseCap: String = "0",
        val tags: String = "",
    )
}
