package cn.kasuminova.astd.campaign.world

import com.fs.starfarer.api.Global

/**
 * 剧情世界生成的存档持久化状态（存 sector.persistentData）。
 *
 * 职责：
 * - 生成幂等标记（主星系 / 双遗址星系），读档补齐据此判定；
 * - 双遗址星系的落位存档（生成后固定，补齐时复用同一坐标）。
 *
 * 序列化兼容：可序列化普通字段 + 无参构造（XStream 约定，同档删字段安全）。
 */
class StoryWorldState {

    /** 主星系是否已生成。 */
    @JvmField
    var mainSystemGenerated: Boolean = false

    /** 双遗址星系是否已生成。 */
    @JvmField
    var chapter2SystemsGenerated: Boolean = false

    /** 星坠星系落位（生成时写入；[chapter2SystemsGenerated] 为 true 时非 null）。 */
    @JvmField
    var starfallLocX: Float = 0f

    @JvmField
    var starfallLocY: Float = 0f

    /** 紫菀星系落位。 */
    @JvmField
    var asterLocX: Float = 0f

    @JvmField
    var asterLocY: Float = 0f

    companion object {
        @JvmStatic
        fun getOrCreate(): StoryWorldState {
            val sector = Global.getSector() ?: return StoryWorldState()
            val pd = sector.persistentData
            val existing = pd[StoryWorldIds.PERSISTENT_STATE_KEY]
            if (existing is StoryWorldState) return existing
            val created = StoryWorldState()
            pd[StoryWorldIds.PERSISTENT_STATE_KEY] = created
            return created
        }
    }
}
