package cn.kasuminova.astd.campaign.automation

import cn.kasuminova.astd.campaign.bounty.BountyKeys
import cn.kasuminova.astd.campaign.bounty.BountyState
import cn.kasuminova.astd.campaign.bounty.MainBounties
import cn.kasuminova.astd.campaign.bounty.MainBountyBridge
import cn.kasuminova.astd.campaign.bounty.MainlineProgression
import cn.kasuminova.astd.campaign.ending.InfiniteBountyGenerator
import cn.kasuminova.astd.campaign.story.BranchStationBackendImpl
import cn.kasuminova.astd.campaign.story.BranchTerminalData
import cn.kasuminova.astd.campaign.story.StoryArchives
import cn.kasuminova.astd.campaign.ui.terminal.ArchivalChoice
import cn.kasuminova.astd.campaign.ui.terminal.BranchTerminalUi
import cn.kasuminova.astd.campaign.ui.terminal.ExecutorSpec
import cn.kasuminova.astd.campaign.ui.terminal.TerminalDataMapper
import cn.kasuminova.astd.campaign.ui.terminal.TerminalTab
import cn.kasuminova.astd.campaign.world.GravityNodes
import cn.kasuminova.astd.campaign.world.StoryWorldIds
import cn.kasuminova.astd.campaign.world.StoryWorldState
import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.campaign.StarSystemAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.campaign.CampaignEngine
import com.fs.starfarer.campaign.save.CampaignGameManager
import org.apache.log4j.Logger
import org.magiclib.bounty.ActiveBounty
import org.magiclib.bounty.MagicBountyCoordinator
import java.io.File

/**
 * 生涯集成自动化检查脚本（实机验收基建）。
 *
 * 仅经 [CareerAutomationInstall] 在 `astd.careerAutomation.enabled=true` 且 automation 模块
 * 在包内时注册（transient，不入存档）。release 构建（-Pastd.includeAutomation=false）不含本类。
 *
 * 文件 IO 协议（工作目录由 `-Dastd.careerAutomation.dir` 指定，默认 `<cwd>/career-automation`）：
 * - 驱动方写入 `command.txt`（单行：`<seq> <command> [args...]`，原子写入）；
 * - 本脚本读取后立即删除 command.txt，跨帧执行命令（含等待条件的多拍命令），
 *   完成后写 `result-<seq>.json`（原子写入）；
 * - 每 0.5s 重写 `status.json` 心跳（当前状态/命令/耗时），驱动方据此判活；
 * - `finish` 命令写 `session-done` 标记文件，启动脚本据此提前收停游戏进程。
 *
 * 击毁推进走「桥接层调试入口」：`ActiveBounty.endBounty(Succeeded)` 置终态后由
 * BountyCampaignManager 正常 tick 消费（等效真实击毁后的结算管线，不经过战斗）。
 * 替代点在报告中标注（见 tools/verify_career_automation.py）。
 */
class CareerAutomationScript : EveryFrameScript {

    companion object {
        /** 工作目录系统属性。 */
        const val DIR_PROPERTY: String = "astd.careerAutomation.dir"

        /** 默认单命令墙钟超时（秒）。 */
        const val DEFAULT_TIMEOUT_SEC: Long = 60L

        private val log: Logger = Global.getLogger(CareerAutomationScript::class.java)

        /**
         * 市场解析：先查经济注册表；查不到时（conditionOnly 遗址/星球市场不入经济）
         * 从全星系实体侧反查 `entity.market`。IndEvoCareerProbe 同用。
         */
        internal fun findMarket(sector: SectorAPI, marketId: String): MarketAPI? {
            sector.economy.getMarket(marketId)?.let { return it }
            for (system in sector.starSystems) {
                for (entity in system.allEntities) {
                    val market = entity.market
                    if (market != null && market.id == marketId) return market
                }
            }
            return null
        }

        /** 最小 JSON 序列化（本模块不引入 JSON 库依赖；支持 null/布尔/数值/字符串/Map/Iterable）。 */
        fun toJson(value: Any?): String = when (value) {
            null -> "null"
            is Boolean -> value.toString()
            is Int, is Long -> value.toString()
            is Float, is Double -> {
                val d = (value as Number).toDouble()
                if (d.isFinite()) {
                    if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) {
                        d.toBigDecimal().stripTrailingZeros().toPlainString()
                    } else {
                        d.toString()
                    }
                } else {
                    "\"${d}\""
                }
            }

            is String -> buildString {
                append('"')
                for (c in value) {
                    when (c) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(c)
                    }
                }
                append('"')
            }

            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") {
                "${toJson(it.key.toString())}:${toJson(it.value)}"
            }

            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { toJson(it) }
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { toJson(it) }
            else -> toJson(value.toString())
        }
    }

    /** 一次命令的跨帧执行任务。 */
    private interface CommandTask {
        /** 每帧推进；返回 true 表示完成（[buildResult] 可取）。 */
        fun advance(amount: Float): Boolean

        /** 完成后的数据载荷（写入 result JSON 的 data 字段）。 */
        fun buildResult(): Any?
    }

    /** 立即执行的命令（同步计算，异常上抛给外层统一记日志）。 */
    private class InstantTask(private val body: () -> Any?) : CommandTask {
        private var done = false
        private var result: Any? = null

        override fun advance(amount: Float): Boolean {
            if (!done) {
                result = body()
                done = true
            }
            return true
        }

        override fun buildResult(): Any? = result
    }

    /** 等待条件成立的命令（[start] 在首帧执行一次；超时由外层按墙钟判定）。 */
    private class WaitTask(
        val start: () -> Unit,
        val condition: () -> Boolean,
        val result: () -> Any?,
    ) : CommandTask {
        private var started = false

        override fun advance(amount: Float): Boolean {
            if (!started) {
                start()
                started = true
            }
            return condition()
        }

        override fun buildResult(): Any? = result()
    }

    /** 顺序执行的子任务链（open_terminal 等多阶段命令）。 */
    private class SequenceTask(private val steps: List<CommandTask>, private val finalResult: () -> Any?) : CommandTask {
        private var index = 0

        override fun advance(amount: Float): Boolean {
            while (index < steps.size) {
                if (!steps[index].advance(amount)) return false
                index++
            }
            return true
        }

        override fun buildResult(): Any? = finalResult()
    }

    private class RunningCommand(
        val seq: Long,
        val raw: String,
        val task: CommandTask,
        val startedAtMs: Long,
        val timeoutMs: Long,
    )

    private val workDir: File = run {
        val configured = System.getProperty(DIR_PROPERTY)?.takeIf { it.isNotBlank() }
        File(configured ?: "career-automation")
    }
    private val commandFile: File = File(workDir, "command.txt")
    private val statusFile: File = File(workDir, "status.json")

    private var current: RunningCommand? = null
    private var heartbeatTimer = 0f
    private var lastFinishedSeq = 0L
    private var advanceTicks = 0L
    private var debugLogTimer = 0f
    private var lastCmdFileSeen = false

    init {
        workDir.mkdirs()
        log.info("[ASTD-Career] 自动化检查脚本已启动，工作目录：${workDir.absolutePath}")
        writeStatus("idle")
    }

    override fun isDone(): Boolean = false

    override fun runWhilePaused(): Boolean = true

    override fun advance(amount: Float) {
        advanceTicks++
        debugLogTimer += amount
        val cmdNow = commandFile.isFile
        if (cmdNow != lastCmdFileSeen) {
            log.info("[ASTD-Career] command.txt 边沿：$lastCmdFileSeen -> $cmdNow（ticks=$advanceTicks）")
            lastCmdFileSeen = cmdNow
        }
        if (advanceTicks == 1L || debugLogTimer >= 10f) {
            val sector = Global.getSector()
            val ui = sector?.campaignUI
            val listing = workDir.listFiles()?.joinToString(",") { f -> "${f.name}(${f.length()})" } ?: "<null>"
            log.info(
                "[ASTD-Career] advance 探针：ticks=$advanceTicks amount=$amount" +
                        " paused=${sector?.isPaused} dialog=${ui?.currentInteractionDialog != null}" +
                        " showingDialog=${ui?.isShowingDialog} cmdFile=${commandFile.isFile} dir=[$listing]",
            )
            debugLogTimer = 0f
        }
        heartbeatTimer += amount
        if (heartbeatTimer >= 0.5f) {
            heartbeatTimer = 0f
            writeStatus(if (current != null) "busy" else "idle")
        }

        val running = current
        if (running == null) {
            // 空闲时自动解除暂停：自动化环境无人按空格，对话框打开期间的暂停除外
            val sector = Global.getSector()
            if (sector != null && sector.isPaused) {
                val ui = sector.campaignUI
                if (ui != null && ui.currentInteractionDialog == null && !ui.isShowingDialog) {
                    sector.isPaused = false
                }
            }
            pollCommand()
            return
        }

        val finished = try {
            running.task.advance(amount)
        } catch (t: Throwable) {
            log.error("[ASTD-Career] 命令执行异常：${running.raw}", t)
            finishCommand(running, false, "exception:${t.javaClass.name}:${t.message}", null)
            return
        }

        if (finished) {
            val data = try {
                running.task.buildResult()
            } catch (t: Throwable) {
                log.error("[ASTD-Career] 命令结果装配异常：${running.raw}", t)
                finishCommand(running, false, "result_exception:${t.javaClass.name}:${t.message}", null)
                return
            }
            finishCommand(running, true, null, data)
        } else if (System.currentTimeMillis() - running.startedAtMs > running.timeoutMs) {
            log.error("[ASTD-Career] 命令超时：${running.raw}（>${running.timeoutMs}ms）")
            finishCommand(running, false, "timeout_after_ms:${running.timeoutMs}", null)
        }
    }

    private fun pollCommand() {
        if (!commandFile.isFile) return
        val line = try {
            commandFile.readText(Charsets.UTF_8).trim()
        } catch (t: Throwable) {
            log.error("[ASTD-Career] 命令文件读取失败", t)
            return
        }
        // 读取后立即删除，驱动方以 result-<seq>.json 出现为完成信号
        if (!commandFile.delete()) {
            log.error("[ASTD-Career] 命令文件删除失败（可能重复执行）：$line")
        }
        if (line.isEmpty()) return

        val tokens = line.split(Regex("\\s+"))
        val seq = tokens.firstOrNull()?.toLongOrNull()
        if (seq == null || tokens.size < 2) {
            log.error("[ASTD-Career] 命令格式非法（需要 <seq> <command>）：$line")
            return
        }
        val command = tokens[1]
        val args = tokens.subList(2, tokens.size)

        val timeoutMs = (System.getProperty("astd.careerAutomation.commandTimeoutSec")?.toLongOrNull()
            ?: DEFAULT_TIMEOUT_SEC) * 1000L

        val task = try {
            dispatch(command, args)
        } catch (t: Throwable) {
            log.error("[ASTD-Career] 命令分发失败：$line", t)
            writeResult(seq, line, false, "dispatch_exception:${t.javaClass.name}:${t.message}", null)
            lastFinishedSeq = seq
            return
        }
        log.info("[ASTD-Career] 收到命令 seq=$seq：${tokens.subList(1, tokens.size).joinToString(" ")}")
        current = RunningCommand(seq, line, task, System.currentTimeMillis(), timeoutMs)
        writeStatus("busy")
    }

    private fun finishCommand(running: RunningCommand, ok: Boolean, error: String?, data: Any?) {
        current = null
        lastFinishedSeq = running.seq
        writeResult(running.seq, running.raw, ok, error, data)
        log.info(
            "[ASTD-Career] 命令完成 seq=${running.seq} ok=$ok" +
                    (error?.let { " error=$it" } ?: "") +
                    " 耗时 ${System.currentTimeMillis() - running.startedAtMs}ms",
        )
        writeStatus("idle")
    }

    private fun writeResult(seq: Long, raw: String, ok: Boolean, error: String?, data: Any?) {
        val payload = linkedMapOf<String, Any?>(
            "seq" to seq,
            "command" to raw,
            "ok" to ok,
            "error" to error,
            "data" to data,
        )
        val target = File(workDir, "result-$seq.json")
        val tmp = File(workDir, "result-$seq.json.tmp")
        try {
            tmp.writeText(toJson(payload), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(toJson(payload), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (t: Throwable) {
            log.error("[ASTD-Career] 结果文件写入失败：${target.absolutePath}", t)
        }
    }

    private fun writeStatus(state: String) {
        val payload = linkedMapOf<String, Any?>(
            "state" to state,
            "lastFinishedSeq" to lastFinishedSeq,
            "currentCommand" to current?.raw,
            "currentElapsedMs" to (current?.let { System.currentTimeMillis() - it.startedAtMs } ?: 0L),
            "gameTimestamp" to (Global.getSector()?.clock?.timestamp ?: -1L),
        )
        val tmp = File(workDir, "status.json.tmp")
        try {
            tmp.writeText(toJson(payload), Charsets.UTF_8)
            if (!tmp.renameTo(statusFile)) {
                statusFile.writeText(toJson(payload), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (t: Throwable) {
            log.error("[ASTD-Career] 状态文件写入失败：${statusFile.absolutePath}", t)
        }
    }

    // ─── 命令分发 ───

    private fun dispatch(command: String, args: List<String>): CommandTask = when (command) {
        "ping" -> InstantTask { linkedMapOf("pong" to true, "timestamp" to (Global.getSector()?.clock?.timestamp ?: -1L)) }
        "dump_state" -> InstantTask { dumpState() }
        "check_world_main" -> InstantTask { worldMainFacts() }
        "check_world_ch2" -> InstantTask { worldChapter2Facts() }
        "accept_prologue" -> InstantTask { acceptPrologue() }
        "wait_posted" -> waitPosted(args)
        "kill" -> killOrder(args)
        "fail" -> failOrder(args)
        "pull_node" -> pullNode(args)
        "settle" -> InstantTask { settleOrder(args) }
        "terminal_snapshot" -> InstantTask { terminalSnapshot() }
        "open_terminal" -> openTerminal()
        "save_copy" -> saveCopy()
        "sign" -> InstantTask { signArchival(args) }
        "issue_executor" -> InstantTask { issueExecutor(args) }
        "assign_command_ship" -> InstantTask { assignCommandShip() }
        "appoint_admin" -> InstantTask { appointAdmin(args) }
        "force_due_effects" -> InstantTask { forceDueEffects() }
        "wait_effects_applied" -> waitEffectsApplied()
        "infinite_status" -> InstantTask { infiniteStatus() }
        "kill_infinite" -> killInfinite(args)
        "settle_infinite" -> settleInfinite(args)
        "finish" -> InstantTask {
            File(workDir, "session-done").writeText("done", Charsets.UTF_8)
            mapOf("sessionDone" to true)
        }

        else -> throw IllegalArgumentException("未知生涯自动化命令：$command")
    }

    private fun requireArg(args: List<String>, index: Int, name: String): String =
        args.getOrNull(index) ?: throw IllegalArgumentException("缺少参数 $name（位置 $index）")

    // ─── 状态转储 ───

    private fun dumpState(): Map<String, Any?> {
        val sector = Global.getSector() ?: throw IllegalStateException("sector 不可用")
        val state = BountyState.getOrCreate()
        val world = StoryWorldState.getOrCreate()
        val mem = sector.memoryWithoutUpdate
        val coord = MagicBountyCoordinator.getInstance()

        return linkedMapOf(
            "credits" to (sector.playerFleet?.cargo?.credits?.get()?.toLong() ?: -1L),
            "chapter" to state.currentChapter,
            "contractorLevel" to state.contractorLevel,
            "liquidationProgress" to state.liquidationProgress.toDouble(),
            "archivalPending" to state.archivalPending,
            "indefiniteContractor" to state.indefiniteContractor,
            "archivalChoice" to state.archivalChoice,
            "tradeFactionId" to state.tradeFactionId,
            "tradePayout" to state.tradePayout,
            "archivesReadOnly" to state.archivesReadOnly,
            "executorIssued" to state.executorIssued,
            "executorSpec" to state.executorSpec,
            "executorCommandShipId" to state.executorCommandShipId,
            "executorAdminMarketId" to state.executorAdminMarketId,
            "posted" to state.postedWorkOrders.toList(),
            "destroyed" to state.destroyedWorkOrders.toList(),
            "settled" to state.settledWorkOrders.toList(),
            "clearedGroups" to state.clearedGroups.toList(),
            "chapterHooks" to state.chapterHooks.toList(),
            "stageIndex" to state.workOrderStageIndex.toMap(),
            "grantedGroupBonuses" to state.grantedGroupBonuses.toMap(),
            "postable" to MainlineProgression.postableOrders(state).map { it.key },
            "activeAstdBounties" to coord.activeBounties.keys.filter { it.startsWith(BountyKeys.BOUNTY_KEY_PREFIX) },
            "memHooks" to listOf(
                MainlineProgression.HOOK_SEALED_CATEGORIES,
                MainlineProgression.HOOK_DUPLICATE_TARGET,
                MainlineProgression.HOOK_HALF_LINE_ORDER,
                MainlineProgression.HOOK_FINAL_RECEIPT,
            ).associateWith { mem.getBoolean(BountyKeys.MEM_HOOK_PREFIX + it) },
            "memArchivalPending" to mem.getBoolean(BountyKeys.MEM_ARCHIVAL_PENDING),
            "memLiquidation" to (mem[BountyKeys.MEM_LIQUIDATION_PROGRESS] as? Float)?.toDouble(),
            "gravityNodesPulled" to world.gravityNodesPulled.toList(),
            "worldState" to linkedMapOf(
                "mainSystemGenerated" to world.mainSystemGenerated,
                "chapter2SystemsGenerated" to world.chapter2SystemsGenerated,
                "starfallLoc" to listOf(world.starfallLocX.toDouble(), world.starfallLocY.toDouble()),
                "asterLoc" to listOf(world.asterLocX.toDouble(), world.asterLocY.toDouble()),
            ),
            "appliedStrengthPct" to state.appliedStrengthPct.mapValues { it.value.toDouble() },
            "pendingStrengthEffects" to state.pendingStrengthEffects.mapValues {
                mapOf("pct" to it.value.pct.toDouble(), "activateTimestamp" to it.value.activateTimestamp)
            },
            "infiniteSlots" to state.infiniteSlots.map {
                mapOf(
                    "index" to it.index,
                    "generation" to it.generation,
                    "lifecycle" to it.lifecycle,
                    "danger" to it.danger,
                    "fp" to it.fp,
                    "quotedReward" to it.quotedReward,
                    "targetFactionId" to it.targetFactionId,
                    "key" to InfiniteBountyGenerator.keyOf(it.index, it.generation),
                )
            },
            "infiniteSettlements" to state.infiniteSettlements.size,
        )
    }

    // ─── 世界生成检查（事实采集；断言在驱动侧） ───

    private fun marketFacts(sector: SectorAPI, marketId: String): Map<String, Any?> {
        val market = findMarket(sector, marketId)
            ?: return mapOf("exists" to false)
        return linkedMapOf(
            "exists" to true,
            "size" to market.size,
            "faction" to market.factionId,
            "conditions" to market.conditions.map { it.id },
            "inEconomy" to market.isInEconomy,
            "primaryEntityId" to market.primaryEntity?.id,
        )
    }

    private fun entityFacts(sector: SectorAPI, ids: List<String>): Map<String, Any?> =
        ids.associateWith { sector.getEntityById(it) != null }

    private fun systemOfEntity(sector: SectorAPI, entityId: String): StarSystemAPI? =
        sector.getEntityById(entityId)?.containingLocation as? StarSystemAPI

    private fun worldMainFacts(): Map<String, Any?> {
        val sector = Global.getSector() ?: throw IllegalStateException("sector 不可用")
        val system = systemOfEntity(sector, StoryWorldIds.MAIN_STAR)
        val indEvoEnabled = Global.getSettings().modManager.isModEnabled(StoryWorldIds.INDEVO_MOD_ID)

        val facts = linkedMapOf<String, Any?>(
            "systemExists" to (system != null),
            "systemId" to system?.id,
            "entities" to entityFacts(
                sector,
                listOf(
                    StoryWorldIds.MAIN_STAR,
                    StoryWorldIds.MAIN_PLANET_LANTAI,
                    StoryWorldIds.MAIN_PLANET_HONGLU,
                    StoryWorldIds.MAIN_PLANET_CUICHI,
                    StoryWorldIds.MAIN_STATION_BRANCH,
                    StoryWorldIds.MAIN_STATION_RESERVE_A,
                    StoryWorldIds.MAIN_STATION_RESERVE_B,
                    StoryWorldIds.MAIN_COMM_RELAY,
                    StoryWorldIds.MAIN_SENSOR_ARRAY,
                    StoryWorldIds.MAIN_NAV_BUOY,
                    StoryWorldIds.MAIN_GATE,
                ),
            ),
            "gateEntityType" to (sector.getEntityById(StoryWorldIds.MAIN_GATE)?.customEntitySpec?.id),
            "markets" to listOf(
                StoryWorldIds.MARKET_LANTAI,
                StoryWorldIds.MARKET_HONGLU,
                StoryWorldIds.MARKET_CUICHI,
                StoryWorldIds.MARKET_MAIN_STATION,
            ).associateWith { marketFacts(sector, it) },
            "mainStarSystemCount" to sector.starSystems.count { it.getEntityById(StoryWorldIds.MAIN_STAR) != null },
            "indEvoEnabled" to indEvoEnabled,
        )
        if (indEvoEnabled) {
            facts["indEvo"] = IndEvoCareerProbe.mainSystemFacts(sector)
        }
        return facts
    }

    private fun worldChapter2Facts(): Map<String, Any?> {
        val sector = Global.getSector() ?: throw IllegalStateException("sector 不可用")
        val starfallSystem = systemOfEntity(sector, StoryWorldIds.STARFALL_STAR)
        val asterSystem = systemOfEntity(sector, StoryWorldIds.ASTER_STAR)
        val world = StoryWorldState.getOrCreate()
        val indEvoEnabled = Global.getSettings().modManager.isModEnabled(StoryWorldIds.INDEVO_MOD_ID)

        val facts = linkedMapOf<String, Any?>(
            "starfall" to linkedMapOf(
                "systemExists" to (starfallSystem != null),
                "entities" to entityFacts(
                    sector,
                    listOf(
                        StoryWorldIds.STARFALL_STAR,
                        StoryWorldIds.STARFALL_PLANET_DUANYUAN,
                        StoryWorldIds.STARFALL_STATION_MAIN,
                        StoryWorldIds.STARFALL_STATION_DOCKYARD,
                        StoryWorldIds.STARFALL_STATION_RESERVE,
                        StoryWorldIds.STARFALL_COMM_RELAY,
                        StoryWorldIds.STARFALL_SENSOR_ARRAY,
                        StoryWorldIds.STARFALL_NAV_BUOY,
                        StoryWorldIds.STARFALL_GATE,
                    ),
                ),
                "gateEntityType" to (sector.getEntityById(StoryWorldIds.STARFALL_GATE)?.customEntitySpec?.id),
                "markets" to mapOf(StoryWorldIds.MARKET_DUANYUAN to marketFacts(sector, StoryWorldIds.MARKET_DUANYUAN)),
            ),
            "aster" to linkedMapOf(
                "systemExists" to (asterSystem != null),
                "entities" to entityFacts(
                    sector,
                    listOf(
                        StoryWorldIds.ASTER_STAR,
                        StoryWorldIds.ASTER_STATION_MAIN,
                        StoryWorldIds.ASTER_STATION_DOCKYARD,
                        StoryWorldIds.ASTER_STATION_SINGULARITY,
                        StoryWorldIds.ASTER_STATION_DEFENSE,
                        StoryWorldIds.ASTER_STATION_SHIGUANG,
                        StoryWorldIds.ASTER_CORE_VAULT,
                        StoryWorldIds.ASTER_COMM_RELAY,
                        StoryWorldIds.ASTER_SENSOR_ARRAY,
                        StoryWorldIds.ASTER_NAV_BUOY,
                        StoryWorldIds.ASTER_GATE,
                    ),
                ),
                "gateEntityType" to (sector.getEntityById(StoryWorldIds.ASTER_GATE)?.customEntitySpec?.id),
                "nodeEntities" to entityFacts(sector, StoryWorldIds.ASTER_NODE_IDS),
                "markets" to listOf(
                    StoryWorldIds.MARKET_SHIGUANG,
                    StoryWorldIds.MARKET_NODE_1,
                    StoryWorldIds.MARKET_NODE_2,
                    StoryWorldIds.MARKET_NODE_3,
                ).associateWith { marketFacts(sector, it) },
            ),
            "gravityNodesPulled" to world.gravityNodesPulled.toList(),
            "chapter2SystemsGenerated" to world.chapter2SystemsGenerated,
            "indEvoEnabled" to indEvoEnabled,
        )
        if (indEvoEnabled) {
            facts["indEvo"] = IndEvoCareerProbe.starfallFacts(sector)
        }
        return facts
    }

    // ─── 赏金链路驱动 ───

    private fun acceptPrologue(): Map<String, Any?> {
        val state = BountyState.getOrCreate()
        val coord = MagicBountyCoordinator.getInstance()
        val accepted = MainBountyBridge.acceptPrologueWorkOrder(state, coord)
        return linkedMapOf(
            "accepted" to accepted,
            "posted" to state.postedWorkOrders.toList(),
        )
    }

    private fun waitPosted(args: List<String>): CommandTask {
        val key = requireArg(args, 0, "key")
        return WaitTask(
            start = {},
            condition = {
                val state = BountyState.getOrCreate()
                key in state.postedWorkOrders &&
                        MagicBountyCoordinator.getInstance().getActiveBounty(key) != null
            },
            result = { mapOf("posted" to true, "key" to key) },
        )
    }

    /**
     * 桥接层调试推进入口：置 Succeeded 终态（等效击毁目标舰队），由 BountyCampaignManager
     * 正常 tick 消费（阶段推进 / 待核销登记）。目标舰队随即 despawn，等效战斗全歼。
     */
    private fun killOrder(args: List<String>): CommandTask {
        val key = requireArg(args, 0, "key")
        val state = BountyState.getOrCreate()
        val def = MainBounties.byKey(key) ?: throw IllegalArgumentException("未知主线工单：$key")
        val initialStage = (state.workOrderStageIndex[key] ?: 0).coerceIn(0, def.stages.lastIndex)
        val initialProgress = state.liquidationProgress

        return WaitTask(
            start = {
                val coord = MagicBountyCoordinator.getInstance()
                val bounty = coord.getActiveBounty(key)
                    ?: throw IllegalStateException("工单目标不在 active 列表：$key")
                bounty.endBounty(ActiveBounty.BountyResult.Succeeded(false))
                bounty.fleet?.despawn()
                log.info("[ASTD-Career] 调试击毁：$key（阶段 ${initialStage + 1}/${def.stages.size}）")
            },
            condition = {
                val now = BountyState.getOrCreate()
                key in now.destroyedWorkOrders ||
                        ((now.workOrderStageIndex[key] ?: 0) > initialStage && key in now.postedWorkOrders)
            },
            result = {
                val now = BountyState.getOrCreate()
                linkedMapOf(
                    "key" to key,
                    "destroyed" to (key in now.destroyedWorkOrders),
                    "stageIndex" to (now.workOrderStageIndex[key] ?: 0),
                    "liquidationProgress" to now.liquidationProgress.toDouble(),
                    "progressDelta" to (now.liquidationProgress - initialProgress).toDouble(),
                    "posted" to now.postedWorkOrders.toList(),
                )
            },
        )
    }

    /** 失败终态重挂验证：置 EndedWithoutPlayerInvolvement 终态，等待管理脚本自动重挂。 */
    private fun failOrder(args: List<String>): CommandTask {
        val key = requireArg(args, 0, "key")
        return WaitTask(
            start = {
                val coord = MagicBountyCoordinator.getInstance()
                val bounty = coord.getActiveBounty(key)
                    ?: throw IllegalStateException("工单目标不在 active 列表：$key")
                bounty.endBounty(ActiveBounty.BountyResult.EndedWithoutPlayerInvolvement())
                bounty.fleet?.despawn()
                log.info("[ASTD-Career] 调试失败终态：$key")
            },
            condition = {
                val state = BountyState.getOrCreate()
                val bounty = MagicBountyCoordinator.getInstance().getActiveBounty(key)
                key in state.postedWorkOrders && bounty != null && bounty.stage == ActiveBounty.Stage.Accepted
            },
            result = {
                mapOf("reposted" to true, "key" to key)
            },
        )
    }

    /** 拔除引力节点（移除节点实体，由 GravityNodeWatchScript 判定拔除并推进 ZW 阶段）。 */
    private fun pullNode(args: List<String>): CommandTask {
        val nodeId = requireArg(args, 0, "nodeId")
        if (nodeId !in StoryWorldIds.ASTER_NODE_IDS) {
            throw IllegalArgumentException("未知引力节点：$nodeId")
        }
        val zwDef = MainBounties.byKey(MainBounties.KEY_ZW_0309)
            ?: throw IllegalStateException("ZW 工单未注册")
        return WaitTask(
            start = {
                val sector = Global.getSector() ?: throw IllegalStateException("sector 不可用")
                val node = sector.getEntityById(nodeId)
                    ?: throw IllegalStateException("引力节点实体不存在：$nodeId")
                node.containingLocation?.removeEntity(node)
                    ?: throw IllegalStateException("引力节点无所属位置：$nodeId")
                log.info("[ASTD-Career] 调试拔除引力节点：$nodeId")
            },
            condition = {
                if (!GravityNodes.isPulled(nodeId)) {
                    false
                } else {
                    // 阶段同步走 BountyCampaignManager 0.7s 节拍，等它追平拔除进度再返回，避免采样竞态
                    val stage = BountyState.getOrCreate().workOrderStageIndex[zwDef.key] ?: 0
                    stage >= GravityNodes.pulledCount().coerceAtMost(zwDef.nodeDrivenStages)
                }
            },
            result = {
                val state = BountyState.getOrCreate()
                linkedMapOf(
                    "nodeId" to nodeId,
                    "pulledCount" to GravityNodes.pulledCount(),
                    "zwStageIndex" to (state.workOrderStageIndex[MainBounties.KEY_ZW_0309] ?: 0),
                )
            },
        )
    }

    /** 终端核销（经分局终端后端，含交割物校验/自愈补发口径）。 */
    private fun settleOrder(args: List<String>): Map<String, Any?> {
        val key = requireArg(args, 0, "key")
        val sector = Global.getSector() ?: throw IllegalStateException("sector 不可用")
        val creditsBefore = sector.playerFleet?.cargo?.credits?.get()?.toLong() ?: -1L
        val outcome = BranchStationBackendImpl().settleOrder(key)
        val creditsAfter = sector.playerFleet?.cargo?.credits?.get()?.toLong() ?: -1L
        val state = BountyState.getOrCreate()
        return linkedMapOf(
            "key" to key,
            "success" to outcome.success,
            "rejectReason" to outcome.rejectReason,
            "payout" to outcome.payout,
            "groupBonusId" to outcome.groupBonusId,
            "groupBonusAmount" to outcome.groupBonusAmount,
            "chapterCleared" to outcome.chapterCleared,
            "liquidationProgress" to outcome.liquidationProgress.toDouble(),
            "creditsDelta" to (creditsAfter - creditsBefore),
            "chapter" to state.currentChapter,
            "contractorLevel" to state.contractorLevel,
            "archivalPending" to state.archivalPending,
            "chapterHooks" to state.chapterHooks.toList(),
            "settled" to state.settledWorkOrders.toList(),
            "posted" to state.postedWorkOrders.toList(),
        )
    }

    // ─── 终端 UI ───

    /** 终端快照事实采集（三 tab 数据 + glitch 触发点 + 结局阶段机）。 */
    private fun terminalSnapshot(): Map<String, Any?> {
        val backend = BranchStationBackendImpl()
        val state = BountyState.getOrCreate()
        val snapshot = backend.snapshot()
        val batches = TerminalDataMapper.mapOrders(snapshot)
        val archiveLayers = TerminalDataMapper.mapArchives(snapshot)
        val account = TerminalDataMapper.mapAccount(snapshot)

        return linkedMapOf(
            "phase" to BranchTerminalData.phase(state).name,
            "orderCount" to snapshot.orders.size,
            "orders" to snapshot.orders.map {
                mapOf(
                    "key" to it.key,
                    "groupId" to it.groupId,
                    "lifecycle" to it.lifecycle.name,
                    "stageIndex" to it.stageIndex,
                    "stageCount" to it.stageCount,
                    "status" to TerminalDataMapper.statusOf(it.lifecycle).name,
                )
            },
            "batches" to batches.map { mapOf("groupId" to it.groupId, "settled" to it.settled, "total" to it.total) },
            "groupTotals" to snapshot.groupTotals,
            "archiveTotal" to StoryArchives.all.size,
            "archiveUnlocked" to snapshot.archives.count { it.unlocked },
            "archiveLayers" to archiveLayers.map {
                mapOf("layer" to it.layer, "unlocked" to it.unlocked, "total" to it.total)
            },
            "ledgerSize" to snapshot.ledger.size,
            "ledgerTotal" to account.totalPayout,
            "chapter" to snapshot.chapter,
            "contractorLevel" to snapshot.contractorLevel,
            "liquidationProgress" to snapshot.liquidationProgress.toDouble(),
            "settledCount" to snapshot.settledCount,
            "showLiquidationTopbar" to TerminalDataMapper.showLiquidationTopbar(snapshot),
            "endingStage" to TerminalDataMapper.endingStageOf(snapshot.ending).name,
            "glitchCh1" to TerminalDataMapper.glitchForChapter(1)?.name,
            "glitchCh2" to TerminalDataMapper.glitchForChapter(2)?.name,
            "glitchCh3" to TerminalDataMapper.glitchForChapter(3)?.name,
            "glitchCh4" to TerminalDataMapper.glitchForChapter(4)?.name,
            "archivesReadOnly" to snapshot.ending.archivesReadOnly,
            "tradeCandidates" to snapshot.ending.tradeCandidates.map {
                mapOf("factionId" to it.factionId, "quote" to it.quote)
            },
        )
    }

    /**
     * 真实打开分局终端 UI 的冒烟：showInteractionDialog（分局空间站）→ BranchTerminalUi.open →
     * 停留一拍让控件树装配 → dismiss。异常即失败（结果走外层 catch）。
     */
    private fun openTerminal(): CommandTask {
        val sector = Global.getSector() ?: throw IllegalStateException("sector 不可用")
        val station = sector.getEntityById(StoryWorldIds.MAIN_STATION_BRANCH)
            ?: throw IllegalStateException("分局空间站不存在：${StoryWorldIds.MAIN_STATION_BRANCH}")
        var pluginName: String? = null
        var shown = false
        var terminalOpened = false

        return SequenceTask(
            listOf(
                InstantTask {
                    shown = sector.campaignUI.showInteractionDialog(station)
                    if (!shown) throw IllegalStateException("showInteractionDialog 返回 false（分局站交互未打开）")
                    null
                },
                WaitTask(
                    start = {},
                    condition = { sector.campaignUI.currentInteractionDialog != null },
                    result = { null },
                ),
                InstantTask {
                    val dialog = sector.campaignUI.currentInteractionDialog
                        ?: throw IllegalStateException("交互对话框未出现")
                    pluginName = dialog.plugin?.javaClass?.name
                    BranchTerminalUi.open(dialog, BranchStationBackendImpl(), TerminalTab.ORDERS)
                    terminalOpened = true
                    log.info("[ASTD-Career] 分局终端已程序化打开（dialog plugin=$pluginName）")
                    null
                },
                // 停留 ~1.5s 让自定义对话框控件树装配/渲染数帧
                object : CommandTask {
                    private var elapsed = 0f
                    override fun advance(amount: Float): Boolean {
                        elapsed += amount
                        return elapsed >= 1.5f
                    }

                    override fun buildResult(): Any? = null
                },
                InstantTask {
                    sector.campaignUI.currentInteractionDialog?.dismiss()
                        ?: throw IllegalStateException("交互对话框在关闭前消失")
                    null
                },
                WaitTask(
                    start = {},
                    condition = { sector.campaignUI.currentInteractionDialog == null },
                    result = { null },
                ),
            ),
            finalResult = {
                linkedMapOf(
                    "shown" to shown,
                    "dialogPlugin" to pluginName,
                    "terminalOpened" to terminalOpened,
                    "dismissed" to (sector.campaignUI.currentInteractionDialog == null),
                )
            },
        )
    }

    // ─── 存档 / 读档 ───

    /**
     * 保存副本：直接走引擎存档管线（CampaignGameManager.saveGame 同步落盘）。
     * `CampaignUIAPI.cmdSaveCopy()` 只会打开 SAVE_AS 对话框，无头场景下不产生存档，故不用。
     * 存档目录名由 [CampaignGameManager.generateSaveName] 生成（`save_<标签>_<随机>`，多次调用不互覆），
     * 结果带回 `saveDirName` 供驱动侧直接定位。
     */
    private fun saveCopy(): CommandTask {
        val sector = Global.getSector() ?: throw IllegalStateException("sector 不可用")
        return InstantTask {
            val engine = CampaignEngine.getInstance()
                ?: throw IllegalStateException("CampaignEngine 不可用")
            val saveDirName = CampaignGameManager.generateSaveName("astd_career")
            engine.saveDirName = saveDirName
            val error = CampaignGameManager.saveGame(sector.campaignUI as CampaignEngine.CampaignUI)
            if (error != null) {
                throw IllegalStateException("保存副本失败：$error")
            }
            log.info("[ASTD-Career] 已保存副本：$saveDirName")
            mapOf("saveRequested" to true, "saveDirName" to saveDirName)
        }
    }

    // ─── 结局 ───

    private fun signArchival(args: List<String>): Map<String, Any?> {
        val choiceName = requireArg(args, 0, "choice")
        val choice = ArchivalChoice.valueOf(choiceName)
        val tradeFaction = args.getOrNull(1)
        val sector = Global.getSector() ?: throw IllegalStateException("sector 不可用")
        val state = BountyState.getOrCreate()
        val relationBefore = tradeFaction?.let { sector.getFaction(it)?.getRelationship("player") }
        val creditsBefore = sector.playerFleet?.cargo?.credits?.get()?.toLong() ?: -1L
        val outcome = BranchStationBackendImpl().signArchival(choice, tradeFaction)
            ?: return mapOf("signed" to false, "reason" to "signArchival_rejected")
        val relationAfter = tradeFaction?.let { sector.getFaction(it)?.getRelationship("player") }
        val creditsAfter = sector.playerFleet?.cargo?.credits?.get()?.toLong() ?: -1L
        return linkedMapOf(
            "signed" to true,
            "choice" to state.archivalChoice,
            "tradeFactionId" to state.tradeFactionId,
            "tradePayout" to state.tradePayout,
            "creditsDelta" to (creditsAfter - creditsBefore),
            "relationBefore" to relationBefore?.toDouble(),
            "relationAfter" to relationAfter?.toDouble(),
            "archivesReadOnly" to state.archivesReadOnly,
            "indefiniteContractor" to state.indefiniteContractor,
            "appliedStrengthPct" to state.appliedStrengthPct.mapValues { it.value.toDouble() },
            "pendingStrengthCount" to state.pendingStrengthEffects.size,
            "receiptPages" to outcome.pages.size,
        )
    }

    private fun issueExecutor(args: List<String>): Map<String, Any?> {
        val specName = requireArg(args, 0, "spec")
        val spec = ExecutorSpec.valueOf(specName)
        val outcome = BranchStationBackendImpl().issueExecutor(spec)
            ?: return mapOf("issued" to false, "reason" to "issueExecutor_rejected")
        val state = BountyState.getOrCreate()
        return linkedMapOf(
            "issued" to state.executorIssued,
            "executorSpec" to state.executorSpec,
            "receiptPages" to outcome.pages.size,
        )
    }

    private fun assignCommandShip(): Map<String, Any?> {
        val sector = Global.getSector() ?: throw IllegalStateException("sector 不可用")
        val member = sector.playerFleet?.fleetData?.membersListCopy?.firstOrNull()
            ?: throw IllegalStateException("玩家舰队无舰只")
        val ok = BranchStationBackendImpl().assignCommandShip(member.id)
        return linkedMapOf(
            "assigned" to ok,
            "memberId" to member.id,
            "stateCommandShipId" to BountyState.getOrCreate().executorCommandShipId,
        )
    }

    private fun appointAdmin(args: List<String>): Map<String, Any?> {
        val marketId = args.getOrNull(0) ?: StoryWorldIds.MARKET_MAIN_STATION
        val ok = BranchStationBackendImpl().appointAdmin(marketId)
        return linkedMapOf(
            "appointed" to ok,
            "marketId" to marketId,
            "stateAdminMarketId" to BountyState.getOrCreate().executorAdminMarketId,
        )
    }

    /** 把全部延迟强度条目的激活时刻改到当前（结局延迟激活路径的实机驱动；原 30 标准日等待不具实机可行性）。 */
    private fun forceDueEffects(): Map<String, Any?> {
        val sector = Global.getSector() ?: throw IllegalStateException("sector 不可用")
        val state = BountyState.getOrCreate()
        val now = sector.clock.timestamp
        var forced = 0
        for (effect in state.pendingStrengthEffects.values) {
            effect.activateTimestamp = now
            forced++
        }
        log.info("[ASTD-Career] 已把 $forced 条延迟强度条目的激活时刻提前到当前（替代 30 标准日等待）")
        return mapOf("forced" to forced)
    }

    private fun waitEffectsApplied(): CommandTask = WaitTask(
        start = {},
        condition = {
            val state = BountyState.getOrCreate()
            state.pendingStrengthEffects.isEmpty() && state.appliedStrengthPct.isNotEmpty()
        },
        result = {
            val state = BountyState.getOrCreate()
            linkedMapOf(
                "appliedStrengthPct" to state.appliedStrengthPct.mapValues { it.value.toDouble() },
                "pendingStrengthCount" to state.pendingStrengthEffects.size,
            )
        },
    )

    // ─── 无限赏金 ───

    private fun infiniteStatus(): Map<String, Any?> {
        val state = BountyState.getOrCreate()
        val coord = MagicBountyCoordinator.getInstance()
        return linkedMapOf(
            "indefiniteContractor" to state.indefiniteContractor,
            "slotCount" to state.infiniteSlots.size,
            "slots" to state.infiniteSlots.map {
                val key = InfiniteBountyGenerator.keyOf(it.index, it.generation)
                mapOf(
                    "index" to it.index,
                    "generation" to it.generation,
                    "lifecycle" to it.lifecycle,
                    "danger" to it.danger,
                    "fp" to it.fp,
                    "quotedReward" to it.quotedReward,
                    "targetFactionId" to it.targetFactionId,
                    "affixIds" to it.affixIds.toList(),
                    "key" to key,
                    "active" to (coord.getActiveBounty(key) != null),
                )
            },
            "settlements" to state.infiniteSettlements.map { mapOf("serial" to it.serial, "amount" to it.amount) },
            "generation" to state.infiniteGeneration,
        )
    }

    private fun killInfinite(args: List<String>): CommandTask {
        val slotIndex = requireArg(args, 0, "slotIndex").toInt()
        val state = BountyState.getOrCreate()
        val slot = state.infiniteSlots.firstOrNull { it.index == slotIndex }
            ?: throw IllegalArgumentException("无限赏金槽位不存在：$slotIndex")
        val generation = slot.generation
        val key = InfiniteBountyGenerator.keyOf(slot.index, generation)
        return WaitTask(
            start = {
                val coord = MagicBountyCoordinator.getInstance()
                val bounty = coord.getActiveBounty(key)
                    ?: throw IllegalStateException("无限赏金目标不在 active 列表：$key")
                bounty.endBounty(ActiveBounty.BountyResult.Succeeded(false))
                bounty.fleet?.despawn()
                log.info("[ASTD-Career] 调试击毁无限赏金：$key（槽位 $slotIndex 第 $generation 代）")
            },
            condition = {
                val now = BountyState.getOrCreate()
                now.infiniteSlots.firstOrNull { it.index == slotIndex }?.lifecycle == "DESTROYED"
            },
            result = {
                mapOf("slotIndex" to slotIndex, "generation" to generation, "destroyed" to true)
            },
        )
    }

    private fun settleInfinite(args: List<String>): CommandTask {
        val slotIndex = requireArg(args, 0, "slotIndex").toInt()
        val state = BountyState.getOrCreate()
        val slot = state.infiniteSlots.firstOrNull { it.index == slotIndex }
            ?: throw IllegalArgumentException("无限赏金槽位不存在：$slotIndex")
        val generationBefore = slot.generation
        val creditsBefore = Global.getSector()?.playerFleet?.cargo?.credits?.get()?.toLong() ?: -1L
        var settleOk = false
        var payout = 0
        return WaitTask(
            start = {
                val key = InfiniteBountyGenerator.keyOf(slotIndex, generationBefore)
                payout = slot.quotedReward
                val outcome = BranchStationBackendImpl().settleOrder(key)
                settleOk = outcome.success
                log.info("[ASTD-Career] 无限赏金核销：$key success=$settleOk")
            },
            condition = {
                if (!settleOk) return@WaitTask true
                val now = BountyState.getOrCreate()
                val current = now.infiniteSlots.firstOrNull { it.index == slotIndex } ?: return@WaitTask false
                current.generation > generationBefore && current.lifecycle == "POSTED" &&
                        MagicBountyCoordinator.getInstance()
                            .getActiveBounty(InfiniteBountyGenerator.keyOf(slotIndex, current.generation)) != null
            },
            result = {
                val creditsAfter = Global.getSector()?.playerFleet?.cargo?.credits?.get()?.toLong() ?: -1L
                val now = BountyState.getOrCreate()
                val current = now.infiniteSlots.firstOrNull { it.index == slotIndex }
                linkedMapOf(
                    "settled" to settleOk,
                    "payout" to payout,
                    "creditsDelta" to (creditsAfter - creditsBefore),
                    "generationBefore" to generationBefore,
                    "generationAfter" to (current?.generation ?: -1),
                    "lifecycleAfter" to current?.lifecycle,
                    "settlements" to now.infiniteSettlements.size,
                )
            },
        )
    }
}
