package cn.kasuminova.astd.campaign.story

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 「执行官」签发的一次性状态与常量口径验证：
 * 终局状态默认值即未签发语义（旧档兼容）、combat/admin 特化类型与修正来源互斥、
 * 延迟档天数与周期裁定对齐、签发入口在未归档/无星区时的拒绝路径。
 */
class EndingExecutiveCoreTest {

    @Test
    fun `终局状态默认值即未签发与无延迟档语义`() {
        val ending = EndingState()
        assertFalse(ending.archiveImmediateApplied)
        assertEquals(-1L, ending.archiveDelayedDueTimestamp, "-1 = 无延迟档排期")
        assertFalse(ending.archiveDelayedApplied)
        assertEquals("", ending.executiveCoreType, "空串 = 未签发（待玩家终端选择）")
        assertEquals("", ending.commandShipId, "空串 = 未指定指挥舰，回落玩家旗舰")
    }

    @Test
    fun `特化类型与修正来源常量口径`() {
        assertEquals("combat", EndingKeys.CORE_TYPE_COMBAT)
        assertEquals("admin", EndingKeys.CORE_TYPE_ADMIN)
        assertNotEquals(EndingKeys.CORE_TYPE_COMBAT, EndingKeys.CORE_TYPE_ADMIN)
        // 三个修正来源 id 互不相同：同 id 会在市场/舰船统计上互相覆盖与误清
        val modIds = listOf(EndingKeys.BOOST_MOD_ID, EndingKeys.COMMAND_MOD_ID, EndingKeys.ADMIN_MOD_ID)
        assertEquals(modIds.size, modIds.toSet().size, "终局修正来源 id 必须互不相同: $modIds")
        assertTrue(EndingKeys.I18N_CATEGORY.isNotBlank())
    }

    @Test
    fun `延迟档天数与周期裁定对齐（13 文档）`() {
        // 1 周期 = 30 星区天：封存延迟 2 周期 = 60 天，交易其余势力延迟 1 周期 = 30 天
        assertEquals(60f, EndingKeys.CYCLE_DAYS * ArchiveBoost.SEALED_DELAY_CYCLES)
        assertEquals(30f, EndingKeys.CYCLE_DAYS * ArchiveBoost.TRADED_DELAY_CYCLES)
    }

    @Test
    fun `未归档时不可签发且读取为空`() {
        // 无星区环境下状态读数为全新默认：未签发合同 → 不可签发、无已签发类型
        assertFalse(EndingSettlement.canIssueExecutiveCore())
        assertEquals("", EndingSettlement.issuedCoreType())
    }

    @Test
    fun `签发入口在无星区环境下拒绝一切请求`() {
        // 签发守卫必须先于状态写入：无星区时 combat/admin/未知类型一律拒绝且不抛异常
        assertFalse(EndingSettlement.issueExecutiveCore(EndingKeys.CORE_TYPE_COMBAT))
        assertFalse(EndingSettlement.issueExecutiveCore(EndingKeys.CORE_TYPE_ADMIN))
        assertFalse(EndingSettlement.issueExecutiveCore("bogus"))
    }
}
