package cn.kasuminova.astd.combat.hullmods.base

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ASTDDualModeConfigTest {

    private fun sampleConfig(hullId: String = "astd_test") = ASTDDualModeConfig(
        switcherId = "astd_dual_mode_switcher",
        crewedModeId = "${hullId}_mode_crewed",
        automatedModeId = "${hullId}_mode_automated",
        nextCrewedMarker = "${hullId}_next_crewed",
        nextAutomatedMarker = "${hullId}_next_automated",
        crewedSystemId = "${hullId}_sys_crewed",
        automatedSystemId = "${hullId}_sys_automated",
    )

    @Test
    fun `config mode and marker and system ids are distinct and non-blank`() {
        val cfg = sampleConfig()
        assertNotEquals(cfg.crewedModeId, cfg.automatedModeId)
        assertNotEquals(cfg.nextCrewedMarker, cfg.nextAutomatedMarker)
        assertNotEquals(cfg.crewedSystemId, cfg.automatedSystemId)
        val all = listOfNotNull(
            cfg.switcherId,
            cfg.crewedModeId, cfg.automatedModeId,
            cfg.nextCrewedMarker, cfg.nextAutomatedMarker,
            cfg.crewedSystemId, cfg.automatedSystemId,
        )
        all.forEach { assertTrue(it.isNotBlank(), "id must not be blank: '$it'") }
        assertEquals(all.size, all.toSet().size, "all config ids must be unique: $all")
    }

    @Test
    fun `registry register then configFor round-trips`() {
        val cfg = sampleConfig("astd_roundtrip")
        ASTDDualModeRegistry.register("astd_roundtrip", cfg)
        assertSame(cfg, ASTDDualModeRegistry.configFor("astd_roundtrip"))
    }

    @Test
    fun `registry configFor returns null for unknown or null hull id`() {
        assertNull(ASTDDualModeRegistry.configFor("astd_never_registered_hull"))
        assertNull(ASTDDualModeRegistry.configFor(null))
    }
}
