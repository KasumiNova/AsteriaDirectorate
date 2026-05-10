package cn.kasuminova.astd.campaign

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.fleet.FleetMemberType
import com.fs.starfarer.api.impl.campaign.ids.*
import org.apache.log4j.Logger

/**
 * 仅用于开发测试的战役注入：
 * - 在 devMode 新开档后生成一个测试星系与市场
 * - 在市场的 storage 里放入本模组所有舰船（Standard 变体）与武器
 *
 * 说明：不依赖 Console Commands，尽量做到开箱即测。
 */
object AsteriaTestCampaignBootstrap {

    private const val DONE_KEY = "asteria_test_campaign_bootstrap_done"

    private const val TEST_SYSTEM_NAME = "Asteria Test Range"
    private const val TEST_SYSTEM_ID = "asteria_test_range"

    private const val TEST_STAR_ID = "asteria_test_star"
    private const val TEST_PLANET_ID = "asteria_test_depot"
    private const val TEST_MARKET_ID = "asteria_test_market"

    /** Dev/test-only: increase shield coverage on all injected test ships. */
    private const val TEST_SHIELD_COVERAGE_HULLMOD_ID = "astd_test_shield_coverage"

    private val log: Logger = Global.getLogger(AsteriaTestCampaignBootstrap::class.java)

    /** 入口：仅在 devMode 下执行。 */
    @JvmStatic
    fun runIfEnabled() {
        try {
            if (!Global.getSettings().isDevMode) return
            val sector = Global.getSector() ?: return

            if (sector.persistentData.containsKey(DONE_KEY)) return

            val market = createTestSystemAndMarket(sector)
            if (market != null) {
                fillStorageWithModContent(market)
            }

            sector.persistentData[DONE_KEY] = true
            log.info("[AsteriaTestCampaignBootstrap] Test campaign content injected.")
        } catch (t: Throwable) {
            // 不要让测试注入把整个新开档搞崩
            log.warn("[AsteriaTestCampaignBootstrap] Failed to inject test campaign content: ${t.message}", t)
        }
    }

    private fun createTestSystemAndMarket(sector: SectorAPI): MarketAPI? {
        // 避免重复创建
        for (sys in sector.starSystems) {
            if (TEST_SYSTEM_NAME == sys.name || TEST_SYSTEM_ID == sys.id) {
                // 尝试找回市场
                for (p in sys.planets) {
                    if (p.market != null) return p.market
                }
                return null
            }
        }

        val system = sector.createStarSystem(TEST_SYSTEM_NAME)
        // 放在核心区附近，方便在大地图里快速找到
        system.location.set(1500f, 5500f)
        system.setBackgroundTextureFilename("graphics/backgrounds/background2.jpg")

        val star = system.initStar(TEST_STAR_ID, StarTypes.YELLOW, 450f, 400f)
        val planet = system.addPlanet(TEST_PLANET_ID, star, "测试补给站", Planets.BARREN, 60f, 120f, 2200f, 50f)

        val market = Global.getFactory().createMarket(TEST_MARKET_ID, "Asteria 测试仓库", 5)
        market.factionId = Factions.INDEPENDENT
        market.primaryEntity = planet
        market.tariff.modifyFlat("asteria_test", 0f)

        // 让它像一个正常市场一样可交互
        market.addCondition(Conditions.POPULATION_5)
        market.addIndustry(Industries.POPULATION)
        market.addIndustry(Industries.SPACEPORT)

        market.addSubmarket(Submarkets.SUBMARKET_OPEN)
        market.addSubmarket(Submarkets.SUBMARKET_BLACK)
        market.addSubmarket(Submarkets.GENERIC_MILITARY)
        market.addSubmarket(Submarkets.SUBMARKET_STORAGE)

        planet.market = market

        val econ = sector.economy
        econ.addMarket(market, true)

        system.autogenerateHyperspaceJumpPoints(true, true)

        return market
    }

    private fun fillStorageWithModContent(market: MarketAPI) {
        val storage = market.getSubmarket(Submarkets.SUBMARKET_STORAGE)
        if (storage == null) {
            log.warn("[AsteriaTestCampaignBootstrap] storage submarket missing on test market.")
            return
        }

        val cargo = storage.cargo
        cargo.credits.add(2_000_000f)
        cargo.addSupplies(5_000f)
        cargo.addFuel(5_000f)

        // 1) 给玩家阵营解锁"已知蓝图"（方便 UI/装配等）
        try {
            for (hull in Global.getSettings().allShipHullSpecs) {
                val id = hull.hullId
                if (id != null && id.startsWith("astd_")) {
                    sectorPlayerFaction().addKnownShip(id, false)
                }
            }
            for (w in Global.getSettings().allWeaponSpecs) {
                val id = w.weaponId
                if (id != null && id.startsWith("astd_")) {
                    sectorPlayerFaction().addKnownWeapon(id, false)
                }
            }
        } catch (t: Throwable) {
            // 解锁失败也不影响把实体塞进仓储
            log.warn("[AsteriaTestCampaignBootstrap] Failed to add known ships/weapons: ${t.message}", t)
        }

        // 2) 把本模组所有 Standard 变体的船放入仓储（mothballed ships）
        val variantIds = Global.getSettings().allVariantIds
        var shipCount = 0
        for (vid in variantIds) {
            if (vid == null) continue
            if (!vid.startsWith("astd_")) continue
            if (!vid.endsWith("_Standard")) continue

            try {
                val member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, vid)

                // Add dev-only shield coverage boost so test hulls are easier to fly/inspect.
                try {
                    member.variant?.addPermaMod(TEST_SHIELD_COVERAGE_HULLMOD_ID)
                } catch (_: Throwable) {
                    // If VariantAPI signature changes, simply skip the debug mod.
                }

                cargo.mothballedShips.addFleetMember(member)
                shipCount++
            } catch (_: Throwable) {
                // 某些 variant 可能缺依赖；跳过即可
            }
        }

        // 3) 把本模组所有武器塞一些到仓储
        var weaponCount = 0
        for (spec in Global.getSettings().allWeaponSpecs) {
            val wid = spec.weaponId ?: continue
            if (!wid.startsWith("astd_")) continue

            cargo.addWeapons(wid, 50)
            weaponCount++
        }

        cargo.sort()

        log.info("[AsteriaTestCampaignBootstrap] Filled storage: $shipCount ships, $weaponCount weapons.")
    }

    private fun sectorPlayerFaction() = Global.getSector().playerFaction
}
