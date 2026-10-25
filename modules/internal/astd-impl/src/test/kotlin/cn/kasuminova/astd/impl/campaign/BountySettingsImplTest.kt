package cn.kasuminova.astd.impl.campaign

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BountySettingsImplTest {

    @Test
    fun `默认不补齐至游戏舰队上限`() {
        assertFalse(BountySettingsImpl.fillToFleetCap)
    }

    @Test
    fun `设置回调更新舰队补齐模式`() {
        try {
            BountySettingsImpl.applyResolvedFillToFleetCap(true)
            assertTrue(BountySettingsImpl.fillToFleetCap)
            BountySettingsImpl.applyResolvedFillToFleetCap(false)
            assertFalse(BountySettingsImpl.fillToFleetCap)
        } finally {
            BountySettingsImpl.applyResolvedFillToFleetCap(BountySettingsImpl.DEFAULT_FILL_TO_FLEET_CAP)
        }
    }
}
