package cn.kasuminova.astd.campaign

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CargoAPI
import com.fs.starfarer.api.campaign.SpecialItemData
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.campaign.econ.EconomyAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.campaign.listeners.EconomyTickListener
import com.fs.starfarer.api.fleet.FleetMemberType
import com.fs.starfarer.api.impl.campaign.ids.*
import com.fs.starfarer.api.impl.campaign.submarkets.LocalResourcesSubmarketPlugin
import com.fs.starfarer.api.impl.campaign.submarkets.StoragePlugin
import org.apache.log4j.Logger

/**
 * 仅用于开发测试的战役注入：
 * - 在 devMode 新开档后生成一个测试星系与市场
 * - 在市场的 storage 里放入当前启用模组环境中可正常获取的舰船、武器与货物
 * - 将玩家舰队传送到测试星系补给站附近
 *
 * 说明：不依赖 Console Commands，尽量做到开箱即测。
 */
object AsteriaTestCampaignBootstrap {

    private const val TEST_SYSTEM_NAME = "Asteria Test Range"
    private const val TEST_SYSTEM_ID = "asteria_test_range"

    private const val TEST_STAR_ID = "asteria_test_star"
    private const val TEST_PLANET_ID = "asteria_test_depot"
    private const val TEST_MARKET_ID = "market_asteria_test_depot"
    private const val PLAYER_FLEET_OFFSET = 450f

    /** Dev/test-only: increase shield coverage on all injected test ships. */
    private const val TEST_SHIELD_COVERAGE_HULLMOD_ID = "astd_test_shield_coverage"
    private const val STORAGE_ACCEPTANCE_PROPERTY = "astd.devStorageAcceptance"
    private const val MAX_SKIPPED_VARIANT_LOGS = 40
    private const val TELEPORT_RETRY_INTERVAL_SECONDS = 0.25f
    private const val TELEPORT_RETRY_MAX_ATTEMPTS = 240

    private val log: Logger = Global.getLogger(AsteriaTestCampaignBootstrap::class.java)

    /** 入口：仅在 devMode 下执行。 */
    @JvmStatic
    fun runIfEnabled() {
        try {
            if (!Global.getSettings().isDevMode) return
            val sector = Global.getSector() ?: return
            val persistentData = sector.persistentData

            val testTarget = createTestSystemAndMarket(sector)
            val market = testTarget?.market
            if (market != null) {
                val storageHasPayload = hasDevStoragePayload(market)
                if (AsteriaTestCampaignBootstrapState.shouldAcceptExistingContent(persistentData, storageHasPayload)) {
                    AsteriaTestCampaignBootstrapState.markContentDone(persistentData)
                    log.info("[AsteriaTestCampaignBootstrap] Existing test storage content verified.")
                } else if (AsteriaTestCampaignBootstrapState.shouldFillContent(persistentData, storageHasPayload)) {
                    if (fillStorageWithModContent(market)) {
                        AsteriaTestCampaignBootstrapState.markContentDone(persistentData)
                        log.info("[AsteriaTestCampaignBootstrap] Test campaign content injected.")
                    }
                }
            }
            if (testTarget != null && AsteriaTestCampaignBootstrapState.shouldAttemptTeleport(persistentData)) {
                attemptTeleport(sector, testTarget.planet, markDoneOnSuccess = false, queueOnFailure = false)
            }
        } catch (t: Throwable) {
            // 不要让测试注入把整个新开档搞崩
            log.warn("[AsteriaTestCampaignBootstrap] Failed to inject test campaign content: ${t.message}", t)
        }
    }

    /** 新开档时间推进完成后再做一次最终传送，避免原版/dev 起始流程把玩家放回超空间。 */
    @JvmStatic
    fun finalizeNewGameTeleportIfEnabled() {
        try {
            if (!Global.getSettings().isDevMode) return
            val sector = Global.getSector() ?: return
            val testTarget = createTestSystemAndMarket(sector) ?: return

            if (
                AsteriaTestCampaignBootstrapState.shouldAttemptTeleport(sector.persistentData) ||
                !isPlayerAtTestPlanet(sector, testTarget.planet)
            ) {
                attemptTeleport(sector, testTarget.planet, markDoneOnSuccess = true, queueOnFailure = true)
            }
        } catch (t: Throwable) {
            log.warn("[AsteriaTestCampaignBootstrap] Failed to finalize test teleport: ${t.message}", t)
        }
    }

    /** 入口：读档后只恢复已经创建过的测试星系传送，不给普通 dev 存档注入新仓库。 */
    @JvmStatic
    fun resumePendingTeleportIfEnabled() {
        try {
            if (!Global.getSettings().isDevMode) return
            val sector = Global.getSector() ?: return
            if (!AsteriaTestCampaignBootstrapState.shouldAttemptTeleport(sector.persistentData)) return

            val testTarget = findTestTarget(sector) ?: return
            attemptTeleport(sector, testTarget.planet, markDoneOnSuccess = true, queueOnFailure = true)
        } catch (t: Throwable) {
            log.warn("[AsteriaTestCampaignBootstrap] Failed to resume pending test teleport: ${t.message}", t)
        }
    }

    /** 入口：普通读档时修复已经存在的测试仓库，尤其是旧版本塞入的危险特殊物品。 */
    @JvmStatic
    fun repairExistingTestStorageIfEnabled() {
        try {
            val sector = Global.getSector() ?: return
            val testTarget = findTestTarget(sector) ?: return
            val storageHasPayload = hasDevStoragePayload(testTarget.market)
            if (!storageHasPayload && fillStorageWithModContent(testTarget.market)) {
                AsteriaTestCampaignBootstrapState.markContentDone(sector.persistentData)
                log.info("[AsteriaTestCampaignBootstrap] Existing empty test storage refilled.")
            }
        } catch (t: Throwable) {
            log.warn("[AsteriaTestCampaignBootstrap] Failed to repair existing test storage: ${t.message}", t)
        }
    }

    /** Explicit campaign-load acceptance hook for dev storage smoke tests. */
    @JvmStatic
    fun runStorageAcceptanceIfRequested() {
        if (!java.lang.Boolean.getBoolean(STORAGE_ACCEPTANCE_PROPERTY)) return
        val sector = Global.getSector()
            ?: throw IllegalStateException("[AsteriaTestCampaignBootstrap] Dev storage acceptance failed: sector is not loaded.")
        val testTarget = findTestTarget(sector)
            ?: throw IllegalStateException("[AsteriaTestCampaignBootstrap] Dev storage acceptance failed: test depot is missing.")

        validateTestStorageAcceptance(sector, testTarget.planet, testTarget.market)
    }

    private data class TestTarget(
        val planet: SectorEntityToken,
        val market: MarketAPI,
    )

    private fun createTestSystemAndMarket(sector: SectorAPI): TestTarget? {
        findTestPlanet(sector)?.let { planet ->
            return TestTarget(planet, ensureTestMarket(sector, planet))
        }

        val system = sector.createStarSystem(TEST_SYSTEM_NAME)
        // 放在核心区附近，方便在大地图里快速找到
        system.location.set(1500f, 5500f)
        system.setBackgroundTextureFilename("graphics/backgrounds/background2.jpg")

        val star = system.initStar(TEST_STAR_ID, StarTypes.YELLOW, 450f, 400f)
        val planet = system.addPlanet(TEST_PLANET_ID, star, "测试补给站", Planets.BARREN, 60f, 120f, 2200f, 50f)

        val market = ensureTestMarket(sector, planet)

        system.autogenerateHyperspaceJumpPoints(true, true)

        return TestTarget(planet, market)
    }

    private fun ensureTestMarket(sector: SectorAPI, planet: SectorEntityToken): MarketAPI {
        val market = selectCanonicalTestMarket(sector, planet)

        market.setName("测试补给站")
        market.size = 5
        market.factionId = Factions.PLAYER
        market.setPlayerOwned(true)
        market.admin = sector.playerPerson
        market.setSurveyLevel(MarketAPI.SurveyLevel.FULL)
        market.setPlanetConditionMarketOnly(false)
        market.setHidden(false)
        market.primaryEntity = planet
        market.tariff.modifyFlat("asteria_test", 0f)
        market.memoryWithoutUpdate["\$core_noDeciv"] = true
        planet.setFaction(Factions.PLAYER)

        // 让它像一个正常市场一样可交互
        if (!market.hasCondition(Conditions.POPULATION_5)) {
            market.addCondition(Conditions.POPULATION_5)
        }
        if (!market.hasIndustry(Industries.POPULATION)) {
            market.addIndustry(Industries.POPULATION)
        }
        if (!market.hasIndustry(Industries.SPACEPORT)) {
            market.addIndustry(Industries.SPACEPORT)
        }
        market.setHasSpaceport(true)
        market.setEconGroup(TEST_MARKET_ID)
        for (cond in market.conditions) {
            cond.setSurveyed(true)
        }

        removeDevLocalResourcesSubmarket(market)
        ensureSubmarket(market, Submarkets.SUBMARKET_OPEN)
        ensureSubmarket(market, Submarkets.SUBMARKET_BLACK)
        ensureSubmarket(market, Submarkets.GENERIC_MILITARY)
        ensureSubmarket(market, Submarkets.SUBMARKET_STORAGE)
        unlockStorage(market)
        migrateDuplicateTestStorageCargo(sector, market)
        removeDevLocalResourcesListeners(sector)

        planet.market = market
        sector.playerFaction.production.setGatheringPoint(market)
        removeDuplicateTestMarketsFromEconomy(sector, market)

        if (!isRegisteredInEconomy(sector, market)) {
            sector.economy.addMarket(market, true)
        }
        removeDuplicateTestSystems(sector, planet)
        logTestMarketState(sector, planet, market, "bound")

        return market
    }

    private fun selectCanonicalTestMarket(sector: SectorAPI, planet: SectorEntityToken): MarketAPI {
        val planetMarket = planet.market?.takeIf { it.id == TEST_MARKET_ID }
        val economyMarket = sector.economy.getMarket(TEST_MARKET_ID)
        val productionMarket = sector.playerFaction.production.gatheringPoint?.takeIf { it.id == TEST_MARKET_ID }

        return when {
            planetMarket != null -> planetMarket
            economyMarket?.primaryEntity === planet -> economyMarket
            productionMarket?.primaryEntity === planet -> productionMarket
            economyMarket != null -> economyMarket
            productionMarket != null -> productionMarket
            else -> sector.economy.marketsCopy.firstOrNull { it.id == TEST_MARKET_ID }
                ?: Global.getFactory().createMarket(TEST_MARKET_ID, "测试补给站", 5)
        }
    }

    private fun removeDuplicateTestMarketsFromEconomy(sector: SectorAPI, canonical: MarketAPI) {
        var removed = 0
        for (market in sector.economy.marketsCopy) {
            if (market === canonical || market.id != TEST_MARKET_ID) continue
            sector.economy.removeMarket(market)
            removed++
        }
        if (removed > 0) {
            log.info("[AsteriaTestCampaignBootstrap] Removed $removed duplicate test market references from economy.")
        }
    }

    private fun isRegisteredInEconomy(sector: SectorAPI, market: MarketAPI): Boolean {
        return sector.economy.marketsCopy.any { it === market }
    }

    private fun migrateDuplicateTestStorageCargo(sector: SectorAPI, canonical: MarketAPI) {
        val canonicalCargo = canonical.getSubmarket(Submarkets.SUBMARKET_STORAGE)?.cargo ?: return
        var mergedMarkets = 0
        var mergedStacks = 0
        var mergedShips = 0

        for (duplicate in findDuplicateTestMarkets(sector, canonical)) {
            val duplicateCargo = duplicate.getSubmarket(Submarkets.SUBMARKET_STORAGE)?.cargoNullOk ?: continue
            val stackCount = duplicateCargo.stacksCopy.size
            val shipCount = duplicateCargo.mothballedShips?.membersListCopy?.size ?: 0
            if (stackCount <= 0 && shipCount <= 0) continue

            canonicalCargo.addAll(duplicateCargo, true)
            duplicateCargo.clear()
            duplicateCargo.mothballedShips?.clear()
            duplicateCargo.removeEmptyStacks()

            mergedMarkets++
            mergedStacks += stackCount
            mergedShips += shipCount
        }

        if (mergedMarkets > 0) {
            canonicalCargo.sort()
            log.info(
                "[AsteriaTestCampaignBootstrap] Migrated duplicate test storage cargo: " +
                    "$mergedMarkets markets, $mergedStacks stacks, $mergedShips ships."
            )
        }
    }

    private fun findDuplicateTestMarkets(sector: SectorAPI, canonical: MarketAPI): Set<MarketAPI> {
        val candidates = linkedSetOf<MarketAPI>()

        for (market in sector.economy.marketsCopy) {
            if (market.id == TEST_MARKET_ID) {
                candidates += market
            }
        }
        sector.playerFaction.production.gatheringPoint
            ?.takeIf { it.id == TEST_MARKET_ID }
            ?.let { candidates += it }
        for (system in sector.starSystems) {
            for (planet in system.planets) {
                planet.market
                    ?.takeIf { it.id == TEST_MARKET_ID }
                    ?.let { candidates += it }
            }
        }
        for (listener in sector.listenerManager.getListeners(EconomyTickListener::class.java)) {
            (listener as? LocalResourcesSubmarketPlugin)
                ?.market
                ?.takeIf { it.id == TEST_MARKET_ID }
                ?.let { candidates += it }
        }
        for (listener in sector.economy.updateListeners) {
            (listener as? LocalResourcesSubmarketPlugin)
                ?.market
                ?.takeIf { it.id == TEST_MARKET_ID }
                ?.let { candidates += it }
        }

        candidates.remove(canonical)
        return candidates
    }

    private fun removeDevLocalResourcesListeners(sector: SectorAPI) {
        val listeners = linkedSetOf<LocalResourcesSubmarketPlugin>()
        for (listener in sector.listenerManager.getListeners(EconomyTickListener::class.java)) {
            (listener as? LocalResourcesSubmarketPlugin)
                ?.takeIf { it.market?.id == TEST_MARKET_ID }
                ?.let { listeners += it }
        }
        for (listener in sector.economy.updateListeners) {
            (listener as? LocalResourcesSubmarketPlugin)
                ?.takeIf { it.market?.id == TEST_MARKET_ID }
                ?.let { listeners += it }
        }

        for (listener in listeners) {
            sector.listenerManager.removeListener(listener)
            sector.economy.removeUpdateListener(listener as EconomyAPI.EconomyUpdateListener)
        }
        if (listeners.isNotEmpty()) {
            log.info("[AsteriaTestCampaignBootstrap] Removed ${listeners.size} dev local_resources listeners.")
        }
    }

    private fun devStoragePayloadScore(market: MarketAPI?): Int {
        val cargo = market?.getSubmarket(Submarkets.SUBMARKET_STORAGE)?.cargoNullOk ?: return 0
        return cargo.stacksCopy.size + cargo.mothballedShips.membersListCopy.size
    }

    private fun logTestMarketState(
        sector: SectorAPI,
        planet: SectorEntityToken,
        market: MarketAPI,
        phase: String,
    ) {
        val economyMarket = sector.economy.getMarket(TEST_MARKET_ID)
        val productionMarket = sector.playerFaction.production.gatheringPoint
        val cargo = market.getSubmarket(Submarkets.SUBMARKET_STORAGE)?.cargoNullOk
        val stackCount = cargo?.stacksCopy?.size ?: 0
        val shipCount = cargo?.mothballedShips?.membersListCopy?.size ?: 0
        log.info(
            "[AsteriaTestCampaignBootstrap] Test market $phase: " +
                "market=${System.identityHashCode(market)}, " +
                "planetMarketSame=${planet.market === market}, " +
                "economyMarketSame=${economyMarket === market}, " +
                "productionSame=${productionMarket === market}, " +
                "inEconomy=${market.isInEconomy}, " +
                "storageStacks=$stackCount, storageShips=$shipCount."
            )
    }

    private fun validateTestStorageAcceptance(
        sector: SectorAPI,
        planet: SectorEntityToken,
        market: MarketAPI,
    ) {
        val storage = market.getSubmarket(Submarkets.SUBMARKET_STORAGE)
            ?: throw IllegalStateException("[AsteriaTestCampaignBootstrap] Dev storage acceptance failed: storage submarket is missing.")
        val cargo = storage.cargoNullOk
            ?: throw IllegalStateException("[AsteriaTestCampaignBootstrap] Dev storage acceptance failed: storage cargo is not initialized.")
        val storageStacks = cargo.stacksCopy.size
        val storageShips = cargo.mothballedShips?.membersListCopy?.size ?: 0

        if (planet.market !== market) {
            throw IllegalStateException("[AsteriaTestCampaignBootstrap] Dev storage acceptance failed: planet is not bound to the canonical market.")
        }
        if (sector.economy.getMarket(TEST_MARKET_ID) !== market) {
            throw IllegalStateException("[AsteriaTestCampaignBootstrap] Dev storage acceptance failed: economy market is not canonical.")
        }
        if (sector.playerFaction.production.gatheringPoint !== market) {
            throw IllegalStateException("[AsteriaTestCampaignBootstrap] Dev storage acceptance failed: production gathering point is not canonical.")
        }
        if (storageStacks <= 0) {
            throw IllegalStateException("[AsteriaTestCampaignBootstrap] Dev storage acceptance failed: storageStacks <= 0.")
        }
        if (storageShips <= 0) {
            throw IllegalStateException("[AsteriaTestCampaignBootstrap] Dev storage acceptance failed: storageShips <= 0.")
        }
        if (findDuplicateTestMarkets(sector, market).isNotEmpty()) {
            throw IllegalStateException("[AsteriaTestCampaignBootstrap] Dev storage acceptance failed: duplicate test markets remain.")
        }

        log.info(
            "[AsteriaTestCampaignBootstrap] Dev storage acceptance passed: " +
                "storageStacks=$storageStacks, storageShips=$storageShips."
        )
    }

    private fun ensureSubmarket(market: MarketAPI, submarketId: String) {
        if (!market.hasSubmarket(submarketId)) {
            market.addSubmarket(submarketId)
        }
    }

    private fun removeDevLocalResourcesSubmarket(market: MarketAPI) {
        if (market.hasSubmarket(Submarkets.LOCAL_RESOURCES)) {
            market.removeSubmarket(Submarkets.LOCAL_RESOURCES)
        }
    }

    private fun unlockStorage(market: MarketAPI) {
        (market.getSubmarket(Submarkets.SUBMARKET_STORAGE)?.plugin as? StoragePlugin)?.setPlayerPaidToUnlock(true)
    }

    private fun findTestPlanet(sector: SectorAPI): SectorEntityToken? {
        val candidates = linkedSetOf<SectorEntityToken>()
        sector.getEntityById(TEST_PLANET_ID)?.let { candidates += it }
        for (sys in sector.starSystems) {
            sys.getEntityById(TEST_PLANET_ID)?.let { candidates += it }
            for (p in sys.planets) {
                if (p.id == TEST_PLANET_ID || p.market?.id == TEST_MARKET_ID) {
                    candidates += p
                }
            }
        }
        return candidates.maxWithOrNull(
            compareBy<SectorEntityToken> { if (it.market?.isInEconomy == true) 1 else 0 }
                .thenBy { if (it === sector.economy.getMarket(TEST_MARKET_ID)?.primaryEntity) 1 else 0 }
                .thenBy { if (it === sector.getEntityById(TEST_PLANET_ID)) 1 else 0 }
                .thenBy { if (it.market?.isInEconomy == true) 1 else 0 }
                .thenBy { if (it.containingLocation === sector.currentLocation) 1 else 0 }
        )
    }

    private fun removeDuplicateTestSystems(sector: SectorAPI, canonicalPlanet: SectorEntityToken) {
        val canonicalLocation = canonicalPlanet.containingLocation
        var removed = 0
        for (system in sector.starSystems.toList()) {
            if (system === canonicalLocation) continue
            val duplicatePlanet = system.getEntityById(TEST_PLANET_ID)
                ?: system.planets.firstOrNull { it.id == TEST_PLANET_ID || it.market?.id == TEST_MARKET_ID }
                ?: continue
            if (devStoragePayloadScore(duplicatePlanet.market) > devStoragePayloadScore(canonicalPlanet.market)) {
                continue
            }
            sector.removeStarSystem(system)
            removed++
        }
        if (removed > 0) {
            log.info("[AsteriaTestCampaignBootstrap] Removed $removed duplicate test star systems.")
        }
    }

    private fun findTestTarget(sector: SectorAPI): TestTarget? {
        val planet = findTestPlanet(sector) ?: return null
        return TestTarget(planet, ensureTestMarket(sector, planet))
    }

    private fun hasDevStoragePayload(market: MarketAPI): Boolean {
        val cargo = market.getSubmarket(Submarkets.SUBMARKET_STORAGE)?.cargoNullOk ?: return false
        return cargo.mothballedShips.membersListCopy.isNotEmpty() ||
            cargo.weapons.isNotEmpty() ||
            cargo.fighters.isNotEmpty()
    }

    private fun fillStorageWithModContent(market: MarketAPI): Boolean {
        val storage = market.getSubmarket(Submarkets.SUBMARKET_STORAGE)
        if (storage == null) {
            log.warn("[AsteriaTestCampaignBootstrap] storage submarket missing on test market.")
            return false
        }

        val cargo = storage.cargo
        cargo.credits.add(2_000_000f)
        cargo.addSupplies(5_000f)
        cargo.addFuel(5_000f)

        // 1) 给玩家阵营解锁"已知蓝图"（方便 UI/装配等）
        var knownShipCount = 0
        var knownWeaponCount = 0
        var knownFighterCount = 0
        var knownHullModCount = 0
        try {
            for (hull in Global.getSettings().allShipHullSpecs) {
                if (ASTDDevContentSelector.isDevStorageShip(hull)) {
                    val id = hull.hullId
                    sectorPlayerFaction().addKnownShip(id, false)
                    knownShipCount++
                }
            }
            for (w in Global.getSettings().actuallyAllWeaponSpecs) {
                if (ASTDDevContentSelector.isDevStorageWeapon(w)) {
                    val id = w.weaponId
                    sectorPlayerFaction().addKnownWeapon(id, false)
                    knownWeaponCount++
                }
            }
            for (wing in Global.getSettings().allFighterWingSpecs) {
                if (ASTDDevContentSelector.isDevStorageFighterWing(wing)) {
                    val id = wing.id
                    sectorPlayerFaction().addKnownFighter(id, false)
                    knownFighterCount++
                }
            }
            for (hullMod in Global.getSettings().allHullModSpecs) {
                if (ASTDDevContentSelector.isDevStorageHullMod(hullMod)) {
                    val id = hullMod.id
                    sectorPlayerFaction().addKnownHullMod(id)
                    knownHullModCount++
                }
            }
        } catch (t: Throwable) {
            // 解锁失败也不影响把实体塞进仓储
            log.warn("[AsteriaTestCampaignBootstrap] Failed to add known content: ${t.message}", t)
        }

        // 2) 把当前启用环境中可实例化、非模块/站点的舰船变体放入仓储（mothballed ships）
        val hullById = Global.getSettings().allShipHullSpecs
            .filterNotNull()
            .associateBy { it.hullId }
        var shipCount = 0
        var skippedVariantCount = 0
        for (vid in Global.getSettings().allVariantIds) {
            if (vid == null) continue

            try {
                val variant = Global.getSettings().getVariant(vid) ?: continue
                val hull = variant.hullSpec ?: continue
                if (!ASTDDevContentSelector.isDevStorageShip(hull)) continue
                if (hullById[hull.hullId] == null) continue

                val validation = ASTDDevContentSelector.validateDevStorageVariant(variant)
                if (!validation.accepted) {
                    skippedVariantCount++
                    if (skippedVariantCount <= MAX_SKIPPED_VARIANT_LOGS) {
                        log.warn("[AsteriaTestCampaignBootstrap] Skipped dev storage variant: ${validation.reason}")
                    }
                    continue
                }

                val member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, vid)

                // Add dev-only shield coverage boost so test hulls are easier to fly/inspect.
                try {
                    member.variant?.addPermaMod(TEST_SHIELD_COVERAGE_HULLMOD_ID)
                } catch (_: Throwable) {
                    // If VariantAPI signature changes, simply skip the debug mod.
                }

                cargo.mothballedShips.addFleetMember(member)
                shipCount++
            } catch (t: Throwable) {
                skippedVariantCount++
                if (skippedVariantCount <= MAX_SKIPPED_VARIANT_LOGS) {
                    log.warn("[AsteriaTestCampaignBootstrap] Skipped dev storage variant $vid: ${t.message}", t)
                }
            }
        }
        if (skippedVariantCount > MAX_SKIPPED_VARIANT_LOGS) {
            log.warn(
                "[AsteriaTestCampaignBootstrap] Skipped ${skippedVariantCount - MAX_SKIPPED_VARIANT_LOGS} " +
                    "additional dev storage variants; earlier warnings show representative reasons."
            )
        }

        // 3) 把当前启用环境中可正常装配的武器/战机/货物放入仓储
        var weaponCount = 0
        for (spec in Global.getSettings().actuallyAllWeaponSpecs) {
            if (!ASTDDevContentSelector.isDevStorageWeapon(spec)) continue
            val wid = spec.weaponId ?: continue

            cargo.addWeapons(wid, 50)
            weaponCount++
        }

        var hullModCount = 0
        for (spec in Global.getSettings().allHullModSpecs) {
            if (!ASTDDevContentSelector.isDevStorageHullMod(spec)) continue
            val id = spec.id ?: continue

            cargo.addHullmods(id, 10)
            hullModCount++
        }

        var fighterCount = 0
        for (spec in Global.getSettings().allFighterWingSpecs) {
            if (!ASTDDevContentSelector.isDevStorageFighterWing(spec)) continue
            val id = spec.id ?: continue

            cargo.addFighters(id, 50)
            fighterCount++
        }

        var commodityCount = 0
        for (spec in Global.getSettings().allCommoditySpecs) {
            if (!ASTDDevContentSelector.isDevStorageCommodity(spec)) continue
            val id = spec.id ?: continue
            val amount = when {
                spec.isPersonnel -> 1_000f
                spec.isSupplies || spec.isFuel -> 5_000f
                else -> 1_000f
            }

            cargo.addCommodity(id, amount)
            commodityCount++
        }

        var specialItemCount = 0
        for (spec in Global.getSettings().allSpecialItemSpecs) {
            if (!ASTDDevContentSelector.isDevStorageSpecialItem(spec)) continue
            val id = spec.id ?: continue
            val params = spec.params?.takeIf { it.isNotBlank() }

            cargo.addSpecial(SpecialItemData(id, params), 10f)
            specialItemCount++
        }

        cargo.sort()

        logTestMarketState(Global.getSector(), market.primaryEntity, market, "filled")
        log.info(
            "[AsteriaTestCampaignBootstrap] Filled storage: " +
                "$shipCount ships, $weaponCount weapons, $hullModCount hullmods, $fighterCount fighters, " +
                "$commodityCount commodities, $specialItemCount special items; " +
                "skipped=$skippedVariantCount variants; " +
                "known=$knownShipCount ships/$knownWeaponCount weapons/$knownFighterCount fighters/$knownHullModCount hullmods."
        )
        return true
    }

    private fun attemptTeleport(
        sector: SectorAPI,
        planet: SectorEntityToken,
        markDoneOnSuccess: Boolean,
        queueOnFailure: Boolean,
    ) {
        if (teleportPlayerToTestPlanet(sector, planet)) {
            if (markDoneOnSuccess) {
                AsteriaTestCampaignBootstrapState.markTeleportDone(sector.persistentData)
            }
            log.info("[AsteriaTestCampaignBootstrap] Player fleet moved to the test range.")
            return
        }

        if (queueOnFailure) {
            if (markDoneOnSuccess) {
                sector.removeTransientScriptsOfClass(TeleportRetryScript::class.java)
            }
            if (!sector.hasTransientScript(TeleportRetryScript::class.java)) {
                sector.addTransientScript(TeleportRetryScript(markDoneOnSuccess))
                log.info("[AsteriaTestCampaignBootstrap] Player fleet not ready; queued test range teleport retry.")
            }
        }
    }

    private fun teleportPlayerToTestPlanet(sector: SectorAPI, planet: SectorEntityToken): Boolean {
        val location = planet.containingLocation ?: return false
        val playerFleet = sector.playerFleet ?: return false

        movePlayerFleetToLocation(playerFleet, location)

        val x = planet.location.x + planet.radius + PLAYER_FLEET_OFFSET
        val y = planet.location.y

        sector.currentLocation = location
        playerFleet.clearAssignments()
        playerFleet.setLocation(x, y)
        playerFleet.velocity.set(0f, 0f)
        playerFleet.setVelocity(0f, 0f)
        playerFleet.setMoveDestination(x, y)
        playerFleet.interactionTarget = planet
        playerFleet.setPreferredResupplyLocation(planet)
        sector.respawnLocation = location
        sector.respawnCoordinates.set(x, y)

        return playerFleet.containingLocation === location && !playerFleet.isInHyperspace
    }

    private fun isPlayerAtTestPlanet(sector: SectorAPI, planet: SectorEntityToken): Boolean {
        val location = planet.containingLocation ?: return false
        val playerFleet = sector.playerFleet ?: return false
        return playerFleet.containingLocation === location && !playerFleet.isInHyperspace
    }

    private fun movePlayerFleetToLocation(
        playerFleet: com.fs.starfarer.api.campaign.CampaignFleetAPI,
        location: com.fs.starfarer.api.campaign.LocationAPI,
    ) {
        val currentLocation = playerFleet.containingLocation
        if (currentLocation !== location) {
            currentLocation?.removeEntity(playerFleet)
            location.addEntity(playerFleet)
        } else if (!location.fleets.contains(playerFleet)) {
            location.addEntity(playerFleet)
        }
        playerFleet.containingLocation = location
    }

    private class TeleportRetryScript(
        private val markDoneOnSuccess: Boolean,
    ) : EveryFrameScript {

        private var done = false
        private var attempts = 0
        private var interval = 0f

        override fun advance(amount: Float) {
            if (done) return

            val sector = Global.getSector()
            if (sector == null || !Global.getSettings().isDevMode) {
                done = true
                return
            }
            if (!AsteriaTestCampaignBootstrapState.shouldAttemptTeleport(sector.persistentData)) {
                done = true
                return
            }

            interval -= amount.coerceAtLeast(0f)
            if (attempts > 0 && interval > 0f) return

            attempts++
            interval = TELEPORT_RETRY_INTERVAL_SECONDS

            val testTarget = findTestTarget(sector) ?: createTestSystemAndMarket(sector)
            if (testTarget != null && teleportPlayerToTestPlanet(sector, testTarget.planet)) {
                if (markDoneOnSuccess) {
                    AsteriaTestCampaignBootstrapState.markTeleportDone(sector.persistentData)
                }
                log.info("[AsteriaTestCampaignBootstrap] Player fleet moved to the test range after $attempts retry attempts.")
                done = true
                return
            }

            if (attempts >= TELEPORT_RETRY_MAX_ATTEMPTS) {
                log.warn("[AsteriaTestCampaignBootstrap] Timed out while moving player fleet to the test range.")
                done = true
            }
        }

        override fun isDone(): Boolean = done

        override fun runWhilePaused(): Boolean = true
    }

    private fun sectorPlayerFaction() = Global.getSector().playerFaction
}
