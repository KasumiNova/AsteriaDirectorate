package cn.kasuminova.astd.ui

import cn.kasuminova.astd.testutil.RepoLayout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class HullModTooltipBackgroundTest {

    @Test
    fun `hullmod tooltip renderer uses lattice pulse background instead of nebula`() {
        val renderer = Files.readString(
            RepoLayout.mainSourceFile("combat/hullmods/base/ASTDHullModTooltipRenderer.kt")!!,
        )
        assertTrue(
            renderer.contains("withLatticePulseBackground"),
            "HullMod tooltip renderer must use the Lattice Pulse background DSL.",
        )
        assertTrue(
            !renderer.contains("withNebulaBackground"),
            "HullMod tooltip renderer must not use the old nebula background.",
        )
        assertTrue(
            renderer.contains("theme.accentColor"),
            "Lattice Pulse background color must follow the hullmod theme accent color.",
        )
    }

    @Test
    fun `lattice pulse background mirrors tooltip editor shader constants`() {
        val source = Files.readString(
            RepoLayout.mainSourceFile("ui/effect/ASTDLatticePulseTooltipBackground.kt")!!,
        )

        assertTrue(source.contains("lattice-pulse"), "Game-side background must keep the editor shader preset id.")
        assertTrue(source.contains("GL20.glUseProgram"), "Lattice Pulse must be rendered by the game-side GLSL pipeline.")
        assertTrue(!source.contains("GL11.GL_LINES"), "Lattice Pulse must not fall back to line-segment approximation.")
        assertTrue(source.contains("LATTICE_COLUMNS: Int = 18"), "Lattice Pulse columns must mirror the editor shader.")
        assertTrue(source.contains("LATTICE_ROWS: Int = 11"), "Lattice Pulse rows must mirror the editor shader.")
        assertTrue(source.contains("0.55f + 0.45f * sin"), "Lattice Pulse must keep the editor pulse formula.")
        assertTrue(source.contains("LINE_ALPHA = 0.13f"), "Lattice Pulse line alpha must mirror the editor shader.")
    }

    @Test
    fun `hullmod tooltip table keeps original table style with compact row height`() {
        val renderer = Files.readString(
            RepoLayout.mainSourceFile("combat/hullmods/base/ASTDHullModTooltipRenderer.kt")!!,
        )

        assertTrue(
            renderer.contains("beginTable(") && renderer.contains("addTable("),
            "Mode attribute tables should keep Starsector's original table widget.",
        )
        assertTrue(
            renderer.contains("TABLE_ROW_HEIGHT = 24f"),
            "Mode attribute tables must pass a compact row height to beginTable.",
        )
        assertTrue(
            renderer.contains("Misc.getBrightPlayerColor()"),
            "Mode attribute table headers must use a visible bright color.",
        )
        assertTrue(
            renderer.contains("Misc.getBasePlayerColor()") && renderer.contains("Misc.getDarkPlayerColor()"),
            "Mode attribute tables must use the original Starsector table colors.",
        )
        assertTrue(
            renderer.contains("valueWidth = tableWidth - labelWidth"),
            "Mode attribute table columns must fill the tooltip width.",
        )
        assertTrue(
            !renderer.contains("addLabelledValue"),
            "Mode attribute tables must not be converted into labelled-value rows.",
        )
    }

    @Test
    fun `arc flare mode hullmod names match tooltip exports`() {
        val i18n = Files.readString(RepoLayout.astdCsvRoot.resolve("src/main/resources/i18n/zh-cn.properties"))

        assertTrue(
            i18n.contains("hullmod.astd_arc_flare_mode_crewed.name=弧光耀斑 - 载人模式"),
            "Crewed mode hullmod name must match the tooltip editor export.",
        )
        assertTrue(
            i18n.contains("hullmod.astd_arc_flare_mode_automated.name=弧光耀斑 - 自动模式"),
            "Automated mode hullmod name must match the tooltip editor export.",
        )
    }
}
