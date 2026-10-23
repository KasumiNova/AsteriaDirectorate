package cn.kasuminova.astd.campaign.world

import com.fs.starfarer.api.Global

enum class WorldgenVersionCheck {

    FIRST_LOAD,

    VERSION_CHANGED,

    CURRENT,
}

class StoryWorldState {

    @JvmField
    var mainSystemGenerated: Boolean = false

    @JvmField
    var chapter2SystemsGenerated: Boolean = false

    @JvmField
    var starfallLocX: Float = 0f

    @JvmField
    var starfallLocY: Float = 0f

    @JvmField
    var asterLocX: Float = 0f

    @JvmField
    var asterLocY: Float = 0f

    @JvmField
    var worldgenDataVersion: Int = 0

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

        @JvmStatic
        fun compareWorldgenVersion(savedVersion: String?, currentVersion: String): WorldgenVersionCheck = when {
            savedVersion == null -> WorldgenVersionCheck.FIRST_LOAD
            savedVersion != currentVersion -> WorldgenVersionCheck.VERSION_CHANGED
            else -> WorldgenVersionCheck.CURRENT
        }
    }
}
