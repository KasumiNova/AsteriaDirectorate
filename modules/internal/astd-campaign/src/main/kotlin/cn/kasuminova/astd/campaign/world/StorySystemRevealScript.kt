package cn.kasuminova.astd.campaign.world

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.StarSystemAPI
import org.apache.log4j.Logger

/**
 * 剧情星系首次进入揭示：星系默认隐藏（[StoryWorldGenerator.hideSystemFromMap]），
 * 玩家舰队实际位于星系内后恢复星图名/图标与地图情报标注。
 * 揭示由 [StarSystemAPI.setDoNotShowIntelFromThisLocationOnMap] 状态持久化，无需额外存档字段。
 */
class StorySystemRevealScript : EveryFrameScript {

    private val log: Logger = Global.getLogger(StorySystemRevealScript::class.java)
    private var elapsed: Float = 0f

    override fun isDone(): Boolean = false

    override fun runWhilePaused(): Boolean = false

    override fun advance(amount: Float) {
        elapsed += amount
        if (elapsed < SCAN_INTERVAL_SECONDS) return
        elapsed = 0f

        val sector = Global.getSector() ?: return
        val playerFleet = sector.playerFleet ?: return
        for (starId in STORY_STAR_IDS) {
            val system = sector.getEntityById(starId)?.containingLocation as? StarSystemAPI ?: continue
            // 以玩家舰队实际所在位置为准：跳跃进入、调试传送等任何入场方式都能触发揭示
            if (playerFleet.containingLocation !== system) continue
            // 隐藏标记在 hideSystemFromMap 中设置；null 表示从未隐藏过（如旧档中玩家已进入的星系）
            if (system.doNotShowIntelFromThisLocationOnMap != true) continue
            StoryWorldGenerator.revealSystemOnMap(sector, system)
            log.info("[ASTD] 玩家首次进入剧情星系 ${system.id}，已解除星图隐藏")
        }
    }

    companion object {
        private const val SCAN_INTERVAL_SECONDS: Float = 1f

        private val STORY_STAR_IDS: List<String> = listOf(
            StoryWorldIds.MAIN_STAR,
            StoryWorldIds.STARFALL_STAR,
            StoryWorldIds.ASTER_STAR,
        )
    }
}
