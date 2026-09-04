package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.Global
import java.util.LinkedHashSet

/**
 * 存档持久化的赏金系统状态。
 *
 * 注意：这里用“可序列化的普通字段 + 无参构造”以尽量兼容 Starsector 的 XStream 存档。
 */
class BountyState() {

    /**
     * 已完成的主线数量（框架保留，内容重做后接入驱动源）。
     */
    @JvmField
    var mainCompleted: Int = 0

    /**
     * 已解锁的词缀 id。
     */
    @JvmField
    var unlockedAffixIds: MutableSet<String> = LinkedHashSet()

    /**
     * 已处理过“接受/生成舰队补丁”的 bounty key，避免重复重建 fleet。
     */
    @JvmField
    var patchedBountyKeys: MutableSet<String> = LinkedHashSet()

    /**
     * 已处理过“结算”的 bounty key。
     */
    @JvmField
    var concludedBountyKeys: MutableSet<String> = LinkedHashSet()

    companion object {
        @JvmStatic
        fun getOrCreate(): BountyState {
            val sector = Global.getSector() ?: return BountyState()
            val pd = sector.persistentData
            val existing = pd[BountyKeys.PERSISTENT_STATE_KEY]
            if (existing is BountyState) return existing
            val created = BountyState()
            // 初始解锁：少量低阶词缀，保证玩法从第一单就开始“有味道”。
            created.unlockedAffixIds.addAll(AffixRegistry.initialUnlock())
            pd[BountyKeys.PERSISTENT_STATE_KEY] = created
            return created
        }
    }
}
