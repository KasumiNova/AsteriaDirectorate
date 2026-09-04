package cn.kasuminova.astd.campaign

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import cn.kasuminova.astd.testutil.RepoLayout

internal class AsteriaTestCampaignBootstrapStateTest {

    @Test
    fun `legacy done key does not suppress content or teleport retry`() {
        val persistentData = linkedMapOf<String, Any?>(
            AsteriaTestCampaignBootstrapState.LEGACY_DONE_KEY to true,
        )

        assertFalse(AsteriaTestCampaignBootstrapState.isContentDone(persistentData))
        assertTrue(AsteriaTestCampaignBootstrapState.shouldAttemptContentFill(persistentData))
        assertFalse(AsteriaTestCampaignBootstrapState.isTeleportDone(persistentData))
        assertTrue(AsteriaTestCampaignBootstrapState.shouldAttemptTeleport(persistentData))
    }

    @Test
    fun `failed teleport attempt remains retryable until teleport is explicitly marked done`() {
        val persistentData = linkedMapOf<String, Any?>()

        AsteriaTestCampaignBootstrapState.markContentDone(persistentData)

        assertTrue(AsteriaTestCampaignBootstrapState.isContentDone(persistentData))
        assertFalse(AsteriaTestCampaignBootstrapState.shouldAttemptContentFill(persistentData))
        assertFalse(AsteriaTestCampaignBootstrapState.isTeleportDone(persistentData))
        assertTrue(AsteriaTestCampaignBootstrapState.shouldAttemptTeleport(persistentData))

        AsteriaTestCampaignBootstrapState.markTeleportDone(persistentData)

        assertTrue(AsteriaTestCampaignBootstrapState.isTeleportDone(persistentData))
        assertFalse(AsteriaTestCampaignBootstrapState.shouldAttemptTeleport(persistentData))
    }

    @Test
    fun `boolean content done marker from failed storage fill is not trusted`() {
        val persistentData = linkedMapOf<String, Any?>(
            AsteriaTestCampaignBootstrapState.CONTENT_DONE_KEY to true,
        )

        assertFalse(AsteriaTestCampaignBootstrapState.isContentDone(persistentData))
        assertTrue(AsteriaTestCampaignBootstrapState.shouldAttemptContentFill(persistentData))
    }

    @Test
    fun `stale content marker with existing payload is accepted instead of duplicated`() {
        val persistentData = linkedMapOf<String, Any?>(
            AsteriaTestCampaignBootstrapState.CONTENT_DONE_KEY to true,
        )

        assertTrue(AsteriaTestCampaignBootstrapState.shouldAcceptExistingContent(persistentData, storageHasPayload = true))
        assertFalse(AsteriaTestCampaignBootstrapState.shouldFillContent(persistentData, storageHasPayload = true))
        assertTrue(AsteriaTestCampaignBootstrapState.shouldFillContent(persistentData, storageHasPayload = false))
    }

    @Test
    fun `uninitialized storage cargo is treated as empty content`() {
        val source = Files.readString(RepoLayout.mainSourceFile("campaign/AsteriaTestCampaignBootstrap.kt")!!)

        assertTrue(
            source.contains("cargoNullOk ?: return false"),
            "storage submarkets whose cargo has not been initialized yet must be filled, not accepted as existing content",
        )
    }

    @Test
    fun `mod plugin retries dev bootstrap after new game time pass`() {
        val plugin = Files.readString(Path.of("src/main/java/cn/kasuminova/astd/AsteriaDirectoratePlugin.java"))
        val timePassBody = plugin
            .substringAfter("public void onNewGameAfterTimePass()")
            .substringBefore("@Override", missingDelimiterValue = plugin.substringAfter("public void onNewGameAfterTimePass()"))

        assertTrue(
            plugin.contains("public void onNewGameAfterTimePass()"),
            "new game finalization must retry dev bootstrap after vanilla start placement",
        )
        assertTrue(
            timePassBody.contains("AsteriaTestCampaignBootstrap.runIfEnabled();"),
            "dev bootstrap should run from the new game finalization callback",
        )
        assertTrue(
            timePassBody.contains("AsteriaTestCampaignBootstrap.finalizeNewGameTeleportIfEnabled();"),
            "new game finalization should confirm the player fleet is no longer left in hyperspace",
        )
    }

    @Test
    fun `mod plugin does not fill dev storage from on game load new game callback`() {
        val plugin = Files.readString(Path.of("src/main/java/cn/kasuminova/astd/AsteriaDirectoratePlugin.java"))
        val onGameLoadBody = plugin
            .substringAfter("public void onGameLoad(boolean newGame)")
            .substringBefore("public Logger logger()")

        assertFalse(
            onGameLoadBody.contains("if (newGame)"),
            "new-game storage injection must wait for economy callbacks instead of onGameLoad(newGame)",
        )
        assertFalse(
            onGameLoadBody.contains("AsteriaTestCampaignBootstrap.runIfEnabled();"),
            "onGameLoad(newGame) is too early for durable dev storage cargo injection",
        )
        assertTrue(
            onGameLoadBody.contains("AsteriaTestCampaignBootstrap.resumePendingTeleportIfEnabled();"),
            "normal load should still resume pending teleport for old dev saves",
        )
    }

    @Test
    fun `mod plugin repairs existing dev test storage during normal load`() {
        val plugin = Files.readString(Path.of("src/main/java/cn/kasuminova/astd/AsteriaDirectoratePlugin.java"))
        val onGameLoadBody = plugin
            .substringAfter("public void onGameLoad(boolean newGame)")
            .substringBefore("public Logger logger()")

        assertTrue(
            onGameLoadBody.contains("AsteriaTestCampaignBootstrap.repairExistingTestStorageIfEnabled();"),
            "existing dev test saves should repair poisoned storage cargo on normal load",
        )
    }

    @Test
    fun `mod plugin runs explicit dev storage acceptance during normal load`() {
        val plugin = Files.readString(Path.of("src/main/java/cn/kasuminova/astd/AsteriaDirectoratePlugin.java"))
        val onGameLoadBody = plugin
            .substringAfter("public void onGameLoad(boolean newGame)")
            .substringBefore("public Logger logger()")

        assertTrue(
            onGameLoadBody.contains("AsteriaTestCampaignBootstrap.runStorageAcceptanceIfRequested();"),
            "campaign storage acceptance should run after normal-load repair when explicitly requested by a JVM property",
        )
    }

    @Test
    fun `normal load repair refills existing empty dev test storage`() {
        val source = Files.readString(RepoLayout.mainSourceFile("campaign/AsteriaTestCampaignBootstrap.kt")!!)
        val repairBody = source
            .substringAfter("fun repairExistingTestStorageIfEnabled()")
            .substringBefore("private data class TestTarget")

        assertTrue(
            repairBody.contains("val storageHasPayload = hasDevStoragePayload(testTarget.market)"),
            "normal load repair should inspect the existing dev storage payload",
        )
        assertTrue(
            repairBody.contains("fillStorageWithModContent(testTarget.market)"),
            "existing empty dev test saves should be refilled on normal load",
        )
    }

    @Test
    fun `normal load repair of existing test depots is not gated by global dev mode`() {
        val source = Files.readString(RepoLayout.mainSourceFile("campaign/AsteriaTestCampaignBootstrap.kt")!!)
        val repairBody = source
            .substringAfter("fun repairExistingTestStorageIfEnabled()")
            .substringBefore("private data class TestTarget")

        assertFalse(
            repairBody.contains("!Global.getSettings().isDevMode"),
            "existing test depots can be loaded with devMode=false; repair must key off the presence of the test depot, not the global devMode setting",
        )
        assertTrue(
            repairBody.contains("findTestTarget(sector) ?: return"),
            "normal load repair should still be inert for saves without the existing ASTD test depot",
        )
    }

    @Test
    fun `explicit dev storage acceptance validates the UI market storage contract`() {
        val source = Files.readString(RepoLayout.mainSourceFile("campaign/AsteriaTestCampaignBootstrap.kt")!!)

        assertTrue(
            source.contains("""private const val STORAGE_ACCEPTANCE_PROPERTY = "astd.devStorageAcceptance""""),
            "campaign storage acceptance must be controlled by an explicit JVM property",
        )
        assertTrue(
            source.contains("fun runStorageAcceptanceIfRequested()"),
            "normal-load acceptance should be callable from the mod plugin",
        )
        assertTrue(
            source.contains("private fun validateTestStorageAcceptance"),
            "acceptance should be a dedicated validation path instead of loose logging",
        )
        assertTrue(source.contains("planet.market !== market"))
        assertTrue(source.contains("sector.economy.getMarket(TEST_MARKET_ID) !== market"))
        assertTrue(source.contains("sector.playerFaction.production.gatheringPoint !== market"))
        assertTrue(source.contains("storageStacks <= 0"))
        assertTrue(source.contains("storageShips <= 0"))
        assertTrue(source.contains("findDuplicateTestMarkets(sector, market).isNotEmpty()"))
        assertTrue(source.contains("throw IllegalStateException"))
        assertTrue(source.contains("Dev storage acceptance passed"))
    }

    @Test
    fun `smoke script can run campaign storage acceptance as a hard log gate`() {
        val script = Files.readString(Path.of("tools/smoke_test_game_launch.sh"))

        assertTrue(
            script.contains("campaign-acceptance"),
            "smoke script should expose a campaign acceptance mode for storage repair verification",
        )
        assertTrue(
            script.contains("-Dastd.devStorageAcceptance=true"),
            "campaign acceptance mode must enable the explicit mod-side storage acceptance hook",
        )
        assertTrue(
            script.contains("Dev storage acceptance passed|Dev storage acceptance failed"),
            "campaign acceptance mode should stop as soon as an acceptance marker appears",
        )
        assertTrue(
            script.contains("dev storage acceptance marker not found; campaign save was not loaded within timeout"),
            "campaign acceptance mode must fail clearly if it never reaches campaign load",
        )
    }

    @Test
    fun `campaign acceptance mode loads a pinned save from title screen agent hook without script-blocked APIs`() {
        val plugin = Files.readString(Path.of("src/main/java/cn/kasuminova/astd/AsteriaDirectoratePlugin.java"))
        val autoload = Files.readString(RepoLayout.mainSourceFile("campaign/AsteriaDevStorageAcceptanceAutoload.kt")!!)
        val agent = Files.readString(Path.of("src/main/java/cn/kasuminova/astd/agent/AsteriaDevStorageAcceptanceAgent.java"))
        val transformer = Files.readString(Path.of("src/main/java/cn/kasuminova/astd/agent/AsteriaTitleScreenAdvanceTransformer.java"))
        val hook = Files.readString(Path.of("src/main/java/cn/kasuminova/astd/agent/AsteriaDevStorageAcceptanceTitleHook.java"))
        val script = Files.readString(Path.of("tools/smoke_test_game_launch.sh"))
        val gradle = Files.readString(Path.of("build.gradle.kts"))

        val onApplicationLoadBody = plugin
            .substringAfter("public void onApplicationLoad()")
            .substringBefore("@Override", missingDelimiterValue = plugin.substringAfter("public void onApplicationLoad()"))

        assertFalse(
            onApplicationLoadBody.contains("AsteriaDevStorageAcceptanceAutoload.installIfRequested();"),
            "campaign save loading is too early from onApplicationLoad and can advance BoxUtil before campaign GL state is ready",
        )
        assertTrue(
            hook.contains("""private static final String CAMPAIGN_STATE_ID = "Campaign State""""),
            "successful autoload should transition into the normal campaign state",
        )
        assertTrue(
            hook.contains("""private static final String SAVE_DIR_PROPERTY = "astd.devStorageAcceptanceSaveDir""""),
            "campaign acceptance should allow tests to pin the exact save directory",
        )
        assertTrue(
            agent.contains("premain(") &&
                (
                    agent.contains("addTransformer(new AsteriaTitleScreenAdvanceTransformer()") ||
                        agent.contains("addTransformer(transformer")
                    ),
            "the campaign acceptance loader must be installed as a javaagent outside Starsector's script class loader",
        )
        assertTrue(
            agent.contains("getAllLoadedClasses()") &&
                agent.contains("retransformClasses"),
            "the campaign acceptance agent must handle TitleScreenState if another bootstrap component loaded it before the transformer was registered",
        )
        assertTrue(
            transformer.contains("com/fs/starfarer/title/TitleScreenState") &&
                transformer.contains("prepare") &&
                transformer.contains("AsteriaDevStorageAcceptanceTitleHook"),
            "the agent must defer save loading until TitleScreenState.prepare instead of ResourceLoaderState.init",
        )
        assertTrue(
            transformer.contains("java.lang.Module") &&
                transformer.contains("transformClass("),
            "the transformer should implement the Java 9+ transformer entrypoint explicitly so Java 25 reliably invokes the hook",
        )
        assertTrue(
            hook.contains("CampaignGameManager.loadGame") &&
                hook.contains("goToState(CAMPAIGN_STATE_ID)"),
            "title-screen autoload must use the vanilla campaign load path and then transition into the normal campaign state",
        )
        assertFalse(
            autoload.contains("CampaignGameManager.loadGame") ||
                autoload.contains("AppDriver.getInstance()"),
            "mod script classes must not directly load the campaign save from the resource-loading callback",
        )
        assertFalse(
            hook.contains("java.lang.reflect") ||
                hook.contains("Class.forName") ||
                hook.contains("getDeclaredField") ||
                hook.contains("getMethod(") ||
                hook.contains(".invoke(") ||
                hook.contains("java.io.File"),
            "campaign acceptance title hook should use public game APIs rather than blocked script reflection/file APIs",
        )
        assertTrue(
            script.contains("ASTD_ACCEPTANCE_SAVE_DIR") &&
                script.contains("-Dastd.devStorageAcceptanceSaveDir="),
            "smoke script should always pass a save-dir override for deterministic campaign acceptance",
        )
        assertTrue(
            script.contains("-javaagent:\${ASTD_ACCEPTANCE_AGENT_JAR}") ||
                script.contains("-javaagent:\$ASTD_ACCEPTANCE_AGENT_JAR") ||
                script.contains("-javaagent:\$acceptance_agent_jar"),
            "campaign acceptance mode must install the ASTD title-screen javaagent",
        )
        assertTrue(
            script.contains("launch_campaign_acceptance_direct"),
            "campaign acceptance must direct-launch Java so the ASTD agent does not poison launch_injected_ss.sh Java 25 probing",
        )
        assertTrue(
            script.contains("AsteriaDirectorate-1.0-SNAPSHOT-acceptance-agent.jar"),
            "campaign acceptance must default to the dedicated agent jar instead of mounting the full mod jar into the system class loader",
        )
        assertTrue(
            gradle.contains("""archiveClassifier.set("acceptance-agent")""") &&
                gradle.contains("""include("cn/kasuminova/astd/agent/**")""") &&
                gradle.contains("""include("org/objectweb/asm/**")"""),
            "the dedicated campaign acceptance javaagent jar must contain only agent classes plus the ASM transformer dependency, not ASTD gameplay Kotlin classes",
        )
        assertFalse(
            script.contains("JAVA_TOOL_OPTIONS=\"\$ORIGINAL_JAVA_TOOL_OPTIONS \$EXTRA_OPTS\"") ||
                script.contains("JAVA_TOOL_OPTIONS=\"\$EXTRA_OPTS\""),
            "campaign acceptance must not pass the ASTD javaagent through JAVA_TOOL_OPTIONS before launch_injected_ss.sh probes Java",
        )
        assertTrue(
            gradle.contains("Premain-Class") &&
                gradle.contains("cn.kasuminova.astd.agent.AsteriaDevStorageAcceptanceAgent"),
            "the mod jar manifest must expose the campaign acceptance premain entry point",
        )
        assertTrue(
            script.contains("save_Dev_") && script.contains("descriptor.xml"),
            "smoke script should resolve the latest Dev save before launching if no explicit save dir is provided",
        )
    }

    @Test
    fun `bootstrap repairs and unlocks existing test market storage before filling cargo`() {
        val source = Files.readString(RepoLayout.mainSourceFile("campaign/AsteriaTestCampaignBootstrap.kt")!!)

        assertTrue(source.contains("ensureTestMarket(sector, planet)"))
        assertTrue(source.contains("ensureSubmarket(market, Submarkets.SUBMARKET_STORAGE)"))
        assertTrue(source.contains("setPlayerPaidToUnlock(true)"))
    }

    @Test
    fun `bootstrap uses the planet generated market id as canonical test market id`() {
        val source = Files.readString(RepoLayout.mainSourceFile("campaign/AsteriaTestCampaignBootstrap.kt")!!)

        assertTrue(
            source.contains("""private const val TEST_MARKET_ID = "market_asteria_test_depot""""),
            "test market id must match the market id generated for TEST_PLANET_ID by addPlanet",
        )
        assertFalse(
            source.contains("""private const val TEST_MARKET_ID = "asteria_test_market""""),
            "a detached market id can split the market opened by the planet from the market containing storage cargo",
        )
    }

    @Test
    fun `bootstrap rebinds all dev storage entry points to one canonical test market`() {
        val source = Files.readString(RepoLayout.mainSourceFile("campaign/AsteriaTestCampaignBootstrap.kt")!!)
        val ensureBody = source
            .substringAfter("private fun ensureTestMarket")
            .substringBefore("private fun ensureSubmarket")

        assertTrue(
            ensureBody.contains("selectCanonicalTestMarket(sector, planet)"),
            "existing saves can contain duplicate test markets; ensureTestMarket must pick the cargo-bearing canonical market",
        )
        assertTrue(
            ensureBody.contains("sector.playerFaction.production.setGatheringPoint(market)"),
            "custom production and colony management must point at the same test market opened from the planet",
        )
        assertTrue(
            ensureBody.contains("removeDuplicateTestMarketsFromEconomy(sector, market)"),
            "economy duplicates with the same test id must be removed after rebinding the planet to the canonical market",
        )
    }

    @Test
    fun `bootstrap does not select orphan cargo markets as the canonical UI market`() {
        val source = Files.readString(RepoLayout.mainSourceFile("campaign/AsteriaTestCampaignBootstrap.kt")!!)
        val selectBody = source
            .substringAfter("private fun selectCanonicalTestMarket")
            .substringBefore("private fun")

        assertFalse(
            selectBody.contains("devStoragePayloadScore"),
            "payload-bearing orphan markets from old local_resources listeners must be merged into the UI market, not selected as canonical",
        )
        assertTrue(
            selectBody.contains("planet.market") && selectBody.contains("sector.economy.getMarket(TEST_MARKET_ID)"),
            "the canonical test market must be selected from the stable planet/economy UI entry points",
        )
    }

    @Test
    fun `bootstrap migrates duplicate test market storage into the canonical UI market`() {
        val source = Files.readString(RepoLayout.mainSourceFile("campaign/AsteriaTestCampaignBootstrap.kt")!!)
        val ensureBody = source
            .substringAfter("private fun ensureTestMarket")
            .substringBefore("private fun selectCanonicalTestMarket")

        assertTrue(
            source.contains("private fun migrateDuplicateTestStorageCargo"),
            "old dev saves can leave filled storage on orphan duplicate markets and must migrate that cargo into the canonical UI market",
        )
        assertTrue(
            ensureBody.contains("migrateDuplicateTestStorageCargo(sector, market)"),
            "storage migration must run before empty-storage refill checks accept or refill the canonical market",
        )
        assertTrue(
            source.contains("canonicalCargo.addAll(duplicateCargo, true)"),
            "duplicate storage cargo should be moved into the canonical storage cargo object opened by the UI",
        )
        assertTrue(
            source.contains("duplicateCargo.clear()"),
            "duplicate storage cargo should be cleared after migration so old orphan markets cannot keep stale payloads",
        )
    }

    @Test
    fun `bootstrap finds existing test planet by entity id instead of star system display name`() {
        val source = Files.readString(RepoLayout.mainSourceFile("campaign/AsteriaTestCampaignBootstrap.kt")!!)
        val findBody = source
            .substringAfter("private fun findTestPlanet")
            .substringBefore("private fun findTestTarget")

        assertTrue(
            findBody.contains("sector.getEntityById(TEST_PLANET_ID)") ||
                findBody.contains("sys.getEntityById(TEST_PLANET_ID)"),
            "test planet lookup must use the stable entity id; StarSystemAPI.getName() may include localized type suffixes",
        )
        assertFalse(
            findBody.contains("TEST_SYSTEM_NAME == sys.name || TEST_SYSTEM_ID == sys.id"),
            "display-name/id gated lookup can miss the existing test system and create duplicate empty storage markets",
        )
    }

    @Test
    fun `bootstrap removes duplicate test systems after selecting the canonical planet`() {
        val source = Files.readString(RepoLayout.mainSourceFile("campaign/AsteriaTestCampaignBootstrap.kt")!!)
        val ensureBody = source
            .substringAfter("private fun ensureTestMarket")
            .substringBefore("private fun selectCanonicalTestMarket")

        assertTrue(
            source.contains("private fun removeDuplicateTestSystems"),
            "old dev saves can contain duplicate Asteria Test Range systems and must be repaired",
        )
        assertTrue(
            ensureBody.contains("removeDuplicateTestSystems(sector, planet"),
            "duplicate systems must be removed after the canonical planet is bound to the canonical market",
        )
    }

    @Test
    fun `bootstrap keeps dev depot storage off local resources listeners`() {
        val source = Files.readString(RepoLayout.mainSourceFile("campaign/AsteriaTestCampaignBootstrap.kt")!!)
        val ensureBody = source
            .substringAfter("private fun ensureTestMarket")
            .substringBefore("private fun selectCanonicalTestMarket")

        assertFalse(
            ensureBody.contains("Submarkets.LOCAL_RESOURCES"),
            "local_resources registers economy listeners and can retain detached duplicate test markets in saves",
        )
    }

    @Test
    fun `bootstrap creates a visible player colony market instead of only a condition market`() {
        val source = Files.readString(RepoLayout.mainSourceFile("campaign/AsteriaTestCampaignBootstrap.kt")!!)

        assertTrue(source.contains("market.factionId = Factions.PLAYER"))
        assertTrue(source.contains("market.setPlayerOwned(true)"))
        assertTrue(
            source.contains("market.admin = sector.playerPerson") ||
                source.contains("market.setAdmin(sector.playerPerson)") ||
                source.contains("market.setAdmin(Global.getSector().getPlayerPerson())"),
            "player-owned test markets must be administered by the player so colony systems treat them as real colonies",
        )
        assertTrue(source.contains("market.setSurveyLevel(MarketAPI.SurveyLevel.FULL)"))
        assertTrue(
            source.contains("cond.setSurveyed(true)"),
            "full survey level is not enough; each existing market condition should be marked surveyed",
        )
        assertTrue(source.contains("market.setPlanetConditionMarketOnly(false)"))
        assertTrue(source.contains("planet.setFaction(Factions.PLAYER)"))
    }

    @Test
    fun `bootstrap purges unsafe special items from existing dev storage`() {
        val source = Files.readString(RepoLayout.mainSourceFile("campaign/AsteriaTestCampaignBootstrap.kt")!!)

        assertTrue(source.contains("purgeUnsafeDevStorageStacks(market)"))
        assertTrue(source.contains("ASTDDevContentSelector.isUnsafeDevStorageSpecialItemId"))
        assertTrue(source.contains("cargo.removeStack(stack)"))
    }
}
