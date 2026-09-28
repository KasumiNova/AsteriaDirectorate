package cn.kasuminova.astd.combat.effect.generic

import cn.kasuminova.astd.combat.automation.AutomationScenarioRegistryImpl
import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.AutomationScenario
import cn.kasuminova.astd.combat.automation.api.AutomationScenarioRegistry
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.api.StarfallEchoStageAccess
import cn.kasuminova.astd.combat.automation.base.AutomationEvidence
import cn.kasuminova.astd.combat.automation.base.StarfallEchoStage
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionShipIds
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionTooltipContracts
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfx
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.combat.CombatState
import org.apache.log4j.Logger
import org.lwjgl.opengl.Display

/**
 * Dev-only combat automation surface for validating Arc Flare + 坠星残响 runtime VFX in game.
 *
 * 分发主干：场景逻辑全部下沉到 [AutomationScenario] 处理器（见 combat.automation 包，
 * 注册表 [AutomationScenarioRegistryImpl]），本类只保留通用 init 闸门、按暂停策略的 advance 分发、
 * renderInUICoords 捕获分发与诊断 JSON 编排（公共头尾 + 活跃场景证据段）。
 *
 * 外部硬契约（不得破坏）：
 * - 类 FQN 被 24 个 MissionDefinition.java 以 `new ASTDAutomationCombatPlugin()` 引用；
 * - [writeTelemetry] 四参私有方法是 SSOptimizer 的 ASM 注入点（签名逐字节保留）；
 * - 诊断日志行格式 `[ASTD-Automation] diagnostics state=$state json=...` 被
 *   tools/verify_ingame_vfx_automation.py 解析。
 */
class ASTDAutomationCombatPlugin : BaseEveryFrameCombatPlugin(), AutomationCombatContext {

    /** 共享日志器：类目固定为本类，保证 starsector.log 行格式与拆分前一致。 */
    override val log: Logger = Global.getLogger(ASTDAutomationCombatPlugin::class.java)

    private var engineRef: CombatEngineAPI? = null

    override val engine: CombatEngineAPI
        get() = requireNotNull(engineRef) { "ASTDAutomationCombatPlugin.engine accessed before init" }

    override var elapsed = 0f
    override var lastWriteAt = -1f
    override var completed = false
        private set
    override var completedAt = -1f
        private set
    override var visualFramesWritten = 0
    override var lastVisualFrameAt = -1f
    override var failureReason: String? = null

    override fun markCompleted() {
        completed = true
        completedAt = elapsed
    }

    override val starfallEcho: StarfallEchoStageAccess = StarfallEchoStage()

    private val registry: AutomationScenarioRegistry = AutomationScenarioRegistryImpl()
    private lateinit var activeHandler: AutomationScenario

    override fun init(engine: CombatEngineAPI) {
        this.engineRef = engine
        // 关闭原版开局部署对话框（仅多舰场景）：CombatState.traverse 的弹框闸门在 engine.init()
        // （即本方法）返回后才判定，玩家后备 != 1 艘时弹「增援部署」对话框（过期快照不刷新、
        // 公开 API 无关闭入口、常驻遮屏；2026-07-30 反编译 CombatState 实锤）。
        // 关键约束（2026-07-31 坠星残响前身场景回归实锤）：静默 deployAll 与弹框在同一闸门块内——
        // 玩家后备 == 1 艘时 vanilla 走静默 deployAll（不弹框但会部署），此处关断会把静默部署
        // 一并跳过，单舰场景（如 xc_001_starfall_echo_basic）将无船可部署。故仅在后备 != 1 艘
        // （必弹框路径）时关断；单舰路径本就不弹框，保留 flag 让 vanilla 静默部署。
        val combatUI = engine.combatUI
        if (combatUI is CombatState) {
            val playerReserves = engine.getFleetManager(0).reservesCopy.size
            if (playerReserves != 1) {
                combatUI.setShowDeploymentDialogOnStart(false)
            }
        } else {
            log.warn("[ASTD-Automation] combatUI 非 CombatState（${combatUI?.javaClass?.name}），开局部署对话框关断失败")
        }
        ProjectileVfxDriverPlugin.ensureInstalled(engine)
        activeHandler = registry.active()
        activeHandler.init(this)
    }

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        val combatEngine = engineRef ?: return
        when (activeHandler.pausePolicy) {
            // 暂停对照语义场景（grg/tpp）：刻意不 unpause——grg 的 SCREENSHOT_VOLLEY 取景定格靠
            // setPaused(true) 冻结舞台（截图捕获与 Completed 上报间有秒级异步延迟，不冻结拍不到 ACTIVE
            // 段的光束）；暂停期插件 advance 仍按真实 amount 推进（obf 实证），相位机计时不受影响。
            PausePolicy.KEEP_PAUSED -> Unit
            PausePolicy.FORCE_UNPAUSE -> if (combatEngine.isPaused) combatEngine.isPaused = false
            PausePolicy.SKIP_WHEN_PAUSED -> if (combatEngine.isPaused) return
        }
        elapsed += amount.coerceAtLeast(0f)
        activeHandler.advance(this, amount.coerceAtLeast(0f))
    }

    override fun renderInUICoords(viewport: ViewportAPI) {
        engineRef ?: return
        activeHandler.renderCapture(this)
    }

    override fun writeTelemetry(state: String, ship: ShipAPI?, weapon: WeaponAPI?) {
        writeTelemetry(engine, state, ship, weapon)
    }

    private fun writeTelemetry(
        engine: CombatEngineAPI,
        state: String,
        ship: ShipAPI? = starfallEcho.findStageShip(engine),
        weapon: WeaponAPI? = ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.WEAPON_ID },
    ) {
        // SSOptimizer patches this method and writes telemetry/screenshots outside the Starsector script sandbox.
    }

    override fun writeDiagnostics(state: String, ship: ShipAPI?) {
        val combatEngine = engineRef ?: return
        writeDiagnostics(combatEngine, state, ship ?: starfallEcho.findStageShip(combatEngine))
    }

    private fun writeDiagnostics(
        engine: CombatEngineAPI,
        state: String,
        ship: ShipAPI?,
    ) {
        if (!registry.anyEnabled()) {
            return
        }

        val displayMode = try {
            Display.getDisplayMode()
        } catch (_: Throwable) {
            null
        }
        val displayWidth = try {
            Display.getWidth()
        } catch (_: Throwable) {
            -1
        }
        val displayHeight = try {
            Display.getHeight()
        } catch (_: Throwable) {
            -1
        }
        val displayPixelScale = try {
            Display.getPixelScaleFactor()
        } catch (_: Throwable) {
            -1f
        }
        val viewport = engine.viewport
        val shipSprite = try {
            ship?.spriteAPI
        } catch (_: Throwable) {
            null
        }
        val vfxTelemetry = ProjectileVfxDriverPlugin.telemetrySnapshot(engine)
        val scenarioId = activeHandler.scenarioId
        val json = buildString {
            appendLine("{")
            appendLine("  \"source\": \"ASTD\",")
            appendLine("  \"scenario\": \"$scenarioId\",")
            appendLine("  \"state\": \"$state\",")
            appendLine("  \"displayWidth\": $displayWidth,")
            appendLine("  \"displayHeight\": $displayHeight,")
            appendLine("  \"displayPixelScale\": ${formatFloat(displayPixelScale)},")
            appendLine("  \"displayModeWidth\": ${displayMode?.width ?: -1},")
            appendLine("  \"displayModeHeight\": ${displayMode?.height ?: -1},")
            appendLine("  \"viewportVisibleWidth\": ${formatFloat(viewport.visibleWidth)},")
            appendLine("  \"viewportVisibleHeight\": ${formatFloat(viewport.visibleHeight)},")
            appendLine("  \"viewportWorldXToScreenX\": ${formatFloat(viewport.worldXtoScreenX)},")
            appendLine("  \"viewportWorldYToScreenY\": ${formatFloat(viewport.worldYtoScreenY)},")
            appendLine("  \"viewportViewMult\": ${formatFloat(viewport.viewMult)},")
            appendLine("  \"shipLocationX\": ${formatFloat(ship?.location?.x ?: -1f)},")
            appendLine("  \"shipLocationY\": ${formatFloat(ship?.location?.y ?: -1f)},")
            appendLine("  \"shipFacing\": ${formatFloat(ship?.facing ?: -1f)},")
            appendLine("  \"shipSpriteWidth\": ${formatFloat(shipSprite?.width ?: -1f)},")
            appendLine("  \"shipSpriteHeight\": ${formatFloat(shipSprite?.height ?: -1f)},")
            appendLine("  \"shipSpriteCenterX\": ${formatFloat(shipSprite?.centerX ?: -1f)},")
            appendLine("  \"shipSpriteCenterY\": ${formatFloat(shipSprite?.centerY ?: -1f)},")
            appendLine("  \"failureReason\": ${jsonString(failureReason)},")
            // 原版开局部署对话框状态探针（2026-07-30 关断修复的回归证据）：插件 init 在多舰
            // （后备 != 1）场景已调 CombatState.setShowDeploymentDialogOnStart(false)，单舰场景
            // 保留闸门让 vanilla 静默 deployAll（2026-07-31 修正）；本字段在 CombatReady 与各相位
            // 写出时采样，任何时刻为 true 都说明闸门被重新打开（多舰场景会常驻遮屏）。
            appendLine("  \"deploymentDialogShowing\": ${engine.combatUI?.isShowingDeploymentDialog},")
            activeHandler.appendDiagnostics(this@ASTDAutomationCombatPlugin, this, vfxTelemetry)
            appendLine("  \"fallbackInPlay\": ${starfallEcho.fallbackProjectile?.let { engine.isEntityInPlay(it) } ?: false},")
            appendLine("  \"fallbackExpired\": ${starfallEcho.fallbackProjectile?.isExpired ?: false},")
            appendLine("  \"fallbackFading\": ${starfallEcho.fallbackProjectile?.isFading ?: false},")
            appendLine("  \"xc102ShockwaveFrames\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_XC_102_SHOCKWAVE_FRAMES)},")
            appendLine("  \"xc102ShockwaveRadius\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_XC_102_SHOCKWAVE_RADIUS)},")
            appendLine(
                "  \"xc102ShockwaveFluxPressure\": ${
                    ASTDArcProductionVfx.counter(
                        engine,
                        ASTDArcProductionVfx.TELEMETRY_XC_102_SHOCKWAVE_FLUX_PRESSURE
                    )
                },"
            )
            appendLine("  \"xc101ShieldOpen\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_XC_101_SHIELD_OPEN)},")
            appendLine("  \"xc101SystemActive\": ${ASTDArcProductionVfx.counter(engine, ASTDArcProductionVfx.TELEMETRY_XC_101_SYSTEM_ACTIVE)},")
            appendLine(
                "  \"xc101ShieldArcEmissions\": ${
                    ASTDArcProductionVfx.counter(
                        engine,
                        ASTDArcProductionVfx.TELEMETRY_XC_101_SHIELD_ARC_EMISSIONS
                    )
                },"
            )
            appendLine(
                "  \"xc103SystemAfterimages\": ${
                    ASTDArcProductionVfx.counter(
                        engine,
                        ASTDArcProductionVfx.TELEMETRY_XC_103_SYSTEM_AFTERIMAGES
                    )
                },"
            )
            val xc102TooltipKeys = tooltipResolvedKeyCount(ASTDArcProductionShipIds.HULL_XC_102, ASTDArcProductionTooltipContracts.xc102Contracts)
            val xc101TooltipKeys = tooltipResolvedKeyCount(ASTDArcProductionShipIds.HULL_XC_101, ASTDArcProductionTooltipContracts.xc101Contracts)
            val xc103TooltipKeys = tooltipResolvedKeyCount(ASTDArcProductionShipIds.HULL_XC_103, ASTDArcProductionTooltipContracts.xc103Contracts)
            appendLine(
                "  \"xc102Tooltip\": ${
                    tooltipBlocksResolved(
                        ASTDArcProductionShipIds.HULL_XC_102,
                        ASTDArcProductionTooltipContracts.xc102Contracts
                    )
                },"
            )
            appendLine(
                "  \"xc101Tooltip\": ${
                    tooltipBlocksResolved(
                        ASTDArcProductionShipIds.HULL_XC_101,
                        ASTDArcProductionTooltipContracts.xc101Contracts
                    )
                },"
            )
            appendLine(
                "  \"xc103Tooltip\": ${
                    tooltipBlocksResolved(
                        ASTDArcProductionShipIds.HULL_XC_103,
                        ASTDArcProductionTooltipContracts.xc103Contracts
                    )
                },"
            )
            appendLine("  \"xc102TooltipKeys\": $xc102TooltipKeys,")
            appendLine("  \"xc101TooltipKeys\": $xc101TooltipKeys,")
            appendLine("  \"xc103TooltipKeys\": $xc103TooltipKeys,")
            appendLine("  \"elapsedSeconds\": ${"%.3f".format(java.util.Locale.ROOT, elapsed)}")
            appendLine("}")
        }
        log.info("[ASTD-Automation] diagnostics state=$state json=${json.lines().joinToString(" ")}")
    }

    private fun tooltipBlocksResolved(hullId: String, contracts: List<ASTDArcProductionTooltipContracts.Contract>): Boolean =
        AutomationEvidence.tooltipBlocksResolved(engine, hullId, contracts)

    private fun tooltipResolvedKeyCount(hullId: String, contracts: List<ASTDArcProductionTooltipContracts.Contract>): Int =
        AutomationEvidence.tooltipResolvedKeyCount(engine, hullId, contracts)

    private fun jsonString(value: String?): String = AutomationEvidence.jsonString(value)

    private fun formatFloat(value: Float): String = AutomationEvidence.formatFloat(value)
}
