package cn.kasuminova.astd.campaign.story

import cn.kasuminova.astd.api.AstdLog
import com.fs.starfarer.api.Global
import java.util.LinkedHashSet

/**
 * 剧情托管资产状态（独立存档状态，不占用真实货舱、不含特殊物品占位）。
 *
 * 任务资产（星坠未交付设计原型 / 紫菀科研数据核心）以「托管记录」形式存在：
 * 不可交易、不可存放、不可丢弃，仅可在分局终端交付核销（07 文档「任务物品锁定规则」）。
 * 托管说明在回收时以原版 HUD 消息可视提示（见 [StorySiteDialog]）。
 *
 * 核销判定由结算侧读取 [hasAsset] 并在交付时调用 [handIn]；
 * 实地回收由遗址交互对话（或战后 callback）调用 [collect]。
 *
 * 注意：XStream 存档友好——可序列化普通字段 + 无参构造；新增字段必须带默认值。
 */
class StoryCargo() {

    /** 当前持有的托管资产（bountyKey 集合；核销交付后移除）。 */
    @JvmField
    var heldAssetKeys: MutableSet<String> = LinkedHashSet()

    /** 该赏金的托管资产是否已回收在案。 */
    fun hasAsset(bountyKey: String): Boolean = bountyKey in heldAssetKeys

    /**
     * 实地回收：登记托管资产。
     *
     * @return 是否登记成功（非资产单 / 重复回收拒绝并记日志）
     */
    fun collect(bountyKey: String): Boolean {
        if (!StorySites.requiresAsset(bountyKey)) {
            AstdLog.logger.warn("[StoryCargo] 拒绝回收：'$bountyKey' 不是托管资产单")
            return false
        }
        if (!heldAssetKeys.add(bountyKey)) {
            AstdLog.logger.warn("[StoryCargo] 拒绝回收：'$bountyKey' 的托管资产已登记在案")
            return false
        }
        AstdLog.logger.info("[StoryCargo] 托管资产已登记：$bountyKey")
        return true
    }

    /**
     * 分局终端交付核销：移除托管记录。
     *
     * @return 是否交付成功（未持有时拒绝并记日志）
     */
    fun handIn(bountyKey: String): Boolean {
        if (!heldAssetKeys.remove(bountyKey)) {
            AstdLog.logger.warn("[StoryCargo] 拒绝交付：'$bountyKey' 没有持有中的托管资产")
            return false
        }
        AstdLog.logger.info("[StoryCargo] 托管资产已交付核销：$bountyKey")
        return true
    }

    companion object {

        /** sector persistentData 中保存 [StoryCargo] 的键。 */
        const val PERSISTENT_KEY: String = "astd_story_cargo"

        @JvmStatic
        fun getOrCreate(): StoryCargo {
            val sector = Global.getSector() ?: return StoryCargo()
            val pd = sector.persistentData
            val existing = pd[PERSISTENT_KEY]
            if (existing is StoryCargo) return existing
            val created = StoryCargo()
            pd[PERSISTENT_KEY] = created
            return created
        }
    }
}
