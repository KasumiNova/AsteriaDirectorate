package cn.kasuminova.astd.campaign.world

import com.fs.starfarer.api.Global

/**
 * 剧情世界生成的存档持久化状态。
 *
 * 与赏金状态一致：可序列化普通字段 + 无参构造，存放于 sector.persistentData。
 * 各标志位只记录“生成流程已走完”；实体级幂等由稳定 ID canonical 去重兜底，
 * 两者结合保证读档恢复时不会重复创建。
 */
class StoryWorldGenState() {

    /** 剧情主星系（含 IndEvo 扩展之外的全部内容）已生成。 */
    @JvmField
    var mainSystemGenerated: Boolean = false

    /** 星坠遗址星系已生成。 */
    @JvmField
    var starfallSystemGenerated: Boolean = false

    /** 紫菀遗址星系已生成。 */
    @JvmField
    var asterSystemGenerated: Boolean = false

    /** 主星系 IndEvo 扩展（中立炮台 + 观锚站）已附加。独立标志：后装 IndEvo 的存档可补齐。 */
    @JvmField
    var indEvoMainExtrasApplied: Boolean = false

    /** 星坠遗址星系 IndEvo 扩展（敌对炮台 + 观锚站）已附加。 */
    @JvmField
    var indEvoStarfallExtrasApplied: Boolean = false

    /** 第二章已解锁（遗址星系生成许可；由章节进度驱动）。 */
    @JvmField
    var chapterTwoUnlocked: Boolean = false

    /** 是否仍有未完成的世界生成工作（供入口短路判断）。 */
    fun pendingWork(indEvoEnabled: Boolean): Boolean =
        !mainSystemGenerated ||
            (chapterTwoUnlocked && (!starfallSystemGenerated || !asterSystemGenerated)) ||
            (indEvoEnabled && mainSystemGenerated && !indEvoMainExtrasApplied) ||
            (indEvoEnabled && starfallSystemGenerated && !indEvoStarfallExtrasApplied)

    companion object {
        @JvmStatic
        fun getOrCreate(): StoryWorldGenState {
            val sector = Global.getSector() ?: return StoryWorldGenState()
            val pd = sector.persistentData
            val existing = pd[StoryWorldIds.PERSISTENT_STATE_KEY]
            if (existing is StoryWorldGenState) return existing
            val created = StoryWorldGenState()
            pd[StoryWorldIds.PERSISTENT_STATE_KEY] = created
            return created
        }
    }
}
