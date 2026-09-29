package cn.kasuminova.astd.campaign.bounty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 赏金舰队后处理的纯逻辑校验：
 * - 护航排除判定（独特舰 + 逐电）；
 * - SMod 优先级预设的清单选择（专属覆盖 / 相位 / 航母 / 主力 / 通用）；
 * - 预设候选不与赏金旗舰 Bounty 变体已装配的普通船插重叠（保证 SMod 槽位有新候选可插）。
 */
class BountyFleetTuningTest {

    private val presets = BountySmodPresetsImpl()

    @Test
    fun `独特舰与逐电被排除在护航位之外`() {
        // 四艘独特舰（含发布范围外的决明）一律排除
        assertTrue(BountyFleetTunerImpl.isEscortExcludedHull("astd_xc_001", astdUnique = true))
        assertTrue(BountyFleetTunerImpl.isEscortExcludedHull("astd_xc_002", astdUnique = true))
        assertTrue(BountyFleetTunerImpl.isEscortExcludedHull("astd_zw_001", astdUnique = true))
        assertTrue(BountyFleetTunerImpl.isEscortExcludedHull("astd_zw_002", astdUnique = true))
        // 逐电不是独特舰，按 id 显式排除
        assertTrue(BountyFleetTunerImpl.isEscortExcludedHull("astd_xc_104", astdUnique = false))
        // 量产舰正常放行
        assertFalse(BountyFleetTunerImpl.isEscortExcludedHull("astd_xc_101", astdUnique = false))
        assertFalse(BountyFleetTunerImpl.isEscortExcludedHull("astd_xc_102", astdUnique = false))
        assertFalse(BountyFleetTunerImpl.isEscortExcludedHull("astd_xc_103", astdUnique = false))
        assertFalse(BountyFleetTunerImpl.isEscortExcludedHull("astd_zw_101", astdUnique = false))
        assertFalse(BountyFleetTunerImpl.isEscortExcludedHull("astd_zw_102", astdUnique = false))
        assertFalse(BountyFleetTunerImpl.isEscortExcludedHull("astd_zw_103", astdUnique = false))
        assertFalse(BountyFleetTunerImpl.isEscortExcludedHull("astd_lh_001", astdUnique = false))
        assertFalse(BountyFleetTunerImpl.isEscortExcludedHull("astd_lh_002", astdUnique = false))
    }

    @Test
    fun `SMod 预设按舰型特征选择清单`() {
        assertEquals(
            BountySmodPresetsImpl.PHASE_PRIORITY,
            presets.prioritiesFor("astd_zw_101", phase = true, carrier = false, capital = false),
        )
        assertEquals(
            BountySmodPresetsImpl.CARRIER_PRIORITY,
            presets.prioritiesFor("astd_zw_102", phase = false, carrier = true, capital = true),
        )
        assertEquals(
            BountySmodPresetsImpl.CAPITAL_PRIORITY,
            presets.prioritiesFor("astd_xc_102", phase = false, carrier = false, capital = true),
        )
        assertEquals(
            BountySmodPresetsImpl.GENERIC_PRIORITY,
            presets.prioritiesFor("astd_xc_103", phase = false, carrier = false, capital = false),
        )
    }

    @Test
    fun `赏金旗舰命中专属覆盖清单且与变体普通船插不重叠`() {
        // 专属覆盖优先于任何通用清单
        assertEquals(
            BountySmodPresetsImpl.HULL_OVERRIDES.getValue("astd_zw_002"),
            presets.prioritiesFor("astd_zw_002", phase = true, carrier = false, capital = false),
        )
        // 三个旗舰变体已装配的普通船插（contents/data/variants 下 *_Bounty.variant 的 hullMods）
        val fittedByVariant = mapOf(
            "astd_xc_001" to setOf("targetingunit", "fluxdistributor", "hardenedshieldemitter", "armoredweapons"),
            "astd_xc_002" to setOf("expanded_deck_crew", "fluxdistributor", "turretgyros"),
            "astd_zw_002" to setOf("ex_phase_coils", "fluxdistributor", "fluxcoil"),
        )
        fittedByVariant.forEach { (hullId, fitted) ->
            val override = BountySmodPresetsImpl.HULL_OVERRIDES.getValue(hullId)
            assertTrue(override.none { it in fitted }, "$hullId 专属 SMod 候选与变体普通船插重叠")
        }
    }

    @Test
    fun `不同特征清单之间确实存在差异`() {
        assertNotEquals(BountySmodPresetsImpl.PHASE_PRIORITY, BountySmodPresetsImpl.GENERIC_PRIORITY)
        assertNotEquals(BountySmodPresetsImpl.CARRIER_PRIORITY, BountySmodPresetsImpl.GENERIC_PRIORITY)
        assertNotEquals(BountySmodPresetsImpl.CAPITAL_PRIORITY, BountySmodPresetsImpl.GENERIC_PRIORITY)
    }
}
