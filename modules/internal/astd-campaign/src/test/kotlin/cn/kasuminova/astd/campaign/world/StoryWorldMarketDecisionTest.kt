package cn.kasuminova.astd.campaign.world

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 市场读档保护决策的纯逻辑校验：
 * 已有市场只补注册，不覆写玩家变化；实体被外部市场占用时完全跳过。
 */
internal class StoryWorldMarketDecisionTest {

    private val ourMarketId = StoryWorldIds.marketIdFor(StoryWorldIds.MAIN_STATION_BRANCH)

    @Test
    fun `no market spec means no action`() {
        assertEquals(
            StoryWorldGenerator.MarketAction.NONE,
            StoryWorldGenerator.decideMarketAction(
                StoryWorldSpecs.MarketKind.NONE, null, ourMarketId, ourMarketInEconomy = false,
            ),
        )
    }

    @Test
    fun `missing market creates fresh`() {
        for (kind in listOf(StoryWorldSpecs.MarketKind.FULL, StoryWorldSpecs.MarketKind.CONDITION_ONLY)) {
            assertEquals(
                StoryWorldGenerator.MarketAction.CREATE,
                StoryWorldGenerator.decideMarketAction(kind, null, ourMarketId, ourMarketInEconomy = false),
            )
        }
    }

    @Test
    fun `our bound market repairs registration only`() {
        assertEquals(
            StoryWorldGenerator.MarketAction.REPAIR_REGISTRATION,
            StoryWorldGenerator.decideMarketAction(
                StoryWorldSpecs.MarketKind.FULL, ourMarketId, ourMarketId, ourMarketInEconomy = true,
            ),
        )
    }

    @Test
    fun `economy registered market without entity binding repairs registration`() {
        // 市场已在经济中但实体未绑定（读档中间态）：只补绑定，不重建。
        assertEquals(
            StoryWorldGenerator.MarketAction.REPAIR_REGISTRATION,
            StoryWorldGenerator.decideMarketAction(
                StoryWorldSpecs.MarketKind.FULL, null, ourMarketId, ourMarketInEconomy = true,
            ),
        )
    }

    @Test
    fun `entity occupied by another market is skipped entirely`() {
        // 玩家殖民后实体会绑定殖民地市场（id 不同）：不接管、不覆写。
        assertEquals(
            StoryWorldGenerator.MarketAction.SKIP_OCCUPIED,
            StoryWorldGenerator.decideMarketAction(
                StoryWorldSpecs.MarketKind.CONDITION_ONLY, "player_colony_123", ourMarketId, ourMarketInEconomy = false,
            ),
        )
        // 即便我们的旧市场还留在经济中，也不得抢回实体。
        assertEquals(
            StoryWorldGenerator.MarketAction.SKIP_OCCUPIED,
            StoryWorldGenerator.decideMarketAction(
                StoryWorldSpecs.MarketKind.FULL, "player_colony_123", ourMarketId, ourMarketInEconomy = true,
            ),
        )
    }
}
