package cn.kasuminova.astd.campaign.world

import com.fs.starfarer.api.impl.campaign.ids.Conditions
import com.fs.starfarer.api.impl.campaign.ids.Entities
import com.fs.starfarer.api.impl.campaign.ids.Factions
import com.fs.starfarer.api.impl.campaign.ids.Planets
import com.fs.starfarer.api.impl.campaign.ids.StarTypes
import java.util.Random

/**
 * 剧情星系的纯数据规格（不依赖运行期 Sector 状态，可单测）。
 *
 * 规格与 `docs/story/03-序章`、`docs/story/07-第二章` 中的星系规格一一对应；
 * 数量带区间的条目（如“荒芜星球 ×2~4”）由注入的 [Random] 在构建期定值，
 * 生成器只负责按规格落地，不再做内容决策。
 */
object StoryWorldSpecs {

    /** 轨道参数：角度（度）、半径（su）、公转周期（天）。 */
    data class OrbitSpec(val angleDeg: Float, val radiusSu: Float, val days: Float)

    /** 市场形态：完整市场（可停靠/参与经济）、仅状况市场（行星状况容器，不进经济）、无市场。 */
    enum class MarketKind { FULL, CONDITION_ONLY, NONE }

    data class StarSpec(
        val id: String,
        val typeId: String,
        val radius: Float,
        val coronaRadius: Float,
        /** 黑洞需要额外的事件视界地形替换日冕。 */
        val blackHole: Boolean = false,
    )

    data class PlanetSpec(
        val id: String,
        val nameKey: String,
        val typeId: String,
        val orbit: OrbitSpec,
        val planetRadius: Float,
        /** 原版状况，必定附加。 */
        val conditionIds: List<String>,
        /** 本模组自定义状况（规格由 condition 内容任务注册；未注册时 addCondition 直接报错）。 */
        val customConditionIds: List<String> = emptyList(),
        val marketKind: MarketKind = MarketKind.CONDITION_ONLY,
        val marketSize: Int = 3,
        val factionId: String = Factions.NEUTRAL,
    )

    data class StationSpec(
        val id: String,
        val nameKey: String,
        val entityTypeId: String,
        val orbit: OrbitSpec,
        val role: String,
        val marketKind: MarketKind = MarketKind.FULL,
        val marketSize: Int = 3,
        val factionId: String = Factions.NEUTRAL,
        val conditionIds: List<String> = emptyList(),
        val customConditionIds: List<String> = emptyList(),
        val extraTags: List<String> = emptyList(),
    )

    /** 稳定点目标实体（通讯中继站/传感器阵列/导航浮标/星门），占用稳定点、绕主星运行。 */
    data class ObjectiveSpec(
        val id: String,
        val entityTypeId: String,
        val orbit: OrbitSpec,
        val factionId: String = Factions.NEUTRAL,
    )

    data class BeltSpec(
        val id: String,
        val nameKey: String,
        val asteroidCount: Int,
        val orbitRadius: Float,
        val width: Float,
        val minOrbitDays: Float,
        val maxOrbitDays: Float,
    )

    data class SystemSpec(
        val id: String,
        val nameKey: String,
        val backgroundTexture: String,
        val star: StarSpec,
        val planets: List<PlanetSpec>,
        val stations: List<StationSpec>,
        val objectives: List<ObjectiveSpec>,
        val belts: List<BeltSpec>,
    ) {
        /** 规格内全部实体 ID（用于唯一性校验与 canonical 去重）。 */
        fun allEntityIds(): List<String> =
            listOf(star.id) +
                planets.map { it.id } +
                stations.map { it.id } +
                objectives.map { it.id } +
                belts.map { it.id }
    }

    // ------------------------------------------------------------------
    // 随机星球抽取池（“随机星球特征”条目）
    //
    // 每次抽取同时确定星球类型与匹配的状况组合，保证：
    // - 任何随机星球都不会零状况；
    // - 状况与星球类型合理匹配（热行星不带极寒、冰行星不带炎热等）。
    // ------------------------------------------------------------------

    private data class RandomPlanetPick(val typeId: String, val conditionIds: List<String>)

    /** 荒芜系星球抽取池：类型与矿物/温度状况匹配。 */
    private val BARREN_PLANET_PICKS: List<RandomPlanetPick> = listOf(
        RandomPlanetPick(Planets.BARREN, listOf(Conditions.ORE_MODERATE, Conditions.NO_ATMOSPHERE)),
        RandomPlanetPick(Planets.BARREN2, listOf(Conditions.ORE_MODERATE, Conditions.RUINS_SCATTERED, Conditions.NO_ATMOSPHERE)),
        RandomPlanetPick(Planets.BARREN3, listOf(Conditions.RARE_ORE_MODERATE, Conditions.NO_ATMOSPHERE)),
        RandomPlanetPick(Planets.BARREN_BOMBARDED, listOf(Conditions.ORE_RICH, Conditions.VERY_HOT, Conditions.NO_ATMOSPHERE)),
        RandomPlanetPick(Planets.BARREN_DESERT, listOf(Conditions.ORE_SPARSE, Conditions.HOT, Conditions.NO_ATMOSPHERE)),
        RandomPlanetPick(Planets.ROCKY_METALLIC, listOf(Conditions.ORE_ABUNDANT, Conditions.RARE_ORE_SPARSE, Conditions.NO_ATMOSPHERE)),
        RandomPlanetPick(Planets.ROCKY_ICE, listOf(Conditions.VOLATILES_DIFFUSE, Conditions.VERY_COLD, Conditions.NO_ATMOSPHERE)),
        RandomPlanetPick(Planets.ROCKY_UNSTABLE, listOf(Conditions.ORE_MODERATE, Conditions.NO_ATMOSPHERE)),
    )

    /** 冰封星球抽取池：仅提供类型与附加资源状况；极寒 + 黑暗由紫菀规格固定附加。 */
    private val FROZEN_PLANET_PICKS: List<RandomPlanetPick> = listOf(
        RandomPlanetPick(Planets.FROZEN1, listOf(Conditions.VOLATILES_ABUNDANT)),
        RandomPlanetPick(Planets.FROZEN2, listOf(Conditions.VOLATILES_DIFFUSE, Conditions.ORGANICS_TRACE)),
        RandomPlanetPick(Planets.FROZEN3, listOf(Conditions.ORE_SPARSE)),
        RandomPlanetPick(Planets.CRYOVOLCANIC, listOf(Conditions.VOLATILES_PLENTIFUL)),
    )

    /** 气态巨行星抽取池：类型与挥发物状况匹配。 */
    private val GAS_GIANT_PICKS: List<RandomPlanetPick> = listOf(
        RandomPlanetPick(Planets.GAS_GIANT, listOf(Conditions.VOLATILES_ABUNDANT)),
        RandomPlanetPick(Planets.GAS_GIANT, listOf(Conditions.VOLATILES_PLENTIFUL)),
        RandomPlanetPick(Planets.ICE_GIANT, listOf(Conditions.VOLATILES_DIFFUSE)),
    )

    private fun pick(random: Random, pool: List<RandomPlanetPick>): RandomPlanetPick =
        pool[random.nextInt(pool.size)]

    private fun orbit(angleDeg: Float, radiusSu: Float): OrbitSpec =
        OrbitSpec(angleDeg, radiusSu, radiusSu / 10f)

    // ------------------------------------------------------------------
    // 剧情主星系（docs/story/03「剧情主星系规格」）
    // ------------------------------------------------------------------

    fun mainSystem(random: Random): SystemSpec {
        val barrenCount = 2 + random.nextInt(3) // 荒芜星球 ×2~4
        val gasCount = 1 + random.nextInt(2) // 气态巨行星 ×1~2

        val planets = mutableListOf(
            // 类地行星「兰台」：宜居、污染、肥沃耕地、轨道恒星镜、少量稀有矿物、少量有机物、大型废墟
            PlanetSpec(
                id = StoryWorldIds.MAIN_PLANET_LANTAI,
                nameKey = "world.main.planet.lantai",
                typeId = Planets.PLANET_TERRAN,
                orbit = orbit(300f, 7200f),
                planetRadius = 170f,
                conditionIds = listOf(
                    Conditions.HABITABLE,
                    Conditions.POLLUTION,
                    Conditions.FARMLAND_RICH,
                    Conditions.SOLAR_ARRAY,
                    Conditions.RARE_ORE_SPARSE,
                    Conditions.ORGANICS_TRACE,
                    Conditions.RUINS_EXTENSIVE,
                ),
                customConditionIds = listOf(StoryWorldIds.COND_ADMIN_RUINS),
            ),
            // 荒芜星球「洪炉」：丰饶矿物、丰饶稀有矿物、少量挥发物、极度炎热、无大气层
            PlanetSpec(
                id = StoryWorldIds.MAIN_PLANET_HONGLU,
                nameKey = "world.main.planet.honglu",
                typeId = Planets.BARREN_BOMBARDED,
                orbit = orbit(10f, 2200f),
                planetRadius = 90f,
                conditionIds = listOf(
                    Conditions.ORE_RICH,
                    Conditions.RARE_ORE_RICH,
                    Conditions.VOLATILES_DIFFUSE,
                    Conditions.VERY_HOT,
                    Conditions.NO_ATMOSPHERE,
                ),
            ),
            // 荒芜星球「淬池」：同洪炉
            PlanetSpec(
                id = StoryWorldIds.MAIN_PLANET_CUICHI,
                nameKey = "world.main.planet.cuichi",
                typeId = Planets.BARREN2,
                orbit = orbit(190f, 2700f),
                planetRadius = 100f,
                conditionIds = listOf(
                    Conditions.ORE_RICH,
                    Conditions.RARE_ORE_RICH,
                    Conditions.VOLATILES_DIFFUSE,
                    Conditions.VERY_HOT,
                    Conditions.NO_ATMOSPHERE,
                ),
            ),
        )

        // 荒芜星球 ×2~4：按星球类型生成匹配的随机特征
        for (i in 1..barrenCount) {
            val pick = pick(random, BARREN_PLANET_PICKS)
            planets += PlanetSpec(
                id = "astd_main_planet_barren_$i",
                nameKey = "world.main.planet.barren_$i",
                typeId = pick.typeId,
                orbit = orbit(40f + i * 75f, 4200f + i * 450f),
                planetRadius = 70f + random.nextInt(40),
                conditionIds = pick.conditionIds,
            )
        }

        // 气态巨行星 ×1~2：按星球类型生成匹配的随机特征
        for (i in 1..gasCount) {
            val pick = pick(random, GAS_GIANT_PICKS)
            planets += PlanetSpec(
                id = "astd_main_planet_gas_$i",
                nameKey = "world.main.planet.gas_$i",
                typeId = pick.typeId,
                orbit = orbit(120f + i * 130f, 9200f + i * 1600f),
                planetRadius = 230f + random.nextInt(60),
                conditionIds = pick.conditionIds,
            )
        }

        return SystemSpec(
            id = StoryWorldIds.SYSTEM_MAIN,
            nameKey = "world.main.system_name",
            backgroundTexture = "graphics/backgrounds/background2.jpg",
            star = StarSpec(StoryWorldIds.MAIN_STAR, StarTypes.BLUE_GIANT, 750f, 600f),
            planets = planets,
            stations = listOf(
                // 分局空间站：剧情主入口，赏金接取/核销点
                StationSpec(
                    id = StoryWorldIds.MAIN_STATION_BRANCH,
                    nameKey = "world.main.station.branch",
                    entityTypeId = "station_hightech3",
                    orbit = orbit(220f, 3400f),
                    role = StoryWorldIds.ROLE_BRANCH_OFFICE,
                    marketSize = 4,
                ),
                // 预留单位（本体阶段仅提供描述文本）
                StationSpec(
                    id = StoryWorldIds.MAIN_STATION_RESERVED,
                    nameKey = "world.main.station.reserved",
                    entityTypeId = "station_lowtech1",
                    orbit = orbit(80f, 6100f),
                    role = StoryWorldIds.ROLE_RESERVED,
                ),
                // 预留单位：后续可能以轨道船坞形式提供新功能
                StationSpec(
                    id = StoryWorldIds.MAIN_STATION_DOCKYARD,
                    nameKey = "world.main.station.dockyard",
                    entityTypeId = "station_midline2",
                    orbit = orbit(250f, 8500f),
                    role = StoryWorldIds.ROLE_DOCKYARD,
                ),
            ),
            objectives = standardObjectives(
                commId = StoryWorldIds.MAIN_OBJ_COMM_RELAY,
                sensorId = StoryWorldIds.MAIN_OBJ_SENSOR_ARRAY,
                navId = StoryWorldIds.MAIN_OBJ_NAV_BUOY,
                gateId = StoryWorldIds.MAIN_OBJ_GATE,
                commOrbit = orbit(150f, 4800f),
                sensorOrbit = orbit(330f, 6000f),
                navOrbit = orbit(20f, 7900f),
                gateOrbit = orbit(210f, 12400f),
            ),
            belts = listOf(
                BeltSpec(
                    id = StoryWorldIds.MAIN_BELT_1,
                    nameKey = "world.main.belt_1",
                    asteroidCount = 120,
                    orbitRadius = 3050f,
                    width = 700f,
                    minOrbitDays = 240f,
                    maxOrbitDays = 360f,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------
    // 星坠遗址星系（docs/story/07「星坠遗址星系」）
    // ------------------------------------------------------------------

    fun starfallSystem(random: Random): SystemSpec {
        val barrenCount = 1 + random.nextInt(3) // 荒芜星球 ×1~3
        val gasCount = 1 + random.nextInt(2) // 气态巨行星 ×1~2

        val planets = mutableListOf(
            // 丛林行星「锻原」：宜居、污染、大量矿物、大量稀有矿物、炎热、大型废墟
            // 防御系统仍在值班：市场归属 derelict，供 IndEvo 敌对炮台挂接
            PlanetSpec(
                id = StoryWorldIds.STARFALL_PLANET_DUANYUAN,
                nameKey = "world.starfall.planet.duanyuan",
                typeId = "jungle",
                orbit = orbit(160f, 5000f),
                planetRadius = 160f,
                conditionIds = listOf(
                    Conditions.HABITABLE,
                    Conditions.POLLUTION,
                    Conditions.ORE_ABUNDANT,
                    Conditions.RARE_ORE_ABUNDANT,
                    Conditions.HOT,
                    Conditions.RUINS_EXTENSIVE,
                ),
                customConditionIds = listOf(StoryWorldIds.COND_STARFALL_ENG_RUINS),
                factionId = Factions.DERELICT,
            ),
        )

        for (i in 1..barrenCount) {
            val pick = pick(random, BARREN_PLANET_PICKS)
            planets += PlanetSpec(
                id = "astd_starfall_planet_barren_$i",
                nameKey = "world.starfall.planet.barren_$i",
                typeId = pick.typeId,
                orbit = orbit(30f + i * 95f, 3200f + i * 500f),
                planetRadius = 65f + random.nextInt(35),
                conditionIds = pick.conditionIds,
            )
        }

        for (i in 1..gasCount) {
            val pick = pick(random, GAS_GIANT_PICKS)
            planets += PlanetSpec(
                id = "astd_starfall_planet_gas_$i",
                nameKey = "world.starfall.planet.gas_$i",
                typeId = pick.typeId,
                orbit = orbit(200f + i * 110f, 8600f + i * 1700f),
                planetRadius = 240f + random.nextInt(60),
                conditionIds = pick.conditionIds,
            )
        }

        return SystemSpec(
            id = StoryWorldIds.SYSTEM_STARFALL,
            nameKey = "world.starfall.system_name",
            backgroundTexture = "graphics/backgrounds/background4.jpg",
            star = StarSpec(StoryWorldIds.STARFALL_STAR, StarTypes.BLUE_SUPERGIANT, 900f, 700f),
            planets = planets,
            stations = listOf(
                StationSpec(
                    id = StoryWorldIds.STARFALL_STATION_MAIN,
                    nameKey = "world.starfall.station.main",
                    entityTypeId = "station_lowtech3",
                    orbit = orbit(310f, 2300f),
                    role = StoryWorldIds.ROLE_RUIN_MAIN_STATION,
                    factionId = Factions.DERELICT,
                ),
                StationSpec(
                    id = StoryWorldIds.STARFALL_STATION_DOCKYARD,
                    nameKey = "world.starfall.station.dockyard",
                    entityTypeId = "station_midline3",
                    orbit = orbit(130f, 6600f),
                    role = StoryWorldIds.ROLE_RUIN_DOCKYARD,
                    factionId = Factions.DERELICT,
                ),
                StationSpec(
                    id = StoryWorldIds.STARFALL_STATION_RESERVED,
                    nameKey = "world.starfall.station.reserved",
                    entityTypeId = "station_lowtech1",
                    orbit = orbit(50f, 9400f),
                    role = StoryWorldIds.ROLE_RUIN_RESERVED,
                    factionId = Factions.DERELICT,
                ),
            ),
            objectives = standardObjectives(
                commId = StoryWorldIds.STARFALL_OBJ_COMM_RELAY,
                sensorId = StoryWorldIds.STARFALL_OBJ_SENSOR_ARRAY,
                navId = StoryWorldIds.STARFALL_OBJ_NAV_BUOY,
                gateId = StoryWorldIds.STARFALL_OBJ_GATE,
                commOrbit = orbit(75f, 4100f),
                sensorOrbit = orbit(285f, 5900f),
                navOrbit = orbit(355f, 7600f),
                gateOrbit = orbit(185f, 12000f),
            ),
            belts = listOf(
                BeltSpec(
                    id = StoryWorldIds.STARFALL_BELT_1,
                    nameKey = "world.starfall.belt_1",
                    asteroidCount = 110,
                    orbitRadius = 3900f,
                    width = 650f,
                    minOrbitDays = 300f,
                    maxOrbitDays = 430f,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------
    // 紫菀遗址星系（docs/story/07「紫菀遗址星系」）
    // ------------------------------------------------------------------

    fun asterSystem(random: Random): SystemSpec {
        val barrenCount = 1 + random.nextInt(2) // 荒芜星球 ×1~2
        val frozenCount = 2 + random.nextInt(3) // 冰封星球 ×2~4（固定极度寒冷 + 黑暗）
        val gasCount = 2 + random.nextInt(3) // 气态巨行星 ×2~4（固定黑暗）

        val planets = mutableListOf<PlanetSpec>()

        for (i in 1..barrenCount) {
            val pick = pick(random, BARREN_PLANET_PICKS)
            planets += PlanetSpec(
                id = "astd_aster_planet_barren_$i",
                nameKey = "world.aster.planet.barren_$i",
                typeId = pick.typeId,
                orbit = orbit(70f + i * 140f, 5000f + i * 600f),
                planetRadius = 65f + random.nextInt(30),
                conditionIds = pick.conditionIds + Conditions.DARK,
            )
        }

        for (i in 1..frozenCount) {
            val pick = pick(random, FROZEN_PLANET_PICKS)
            planets += PlanetSpec(
                id = "astd_aster_planet_frozen_$i",
                nameKey = "world.aster.planet.frozen_$i",
                typeId = pick.typeId,
                orbit = orbit(15f + i * 80f, 8200f + i * 500f),
                planetRadius = 85f + random.nextInt(45),
                conditionIds = listOf(Conditions.VERY_COLD, Conditions.DARK) + pick.conditionIds,
            )
        }

        for (i in 1..gasCount) {
            val pick = pick(random, GAS_GIANT_PICKS)
            planets += PlanetSpec(
                id = "astd_aster_planet_gas_$i",
                nameKey = "world.aster.planet.gas_$i",
                typeId = pick.typeId,
                orbit = orbit(240f + i * 55f, 11600f + i * 1100f),
                planetRadius = 250f + random.nextInt(70),
                conditionIds = listOf(Conditions.DARK) + pick.conditionIds,
            )
        }

        return SystemSpec(
            id = StoryWorldIds.SYSTEM_ASTER,
            nameKey = "world.aster.system_name",
            backgroundTexture = "graphics/backgrounds/background3.jpg",
            star = StarSpec(StoryWorldIds.ASTER_STAR, StarTypes.BLACK_HOLE, 350f, 0f, blackHole = true),
            planets = planets,
            stations = buildList {
                // 引力节点 ×3：等边三角形布局（赏金层识别 astd_gravity_node 触发战斗）。
                // 生涯层以可交互原版空间站实体承载，挂 derelict 仅状况市场。
                val nodeIds = listOf(
                    StoryWorldIds.ASTER_GRAVITY_NODE_1,
                    StoryWorldIds.ASTER_GRAVITY_NODE_2,
                    StoryWorldIds.ASTER_GRAVITY_NODE_3,
                )
                nodeIds.forEachIndexed { index, nodeId ->
                    add(
                        StationSpec(
                            id = nodeId,
                            nameKey = "world.aster.node_${index + 1}",
                            entityTypeId = "station_research",
                            orbit = orbit(index * 120f, 2600f),
                            role = StoryWorldIds.ROLE_GRAVITY_NODE,
                            marketKind = MarketKind.CONDITION_ONLY,
                            factionId = Factions.DERELICT,
                            extraTags = listOf(StoryWorldIds.TAG_GRAVITY_NODE),
                        ),
                    )
                }
                add(
                    StationSpec(
                        id = StoryWorldIds.ASTER_STATION_MAIN,
                        nameKey = "world.aster.station.main",
                        entityTypeId = "station_hightech2",
                        orbit = orbit(200f, 3600f),
                        role = StoryWorldIds.ROLE_RUIN_MAIN_STATION,
                        factionId = Factions.DERELICT,
                    ),
                )
                add(
                    StationSpec(
                        id = StoryWorldIds.ASTER_STATION_GRAVITY_DOCKYARD,
                        nameKey = "world.aster.station.gravity_dockyard",
                        entityTypeId = "station_midline1",
                        orbit = orbit(90f, 4600f),
                        role = StoryWorldIds.ROLE_RUIN_DOCKYARD,
                        factionId = Factions.DERELICT,
                    ),
                )
                // 奇点跃迁器（占位，后续内容扩展）
                add(
                    StationSpec(
                        id = StoryWorldIds.ASTER_STATION_SINGULARITY,
                        nameKey = "world.aster.station.singularity",
                        entityTypeId = "station_hightech1",
                        orbit = orbit(320f, 6800f),
                        role = StoryWorldIds.ROLE_SINGULARITY_DRIVE,
                    ),
                )
                // 预留位（力场防御系统使用）
                add(
                    StationSpec(
                        id = StoryWorldIds.ASTER_STATION_FORCEFIELD_RESERVED,
                        nameKey = "world.aster.station.forcefield_reserved",
                        entityTypeId = "station_lowtech1",
                        orbit = orbit(10f, 7800f),
                        role = StoryWorldIds.ROLE_FORCEFIELD_RESERVED,
                    ),
                )
                // 轨道生活空间站「拾光」：宜居 + 视界动力 + 紫菀科研部遗址
                add(
                    StationSpec(
                        id = StoryWorldIds.ASTER_STATION_SHIGUANG,
                        nameKey = "world.aster.station.shiguang",
                        entityTypeId = "orbital_habitat",
                        orbit = orbit(160f, 9000f),
                        role = StoryWorldIds.ROLE_HABITAT,
                        marketSize = 4,
                        conditionIds = listOf(Conditions.HABITABLE),
                        customConditionIds = listOf(
                            StoryWorldIds.COND_EVENT_HORIZON_POWER,
                            StoryWorldIds.COND_ASTER_RESEARCH_RUINS,
                        ),
                    ),
                )
            },
            objectives = standardObjectives(
                commId = StoryWorldIds.ASTER_OBJ_COMM_RELAY,
                sensorId = StoryWorldIds.ASTER_OBJ_SENSOR_ARRAY,
                navId = StoryWorldIds.ASTER_OBJ_NAV_BUOY,
                gateId = StoryWorldIds.ASTER_OBJ_GATE,
                commOrbit = orbit(45f, 4200f),
                sensorOrbit = orbit(255f, 6000f),
                navOrbit = orbit(135f, 10400f),
                gateOrbit = orbit(330f, 16200f),
            ),
            belts = listOf(
                BeltSpec(
                    id = StoryWorldIds.ASTER_BELT_1,
                    nameKey = "world.aster.belt_1",
                    asteroidCount = 90,
                    orbitRadius = 2000f,
                    width = 500f,
                    minOrbitDays = 160f,
                    maxOrbitDays = 240f,
                ),
                BeltSpec(
                    id = StoryWorldIds.ASTER_BELT_2,
                    nameKey = "world.aster.belt_2",
                    asteroidCount = 130,
                    orbitRadius = 10800f,
                    width = 800f,
                    minOrbitDays = 900f,
                    maxOrbitDays = 1200f,
                ),
            ),
        )
    }

    /** 标准稳定点组合：通讯中继站、传感器阵列、导航浮标、星门各一。 */
    private fun standardObjectives(
        commId: String,
        sensorId: String,
        navId: String,
        gateId: String,
        commOrbit: OrbitSpec,
        sensorOrbit: OrbitSpec,
        navOrbit: OrbitSpec,
        gateOrbit: OrbitSpec,
    ): List<ObjectiveSpec> = listOf(
        ObjectiveSpec(commId, Entities.COMM_RELAY, commOrbit),
        ObjectiveSpec(sensorId, Entities.SENSOR_ARRAY, sensorOrbit),
        ObjectiveSpec(navId, Entities.NAV_BUOY, navOrbit),
        ObjectiveSpec(gateId, Entities.INACTIVE_GATE, gateOrbit),
    )
}
