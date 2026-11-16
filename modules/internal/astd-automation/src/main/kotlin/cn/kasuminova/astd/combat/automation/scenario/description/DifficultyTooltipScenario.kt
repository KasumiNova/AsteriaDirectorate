package cn.kasuminova.astd.combat.automation.scenario.description

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.description.DifficultyDescriptions
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.impl.difficulty.LunaLibSupport
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.loading.Description
import com.fs.starfarer.api.loading.WeaponSpecAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc

class DifficultyTooltipScenario : AbstractAutomationScenario() {

    override val scenarioId: String = SCENARIO_ID

    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    private var finished = false
    private val failures = mutableListOf<String>()
    private val anomalies = mutableListOf<String>()
    private val entries = linkedSetOf<String>()
    private var matrixSteps = 0
    private var baselineMismatches = 0
    private var repeatStable = true
    private var refreshIfChangedStable = true
    private var nativeParagraphWrites = 0
    private var visualEntriesDrawn = 0
    private var visualTruncated = 0
    private var visualElementWidth = 0f
    private var visualElementHeight = 0f
    private var listenerChecked = false
    private var listenerPassed = false
    private var visualScalePrepared = false

    override fun isEnabled(): Boolean {
        val enabled = System.getProperty(ASTDInGameAutomationScenario.ENABLED_PROPERTY)
            ?.equals("true", ignoreCase = true) == true
        return enabled && System.getProperty(ASTDInGameAutomationScenario.SCENARIO_PROPERTY) == SCENARIO_ID
    }

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        ctx.log.info("[ASTD-Automation] scenario=$SCENARIO_ID combat plugin initialized")
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        if (finished) return
        if (ctx.engine.playerShip == null && ctx.elapsed < SHIP_WAIT_SECONDS) return
        finished = true
        try {
            verify()
        } catch (failure: Throwable) {
            ctx.log.error("[ASTD-Automation] $SCENARIO_ID verification aborted", failure)
            failures += "unexpected: ${failure.javaClass.simpleName}: ${failure.message}"
        } finally {
            restore()
        }
        val ship = ctx.engine.playerShip
        ctx.markCompleted()
        if (failures.isNotEmpty() || anomalies.isNotEmpty()) {
            ctx.failureReason = (failures + anomalies).joinToString(" | ")
            ctx.writeDiagnostics("Failed", ship)
            ctx.writeTelemetry("Failed", ship, null)
            ctx.log.error("[ASTD-Automation] $SCENARIO_ID FAIL: ${ctx.failureReason}")
        } else {
            ctx.writeDiagnostics("Completed", ship)
        }
    }

    override fun renderCapture(ctx: AutomationCombatContext) {
        if (!ctx.completed) return
        if (failures.isNotEmpty() || anomalies.isNotEmpty()) return
        if (ctx.visualFramesWritten >= VISUAL_FRAMES.size) return
        if (ctx.visualFramesWritten > 0 && ctx.elapsed - ctx.lastVisualFrameAt < VISUAL_FRAME_INTERVAL) return

        val frame = ctx.visualFramesWritten
        val ship = ctx.engine.playerShip
        prepareVisualScale(ctx)
        ctx.lastVisualFrameAt = ctx.elapsed
        ctx.visualFramesWritten++
        try {
            drawVisualFrame(ctx, frame)
        } catch (failure: Throwable) {
            ctx.log.error("[ASTD-Automation] $SCENARIO_ID visual frame $frame failed", failure)
            failures += "visual frame $frame: ${failure.javaClass.simpleName}: ${failure.message}"
            ctx.failureReason = failures.joinToString(" | ")
            restoreVisualScale(ctx)
            ctx.writeDiagnostics("Failed", ship)
            ctx.writeTelemetry("Failed", ship, null)
            return
        }
        // 最后一帧绘制完成即恢复实际强度：像素已提交到帧缓冲，refresh 只改描述文本，不影响本次取帧。
        if (ctx.visualFramesWritten >= VISUAL_FRAMES.size) {
            restoreVisualScale(ctx)
        }
        ctx.writeDiagnostics("Completed", ship)
        ctx.writeTelemetry("Completed", ship, ship?.allWeapons?.firstOrNull())
        ctx.log.info("[ASTD-Automation] $SCENARIO_ID visual frame $frame drawn")
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        json.appendLine("  \"difficultyPassed\": ${failures.isEmpty() && anomalies.isEmpty()},")
        json.appendLine("  \"difficultyBaselineMismatches\": $baselineMismatches,")
        json.appendLine("  \"difficultyMatrixSteps\": $matrixSteps,")
        json.appendLine("  \"difficultyRepeatRefreshStable\": $repeatStable,")
        json.appendLine("  \"difficultyRefreshIfChangedStable\": $refreshIfChangedStable,")
        json.appendLine("  \"difficultyNativeParagraphWrites\": $nativeParagraphWrites,")
        json.appendLine("  \"difficultyNativeEntries\": ${jsonStringList(entries.toList())},")
        json.appendLine("  \"difficultyNativeAnomalies\": ${jsonStringList(anomalies)},")
        json.appendLine("  \"difficultyFailures\": ${jsonStringList(failures)},")
        json.appendLine("  \"difficultyVisualFrames\": ${ctx.visualFramesWritten},")
        json.appendLine("  \"difficultyVisualEntriesDrawn\": $visualEntriesDrawn,")
        json.appendLine("  \"difficultyVisualTruncated\": $visualTruncated,")
        json.appendLine("  \"difficultyVisualElementWidth\": ${formatFloat(visualElementWidth)},")
        json.appendLine("  \"difficultyVisualElementHeight\": ${formatFloat(visualElementHeight)},")
        json.appendLine("  \"listenerChecked\": $listenerChecked,")
        json.appendLine("  \"listenerPassed\": $listenerPassed,")
    }

    private fun verify() {
        val baseline = DifficultyDescriptions.validateBaseline()
        baselineMismatches = baseline.size
        baseline.forEach { failures += "baseline.$it" }

        MATRIX.forEach { (player, enemy) ->
            DifficultyTuningImpl.installPlayerScaleForTests(player)
            DifficultyTuningImpl.installScaleForTests(enemy)
            DifficultyDescriptions.refresh()
            matrixSteps++

            DifficultyDescriptions.validateApplied().forEach {
                failures += "matrix(player=$player,enemy=$enemy).applied.$it"
            }

            val applied = signature()
            DifficultyDescriptions.refresh()
            DifficultyDescriptions.refresh()
            if (signature() != applied) {
                repeatStable = false
                failures += "matrix(player=$player,enemy=$enemy).repeatRefreshChanged"
            }
            DifficultyDescriptions.refreshIfChanged()
            if (signature() != applied) {
                refreshIfChangedStable = false
                failures += "matrix(player=$player,enemy=$enemy).refreshIfChangedChanged"
            }

            renderNative("matrix(player=$player,enemy=$enemy)")
        }

        verifyLunaListener()
    }

    private fun verifyLunaListener() {
        if (!LunaLibSupport.isAvailable()) {
            ctx.log.info("[ASTD-Automation] $SCENARIO_ID LunaSettings listener check skipped: LunaLib unavailable")
            return
        }
        listenerChecked = true
        try {
            DifficultyTuningImpl.installPlayerScaleForTests(LISTENER_SCALE)
            DifficultyDescriptions.refresh()
            val before = signature()

            DifficultyTuningImpl.installPlayerScaleForTests(LISTENER_SCALE_CHANGED)
            LunaSettingsProbe.reportSettingsChanged()

            val applied = DifficultyDescriptions.validateApplied()
            applied.forEach { failures += "listener.applied.$it" }
            if (signature() == before) {
                failures += "listener.textUnchanged"
            }
            listenerPassed = applied.isEmpty() && signature() != before
        } catch (failure: Throwable) {
            ctx.log.error("[ASTD-Automation] $SCENARIO_ID LunaSettings listener check failed", failure)
            failures += "listener: ${failure.javaClass.simpleName}: ${failure.message}"
        }
    }

    private fun renderNative(stage: String) {
        val settings = Global.getSettings()
        DifficultyDescriptions.weaponIds().sorted().forEach { id ->
            val spec = settings.getWeaponSpec(id)
            renderWeaponParagraph(spec.customPrimary, spec.customPrimaryHL, "$stage/weapon.$id.customPrimary")
            renderWeaponParagraph(spec.customAncillary, spec.customAncillaryHL, "$stage/weapon.$id.customAncillary")
        }
        DifficultyDescriptions.systemIds().sorted().forEach { id ->
            val description = settings.getDescription(id, Description.Type.SHIP_SYSTEM)
            renderPlainParagraph(description.text1, "$stage/system.$id.text1")
            renderPlainParagraph(description.text3, "$stage/system.$id.text3")
            renderPlainParagraph(description.text4, "$stage/system.$id.text4")
            renderPlainParagraph(description.text5, "$stage/system.$id.text5")
        }
    }

    private fun renderWeaponParagraph(body: String?, highlights: String?, location: String) {
        if (body.isNullOrEmpty()) {
            if (!highlights.isNullOrEmpty()) {
                anomalies += "$location: highlights without body"
            }
            return
        }
        try {
            val element = createElement()
            if (highlights == null) {
                element.addPara(body, 0f)
            } else {
                element.addPara(body, 0f, Misc.getHighlightColor(), *highlightParts(highlights))
            }
            nativeParagraphWrites++
        } catch (failure: Throwable) {
            ctx.log.error("[ASTD-Automation] native weapon tooltip failed at $location", failure)
            anomalies += "$location: ${failure.javaClass.simpleName}: ${failure.message}"
        }
    }

    private fun renderPlainParagraph(text: String?, location: String) {
        if (text.isNullOrEmpty()) return
        try {
            createElement().addPara(text, 0f)
            nativeParagraphWrites++
        } catch (failure: Throwable) {
            ctx.log.error("[ASTD-Automation] native ship system tooltip failed at $location", failure)
            anomalies += "$location: ${failure.javaClass.simpleName}: ${failure.message}"
        }
    }

    private fun createElement(): TooltipMakerAPI {
        entries += ENTRY_WEAPON_HIGHLIGHT
        entries += ENTRY_SYSTEM_TEXT
        return Global.getSettings().createCustom(PANEL_WIDTH, PANEL_HEIGHT, null)
            .createUIElement(PANEL_WIDTH, PANEL_HEIGHT, false)
    }

    private fun drawVisualFrame(ctx: AutomationCombatContext, frame: Int) {
        val settings = Global.getSettings()
        val screenWidth = settings.screenWidth
        val screenHeight = settings.screenHeight
        val width = (screenWidth * WIDTH_FRACTION).coerceIn(MIN_WIDTH, MAX_WIDTH)
        val height = screenHeight - VERTICAL_MARGIN * 2f

        val panel = settings.createCustom(width, height, null)
        val element = panel.createUIElement(width, height, false)
        VISUAL_FRAMES[frame].forEach { entry ->
            element.addPara("${entry.kind.name.lowercase()}.${entry.id}.${entry.field}", SECTION_PAD)
            when (entry.kind) {
                EntryKind.WEAPON -> {
                    val spec = settings.getWeaponSpec(entry.id)
                    val body = if (entry.field == FIELD_CUSTOM_PRIMARY) spec.customPrimary else spec.customAncillary
                    val highlights =
                        if (entry.field == FIELD_CUSTOM_PRIMARY) spec.customPrimaryHL else spec.customAncillaryHL
                    addWeaponEntry(element, body, highlights, entry, ctx)
                }

                EntryKind.SYSTEM -> {
                    val description = settings.getDescription(entry.id, Description.Type.SHIP_SYSTEM)
                    val text = if (entry.field == FIELD_TEXT1) description.text1 else description.text5
                    addSystemEntry(element, text, entry, ctx)
                }
            }
        }

        // 必须先挂载元素以初始化面板尺寸，否则渲染时会被零尺寸矩形裁剪。
        panel.addUIElement(element).inTL(0f, 0f)
        panel.position.setSize(width, height)
        panel.position.setLocation(HORIZONTAL_MARGIN, VERTICAL_MARGIN)
        panel.advance(1f)
        panel.render(1f)

        visualElementWidth = element.widthSoFar
        visualElementHeight = element.heightSoFar
        ctx.log.info(
            "[ASTD-Automation] visual frame $frame rendered at ${width}x$height" +
                " content=${element.widthSoFar}x${element.heightSoFar}" +
                " box=${element.position.width}x${element.position.height}" +
                " entries=$visualEntriesDrawn"
        )
    }

    private fun addWeaponEntry(
        element: TooltipMakerAPI,
        body: String?,
        highlights: String?,
        entry: VisualEntry,
        ctx: AutomationCombatContext,
    ) {
        if (body.isNullOrEmpty()) {
            element.addPara("(${entry.id}.${entry.field} 无文本)", SECTION_PAD)
            return
        }
        val sample = visualSample(body, entry, ctx) ?: return
        if (highlights == null) {
            element.addPara(sample, SECTION_PAD)
        } else {
            element.addPara(sample, SECTION_PAD, Misc.getHighlightColor(), *highlightParts(highlights))
        }
        visualEntriesDrawn++
    }

    private fun addSystemEntry(
        element: TooltipMakerAPI,
        text: String?,
        entry: VisualEntry,
        ctx: AutomationCombatContext,
    ) {
        if (text.isNullOrEmpty()) {
            element.addPara("(${entry.id}.${entry.field} 无文本)", SECTION_PAD)
            return
        }
        val sample = visualSample(text, entry, ctx) ?: return
        element.addPara(sample, SECTION_PAD)
        visualEntriesDrawn++
    }

    private fun visualSample(text: String, entry: VisualEntry, ctx: AutomationCombatContext): String? {
        val lines = text.split('\n')
        if (lines.size <= MAX_VISUAL_LINES) return text
        visualTruncated++
        ctx.log.info(
            "[ASTD-Automation] visual sample truncated at ${entry.id}.${entry.field}: ${lines.size} lines"
        )
        return lines.take(MAX_VISUAL_LINES).joinToString("\n") + "\n…（视觉抽样截断）"
    }

    private fun highlightParts(highlights: String): Array<String> =
        highlights.split('|').map(String::trim).toTypedArray()

    private fun signature(): String = buildString {
        val settings = Global.getSettings()
        DifficultyDescriptions.weaponIds().sorted().forEach { id ->
            val spec: WeaponSpecAPI = settings.getWeaponSpec(id)
            append(spec.customPrimary).append('\u0001')
            append(spec.customPrimaryHL).append('\u0001')
            append(spec.customAncillary).append('\u0001')
            append(spec.customAncillaryHL).append('\u0002')
        }
        DifficultyDescriptions.systemIds().sorted().forEach { id ->
            val description = settings.getDescription(id, Description.Type.SHIP_SYSTEM)
            append(description.text1).append('\u0001')
            append(description.text3).append('\u0001')
            append(description.text4).append('\u0001')
            append(description.text5).append('\u0002')
        }
    }

    private fun prepareVisualScale(ctx: AutomationCombatContext) {
        if (visualScalePrepared) return
        DifficultyTuningImpl.installPlayerScaleForTests(VISUAL_SCALE)
        DifficultyDescriptions.refresh()
        visualScalePrepared = true
        ctx.log.info("[ASTD-Automation] $SCENARIO_ID visual scale prepared: player=$VISUAL_SCALE")
    }

    private fun restoreVisualScale(ctx: AutomationCombatContext) {
        visualScalePrepared = false
        restore()
        ctx.log.info("[ASTD-Automation] $SCENARIO_ID visual scale restored")
    }

    private fun restore() {
        DifficultyTuningImpl.installScaleForTests(null)
        DifficultyTuningImpl.installPlayerScaleForTests(null)
        try {
            DifficultyDescriptions.refresh()
        } catch (failure: Throwable) {
            ctx.log.error("[ASTD-Automation] $SCENARIO_ID failed to restore descriptions", failure)
            failures += "restore: ${failure.javaClass.simpleName}: ${failure.message}"
        }
    }

    private enum class EntryKind { WEAPON, SYSTEM }

    private data class VisualEntry(val kind: EntryKind, val id: String, val field: String)

    private companion object {
        private const val SCENARIO_ID = "difficulty_tooltips"
        private const val PANEL_WIDTH = 720f
        private const val PANEL_HEIGHT = 480f
        private const val SHIP_WAIT_SECONDS = 1f
        private const val FIELD_CUSTOM_PRIMARY = "customPrimary"
        private const val FIELD_TEXT1 = "text1"
        private const val ENTRY_WEAPON_HIGHLIGHT =
            "weapon.customPrimary/customAncillary+HL@TooltipMakerAPI.addPara(String,float,Color,String[])"

        private const val ENTRY_SYSTEM_TEXT = "system.text1/text3/text4/text5@TooltipMakerAPI.addPara(String,float)"

        private const val WIDTH_FRACTION = 0.44f
        private const val MIN_WIDTH = 720f
        private const val MAX_WIDTH = 900f
        private const val HORIZONTAL_MARGIN = 60f
        private const val VERTICAL_MARGIN = 60f
        private const val SECTION_PAD = 8f
        private const val MAX_VISUAL_LINES = 18
        private const val VISUAL_FRAME_INTERVAL = 0.2f

        private const val VISUAL_SCALE = 1f
        private const val LISTENER_SCALE = 3f
        private const val LISTENER_SCALE_CHANGED = 4f

        private val MATRIX = listOf(
            1f to 1f,
            2f to 2f,
            5f to 5f,
            1f to 5f,
            5f to 1f,
            2f to 2f,
            2f to 2.5f,
        )

        private val VISUAL_FRAMES = listOf(
            listOf(
                VisualEntry(EntryKind.WEAPON, "astd_starfall_wing", FIELD_CUSTOM_PRIMARY),
                VisualEntry(EntryKind.WEAPON, "astd_starfall_wing", "customAncillary"),
            ),
            listOf(
                VisualEntry(EntryKind.SYSTEM, "astd_grav_rift_generator", FIELD_TEXT1),
                VisualEntry(EntryKind.SYSTEM, "astd_grav_rift_generator", "text5"),
            ),
            listOf(
                VisualEntry(EntryKind.SYSTEM, "astd_grav_storm", "text5"),
                VisualEntry(EntryKind.SYSTEM, "astd_grav_replicator", "text5"),
            ),
        )
    }
}
