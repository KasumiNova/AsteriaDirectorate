package cn.kasuminova.astd.campaign.world

import com.fs.starfarer.api.impl.campaign.ids.Conditions
import com.fs.starfarer.api.impl.campaign.ids.Entities
import com.fs.starfarer.api.impl.campaign.ids.Factions
import com.fs.starfarer.api.impl.campaign.ids.Industries
import com.fs.starfarer.api.impl.campaign.ids.Planets
import com.fs.starfarer.api.impl.campaign.ids.StarTypes
import kotlin.random.Random

/**
 * 三个剧情星系的生成规格（纯数据，不触碰 Global，可直接单测）。
 *
 * 内容固定、计数类规格（随机荒芜/气态巨行星数量等）由种子派生：
 * 同一种子下规格完全确定。轨道半径/角度/周期均为常数表，合法性由单测约束。
 *
 * 轨道布局为紧凑设计：环恒星轨道整体收缩（各星系最远轨道 ≤ 13500su），
 * 槽位间距须保证相邻天体表面不重叠（单测按碰撞半径 + 安全余量校验）；
 * 紫菀内圈受黑洞事件视界下限约束，收缩比例小于外圈。
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
        /**
         * 产业清单（仅 FULL 市场需要）。
         *
         * 非 conditionOnly 市场必须至少包含人口产业（[Industries.POPULATION]）：
         * 零产业市场会让 Nexerelin 地面战情报初始化（GroundBattleIntel.init 的
         * 兜底取首个产业）越界崩溃，且原版全部可交互市场均至少有人口产业。
         */
        val industryIds: List<String> = emptyList(),
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
        /** 轨道恒星镜数量（兰台的宜居链设施）。 */
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
        /** 幂等标签（生成/迁移据此去重；环带实体无 canonical id，标签是唯一落点）。 */
        val tag: String,
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

    /** 气态巨行星挥发物组（择一）：丰富/富足。 */
    private val GAS_GIANT_VOLATILES_GROUP = listOf(Conditions.VOLATILES_ABUNDANT, Conditions.VOLATILES_PLENTIFUL)

    /** 气态巨行星重力组（择一）：高/低重力。 */
    private val GAS_GIANT_GRAVITY_GROUP = listOf(Conditions.HIGH_GRAVITY, Conditions.LOW_GRAVITY)

    /** 气态巨行星气候组（择一）：极端气候/炎热/极端炎热/稠密大气层。 */
    private val GAS_GIANT_CLIMATE_GROUP = listOf(
        Conditions.EXTREME_WEATHER, Conditions.HOT, Conditions.VERY_HOT, Conditions.DENSE_ATMOSPHERE,
    )

    /** 主星系/星坠气态巨行星追加随机组（挥发物 + 重力 + 气候各择一）。 */
    private val GAS_GIANT_GROUPS_FULL = listOf(GAS_GIANT_VOLATILES_GROUP, GAS_GIANT_GRAVITY_GROUP, GAS_GIANT_CLIMATE_GROUP)

    /** 紫菀气态巨行星追加随机组（仅挥发物择一）。 */
    private val GAS_GIANT_GROUPS_VOLATILES = listOf(GAS_GIANT_VOLATILES_GROUP)

    /**
     * 剧情星系生成器写入行星市场的全部状况 id（迁移的「受管池」：
     * 旧档状况同步时仅在池内做增删，玩家侧/其他模组写入的状况不受影响）。
     */
    val MANAGED_PLANET_CONDITIONS: Set<String> = setOf(
        // 通用随机池
        *BARREN_TRAITS.toTypedArray(), *GAS_GIANT_TRAITS.toTypedArray(), *FROZEN_TRAITS.toTypedArray(),
        // 气态巨行星追加组
        *GAS_GIANT_VOLATILES_GROUP.toTypedArray(), *GAS_GIANT_GRAVITY_GROUP.toTypedArray(),
        *GAS_GIANT_CLIMATE_GROUP.toTypedArray(),
        // 固定状况（含历史版本曾写入、现已被替换的档位）
        Conditions.HABITABLE, Conditions.POLLUTION, Conditions.MILD_CLIMATE,
        Conditions.FARMLAND_BOUNTIFUL, Conditions.FARMLAND_RICH, Conditions.INIMICAL_BIOSPHERE,
        Conditions.ORGANICS_TRACE, Conditions.ORGANICS_ABUNDANT,
        Conditions.ORE_ABUNDANT, Conditions.ORE_RICH, Conditions.ORE_ULTRARICH,
        Conditions.RARE_ORE_ABUNDANT, Conditions.RARE_ORE_RICH, Conditions.RARE_ORE_ULTRARICH,
        Conditions.VOLATILES_TRACE, Conditions.VERY_HOT, Conditions.VERY_COLD, Conditions.DARK,
        Conditions.NO_ATMOSPHERE, Conditions.RUINS_WIDESPREAD,
        StoryWorldIds.CONDITION_WANXING_ADMIN_RUINS, StoryWorldIds.CONDITION_STARFALL_ENGINEERING_RUINS,
        StoryWorldIds.CONDITION_EVENT_HORIZON_POWER, StoryWorldIds.CONDITION_ASTER_RESEARCH_RUINS,
    )

    /**
     * 实体碰撞半径契约（原版 data/config/custom_entities.json defaultRadius；
     * astd_* 类型见本模组 contents/data/config/custom_entities.json）。
     * 布局间距校验与外环小行星带避障共用同一份真相。
     */
    val ENTITY_COLLISION_RADIUS: Map<String, Float> = mapOf(
        "inactive_gate" to 120f,
        "comm_relay" to 75f,
        "sensor_array" to 75f,
        "nav_buoy" to 75f,
        "station_side00" to 50f,
        "station_side02" to 50f,
        "astd_reserved_station" to 45f,
        "astd_station_research_remnant" to 45f,
        "astd_station_mining_remnant" to 45f,
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
        /** 追加随机组：每组择一加入（同一行星可能从多个组各得一项；与既有特征去重）。 */
        randomTraitGroups: List<List<String>> = emptyList(),
        marketFaction: String = Factions.NEUTRAL,
    ): List<PlanetSpec> {
        val count = countRange.random(rnd)
        val slots = radiusSlots.shuffled(rnd).take(count).sorted()
        return slots.mapIndexed { index, slot ->
            val conditions = (fixedTraits + traits.random(rnd) + randomTraitGroups.map { it.random(rnd) }).distinct()
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

    // ─── 剧情主星系 ───

    /**
     * 外环小行星带避障解算：标称半径 = 一环 × ratio，与既有环恒星天体（行星/实体/已解算的环带）
     * 冲突时反复外推至净空（障碍碰撞半径 + 最小净空 + 半带宽）。确定性：同一规格输入输出相同，
     * 新生成与旧档迁移得到同一布局。
     */
    private fun outerBelt(
        star: String,
        baseRadius: Float,
        ratio: Float,
        obstacles: List<Pair<Float, Float>>,
        tag: String,
    ): BeltSpec {
        val halfWidth = 300f
        var radius = baseRadius * ratio
        var adjusted = true
        while (adjusted) {
            adjusted = false
            for ((orbitRadius, collision) in obstacles) {
                val clearance = collision + BELT_MIN_CLEARANCE + halfWidth
                if (kotlin.math.abs(radius - orbitRadius) < clearance) {
                    radius = orbitRadius + clearance
                    adjusted = true
                }
            }
        }
        return BeltSpec(star, radius, halfWidth * 2f, (240f * radius / baseRadius).toInt(), periodForOrbit(radius), tag)
    }

    private const val BELT_MIN_CLEARANCE = 100f

    fun mainSystemSpec(seed: Long): SystemSpec {
        val rnd = Random(seed)
        val star = StoryWorldIds.MAIN_STAR
        val planets = buildList {
                add(
                    PlanetSpec(
                        id = StoryWorldIds.MAIN_PLANET_HONGLU,
                        nameKey = "world.main.planet.honglu",
                        typeId = Planets.BARREN_BOMBARDED,
                        radius = 110f,
                        orbit = OrbitSpec(star, 40f, 2250f, periodForOrbit(2250f)),
                        market = MarketSpec(
                            marketId = StoryWorldIds.MARKET_HONGLU,
                            size = 1,
                            factionId = Factions.NEUTRAL,
                            conditionOnly = true,
                            conditionIds = listOf(
                                Conditions.ORE_ULTRARICH, Conditions.RARE_ORE_ULTRARICH,
                                Conditions.VOLATILES_TRACE, Conditions.VERY_HOT,
                                Conditions.NO_ATMOSPHERE, Conditions.RUINS_WIDESPREAD,
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
                        orbit = OrbitSpec(star, 200f, 3100f, periodForOrbit(3100f)),
                        market = MarketSpec(
                            marketId = StoryWorldIds.MARKET_CUICHI,
                            size = 1,
                            factionId = Factions.NEUTRAL,
                            conditionOnly = true,
                            conditionIds = listOf(
                                Conditions.ORE_RICH, Conditions.RARE_ORE_RICH,
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
                        orbit = OrbitSpec(star, 320f, 4750f, periodForOrbit(4750f)),
                        market = MarketSpec(
                            marketId = StoryWorldIds.MARKET_LANTAI,
                            size = 1,
                            factionId = Factions.DERELICT,
                            conditionOnly = true,
                            conditionIds = listOf(
                                Conditions.HABITABLE, Conditions.POLLUTION,
                                Conditions.MILD_CLIMATE, Conditions.FARMLAND_BOUNTIFUL,
                                Conditions.RARE_ORE_SPARSE, Conditions.ORGANICS_ABUNDANT,
                                Conditions.RUINS_WIDESPREAD,
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
                        listOf(6000f, 7000f, 8250f, 9500f), 60f, 90..130, "barren",
                    )
                )
                // 随机气态巨行星 ×1~2（挥发物/重力/气候各择一追加）
                addAll(
                    randomPlanets(
                        rnd, StoryWorldIds.SYSTEM_MAIN, star, 1..2,
                        GAS_GIANT_TYPES, GAS_GIANT_TRAITS,
                        listOf(11500f, 13500f), 150f, 240..300, "gas",
                        randomTraitGroups = GAS_GIANT_GROUPS_FULL,
                    )
                )
            }
            val entities = buildList {
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
                            industryIds = listOf(Industries.POPULATION, Industries.SPACEPORT),
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
                addAll(objectives(star, 5500f, 10f, StoryWorldIds.MAIN_COMM_RELAY, StoryWorldIds.MAIN_SENSOR_ARRAY, StoryWorldIds.MAIN_NAV_BUOY))
                add(
                    EntitySpec(
                        id = StoryWorldIds.MAIN_GATE,
                        nameKey = null,
                        entityType = Entities.INACTIVE_GATE,
                        orbit = OrbitSpec(star, 70f, 6400f, periodForOrbit(6400f)),
                        factionId = Factions.NEUTRAL,
                    )
                )
            }
            val beltObstacles = buildList<Pair<Float, Float>> {
                for (planet in planets.filter { it.orbit.focusId == star }) {
                    add(planet.orbit.radius to planet.radius)
                }
                for (entity in entities.filter { it.orbit.focusId == star }) {
                    add(
                        entity.orbit.radius to
                                (ENTITY_COLLISION_RADIUS[entity.entityType]
                                    ?: error("实体类型 ${entity.entityType} 缺少碰撞半径契约"))
                    )
                }
            }
            val innerBelt = BeltSpec(star, 3900f, 600f, 240, periodForOrbit(3900f), "astd_belt_main_1")
            // 外环 ×2：标称一环的 1.6x / 2.2x，与既有天体冲突时外推至净空
            val outerBelt2 = outerBelt(
                star, 3900f, 1.6f,
                beltObstacles + (innerBelt.orbitRadius to innerBelt.bandWidth / 2f),
                "astd_belt_main_2",
            )
            val outerBelt3 = outerBelt(
                star, 3900f, 2.2f,
                beltObstacles + (innerBelt.orbitRadius to innerBelt.bandWidth / 2f) +
                        (outerBelt2.orbitRadius to outerBelt2.bandWidth / 2f),
                "astd_belt_main_3",
            )
            return SystemSpec(
            systemId = StoryWorldIds.SYSTEM_MAIN,
            nameKey = "world.system.main.name",
            starId = star,
            starType = StarTypes.BLUE_GIANT,
            starRadius = 750f,
            coronaSize = 600f,
            planets = planets,
            entities = entities,
            belts = listOf(innerBelt, outerBelt2, outerBelt3),
        )
    }

    // ─── 星坠遗址星系 ───

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
                        orbit = OrbitSpec(star, 60f, 3500f, periodForOrbit(3500f)),
                        market = MarketSpec(
                            marketId = StoryWorldIds.MARKET_DUANYUAN,
                            size = 1,
                            factionId = Factions.DERELICT,
                            conditionOnly = true,
                            conditionIds = listOf(
                                Conditions.HABITABLE, Conditions.POLLUTION,
                                Conditions.FARMLAND_RICH, Conditions.INIMICAL_BIOSPHERE,
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
                        listOf(4800f, 5800f, 6800f), 100f, 90..130, "barren",
                    )
                )
                // 随机气态巨行星 ×1~2（挥发物/重力/气候各择一追加）
                addAll(
                    randomPlanets(
                        rnd, StoryWorldIds.SYSTEM_STARFALL, star, 1..2,
                        GAS_GIANT_TYPES, GAS_GIANT_TRAITS,
                        listOf(8600f, 10000f), 200f, 250..310, "gas",
                        randomTraitGroups = GAS_GIANT_GROUPS_FULL,
                    )
                )
            },
            entities = buildList {
                add(
                    EntitySpec(
                        id = StoryWorldIds.STARFALL_STATION_MAIN,
                        nameKey = "world.starfall.station.main",
                        entityType = "astd_station_research_remnant",
                        orbit = OrbitSpec(StoryWorldIds.STARFALL_PLANET_DUANYUAN, 20f, 590f, 24f),
                        factionId = Factions.DERELICT,
                    )
                )
                add(
                    EntitySpec(
                        id = StoryWorldIds.STARFALL_STATION_DOCKYARD,
                        nameKey = "world.starfall.station.dockyard",
                        entityType = "astd_station_mining_remnant",
                        orbit = OrbitSpec(StoryWorldIds.STARFALL_PLANET_DUANYUAN, 200f, 640f, 25f),
                        factionId = Factions.DERELICT,
                    )
                )
                add(
                    EntitySpec(
                        id = StoryWorldIds.STARFALL_STATION_RESERVE,
                        nameKey = "world.starfall.station.reserve",
                        entityType = "astd_reserved_station",
                        orbit = OrbitSpec(star, 290f, 7850f, periodForOrbit(7850f)),
                        factionId = Factions.NEUTRAL,
                    )
                )
                addAll(
                    objectives(
                        star,
                        7300f,
                        45f,
                        StoryWorldIds.STARFALL_COMM_RELAY,
                        StoryWorldIds.STARFALL_SENSOR_ARRAY,
                        StoryWorldIds.STARFALL_NAV_BUOY
                    )
                )
                add(
                    EntitySpec(
                        id = StoryWorldIds.STARFALL_GATE,
                        nameKey = null,
                        entityType = Entities.INACTIVE_GATE,
                        orbit = OrbitSpec(star, 330f, 9300f, periodForOrbit(9300f)),
                        factionId = Factions.NEUTRAL,
                    )
                )
            },
            belts = listOf(BeltSpec(star, 4150f, 600f, 220, periodForOrbit(4150f), "astd_belt_starfall_1")),
        )
    }

    // ─── 紫菀遗址星系 ───

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
                        listOf(4000f, 4350f, 4700f, 5050f), 80f, 80..120, "frozen",
                        fixedTraits = listOf(Conditions.VERY_COLD, Conditions.DARK),
                    )
                )
                // 随机荒芜 ×1~2
                addAll(
                    randomPlanets(
                        rnd, StoryWorldIds.SYSTEM_ASTER, star, 1..2,
                        BARREN_TYPES, BARREN_TRAITS,
                        listOf(5400f, 5760f), 300f, 90..130, "barren",
                    )
                )
                // 随机气态巨行星 ×2~4（固定黑暗；挥发物择一追加）
                addAll(
                    randomPlanets(
                        rnd, StoryWorldIds.SYSTEM_ASTER, star, 2..4,
                        GAS_GIANT_TYPES, GAS_GIANT_TRAITS,
                        listOf(6300f, 7000f, 7700f, 8400f), 140f, 240..300, "gas",
                        fixedTraits = listOf(Conditions.DARK),
                        randomTraitGroups = GAS_GIANT_GROUPS_VOLATILES,
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
                        orbit = OrbitSpec(star, 190f, 1500f, periodForOrbit(1500f)),
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
                            industryIds = listOf(Industries.POPULATION, Industries.SPACEPORT),
                            inEconomy = true,
                        ),
                    )
                )
                add(
                    EntitySpec(
                        id = StoryWorldIds.ASTER_STATION_MAIN,
                        nameKey = "world.aster.station.main",
                        entityType = "astd_station_research_remnant",
                        orbit = OrbitSpec(star, 30f, 2300f, periodForOrbit(2300f)),
                        factionId = Factions.DERELICT,
                    )
                )
                add(
                    EntitySpec(
                        id = StoryWorldIds.ASTER_STATION_DOCKYARD,
                        nameKey = "world.aster.station.dockyard",
                        entityType = "astd_station_mining_remnant",
                        orbit = OrbitSpec(star, 150f, 2550f, periodForOrbit(2550f)),
                        factionId = Factions.DERELICT,
                    )
                )
                add(
                    EntitySpec(
                        id = StoryWorldIds.ASTER_STATION_SINGULARITY,
                        nameKey = "world.aster.station.singularity",
                        entityType = "astd_reserved_station",
                        orbit = OrbitSpec(star, 250f, 2800f, periodForOrbit(2800f)),
                        factionId = Factions.NEUTRAL,
                    )
                )
                add(
                    EntitySpec(
                        id = StoryWorldIds.ASTER_STATION_DEFENSE,
                        nameKey = "world.aster.station.defense",
                        entityType = "astd_reserved_station",
                        orbit = OrbitSpec(star, 340f, 3050f, periodForOrbit(3050f)),
                        factionId = Factions.NEUTRAL,
                    )
                )
                addAll(objectives(star, 8900f, 15f, StoryWorldIds.ASTER_COMM_RELAY, StoryWorldIds.ASTER_SENSOR_ARRAY, StoryWorldIds.ASTER_NAV_BUOY))
                add(
                    EntitySpec(
                        id = StoryWorldIds.ASTER_GATE,
                        nameKey = null,
                        entityType = Entities.INACTIVE_GATE,
                        orbit = OrbitSpec(star, 220f, 9200f, periodForOrbit(9200f)),
                        factionId = Factions.NEUTRAL,
                    )
                )
            },
            belts = listOf(
                BeltSpec(star, 1900f, 400f, 160, periodForOrbit(1900f), "astd_belt_aster_1"),
                BeltSpec(star, 3500f, 500f, 200, periodForOrbit(3500f), "astd_belt_aster_2"),
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
        EntitySpec(
            arrayId,
            null,
            Entities.SENSOR_ARRAY,
            OrbitSpec(starId, angleStart + 120f, orbitRadius, periodForOrbit(orbitRadius)),
            Factions.NEUTRAL
        ),
        EntitySpec(buoyId, null, Entities.NAV_BUOY, OrbitSpec(starId, angleStart + 240f, orbitRadius, periodForOrbit(orbitRadius)), Factions.NEUTRAL),
    )
}
