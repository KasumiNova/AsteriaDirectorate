package cn.kasuminova.astd.campaign.world

import com.fs.starfarer.api.impl.campaign.ids.Conditions
import com.fs.starfarer.api.impl.campaign.ids.Entities
import com.fs.starfarer.api.impl.campaign.ids.Factions
import com.fs.starfarer.api.impl.campaign.ids.StarTypes
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 剧情星系规格的内容校验：对照 docs/story/03、07 的星系规格，验证生成内容与稳定 ID。
 */
internal class StoryWorldSpecsTest {

    private fun assertAllIdsUnique(spec: StoryWorldSpecs.SystemSpec) {
        val ids = spec.allEntityIds()
        assertEquals(ids.size, ids.toSet().size, "星系 ${spec.id} 的实体 ID 必须唯一: $ids")
    }

    private fun assertNameKeysPresent(spec: StoryWorldSpecs.SystemSpec) {
        val keys = spec.planets.map { it.nameKey } + spec.stations.map { it.nameKey } +
            spec.belts.map { it.nameKey } + spec.nameKey
        for (key in keys) {
            assertTrue(key.startsWith("world."), "名称 key 必须使用 world. 前缀: $key")
        }
    }

    @Test
    fun `main system matches chapter zero spec`() {
        val spec = StoryWorldSpecs.mainSystem(Random(42L))

        assertEquals(StoryWorldIds.SYSTEM_MAIN, spec.id)
        assertEquals(StarTypes.BLUE_GIANT, spec.star.typeId)

        // 空间站 ×3：分局空间站 + 预留单位 ×2（含轨道船坞预留）
        assertEquals(3, spec.stations.size)
        assertEquals(
            setOf(
                StoryWorldIds.ROLE_BRANCH_OFFICE,
                StoryWorldIds.ROLE_RESERVED,
                StoryWorldIds.ROLE_DOCKYARD,
            ),
            spec.stations.map { it.role }.toSet(),
        )
        assertTrue(spec.stations.all { it.marketKind == StoryWorldSpecs.MarketKind.FULL })
        assertTrue(spec.stations.all { it.factionId == Factions.NEUTRAL })

        // 稳定点 ×4：通讯中继站、传感器阵列、导航浮标、星门各一
        assertEquals(
            listOf(Entities.COMM_RELAY, Entities.SENSOR_ARRAY, Entities.NAV_BUOY, Entities.INACTIVE_GATE),
            spec.objectives.map { it.entityTypeId },
        )

        // 小行星带 ×1
        assertEquals(1, spec.belts.size)

        // 兰台：宜居、污染、肥沃耕地、轨道恒星镜、少量稀有矿物、少量有机物、大型废墟 + 菀星行政部遗址
        val lantai = spec.planets.single { it.id == StoryWorldIds.MAIN_PLANET_LANTAI }
        assertEquals(
            listOf(
                Conditions.HABITABLE, Conditions.POLLUTION, Conditions.FARMLAND_RICH,
                Conditions.SOLAR_ARRAY, Conditions.RARE_ORE_SPARSE, Conditions.ORGANICS_TRACE,
                Conditions.RUINS_EXTENSIVE,
            ),
            lantai.conditionIds,
        )
        assertEquals(listOf(StoryWorldIds.COND_ADMIN_RUINS), lantai.customConditionIds)

        // 洪炉 / 淬池：丰饶矿物、丰饶稀有矿物、少量挥发物、极度炎热、无大气层
        for (id in listOf(StoryWorldIds.MAIN_PLANET_HONGLU, StoryWorldIds.MAIN_PLANET_CUICHI)) {
            val planet = spec.planets.single { it.id == id }
            assertEquals(
                listOf(
                    Conditions.ORE_RICH, Conditions.RARE_ORE_RICH, Conditions.VOLATILES_DIFFUSE,
                    Conditions.VERY_HOT, Conditions.NO_ATMOSPHERE,
                ),
                planet.conditionIds,
            )
            assertEquals(Factions.NEUTRAL, planet.factionId)
        }

        // 荒芜星球 ×2~4、气态巨行星 ×1~2
        val barren = spec.planets.filter { it.id.startsWith("astd_main_planet_barren_") }
        val gas = spec.planets.filter { it.id.startsWith("astd_main_planet_gas_") }
        assertTrue(barren.size in 2..4, "荒芜星球数量应在 2~4: ${barren.size}")
        assertTrue(gas.size in 1..2, "气态巨行星数量应在 1~2: ${gas.size}")
        assertTrue(barren.all { it.conditionIds.isNotEmpty() }, "随机星球必须带随机特征")

        assertAllIdsUnique(spec)
        assertNameKeysPresent(spec)
    }

    @Test
    fun `starfall system matches chapter two spec`() {
        val spec = StoryWorldSpecs.starfallSystem(Random(42L))

        assertEquals(StoryWorldIds.SYSTEM_STARFALL, spec.id)
        assertEquals(StarTypes.BLUE_SUPERGIANT, spec.star.typeId)

        // 空间站 ×3
        assertEquals(3, spec.stations.size)
        assertTrue(spec.stations.all { it.factionId == Factions.DERELICT })

        // 稳定点 ×4 + 小行星带 ×1
        assertEquals(4, spec.objectives.size)
        assertEquals(1, spec.belts.size)

        // 锻原：丛林行星，宜居、污染、大量矿物、大量稀有矿物、炎热、大型废墟 + 星坠工程部遗址；
        // derelict 阵营（敌对防御系统供 IndEvo 炮台挂接）
        val duanyuan = spec.planets.single { it.id == StoryWorldIds.STARFALL_PLANET_DUANYUAN }
        assertEquals("jungle", duanyuan.typeId)
        assertEquals(
            listOf(
                Conditions.HABITABLE, Conditions.POLLUTION, Conditions.ORE_ABUNDANT,
                Conditions.RARE_ORE_ABUNDANT, Conditions.HOT, Conditions.RUINS_EXTENSIVE,
            ),
            duanyuan.conditionIds,
        )
        assertEquals(listOf(StoryWorldIds.COND_STARFALL_ENG_RUINS), duanyuan.customConditionIds)
        assertEquals(Factions.DERELICT, duanyuan.factionId)

        val barren = spec.planets.filter { it.id.startsWith("astd_starfall_planet_barren_") }
        val gas = spec.planets.filter { it.id.startsWith("astd_starfall_planet_gas_") }
        assertTrue(barren.size in 1..3, "荒芜星球数量应在 1~3: ${barren.size}")
        assertTrue(gas.size in 1..2, "气态巨行星数量应在 1~2: ${gas.size}")

        assertAllIdsUnique(spec)
        assertNameKeysPresent(spec)
    }

    @Test
    fun `aster system matches chapter two spec`() {
        val spec = StoryWorldSpecs.asterSystem(Random(42L))

        assertEquals(StoryWorldIds.SYSTEM_ASTER, spec.id)
        assertEquals(StarTypes.BLACK_HOLE, spec.star.typeId)
        assertTrue(spec.star.blackHole)

        // 空间站 ×5 + 引力节点 ×3（以可交互原版空间站实体承载）
        val nodes = spec.stations.filter { StoryWorldIds.TAG_GRAVITY_NODE in it.extraTags }
        assertEquals(3, nodes.size)
        assertEquals(8, spec.stations.size)

        // 引力节点等边三角形布局：同半径、120° 均布
        val nodeRadius = nodes.map { it.orbit.radiusSu }.toSet()
        assertEquals(1, nodeRadius.size, "引力节点必须同半径")
        assertEquals(listOf(0f, 120f, 240f), nodes.map { it.orbit.angleDeg })
        assertTrue(nodes.all { it.factionId == Factions.DERELICT })
        assertTrue(nodes.all { it.role == StoryWorldIds.ROLE_GRAVITY_NODE })

        // 拾光：宜居 + 视界动力 + 紫菀科研部遗址
        val shiguang = spec.stations.single { it.id == StoryWorldIds.ASTER_STATION_SHIGUANG }
        assertEquals(listOf(Conditions.HABITABLE), shiguang.conditionIds)
        assertEquals(
            listOf(StoryWorldIds.COND_EVENT_HORIZON_POWER, StoryWorldIds.COND_ASTER_RESEARCH_RUINS),
            shiguang.customConditionIds,
        )

        // 稳定点 ×4 + 小行星带 ×2
        assertEquals(4, spec.objectives.size)
        assertEquals(2, spec.belts.size)

        // 冰封星球 ×2~4 固定极度寒冷 + 黑暗；气态巨行星 ×2~4 固定黑暗
        val frozen = spec.planets.filter { it.id.startsWith("astd_aster_planet_frozen_") }
        val gas = spec.planets.filter { it.id.startsWith("astd_aster_planet_gas_") }
        val barren = spec.planets.filter { it.id.startsWith("astd_aster_planet_barren_") }
        assertTrue(frozen.size in 2..4, "冰封星球数量应在 2~4: ${frozen.size}")
        assertTrue(gas.size in 2..4, "气态巨行星数量应在 2~4: ${gas.size}")
        assertTrue(barren.size in 1..2, "荒芜星球数量应在 1~2: ${barren.size}")
        assertTrue(
            frozen.all { Conditions.VERY_COLD in it.conditionIds && Conditions.DARK in it.conditionIds },
            "冰封星球必须固定携带极度寒冷 + 黑暗",
        )
        assertTrue(gas.all { Conditions.DARK in it.conditionIds }, "气态巨行星必须固定携带黑暗")

        assertAllIdsUnique(spec)
        assertNameKeysPresent(spec)
    }

    @Test
    fun `specs are deterministic for the same seed`() {
        val a = StoryWorldSpecs.mainSystem(Random(7L))
        val b = StoryWorldSpecs.mainSystem(Random(7L))
        assertEquals(a, b)

        // 不同种子序列中至少一个应产生不同随机内容（数量/类型/状况池均由种子驱动）。
        val variants = (8L..15L).map { StoryWorldSpecs.mainSystem(Random(it)).toString() }
        assertTrue(variants.any { it != a.toString() }, "不同种子应产生不同随机内容")
    }

    @Test
    fun `all system ids are distinct across specs`() {
        val random = Random(1L)
        val specs = listOf(
            StoryWorldSpecs.mainSystem(random),
            StoryWorldSpecs.starfallSystem(random),
            StoryWorldSpecs.asterSystem(random),
        )
        val ids = specs.flatMap { it.allEntityIds() }
        assertEquals(ids.size, ids.toSet().size, "三个星系的实体 ID 全局唯一")
    }

    @Test
    fun `random planets always carry type-matched conditions`() {
        // 多枚种子遍历随机池：任何随机星球不得零状况，且不得出现类型矛盾的温度组合。
        val gasVolatileConditions = setOf(
            Conditions.VOLATILES_TRACE, Conditions.VOLATILES_DIFFUSE,
            Conditions.VOLATILES_ABUNDANT, Conditions.VOLATILES_PLENTIFUL,
            Conditions.DARK,
        )
        for (seed in 0L..24L) {
            val random = Random(seed)
            val specs = listOf(
                StoryWorldSpecs.mainSystem(random),
                StoryWorldSpecs.starfallSystem(random),
                StoryWorldSpecs.asterSystem(random),
            )
            for (spec in specs) {
                for (planet in spec.planets) {
                    assertTrue(
                        planet.conditionIds.isNotEmpty(),
                        "seed=$seed 星球 ${planet.id} 不得零状况",
                    )
                    assertFalse(
                        Conditions.VERY_HOT in planet.conditionIds && Conditions.VERY_COLD in planet.conditionIds,
                        "seed=$seed 星球 ${planet.id} 温度状况矛盾",
                    )
                }
                // 气态巨行星仅携带挥发物类（+紫菀的黑暗）状况
                val gas = spec.planets.filter { "gas" in it.id }
                for (planet in gas) {
                    assertTrue(
                        planet.conditionIds.all { it in gasVolatileConditions },
                        "seed=$seed 气态巨行星 ${planet.id} 状况越界: ${planet.conditionIds}",
                    )
                }
            }
        }
    }
}
