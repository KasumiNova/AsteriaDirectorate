package cn.kasuminova.astd.campaign.automation

import org.json.JSONObject

/** 生涯验收步骤；在游戏主线程逐帧执行，只有实际断言全部完成才返回 true。 */
interface CampaignCheck {
    /** 返回 false 表示仍等待游戏状态变化，超时由外层运行器负责。 */
    fun advance(amount: Float): Boolean
}

/** 一个隔离测试进程的证据记录。完成状态由必填证据决定，不能仅凭步骤返回值成功。 */
class CampaignRun(val scenario: String, val runId: String) {
    val evidence: MutableMap<String, Boolean> = linkedMapOf()
    val details: MutableMap<String, Any> = linkedMapOf()
    var frame: Long = 0
        private set
    var stageStartFrame: Long = 0
        private set
    var currentStage: String = "initializing"
        private set
    var state: String = "Running"
        private set
    var failureCode: String? = null
        private set
    private var stageStarted = System.nanoTime()

    init {
        require(scenario in requiredEvidence) { "Unsupported campaign scenario: $scenario" }
        require(runId.isNotBlank()) { "Missing campaign runId" }
    }

    fun tick() { frame++ }

    /** 阶段不变时不重置截止时间，防止每帧调用掩盖卡死。 */
    fun stage(name: String) {
        require(name.isNotBlank())
        if (name == currentStage) return
        currentStage = name
        stageStartFrame = frame
        stageStarted = System.nanoTime()
    }

    fun timedOut(seconds: Long): Boolean = System.nanoTime() - stageStarted > seconds * 1_000_000_000L

    /** 同一证据重复检查也会执行断言；先前通过不能掩盖后续失败。 */
    fun check(key: String, condition: Boolean, detail: String) {
        evidence[key] = condition
        details[key] = detail
        if (!condition) throw CampaignCheckFailure(key, detail)
    }

    /** 记录尚未成立的异步证据，不把等待状态误报为失败。 */
    fun observe(key: String, condition: Boolean, detail: String) {
        evidence[key] = condition
        details[key] = detail
    }

    fun detail(key: String, value: Any) { details[key] = value }

    fun verifyRequired(excluding: Set<String> = emptySet()) {
        val missing = requiredEvidence.getValue(scenario).filter { it !in excluding && evidence[it] != true }
        if (missing.isNotEmpty()) throw CampaignCheckFailure("missing_evidence", missing.joinToString())
    }

    fun complete() {
        verifyRequired()
        state = "Completed"
    }

    fun fail(code: String, message: String) {
        failureCode = code
        details["error"] = message
        state = "Failed"
    }

    /** 交给外部 runner 落盘，不依赖 SSOptimizer 为新场景生成固定格式文件。 */
    fun json(): String = JSONObject()
        .put("scenario", scenario).put("runId", runId).put("state", state)
        .put("stage", currentStage).put("failureCode", failureCode ?: JSONObject.NULL)
        .put("frame", frame).put("stageStartFrame", stageStartFrame)
        .put("evidence", JSONObject(evidence)).put("details", JSONObject(details)).toString()

    companion object {
        val requiredEvidence: Map<String, Set<String>> = linkedMapOf(
            "campaign_world_indevo" to setOf("mainSystem", "starfallSystem", "asterSystem", "markets", "conditions", "indEvoLoaded", "mainArtillery", "starfallArtillery", "watchtowers", "idempotent", "transfers", "persisted"),
            "campaign_bounty_battle" to setOf("accepted", "enteredBattle", "enemiesDestroyed", "magicSucceeded", "assetCollected", "settled", "rewardGranted", "persisted"),
            "campaign_terminal" to setOf("opened", "selected", "accepted", "tracked", "delivered", "closed", "persisted"),
            "campaign_mainline_smoke" to setOf("prologue", "chapterOne", "chapterTwo", "chapterThree", "chapterFour", "archiveChoice", "executiveCore", "infiniteAvailable", "persisted"),
            "campaign_ai_core" to setOf("coreSpecs", "rewardCargo", "combatCore", "adminCore", "persisted", "noDuplicate"),
        )
    }
}

/** 带稳定错误编号的断言异常，外层统一写完整堆栈及最后阶段。 */
class CampaignCheckFailure(val code: String, message: String) : IllegalStateException(message)
