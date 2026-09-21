package cn.kasuminova.astd.combat.hullmods

import cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityStabilizerHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.impl.campaign.ids.HullMods
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * 奇点稳定器锚定判定（[ASTDSingularityStabilizerHullMod.resolveTimeAnchor] 纯函数全分支）
 * 与安全协议超驰五路清理（applyEffectsBeforeShipCreation → stripForbiddenHullMods）验证。
 */
class SingularityStabilizerHullModTest {

    @Test
    fun `零时流异常态 不钳不补`() {
        val d = ASTDSingularityStabilizerHullMod.resolveTimeAnchor(0f)
        assertNull(d.clampMult)
        assertNull(d.anchor)
    }

    @Test
    fun `时流被压低 钳制优先且关闭锚定`() {
        val d = ASTDSingularityStabilizerHullMod.resolveTimeAnchor(0.5f)
        assertEquals(2f, d.clampMult!!, 1e-6f)
        assertNull(d.anchor)
    }

    @Test
    fun `时流恰为一及容差带内 不动`() {
        listOf(1f, 0.9995f, 1.0005f).forEach { raw ->
            val d = ASTDSingularityStabilizerHullMod.resolveTimeAnchor(raw)
            assertNull(d.clampMult, "raw=$raw 不应钳制")
            assertNull(d.anchor, "raw=$raw 不应锚定")
        }
    }

    @Test
    fun `时流加速 仅锚定峰值与 CR`() {
        val d = ASTDSingularityStabilizerHullMod.resolveTimeAnchor(2f)
        assertNull(d.clampMult)
        assertEquals(0.5f, d.anchor!!, 1e-6f)
    }

    @Test
    fun `安全协议超驰五路清理`() {
        val sMods = linkedSetOf(HullMods.SAFETYOVERRIDES)
        val sModdedBuiltIns = linkedSetOf(HullMods.SAFETYOVERRIDES)
        val variant = mock(ShipVariantAPI::class.java)
        `when`(variant.sMods).thenReturn(sMods)
        `when`(variant.sModdedBuiltIns).thenReturn(sModdedBuiltIns)
        val stats = mock(MutableShipStatsAPI::class.java)
        `when`(stats.variant).thenReturn(variant)

        ASTDSingularityStabilizerHullMod()
            .applyEffectsBeforeShipCreation(ShipAPI.HullSize.FRIGATE, stats, "test")

        verify(variant).removeMod(HullMods.SAFETYOVERRIDES)
        verify(variant).removePermaMod(HullMods.SAFETYOVERRIDES)
        verify(variant).removeSuppressedMod(HullMods.SAFETYOVERRIDES)
        assertFalse(sMods.contains(HullMods.SAFETYOVERRIDES))
        assertFalse(sModdedBuiltIns.contains(HullMods.SAFETYOVERRIDES))
    }
}
