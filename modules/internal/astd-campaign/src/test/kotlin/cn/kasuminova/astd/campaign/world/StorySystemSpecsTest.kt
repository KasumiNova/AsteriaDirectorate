package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.testutil.CsvTestUtil
import com.fs.starfarer.api.impl.campaign.ids.Conditions
import com.fs.starfarer.api.impl.campaign.ids.Industries
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 剧情星系生成规格校验：种子确定性、实体清单/计数、轨道参数合法性、
 * id 唯一性、剧情状况注册一致性（对真实 market_conditions.csv）。
 */
class StorySystemSpecsTest {

    private val main = StorySystemSpecs.mainSystemSpec(42L)
    private val starfall = StorySystemSpecs.starfallSystemSpec(42L)
    private val aster = StorySystemSpecs.asterSystemSpec(42L)

    @Test
    fun `同一种子生成规格完全确定`() {
        assertEquals(main, StorySystemSpecs.mainSystemSpec(42L))
        assertEquals(starfall, StorySystemSpecs.starfallSystemSpec(42L))
        assertEquals(aster, StorySystemSpecs.asterSystemSpec(42L))
    }

    @Test
    fun `三个星系的实体与市场 id 全局唯一`() {
        val entityIds = (main.allEntityIds() + starfall.allEntityIds() + aster.allEntityIds())
        assertEquals(entityIds.size, entityIds.toSet().size, "实体 id 重复")
        val marketIds = (main.allMarketIds() + starfall.allMarketIds() + aster.allMarketIds())
        assertEquals(marketIds.size, marketIds.toSet().size, "市场 id 重复")
    }

    @Test
    fun `非 conditionOnly 市场必须声明人口产业`() {
        // Nexerelin 地面战情报初始化对零产业市场越界崩溃（GroundBattleIntel.init 兜底取首个产业），
        // FULL 市场必须至少带人口产业。
        for (spec in listOf(main, starfall, aster)) {
            for (market in spec.planets.mapNotNull { it.market } + spec.entities.mapNotNull { it.market }) {
                if (market.conditionOnly) continue
                assertTrue(
                    market.industryIds.contains(Industries.POPULATION),
                    "${market.marketId} 缺少人口产业",
                )
            }
        }
    }

    @Test
    fun `轨道参数合法且焦点在星系内`() {
        for (spec in listOf(main, starfall, aster)) {
            val focusIds = spec.allEntityIds().toSet()
            for (planet in spec.planets) {
                assertTrue(planet.orbit.focusId in focusIds, "${planet.id} 焦点缺失")
                assertTrue(planet.orbit.radius > spec.starRadius, "${planet.id} 轨道半径须大于恒星半径")
                assertTrue(planet.orbit.radius < 30000f, "${planet.id} 轨道半径越界")
                assertTrue(planet.orbit.periodDays > 0f)
                assertTrue(planet.radius > 0f)
                // 行星轨道不与恒星重叠（含黑洞事件视界 ~1200su 安全余量）
                if (spec.blackHole) {
                    assertTrue(planet.orbit.radius > 1400f, "紫菀行星须位于事件视界之外：${planet.id}")
                }
            }
            for (entity in spec.entities) {
                assertTrue(entity.orbit.focusId in focusIds, "${entity.id} 焦点缺失")
                assertTrue(entity.orbit.radius > 0f)
                assertTrue(entity.orbit.periodDays > 0f)
            }
            for (belt in spec.belts) {
                assertTrue(belt.focusId in focusIds)
                assertTrue(belt.orbitRadius > spec.starRadius)
            }
        }
    }

    @Test
    fun `环恒星轨道间距安全无碰撞`() {
        // 实体碰撞半径契约（原版 data/config/custom_entities.json defaultRadius；
        // astd_reserved_station 见本模组 contents/data/config/custom_entities.json）
        val entityCollisionRadius = mapOf(
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
        // 相邻天体表面最小净空（su）：紧凑布局下仍须保证任何两颗天体不重叠
        val minClearance = 100f

        for (spec in listOf(main, starfall, aster)) {
            // 环恒星轨道体：轨道半径 + 碰撞半径 + id（绕行星的本体轨道不参与）
            val orbiters = mutableListOf<Triple<Float, Float, String>>()
            for (planet in spec.planets.filter { it.orbit.focusId == spec.starId }) {
                orbiters += Triple(planet.orbit.radius, planet.radius, planet.id)
            }
            for (entity in spec.entities.filter { it.orbit.focusId == spec.starId }) {
                val collision = assertNotNull(
                    entityCollisionRadius[entity.entityType],
                    "${entity.id} 实体类型 ${entity.entityType} 缺少碰撞半径契约",
                )
                orbiters += Triple(entity.orbit.radius, collision, entity.id)
            }

            for (i in orbiters.indices) {
                for (j in i + 1 until orbiters.size) {
                    val (r1, c1, id1) = orbiters[i]
                    val (r2, c2, id2) = orbiters[j]
                    val gap = kotlin.math.abs(r1 - r2)
                    if (gap >= c1 + c2 + minClearance) continue
                    // 同环共轨（如三处功能设施）：按角距换算弦长判定
                    val a1 = orbitAngleOf(spec, id1)
                    val a2 = orbitAngleOf(spec, id2)
                    val angleDiff = kotlin.math.abs(a1 - a2) % 360f
                    val chord = 2.0 * r1 * kotlin.math.sin(Math.toRadians((angleDiff / 2f).toDouble()))
                    assertTrue(
                        chord >= c1 + c2 + minClearance,
                        "${spec.systemId} 轨道重叠：$id1($r1) 与 $id2($r2) 间隙 $gap/弦长 $chord 过近",
                    )
                }
            }

            // 小行星带/环带内外缘与任意环恒星天体保持净空
            for (belt in spec.belts.filter { it.focusId == spec.starId }) {
                val inner = belt.orbitRadius - belt.bandWidth / 2f
                val outer = belt.orbitRadius + belt.bandWidth / 2f
                for ((r, c, id) in orbiters) {
                    assertTrue(
                        r + c + minClearance <= inner || r - c - minClearance >= outer,
                        "${spec.systemId} 小行星带与 $id 轨道穿插（带 $inner~$outer，天体 $r）",
                    )
                }
                if (spec.blackHole) {
                    assertTrue(inner > 1400f, "${spec.systemId} 小行星带内缘须位于事件视界之外")
                }
            }
        }
    }

    private fun orbitAngleOf(spec: StorySystemSpecs.SystemSpec, id: String): Float =
        (spec.planets.firstOrNull { it.id == id }?.orbit?.angleDeg
            ?: spec.entities.first { it.id == id }.orbit.angleDeg)

    @Test
    fun `主星系实体清单符合规格`() {
        // 蓝巨星 + 兰台（宜居链 + 菀星行政部遗址 + 恒星镜）+ 洪炉/淬池（固定荒芜特征链）
        assertEquals("star_blue_giant", main.starType)
        val lantai = main.planets.first { it.id == StoryWorldIds.MAIN_PLANET_LANTAI }
        assertEquals(
            listOf(
                Conditions.HABITABLE, Conditions.POLLUTION, Conditions.FARMLAND_BOUNTIFUL,
                Conditions.RARE_ORE_SPARSE, Conditions.ORGANICS_TRACE, Conditions.RUINS_WIDESPREAD,
                StoryWorldIds.CONDITION_WANXING_ADMIN_RUINS,
            ),
            lantai.market?.conditionIds,
        )
        assertTrue(lantai.stellarMirrors >= 1, "兰台需轨道恒星镜")
        for (id in listOf(StoryWorldIds.MAIN_PLANET_HONGLU, StoryWorldIds.MAIN_PLANET_CUICHI)) {
            val planet = main.planets.first { it.id == id }
            assertTrue(
                planet.market!!.conditionIds.containsAll(
                    listOf(
                        Conditions.ORE_ABUNDANT, Conditions.RARE_ORE_ABUNDANT,
                        Conditions.VOLATILES_TRACE, Conditions.VERY_HOT, Conditions.NO_ATMOSPHERE,
                    )
                ),
                "$id 荒芜特征链不完整",
            )
        }
        // 随机荒芜 2~4 + 气态巨 1~2（随机行星无固定命名，nameKey == null）
        val randomBarren = main.planets.count { it.nameKey == null && it.typeId != "gas_giant" && it.typeId != "ice_giant" }
        assertTrue(randomBarren in 2..4, "随机荒芜行星数量 $randomBarren 越界")
        val gasGiants = main.planets.count { it.typeId == "gas_giant" || it.typeId == "ice_giant" }
        assertTrue(gasGiants in 1..2, "气态巨行星数量 $gasGiants 越界")
        // 空间站 ×3 + 稳定点功能设施 ×3 + 休眠星门 + 小行星带
        assertTrue(main.entities.any { it.id == StoryWorldIds.MAIN_STATION_BRANCH && it.market?.size == 4 && it.market.inEconomy })
        assertTrue(main.entities.any { it.id == StoryWorldIds.MAIN_STATION_RESERVE_A && it.market == null })
        assertTrue(main.entities.any { it.id == StoryWorldIds.MAIN_STATION_RESERVE_B && it.market == null })
        assertTrue(main.entities.any { it.entityType == "comm_relay" })
        assertTrue(main.entities.any { it.entityType == "sensor_array" })
        assertTrue(main.entities.any { it.entityType == "nav_buoy" })
        assertTrue(main.entities.any { it.entityType == "inactive_gate" })
        assertEquals(1, main.belts.size)
    }

    @Test
    fun `星坠星系实体清单符合规格`() {
        assertEquals("star_blue_supergiant", starfall.starType)
        val duanyuan = starfall.planets.first { it.id == StoryWorldIds.STARFALL_PLANET_DUANYUAN }
        assertEquals("jungle", duanyuan.typeId)
        assertTrue(duanyuan.market!!.conditionOnly)
        assertEquals("derelict", duanyuan.market!!.factionId)
        assertTrue(StoryWorldIds.CONDITION_STARFALL_ENGINEERING_RUINS in duanyuan.market!!.conditionIds)
        // 空间站 ×3 + 随机荒芜 1~3 + 气态巨 1~2 + 带 ×1 + 设施 ×4
        assertTrue(starfall.entities.any { it.id == StoryWorldIds.STARFALL_STATION_MAIN })
        assertTrue(starfall.entities.any { it.id == StoryWorldIds.STARFALL_STATION_DOCKYARD })
        assertTrue(starfall.entities.any { it.id == StoryWorldIds.STARFALL_STATION_RESERVE && it.market == null })
        val randomBarren = starfall.planets.count { it.nameKey == null && it.typeId != "gas_giant" && it.typeId != "ice_giant" }
        assertTrue(randomBarren in 1..3, "星坠随机荒芜行星数量 $randomBarren 越界")
        assertEquals(1, starfall.belts.size)
    }

    @Test
    fun `紫菀星系实体清单符合规格`() {
        assertEquals("black_hole", aster.starType)
        assertTrue(aster.blackHole)
        // 拾光：FULL size 4 + 宜居 + 两剧情状况
        val shiguang = aster.entities.first { it.id == StoryWorldIds.ASTER_STATION_SHIGUANG }
        val shiguangMarket = shiguang.market!!
        assertEquals(4, shiguangMarket.size)
        assertTrue(shiguangMarket.inEconomy)
        assertTrue(
            shiguangMarket.conditionIds.containsAll(
                listOf(
                    Conditions.HABITABLE,
                    StoryWorldIds.CONDITION_EVENT_HORIZON_POWER,
                    StoryWorldIds.CONDITION_ASTER_RESEARCH_RUINS,
                )
            ),
        )
        // 拾光须在事件视界（~1200su）之外
        assertTrue(shiguang.orbit.radius > 1400f, "拾光须位于事件视界之外")
        // 冰封 2~4（固定极寒+黑暗）、荒芜 1~2、气态巨 2~4（固定黑暗）、带 ×2、占位站 ×2
        val frozen = aster.planets.filter { it.market!!.conditionIds.containsAll(listOf(Conditions.VERY_COLD, Conditions.DARK)) }
        assertTrue(frozen.size in 2..4, "冰封行星数量 ${frozen.size} 越界")
        val darkGas = aster.planets.filter { (it.typeId == "gas_giant" || it.typeId == "ice_giant") && Conditions.DARK in it.market!!.conditionIds }
        assertTrue(darkGas.size in 2..4, "气态巨行星数量 ${darkGas.size} 越界")
        assertEquals(2, aster.belts.size)
    }

    @Test
    fun `剧情状况与 market_conditions csv 注册一致`() {
        val rows = CsvTestUtil.readRowsById(Path.of("contents/data/campaign/market_conditions.csv"))
        val expected = mapOf(
            StoryWorldIds.CONDITION_WANXING_ADMIN_RUINS to
                    "cn.kasuminova.astd.campaign.world.WanxingAdminRuinsCondition",
            StoryWorldIds.CONDITION_STARFALL_ENGINEERING_RUINS to
                    "cn.kasuminova.astd.campaign.world.StarfallEngineeringRuinsCondition",
            StoryWorldIds.CONDITION_EVENT_HORIZON_POWER to
                    "cn.kasuminova.astd.campaign.world.EventHorizonPowerCondition",
            StoryWorldIds.CONDITION_ASTER_RESEARCH_RUINS to
                    "cn.kasuminova.astd.campaign.world.AsterResearchRuinsCondition",
        )
        for ((id, script) in expected) {
            val row = assertNotNull(rows[id], "market_conditions.csv 缺少 $id")
            assertEquals(script, row["script"], "$id 脚本类不一致")
            val order = row.getValue("order").toInt()
            assertTrue(order in 700..703, "$id order=$order 须在 700~703 段")
        }

        // 规格中引用的全部 astd_ 前缀状况必须已注册
        val allConditionIds = (listOf(main, starfall, aster).flatMap { it.planets.mapNotNull { p -> p.market } } +
                listOf(main, starfall, aster).flatMap { s -> s.entities.mapNotNull { it.market } })
            .flatMap { it.conditionIds }
        for (conditionId in allConditionIds.filter { it.startsWith("astd_") }) {
            assertTrue(rows.containsKey(conditionId), "规格引用的状况未注册：$conditionId")
        }
    }
}
