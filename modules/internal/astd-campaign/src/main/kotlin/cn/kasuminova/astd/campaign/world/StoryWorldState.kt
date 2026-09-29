package cn.kasuminova.astd.campaign.world

import com.fs.starfarer.api.Global

/**
 * 存档世界生成版本比对结果（[StoryWorldState.compareWorldgenVersion]）。
 */
enum class WorldgenVersionCheck {

    /** 版本键缺失：旧档首次载入（中途加入模组），需补生成并写入版本记录。 */
    FIRST_LOAD,

    /** 记录版本与当前模组版本不一致：重新校验生成，并作为后续版本数据迁移的挂点。 */
    VERSION_CHANGED,

    /** 版本一致：常规读档补齐（残缺修复）。 */
    CURRENT,
}

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

        /**
         * 存档世界生成版本比对（纯逻辑）：键缺失 → [WorldgenVersionCheck.FIRST_LOAD]；
         * 与当前模组版本不同 → [WorldgenVersionCheck.VERSION_CHANGED]；一致 → [WorldgenVersionCheck.CURRENT]。
         */
        @JvmStatic
        fun compareWorldgenVersion(savedVersion: String?, currentVersion: String): WorldgenVersionCheck = when {
            savedVersion == null -> WorldgenVersionCheck.FIRST_LOAD
            savedVersion != currentVersion -> WorldgenVersionCheck.VERSION_CHANGED
            else -> WorldgenVersionCheck.CURRENT
        }
    }
}
