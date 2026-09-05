package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.campaign.bounty.MainBounties
import cn.kasuminova.astd.campaign.world.StoryWorldIds
import cn.kasuminova.astd.campaign.world.StoryWorldSpecs
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 第二章遗址实地交互（[StorySites] / [StoryCargo]）验证：
 * - 赏金目标 ↔ 遗址实体映射的完整性与唯一性，且目标实体真实存在于世界生成规格；
 * - 交互状态机（接取/击退门槛/排斥/回收/待交付/已核销）真值表；
 * - 托管资产登记的受理与拒绝路径。
 */
class StorySitesTest {

    // ------------------------------------------------------------------
    // 映射
    // ------------------------------------------------------------------

    @Test
    fun `第二章全部工单都有遗址目标实体且互不重复`() {
        val chapterTwoKeys = MainBounties.chapterMembers.getValue(2)
        assertEquals(7, chapterTwoKeys.size, "第二章应为 3 单星坠 + 4 阶段紫菀")

        val entityIds = chapterTwoKeys.map { key ->
            StorySites.targetId(key) ?: error("工单 '$key' 缺少遗址目标实体")
        }
        assertEquals(entityIds.size, entityIds.toSet().size, "目标实体不允许复用")

        // 反向解析一致
        for ((key, entityId) in chapterTwoKeys.zip(entityIds)) {
            assertEquals(key, StorySites.siteForEntity(entityId)?.bountyKey)
        }
        assertNull(StorySites.targetId("astd_main_c1_b3"), "第一章工单不属于遗址实地交互")
    }

    @Test
    fun `目标映射口径：星坠由外向内递进，紫菀节点对号、核心在主站`() {
        assertEquals(StoryWorldIds.STARFALL_STATION_RESERVED, StorySites.targetId(StorySites.KEY_C2_XC_1))
        assertEquals(StoryWorldIds.STARFALL_STATION_DOCKYARD, StorySites.targetId(StorySites.KEY_C2_XC_2))
        assertEquals(StoryWorldIds.STARFALL_STATION_MAIN, StorySites.targetId(StorySites.KEY_C2_XC_3))
        assertEquals(StoryWorldIds.ASTER_GRAVITY_NODE_1, StorySites.targetId(StorySites.KEY_C2_ZW_S1))
        assertEquals(StoryWorldIds.ASTER_GRAVITY_NODE_2, StorySites.targetId(StorySites.KEY_C2_ZW_S2))
        assertEquals(StoryWorldIds.ASTER_GRAVITY_NODE_3, StorySites.targetId(StorySites.KEY_C2_ZW_S3))
        assertEquals(StoryWorldIds.ASTER_STATION_MAIN, StorySites.targetId(StorySites.KEY_C2_ZW_S4))
    }

    @Test
    fun `目标与描述站实体都真实存在于世界生成规格`() {
        val allIds = buildSet {
            addAll(StoryWorldSpecs.mainSystem(Random(1)).allEntityIds())
            addAll(StoryWorldSpecs.starfallSystem(Random(2)).allEntityIds())
            addAll(StoryWorldSpecs.asterSystem(Random(3)).allEntityIds())
        }
        for (site in StorySites.sites) {
            assertTrue(site.targetEntityId in allIds, "目标实体不存在于生成规格：${site.targetEntityId}")
        }
        for (entityId in StorySites.flavorEntityTextKeys.keys) {
            assertTrue(entityId in allIds, "描述站实体不存在于生成规格：$entityId")
        }
    }

    @Test
    fun `仅星坠核心库与紫菀核心需要实地回收托管资产`() {
        assertEquals(
            setOf(StorySites.KEY_C2_XC_3, StorySites.KEY_C2_ZW_S4),
            StorySites.sites.filter { it.requiresAsset }.map { it.bountyKey }.toSet(),
        )
        assertFalse(StorySites.requiresAsset(StorySites.KEY_C2_XC_1))
        assertFalse(StorySites.requiresAsset("astd_main_c3_1"))
    }

    @Test
    fun `描述站与赏金目标站不重叠`() {
        for (entityId in StorySites.flavorEntityTextKeys.keys) {
            assertNull(StorySites.siteForEntity(entityId), "描述站不应同时是赏金目标：$entityId")
        }
    }

    // ------------------------------------------------------------------
    // 交互状态机
    // ------------------------------------------------------------------

    private fun resolve(
        key: String,
        accepted: Boolean = false,
        defeated: Boolean = false,
        succeeded: Boolean = false,
        hasAsset: Boolean = false,
        breached: Set<String> = emptySet(),
    ): StorySites.SiteInteraction = StorySites.resolveInteraction(
        StorySites.sitesByBountyKey.getValue(key), accepted, defeated, succeeded, hasAsset, breached,
    )

    @Test
    fun `未接取为锁定描述，接取后可交战`() {
        assertEquals(StorySites.SiteInteraction.LOCKED, resolve(StorySites.KEY_C2_XC_1))
        assertEquals(StorySites.SiteInteraction.ENGAGE, resolve(StorySites.KEY_C2_XC_1, accepted = true))
    }

    @Test
    fun `节点二与三须依序破除前序节点`() {
        assertEquals(StorySites.SiteInteraction.ORDER_LOCKED, resolve(StorySites.KEY_C2_ZW_S2))
        assertEquals(
            StorySites.SiteInteraction.ORDER_LOCKED,
            resolve(StorySites.KEY_C2_ZW_S3, breached = setOf(StorySites.KEY_C2_ZW_S1)),
        )
        assertEquals(
            StorySites.SiteInteraction.LOCKED,
            resolve(
                StorySites.KEY_C2_ZW_S3,
                breached = setOf(StorySites.KEY_C2_ZW_S1, StorySites.KEY_C2_ZW_S2),
            ),
        )
    }

    @Test
    fun `核心在三节点未全部破除时被排斥且不可强闯`() {
        assertEquals(StorySites.SiteInteraction.REPULSED, resolve(StorySites.KEY_C2_ZW_S4))
        assertEquals(
            StorySites.SiteInteraction.REPULSED,
            resolve(
                StorySites.KEY_C2_ZW_S4,
                breached = setOf(StorySites.KEY_C2_ZW_S1, StorySites.KEY_C2_ZW_S3),
            ),
        )
        val allBreached = setOf(StorySites.KEY_C2_ZW_S1, StorySites.KEY_C2_ZW_S2, StorySites.KEY_C2_ZW_S3)
        assertEquals(StorySites.SiteInteraction.LOCKED, resolve(StorySites.KEY_C2_ZW_S4, breached = allBreached))
        assertEquals(
            StorySites.SiteInteraction.ENGAGE,
            resolve(StorySites.KEY_C2_ZW_S4, accepted = true, breached = allBreached),
        )
    }

    @Test
    fun `赢下战斗不等于已核销：资产单须先回收再交付`() {
        // 非资产单：赢下后直接进入待交付
        assertEquals(StorySites.SiteInteraction.AWAIT_HANDIN, resolve(StorySites.KEY_C2_XC_1, defeated = true))

        // 资产单：赢下后先回收，回收后待交付
        assertEquals(StorySites.SiteInteraction.RECOVER, resolve(StorySites.KEY_C2_XC_3, defeated = true))
        assertEquals(
            StorySites.SiteInteraction.AWAIT_HANDIN,
            resolve(StorySites.KEY_C2_XC_3, defeated = true, hasAsset = true),
        )

        // 核销后只余现场描述；核销优先于一切中间态
        assertEquals(
            StorySites.SiteInteraction.DONE,
            resolve(StorySites.KEY_C2_ZW_S4, accepted = true, defeated = true, succeeded = true),
        )
    }

    // ------------------------------------------------------------------
    // 托管资产登记
    // ------------------------------------------------------------------

    @Test
    fun `托管资产仅受理资产单，登记与交付成对`() {
        val cargo = StoryCargo()
        assertFalse(cargo.hasAsset(StorySites.KEY_C2_XC_3))

        assertFalse(cargo.collect(StorySites.KEY_C2_XC_1), "非资产单不得登记")
        assertTrue(cargo.collect(StorySites.KEY_C2_XC_3))
        assertTrue(cargo.hasAsset(StorySites.KEY_C2_XC_3))
        assertFalse(cargo.collect(StorySites.KEY_C2_XC_3), "重复登记应被拒绝")

        assertTrue(cargo.handIn(StorySites.KEY_C2_XC_3))
        assertFalse(cargo.hasAsset(StorySites.KEY_C2_XC_3))
        assertFalse(cargo.handIn(StorySites.KEY_C2_XC_3), "未持有不得交付")
    }
}
