package cn.kasuminova.astd.campaign.world

import com.fs.starfarer.api.impl.campaign.ids.Conditions
import com.fs.starfarer.api.impl.campaign.ids.Entities
import com.fs.starfarer.api.impl.campaign.ids.Factions
import com.fs.starfarer.api.impl.campaign.ids.Planets
import com.fs.starfarer.api.impl.campaign.ids.StarTypes
import kotlin.random.Random

/**
 * 三个剧情星系的生成规格（纯数据，不触碰 Global，可直接单测）。
 *
 * 内容固定、计数类规格（随机荒芜/气态巨行星数量等）由种子派生：
 * 同一种子下规格完全确定。轨道半径/角度/周期均为常数表，合法性由单测约束。
 *
 * 名称一律使用 i18n key（strings.json 的 world.* 段），生成侧再解析。
 */
object StorySystemSpecs {

    /** 圆形轨道参数。 */
    data class OrbitSpec(
        /** 轨道焦点实体 id（星系内固定实体）。 */
        val focusId: String,
        val angleDeg: Float,
        val radius: Float,
        val periodDays: Float,
    )

    /** 市场规格：conditionOnly=仅承载星球状况（不进经济、不可停靠）。 */
    data class MarketSpec(
        val marketId: String,
        val size: Int,
        val factionId: String,
        val conditionOnly: Boolean,
        /** 追加状况（行星地表状况或剧情特殊状况）。 */
        val conditionIds: List<String> = emptyList(),
        /** 是否注册进经济（仅 FULL 市场）。 */
        val inEconomy: Boolean = false,
    )

    /** 行星规格。 */
    data class PlanetSpec(
        val id: String,
        /** i18n key；null 表示生成侧按「星系名 + 序号字母」命名（随机行星）。 */
        val nameKey: String?,
        val typeId: String,
        val radius: Float,
        val orbit: OrbitSpec,
        val market: MarketSpec?,
        /** 轨道恒星镜数量（03 文档：兰台的宜居链设施）。 */
        val stellarMirrors: Int = 0,
    )

    /** 站/设施类自定义实体规格（空间站、功能设施、休眠星门、占位预留位）。 */
    data class EntitySpec(
        val id: String,
        val nameKey: String?,
        val entityType: String,
        val orbit: OrbitSpec,
        val factionId: String,
        val market: MarketSpec? = null,
        val tags: List<String> = emptyList(),
    )

    /** 小行星带规格。 */
    data class BeltSpec(
        val focusId: String,
        val orbitRadius: Float,
        val bandWidth: Float,
        val asteroidCount: Int,
        val orbitDays: Float,
    )

    /** 星系规格。 */
    data class SystemSpec(
        val systemId: String,
        val nameKey: String,
        val starId: String,
        val starType: String,
        val starRadius: Float,
        val coronaSize: Float,
        /** 黑洞星系：生成事件视界地形（替代日冕）。 */
        val blackHole: Boolean = false,
        val planets: List<PlanetSpec> = emptyList(),
        val entities: List<EntitySpec> = emptyList(),
        val belts: List<BeltSpec> = emptyList(),
    ) {
        /** 星系内全部固定实体 id（恒星 + 行星 + 自定义实体），用于唯一性校验。 */
        fun allEntityIds(): List<String> =
            listOf(starId) + planets.map { it.id } + entities.map { it.id }

        /** 星系内全部市场 id，用于注册一致性校验。 */
        fun allMarketIds(): List<String> =
            (planets.mapNotNull { it.market } + entities.mapNotNull { it.market }).map { it.marketId }

        /**
         * 完整性判定：给定星系当前已落地的实体 id 集合，规格实体是否全部就位。
         *
         * 生成幂等/补齐的判定核心（[StoryWorldGenerator] 状态机使用）：不看恒星单点，
         * 而是整份规格清单对照，半途失败留下的残缺星系能被识别为不完整并整体重放。
         */
        fun isComplete(presentIds: Collection<String>): Boolean =
            allEntityIds().all { it in presentIds }
    }

    // ─── 通用池（随机星球特征） ───

    /** 随机荒芜行星类型池。 */
    private val BARREN_TYPES = listOf(
        Planets.BARREN, Planets.BARREN2, Planets.BARREN3,
        Planets.BARREN_DESERT, Planets.BARREN_CASTIRON, Planets.BARREN_BOMBARDED,
    )

    /** 随机气态巨行星类型池。 */
    private val GAS_GIANT_TYPES = listOf(Planets.GAS_GIANT, Planets.ICE_GIANT)

    /** 随机冰封行星类型池。 */
    private val FROZEN_TYPES = listOf(Planets.FROZEN, Planets.FROZEN1, Planets.FROZEN2, Planets.FROZEN3)

    /** 荒芜行星随机特征池（每个随机荒芜行星取 1 项）。 */
    private val BARREN_TRAITS = listOf(
        Conditions.ORE_SPARSE, Conditions.ORE_MODERATE,
        Conditions.RARE_ORE_SPARSE, Conditions.VOLATILES_TRACE,
        Conditions.NO_ATMOSPHERE, Conditions.THIN_ATMOSPHERE,
        Conditions.HOT, Conditions.COLD, Conditions.POOR_LIGHT,
        Conditions.LOW_GRAVITY, Conditions.HIGH_GRAVITY, Conditions.IRRADIATED,
        Conditions.TECTONIC_ACTIVITY, Conditions.METEOR_IMPACTS,
    )

    /** 气态巨行星随机特征池。 */
    private val GAS_GIANT_TRAITS = listOf(
        Conditions.VOLATILES_TRACE, Conditions.VOLATILES_DIFFUSE,
        Conditions.VOLATILES_ABUNDANT, Conditions.HIGH_GRAVITY,
        Conditions.POOR_LIGHT, Conditions.EXTREME_WEATHER,
    )

    /** 冰封行星随机特征池（在固定「极度寒冷 + 黑暗」之上追加 1 项）。 */
    private val FROZEN_TRAITS = listOf(
        Conditions.VOLATILES_TRACE, Conditions.VOLATILES_DIFFUSE,
        Conditions.ORE_SPARSE, Conditions.ORE_MODERATE,
        Conditions.RARE_ORE_SPARSE, Conditions.ORGANICS_TRACE,
        Conditions.THIN_ATMOSPHERE, Conditions.NO_ATMOSPHERE,
        Conditions.LOW_GRAVITY, Conditions.POOR_LIGHT, Conditions.IRRADIATED,
    )

    private fun periodForOrbit(radius: Float): Float = radius / 45f

    private fun randomPlanets(
        rnd: Random,
        systemId: String,
        starId: String,
        countRange: IntRange,
        types: List<String>,
        traits: List<String>,
        radiusSlots: List<Float>,
        angleStart: Float,
        planetRadiusRange: IntRange,
        marketPrefix: String,
        fixedTraits: List<String> = emptyList(),
        marketFaction: String = Factions.NEUTRAL,
    ): List<PlanetSpec> {
        val count = countRange.random(rnd)
        val slots = radiusSlots.shuffled(rnd).take(count).sorted()
        return slots.mapIndexed { index, slot ->
            val conditions = fixedTraits + traits.random(rnd)
            PlanetSpec(
                id = "${systemId}_rnd_$marketPrefix$index",
                nameKey = null,
                typeId = types.random(rnd),
                radius = planetRadiusRange.random(rnd).toFloat(),
                orbit = OrbitSpec(starId, (angleStart + index * 137f) % 360f, slot, periodForOrbit(slot)),
                market = MarketSpec(
                    marketId = "${systemId}_market_rnd_$marketPrefix$index",
                    size = 1,
                    factionId = marketFaction,
                    conditionOnly = true,
                    conditionIds = conditions,
                ),
            )
        }
    }

    // ─── 剧情主星系（03 文档） ───

    fun mainSystemSpec(seed: Long): SystemSpec {
        val rnd = Random(seed)
        val star = StoryWorldIds.MAIN_STAR
        return SystemSpec(
            systemId = StoryWorldIds.SYSTEM_MAIN,
            nameKey = "world.system.main.name",
            starId = star,
            starType = StarTypes.BLUE_GIANT,
            starRadius = 750f,
            coronaSize = 600f,
            planets = buildList {
                add(
                    PlanetSpec(
                        id = StoryWorldIds.MAIN_PLANET_HONGLU,
                        nameKey = "world.main.planet.honglu",
                        typeId = Planets.BARREN_BOMBARDED,
                        radius = 110f,
                        orbit = OrbitSpec(star, 40f, 4500f, 100f),
                        market = MarketSpec(
                            marketId = StoryWorldIds.MARKET_HONGLU,
                            size = 1,
                            factionId = Factions.NEUTRAL,
                            conditionOnly = true,
                            conditionIds = listOf(
                                Conditions.ORE_ABUNDANT, Conditions.RARE_ORE_ABUNDANT,
                                Conditions.VOLATILES_TRACE, Conditions.VERY_HOT,
                                Conditions.NO_ATMOSPHERE,
                            ),
                        ),
                    )
                )
                add(
                    PlanetSpec(
                        id = StoryWorldIds.MAIN_PLANET_CUICHI,
                        nameKey = "world.main.planet.cuichi",
                        typeId = Planets.BARREN_VENUSLIKE,
                        radius = 105f,
                        orbit = OrbitSpec(star, 200f, 6200f, 138f),
                        market = MarketSpec(
                            marketId = StoryWorldIds.MARKET_CUICHI,
                            size = 1,
                            factionId = Factions.NEUTRAL,
                            conditionOnly = true,
                            conditionIds = listOf(
                                Conditions.ORE_ABUNDANT, Conditions.RARE_ORE_ABUNDANT,
                                Conditions.VOLATILES_TRACE, Conditions.VERY_HOT,
                                Conditions.NO_ATMOSPHERE,
                            ),
                        ),
                    )
                )
                add(
                    PlanetSpec(
                        id = StoryWorldIds.MAIN_PLANET_LANTAI,
                        nameKey = "world.main.planet.lantai",
                        typeId = Planets.PLANET_TERRAN,
                        radius = 150f,
                        orbit = OrbitSpec(star, 320f, 9500f, 211f),
                        market = MarketSpec(
                            marketId = StoryWorldIds.MARKET_LANTAI,
                            size = 1,
                            factionId = Factions.DERELICT,
                            conditionOnly = true,
                            conditionIds = listOf(
                                Conditions.HABITABLE, Conditions.POLLUTION,
                                Conditions.FARMLAND_BOUNTIFUL, Conditions.RARE_ORE_SPARSE,
                                Conditions.ORGANICS_TRACE, Conditions.RUINS_WIDESPREAD,
                                StoryWorldIds.CONDITION_WANXING_ADMIN_RUINS,
                            ),
                        ),
                        stellarMirrors = 2,
                    )
                )
                // 随机荒芜 ×2~4
                addAll(
                    randomPlanets(
                        rnd, StoryWorldIds.SYSTEM_MAIN, star, 2..4,
                        BARREN_TYPES, BARREN_TRAITS,
                        listOf(12000f, 14000f, 16500f, 19000f), 60f, 90..130, "barren",
                    )
                )
                // 随机气态巨行星 ×1~2
                addAll(
                    randomPlanets(
                        rnd, StoryWorldIds.SYSTEM_MAIN, star, 1..2,
                        GAS_GIANT_TYPES, GAS_GIANT_TRAITS,
                        listOf(23000f, 27000f), 150f, 240..300, "gas",
                    )
                )
            },
            entities = buildList {
                add(
                    EntitySpec(
                        id = StoryWorldIds.MAIN_STATION_BRANCH,
                        nameKey = "world.main.station.branch",
                        entityType = "station_side00",
                        orbit = OrbitSpec(StoryWorldIds.MAIN_PLANET_LANTAI, 10f, 500f, 22f),
                        factionId = Factions.INDEPENDENT,
                        market = MarketSpec(
                            marketId = StoryWorldIds.MARKET_MAIN_STATION,
                            size = 4,
                            factionId = Factions.INDEPENDENT,
                            conditionOnly = false,
                            inEconomy = true,
                        ),
                    )
                )
                add(
                    EntitySpec(
                        id = StoryWorldIds.MAIN_STATION_RESERVE_A,
                        nameKey = "world.main.station.reserve_a",
                        entityType = "astd_reserved_station",
                        orbit = OrbitSpec(StoryWorldIds.MAIN_PLANET_HONGLU, 250f, 510f, 23f),
                        factionId = Factions.NEUTRAL,
                    )
                )
                add(
                    EntitySpec(
                        id = StoryWorldIds.MAIN_STATION_RESERVE_B,
                        nameKey = "world.main.station.reserve_b",
                        entityType = "astd_reserved_station",
                        orbit = OrbitSpec(StoryWorldIds.MAIN_PLANET_CUICHI, 100f, 555f, 25f),
                        factionId = Factions.NEUTRAL,
                    )
                )
                addAll(objectives(star, 11000f, 10f, StoryWorldIds.MAIN_COMM_RELAY, StoryWorldIds.MAIN_SENSOR_ARRAY, StoryWorldIds.MAIN_NAV_BUOY))
                add(
                    EntitySpec(
                        id = StoryWorldIds.MAIN_GATE,
                        nameKey = null,
                        entityType = Entities.INACTIVE_GATE,
                        orbit = OrbitSpec(star, 70f, 13500f, 300f),
                        factionId = Factions.NEUTRAL,
                    )
                )
            },
            belts = listOf(BeltSpec(star, 7800f, 600f, 240, 173f)),
        )
    }

    // ─── 星坠遗址星系（07 文档） ───

    fun starfallSystemSpec(seed: Long): SystemSpec {
        val rnd = Random(seed)
        val star = StoryWorldIds.STARFALL_STAR
        return SystemSpec(
            systemId = StoryWorldIds.SYSTEM_STARFALL,
            nameKey = "world.system.starfall.name",
            starId = star,
            starType = StarTypes.BLUE_SUPERGIANT,
            starRadius = 900f,
            coronaSize = 800f,
            planets = buildList {
                add(
                    PlanetSpec(
                        id = StoryWorldIds.STARFALL_PLANET_DUANYUAN,
                        nameKey = "world.starfall.planet.duanyuan",
                        typeId = Conditions.JUNGLE,
                        radius = 140f,
                        orbit = OrbitSpec(star, 60f, 7000f, 156f),
                        market = MarketSpec(
                            marketId = StoryWorldIds.MARKET_DUANYUAN,
                            size = 1,
                            factionId = Factions.DERELICT,
                            conditionOnly = true,
                            conditionIds = listOf(
                                Conditions.HABITABLE, Conditions.POLLUTION,
                                Conditions.ORE_RICH, Conditions.RARE_ORE_RICH,
                                Conditions.HOT, Conditions.RUINS_WIDESPREAD,
                                StoryWorldIds.CONDITION_STARFALL_ENGINEERING_RUINS,
                            ),
                        ),
                    )
                )
                // 随机荒芜 ×1~3
                addAll(
                    randomPlanets(
                        rnd, StoryWorldIds.SYSTEM_STARFALL, star, 1..3,
                        BARREN_TYPES, BARREN_TRAITS,
                        listOf(9500f, 11500f, 13500f), 100f, 90..130, "barren",
                    )
                )
                // 随机气态巨行星 ×1~2
                addAll(
                    randomPlanets(
                        rnd, StoryWorldIds.SYSTEM_STARFALL, star, 1..2,
                        GAS_GIANT_TYPES, GAS_GIANT_TRAITS,
                        listOf(17000f, 20000f), 200f, 250..310, "gas",
                    )
                )
            },
            entities = buildList {
                add(
                    EntitySpec(
                        id = StoryWorldIds.STARFALL_STATION_MAIN,
                        nameKey = "world.starfall.station.main",
                        entityType = Entities.STATION_RESEARCH_REMNANT,
                        orbit = OrbitSpec(StoryWorldIds.STARFALL_PLANET_DUANYUAN, 20f, 590f, 24f),
                        factionId = Factions.DERELICT,
                    )
                )
                add(
                    EntitySpec(
                        id = StoryWorldIds.STARFALL_STATION_DOCKYARD,
                        nameKey = "world.starfall.station.dockyard",
                        entityType = Entities.STATION_MINING_REMNANT,
                        orbit = OrbitSpec(StoryWorldIds.STARFALL_PLANET_DUANYUAN, 200f, 640f, 25f),
                        factionId = Factions.DERELICT,
                    )
                )
                add(
                    EntitySpec(
                        id = StoryWorldIds.STARFALL_STATION_RESERVE,
                        nameKey = "world.starfall.station.reserve",
                        entityType = "astd_reserved_station",
                        orbit = OrbitSpec(star, 290f, 15500f, 344f),
                        factionId = Factions.NEUTRAL,
                    )
                )
                addAll(objectives(star, 15000f, 45f, StoryWorldIds.STARFALL_COMM_RELAY, StoryWorldIds.STARFALL_SENSOR_ARRAY, StoryWorldIds.STARFALL_NAV_BUOY))
                add(
                    EntitySpec(
                        id = StoryWorldIds.STARFALL_GATE,
                        nameKey = null,
                        entityType = Entities.INACTIVE_GATE,
                        orbit = OrbitSpec(star, 330f, 18000f, 400f),
                        factionId = Factions.NEUTRAL,
                    )
                )
            },
            belts = listOf(BeltSpec(star, 8300f, 600f, 220, 184f)),
        )
    }

    // ─── 紫菀遗址星系（07 文档） ───

    fun asterSystemSpec(seed: Long): SystemSpec {
        val rnd = Random(seed)
        val star = StoryWorldIds.ASTER_STAR
        return SystemSpec(
            systemId = StoryWorldIds.SYSTEM_ASTER,
            nameKey = "world.system.aster.name",
            starId = star,
            starType = StarTypes.BLACK_HOLE,
            starRadius = 175f,
            coronaSize = 0f,
            blackHole = true,
            planets = buildList {
                // 随机冰封 ×2~4（固定极度寒冷 + 黑暗）
                addAll(
                    randomPlanets(
                        rnd, StoryWorldIds.SYSTEM_ASTER, star, 2..4,
                        FROZEN_TYPES, FROZEN_TRAITS,
                        listOf(6000f, 6800f, 7600f, 8400f), 80f, 80..120, "frozen",
                        fixedTraits = listOf(Conditions.VERY_COLD, Conditions.DARK),
                    )
                )
                // 随机荒芜 ×1~2
                addAll(
                    randomPlanets(
                        rnd, StoryWorldIds.SYSTEM_ASTER, star, 1..2,
                        BARREN_TYPES, BARREN_TRAITS,
                        listOf(9200f, 10000f), 300f, 90..130, "barren",
                    )
                )
                // 随机气态巨行星 ×2~4（固定黑暗）
                addAll(
                    randomPlanets(
                        rnd, StoryWorldIds.SYSTEM_ASTER, star, 2..4,
                        GAS_GIANT_TYPES, GAS_GIANT_TRAITS,
                        listOf(11000f, 12500f, 14000f, 15500f), 140f, 240..300, "gas",
                        fixedTraits = listOf(Conditions.DARK),
                    )
                )
            },
            entities = buildList {
                // 轨道生活空间站「拾光」：宜居 + 视界动力 + 紫菀科研部遗址
                add(
                    EntitySpec(
                        id = StoryWorldIds.ASTER_STATION_SHIGUANG,
                        nameKey = "world.aster.station.shiguang",
                        entityType = "station_side02",
                        orbit = OrbitSpec(star, 190f, 2400f, 53f),
                        factionId = Factions.DERELICT,
                        market = MarketSpec(
                            marketId = StoryWorldIds.MARKET_SHIGUANG,
                            size = 4,
                            factionId = Factions.DERELICT,
                            conditionOnly = false,
                            conditionIds = listOf(
                                Conditions.HABITABLE,
                                StoryWorldIds.CONDITION_EVENT_HORIZON_POWER,
                                StoryWorldIds.CONDITION_ASTER_RESEARCH_RUINS,
                            ),
                            inEconomy = true,
                        ),
                    )
                )
                // 引力节点 ×3：等边三角布局
                StoryWorldIds.ASTER_NODE_IDS.forEachIndexed { index, nodeId ->
                    add(
                        EntitySpec(
                            id = nodeId,
                            nameKey = "world.aster.node.${index + 1}",
                            entityType = Entities.STATION_RESEARCH_REMNANT,
                            orbit = OrbitSpec(star, 90f + index * 120f, 3200f, 71f),
                            factionId = Factions.REMNANTS,
                            market = MarketSpec(
                                marketId = listOf(StoryWorldIds.MARKET_NODE_1, StoryWorldIds.MARKET_NODE_2, StoryWorldIds.MARKET_NODE_3)[index],
                                size = 1,
                                factionId = Factions.REMNANTS,
                                conditionOnly = true,
                            ),
                            tags = listOf(StoryWorldIds.TAG_GRAVITY_NODE),
                        )
                    )
                }
                // 核心数据舱（节点阵地内侧，未拔全节点前交互被拒）
                add(
                    EntitySpec(
                        id = StoryWorldIds.ASTER_CORE_VAULT,
                        nameKey = "world.aster.core_vault",
                        entityType = Entities.STATION_RESEARCH_REMNANT,
                        orbit = OrbitSpec(star, 270f, 1800f, 40f),
                        factionId = Factions.REMNANTS,
                    )
                )
                add(
                    EntitySpec(
                        id = StoryWorldIds.ASTER_STATION_MAIN,
                        nameKey = "world.aster.station.main",
                        entityType = Entities.STATION_RESEARCH_REMNANT,
                        orbit = OrbitSpec(star, 30f, 4200f, 93f),
                        factionId = Factions.DERELICT,
                    )
                )
                add(
                    EntitySpec(
                        id = StoryWorldIds.ASTER_STATION_DOCKYARD,
                        nameKey = "world.aster.station.dockyard",
                        entityType = Entities.STATION_MINING_REMNANT,
                        orbit = OrbitSpec(star, 150f, 4600f, 102f),
                        factionId = Factions.DERELICT,
                    )
                )
                add(
                    EntitySpec(
                        id = StoryWorldIds.ASTER_STATION_SINGULARITY,
                        nameKey = "world.aster.station.singularity",
                        entityType = "astd_reserved_station",
                        orbit = OrbitSpec(star, 250f, 5000f, 111f),
                        factionId = Factions.NEUTRAL,
                    )
                )
                add(
                    EntitySpec(
                        id = StoryWorldIds.ASTER_STATION_DEFENSE,
                        nameKey = "world.aster.station.defense",
                        entityType = "astd_reserved_station",
                        orbit = OrbitSpec(star, 340f, 5400f, 120f),
                        factionId = Factions.NEUTRAL,
                    )
                )
                addAll(objectives(star, 17000f, 15f, StoryWorldIds.ASTER_COMM_RELAY, StoryWorldIds.ASTER_SENSOR_ARRAY, StoryWorldIds.ASTER_NAV_BUOY))
                add(
                    EntitySpec(
                        id = StoryWorldIds.ASTER_GATE,
                        nameKey = null,
                        entityType = Entities.INACTIVE_GATE,
                        orbit = OrbitSpec(star, 220f, 19000f, 436f),
                        factionId = Factions.NEUTRAL,
                    )
                )
            },
            belts = listOf(
                BeltSpec(star, 3700f, 400f, 160, 82f),
                BeltSpec(star, 5700f, 500f, 200, 127f),
            ),
        )
    }

    /** 三处功能设施（通讯中继/传感器阵列/导航浮标），角度依次错开 120°。 */
    private fun objectives(
        starId: String,
        orbitRadius: Float,
        angleStart: Float,
        relayId: String,
        arrayId: String,
        buoyId: String,
    ): List<EntitySpec> = listOf(
        EntitySpec(relayId, null, Entities.COMM_RELAY, OrbitSpec(starId, angleStart, orbitRadius, periodForOrbit(orbitRadius)), Factions.NEUTRAL),
        EntitySpec(arrayId, null, Entities.SENSOR_ARRAY, OrbitSpec(starId, angleStart + 120f, orbitRadius, periodForOrbit(orbitRadius)), Factions.NEUTRAL),
        EntitySpec(buoyId, null, Entities.NAV_BUOY, OrbitSpec(starId, angleStart + 240f, orbitRadius, periodForOrbit(orbitRadius)), Factions.NEUTRAL),
    )
}
