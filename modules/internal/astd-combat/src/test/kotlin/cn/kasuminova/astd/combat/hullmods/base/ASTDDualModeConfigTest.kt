package cn.kasuminova.astd.combat.hullmods.base

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 通用双模式框架测试。
 *
 * 可纯单测的逻辑：[ASTDDualModeConfig] 字段自洽 + [ASTDDualModeRegistry] 的 register/configFor 往返。
 * 状态机扩展函数（ensureASTDDualModeState 等）依赖 ShipVariantAPI 引擎接口，由 Task 4 实机切换验证，不在此单测。
 */
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
        // 同一对（mode / marker / system）的载人、无人 id 必须互不相同，否则状态机会自我覆盖
        assertNotEquals(cfg.crewedModeId, cfg.automatedModeId)
        assertNotEquals(cfg.nextCrewedMarker, cfg.nextAutomatedMarker)
        assertNotEquals(cfg.crewedSystemId, cfg.automatedSystemId)
        // 全字段非空
        val all = listOf(
            cfg.switcherId,
            cfg.crewedModeId, cfg.automatedModeId,
            cfg.nextCrewedMarker, cfg.nextAutomatedMarker,
            cfg.crewedSystemId, cfg.automatedSystemId,
        )
        all.forEach { assertTrue(it.isNotBlank(), "id must not be blank: '$it'") }
        // 七个字段两两不重复（switcher 与各 mode/marker/system 也不应撞 id）
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
