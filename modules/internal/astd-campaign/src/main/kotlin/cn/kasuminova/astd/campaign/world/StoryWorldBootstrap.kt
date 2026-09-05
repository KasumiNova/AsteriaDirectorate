package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.campaign.bounty.BountyState
import com.fs.starfarer.api.Global
import org.apache.log4j.Logger

/**
 * 剧情世界生成的生命周期入口（由 ModPlugin 接线）。
 *
 * - 新档经济加载后：幂等生成剧情主星系（含 IndEvo 扩展）；
 * - 读档：恢复/补齐已解锁内容（老档补主星系，第二章已解锁补遗址双星系），不重复创建；
 * - 第二章解锁：严格由第一章结清状态驱动；正常流程由 [notifyChapterTwoUnlocked] 主动生成，
 *   读档路径用持久化章节集合补齐遗漏。
 */
object StoryWorldBootstrap {

    /** 第一章（章节编号 1）结清后才允许生成第二章两处遗址星系。 */
    private const val CHAPTER_ONE_INDEX = 1

    private val log: Logger = Global.getLogger(StoryWorldBootstrap::class.java)

    /** 新档经济加载后调用（ModPlugin.onNewGameAfterEconomyLoad）。 */
    @JvmStatic
    fun onNewGameAfterEconomyLoad() = runGuarded("onNewGameAfterEconomyLoad") {
        val sector = Global.getSector() ?: return@runGuarded
        val state = StoryWorldGenState.getOrCreate()
        StoryWorldGenerator.ensureMainSystem(sector, state)
        applyIndEvoExtras(sector, state)
    }

    /**
     * 读档路径调用（ModPlugin.onGameLoad 且 !newGame）。
     *
     * 注意：新档的 onGameLoad 早于 procgen / economy load，剧情星系的新档生成
     * 统一走 [onNewGameAfterEconomyLoad]，本入口不得在新档路径调用。
     */
    @JvmStatic
    fun onGameLoad() = runGuarded("onGameLoad") {
        val sector = Global.getSector() ?: return@runGuarded
        val state = StoryWorldGenState.getOrCreate()

        // 读档恢复：中途加入模组或上次生成被中断的存档，补齐主星系。
        StoryWorldGenerator.ensureMainSystem(sector, state)

        if (!state.chapterTwoUnlocked &&
            CHAPTER_ONE_INDEX in BountyState.getOrCreate().completedChapters
        ) {
            state.chapterTwoUnlocked = true
            log.info("[StoryWorldBootstrap] 检测到第二章解锁条件，遗址星系生成许可已开启。")
        }
        if (state.chapterTwoUnlocked) {
            StoryWorldGenerator.ensureChapterTwoSystems(sector, state)
        }
        applyIndEvoExtras(sector, state)
    }

    /** 章节系统主动通知第二章解锁（幂等）。 */
    @JvmStatic
    fun notifyChapterTwoUnlocked() = runGuarded("notifyChapterTwoUnlocked") {
        val sector = Global.getSector() ?: return@runGuarded
        val state = StoryWorldGenState.getOrCreate()
        state.chapterTwoUnlocked = true
        StoryWorldGenerator.ensureMainSystem(sector, state)
        StoryWorldGenerator.ensureChapterTwoSystems(sector, state)
        applyIndEvoExtras(sector, state)
    }

    /** IndEvo 扩展附加：mod manager 检测 + 标志位去重 + 异常隔离（不影响原版内容）。 */
    private fun applyIndEvoExtras(sector: com.fs.starfarer.api.campaign.SectorAPI, state: StoryWorldGenState) {
        if (!StoryWorldGenerator.isIndEvoEnabled()) return

        if (state.mainSystemGenerated && !state.indEvoMainExtrasApplied) {
            try {
                val main = sector.getEntityById(StoryWorldIds.MAIN_STAR)?.starSystem
                if (main != null) {
                    IndEvoWorldExtras.applyMainSystemExtras(main)
                    state.indEvoMainExtrasApplied = true
                }
            } catch (t: Throwable) {
                log.warn("[StoryWorldBootstrap] 主星系 IndEvo 扩展附加失败，将在下次读档重试。", t)
            }
        }

        if (state.starfallSystemGenerated && !state.indEvoStarfallExtrasApplied) {
            try {
                val starfall = sector.getEntityById(StoryWorldIds.STARFALL_STAR)?.starSystem
                if (starfall != null) {
                    IndEvoWorldExtras.applyStarfallExtras(starfall)
                    state.indEvoStarfallExtrasApplied = true
                }
            } catch (t: Throwable) {
                log.warn("[StoryWorldBootstrap] 星坠遗址 IndEvo 扩展附加失败，将在下次读档重试。", t)
            }
        }
    }

    private fun runGuarded(entry: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log.warn("[StoryWorldBootstrap] 剧情世界生成入口 $entry 执行失败。", t)
        }
    }
}
