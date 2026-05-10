package cn.kasuminova.astd.campaign.companions

import com.fs.starfarer.api.Global

/**
 * 同伴彩蛋状态（持久化）。
 */
class CompanionState() {
    @JvmField
    var offered: Boolean = false

    @JvmField
    var offerPending: Boolean = false

    @JvmField
    var watcherRecruited: Boolean = false

    @JvmField
    var echoObtained: Boolean = false

    @JvmField
    var intelAdded: Boolean = false

    companion object {
        @JvmStatic
        fun getOrCreate(): CompanionState {
            val sector = Global.getSector() ?: return CompanionState()
            val pd = sector.persistentData
            val existing = pd[CompanionIds.PERSISTENT_KEY]
            if (existing is CompanionState) return existing
            val created = CompanionState()
            pd[CompanionIds.PERSISTENT_KEY] = created
            return created
        }
    }
}
