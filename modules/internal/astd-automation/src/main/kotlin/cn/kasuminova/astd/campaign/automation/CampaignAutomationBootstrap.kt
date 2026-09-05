package cn.kasuminova.astd.campaign.automation

import cn.kasuminova.astd.campaign.bounty.MainBounties
import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import org.apache.log4j.Logger

/**
 * 独立生涯验收入口。类只装配进 automation 包，不复用 SSOptimizer 的 mission 场景开关。
 * 所有测试脚本都是 transient，存档中仅保存跨进程核对用的普通数据。
 */
object CampaignAutomationBootstrap {
    const val ENABLED_PROPERTY = "astd.campaignAutomation.enabled"
    const val SCENARIO_PROPERTY = "astd.campaignAutomation.scenario"
    const val RUN_ID_PROPERTY = "astd.campaignAutomation.runId"
    const val PHASE_PROPERTY = "astd.campaignAutomation.phase"
    const val CHECKPOINT_PROPERTY = "astd.campaignAutomation.checkpointRequested"
    const val SNAPSHOT_KEY = "astd_campaign_automation_checkpoint"

    @JvmStatic
    fun onGameLoad() {
        if (!java.lang.Boolean.getBoolean(ENABLED_PROPERTY)) return
        val sector = checkNotNull(Global.getSector()) { "Campaign automation requires a loaded sector" }
        val phase = System.getProperty(PHASE_PROPERTY, "run")
        val memoryKey = "\$astd_campaign_automation_installed_${System.getProperty(SCENARIO_PROPERTY)}_$phase"
        if (sector.memoryWithoutUpdate.getBoolean(memoryKey)) return
        val run = CampaignRun(
            checkNotNull(System.getProperty(SCENARIO_PROPERTY)) { "Missing campaign scenario" },
            checkNotNull(System.getProperty(RUN_ID_PROPERTY)) { "Missing campaign runId" },
        )
        sector.addTransientScript(CampaignAutomationScript(run))
        sector.memoryWithoutUpdate.set(memoryKey, true)
    }
}

private class CampaignAutomationScript(private val run: CampaignRun) : EveryFrameScript {
    private val log = Logger.getLogger(CampaignAutomationScript::class.java)
    private var check: CampaignCheck? = null
    private var checkpointRequested = false
    private var initialized = false
    private var lastEmission = 0L
    private val reload = System.getProperty(CampaignAutomationBootstrap.PHASE_PROPERTY) == "reload"

    override fun isDone(): Boolean = run.state != "Running"
    override fun runWhilePaused(): Boolean = true

    override fun advance(amount: Float) {
        if (isDone()) return
        run.tick()
        try {
            val sector = checkNotNull(Global.getSector())
            if (!initialized) {
                require(Global.getSettings().isDevMode) { "Campaign automation requires devMode" }
                require(System.getProperty("astd.campaignAutomation.saveDir")?.isNotBlank() == true) {
                    "Campaign automation requires an explicitly isolated saveDir"
                }
                run.check("indEvoLoaded", Global.getSettings().modManager.isModEnabled("IndEvo"), "IndEvo is mandatory in this suite")
                initialized = true
                if (reload) {
                    run.stage("reload.verify")
                } else {
                    CampaignSaveChecks.requireCleanFixture()
                    check = when (run.scenario) {
                        "campaign_world_indevo" -> CampaignWorldChecks(run)
                        "campaign_bounty_battle" -> CampaignBountyChecks(run,
                            System.getProperty("astd.campaignAutomation.bountyKey", MainBounties.defs.first().key))
                        "campaign_terminal" -> CampaignTerminalChecks(run)
                        "campaign_ai_core" -> CampaignCoreChecks(run)
                        "campaign_mainline_smoke" -> CampaignMainlineChecks(run)
                        else -> throw CampaignCheckFailure("unknown_scenario", run.scenario)
                    }
                }
            }
            if (reload) {
                // 给读档时注册的状况/核心维护脚本实际运行的机会。
                sector.isPaused = false
                if (run.frame >= 120) {
                    CampaignSaveChecks.verify(run)
                    run.stage("reload.completed")
                    run.complete()
                    emit()
                }
                return
            }
            if (checkpointRequested) return
            if (run.timedOut(180)) throw CampaignCheckFailure("stage_timeout", run.currentStage)
            if (checkNotNull(check).advance(amount)) {
                run.verifyRequired(excluding = setOf("persisted"))
                run.stage("checkpoint.requested")
                CampaignSaveChecks.capture(run)
                sector.isPaused = true
                checkpointRequested = true
                emit()
                // agent 在安全的 campaign 帧读取请求并保存到副本；runner 二次启动验证。
                System.setProperty(CampaignAutomationBootstrap.CHECKPOINT_PROPERTY, run.runId)
            } else if (System.nanoTime() - lastEmission > 2_000_000_000L) {
                emit()
            }
        } catch (failure: Throwable) {
            run.fail((failure as? CampaignCheckFailure)?.code ?: "exception", failure.message ?: failure.javaClass.name)
            log.error("[ASTD-CampaignAutomation] failed at ${run.currentStage}", failure)
            emit()
        }
    }

    private fun emit() {
        log.info("[ASTD-CampaignAutomation] telemetry json=${run.json()}")
        lastEmission = System.nanoTime()
    }
}
