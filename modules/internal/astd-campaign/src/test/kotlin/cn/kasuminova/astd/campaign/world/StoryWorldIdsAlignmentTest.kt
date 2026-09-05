package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.campaign.bounty.BountyKeys
import cn.kasuminova.astd.campaign.bounty.InfiniteBountyState
import cn.kasuminova.astd.campaign.story.EndingKeys
import cn.kasuminova.astd.campaign.story.StoryCargo
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 关键 ID 对齐校验：持久化键/memory 键规范与互斥、赏金出现门槛触发市场与分局空间站对齐、
 * 三世界规格实体 ID 统一模组前缀、IndEvo 扩展锚点不与生成实体冲突。
 */
internal class StoryWorldIdsAlignmentTest {

    private fun allSpecs(random: Random = Random(5L)) = listOf(
        StoryWorldSpecs.mainSystem(random),
        StoryWorldSpecs.starfallSystem(random),
        StoryWorldSpecs.asterSystem(random),
    )

    @Test
    fun `persistent state keys are mutually distinct`() {
        val keys = listOf(
            BountyKeys.PERSISTENT_STATE_KEY,
            InfiniteBountyState.PERSISTENT_STATE_KEY,
            StoryCargo.PERSISTENT_KEY,
            EndingKeys.PERSISTENT_STATE_KEY,
            StoryWorldIds.PERSISTENT_STATE_KEY,
        )
        assertEquals(keys.size, keys.toSet().size, "persistentData 键冲突会导致读档串状态: $keys")
    }

    @Test
    fun `memory keys carry dollar prefix and are mutually distinct`() {
        val keys = listOf(
            BountyKeys.MEM_PROLOGUE_DOC_RECEIVED,
            BountyKeys.MEM_ARCHIVE_PENDING,
            BountyKeys.MEM_ARCHIVE_CHOICE,
            BountyKeys.MEM_ARCHIVE_TRADE_FACTION,
            BountyKeys.MEM_INFINITE_CONTRACTOR,
            BountyKeys.MEMORY_MANAGER_ADDED,
            BountyKeys.MEMORY_STORY_RUNTIME_ADDED,
            BountyKeys.MEM_K,
            BountyKeys.MEM_TOTAL_MULT,
            BountyKeys.MEM_FLEET_PATCHED,
            BountyKeys.MEM_AFFIXES,
            BountyKeys.MEM_BOUNTY_KEY,
            BountyKeys.MEM_SUCCESS_TEXT,
            BountyKeys.MEM_SUCCESS_SHOWN,
            EndingKeys.MEMORY_ENDING_RUNTIME_ADDED,
            EndingKeys.MEM_EXECUTIVE_CORE_TYPE,
            StoryWorldIds.MEM_STORY_ROLE,
        )
        for (key in keys) {
            assertTrue(key.startsWith("\$"), "sector/fleet memory 键必须带 \$ 前缀: $key")
        }
        assertEquals(keys.size, keys.toSet().size, "memory 键冲突会互相覆盖状态: $keys")
    }

    @Test
    fun `bounty trigger market ids pin to branch office station`() {
        // 双段口径：市场 id 先行匹配，实体 id 兜底拒绝其余市场（缺失任一工单会全市场可挂）。
        assertEquals(
            listOf(
                StoryWorldIds.marketIdFor(StoryWorldIds.MAIN_STATION_BRANCH),
                StoryWorldIds.MAIN_STATION_BRANCH,
            ),
            BountyKeys.STATION_TRIGGER_MARKET_IDS,
        )
    }

    @Test
    fun `story system ids carry story prefix and are mutually distinct`() {
        val systems = listOf(
            StoryWorldIds.SYSTEM_MAIN,
            StoryWorldIds.SYSTEM_STARFALL,
            StoryWorldIds.SYSTEM_ASTER,
        )
        assertEquals(systems.size, systems.toSet().size, "星系 ID 必须互不相同: $systems")
        assertTrue(systems.all { it.startsWith(StoryWorldIds.ID_PREFIX) }, "星系 ID 必须带剧情前缀: $systems")
    }

    @Test
    fun `all generated entity ids carry mod prefix`() {
        for (spec in allSpecs()) {
            for (id in spec.allEntityIds()) {
                assertTrue(id.startsWith("astd_"), "实体 ID 必须带模组前缀以避免与原版/其它模组冲突: $id")
            }
        }
    }

    @Test
    fun `custom condition ids carry mod prefix and are mutually distinct`() {
        val conditions = listOf(
            StoryWorldIds.COND_ADMIN_RUINS,
            StoryWorldIds.COND_STARFALL_ENG_RUINS,
            StoryWorldIds.COND_EVENT_HORIZON_POWER,
            StoryWorldIds.COND_ASTER_RESEARCH_RUINS,
        )
        assertEquals(conditions.size, conditions.toSet().size, "自定义状况 ID 必须互不相同: $conditions")
        assertTrue(conditions.all { it.startsWith("astd_cond_") }, "自定义状况 ID 口径为 astd_cond_*: $conditions")
    }

    @Test
    fun `indEvo watchtower anchor ids do not collide with generated entities`() {
        val anchors = listOf(
            StoryWorldIds.MAIN_INDEVO_STABLE_1,
            StoryWorldIds.MAIN_INDEVO_STABLE_2,
            StoryWorldIds.MAIN_INDEVO_STABLE_3,
            StoryWorldIds.MAIN_INDEVO_STABLE_4,
            StoryWorldIds.STARFALL_INDEVO_STABLE_1,
            StoryWorldIds.STARFALL_INDEVO_STABLE_2,
            StoryWorldIds.STARFALL_INDEVO_STABLE_3,
            StoryWorldIds.STARFALL_INDEVO_STABLE_4,
        )
        assertEquals(anchors.size, anchors.toSet().size, "观锚站锚点 ID 必须互不相同: $anchors")
        val generated = allSpecs().flatMap { it.allEntityIds() }.toSet()
        for (anchor in anchors) {
            assertTrue(anchor.startsWith("astd_"), "观锚站锚点 ID 必须带模组前缀: $anchor")
            assertTrue(anchor !in generated, "观锚站锚点不得与三世界生成实体冲突: $anchor")
        }
    }

    @Test
    fun `stable anchor id follows objective id suffix convention`() {
        assertEquals(
            "${StoryWorldIds.MAIN_OBJ_GATE}_anchor",
            StoryWorldIds.stableAnchorIdFor(StoryWorldIds.MAIN_OBJ_GATE),
        )
    }
}
