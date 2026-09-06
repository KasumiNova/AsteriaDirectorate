package cn.kasuminova.astd.campaign.world

import com.fs.starfarer.api.Global
import java.util.LinkedHashSet

/**
 * 剧情世界生成的存档持久化状态（存 sector.persistentData）。
 *
 * 职责：
 * - 生成幂等标记（主星系 / 第二章双星系），读档补齐据此判定；
 * - 第二章双星系的落位存档（生成后固定，补齐时复用同一坐标）；
 * - 引力节点摧毁进度（阶段 2 的 ZW 工单阶段推进直接消费 [gravityNodesPulled]）。
 *
 * 序列化兼容：可序列化普通字段 + 无参构造（同 BountyState 的 XStream 约定）。
 */
class StoryWorldState() {

    /** 主星系是否已生成。 */
    @JvmField
    var mainSystemGenerated: Boolean = false

    /** 第二章双星系是否已生成。 */
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

    /** 已拔除（摧毁/打捞移除）的引力节点实体 id（[StoryWorldIds.ASTER_NODE_IDS] 子集）。 */
    @JvmField
    var gravityNodesPulled: MutableSet<String> = LinkedHashSet()

    /** 已触发过护卫舰队的引力节点实体 id（每节点仅触发一次）。 */
    @JvmField
    var gravityNodesGuardTriggered: MutableSet<String> = LinkedHashSet()

    /** 已拔除的引力节点数（ZW 工单阶段推进的查询入口）。 */
    fun pulledNodeCount(): Int = gravityNodesPulled.size

    /** 指定节点是否已拔除。 */
    fun isNodePulled(nodeId: String): Boolean = nodeId in gravityNodesPulled

    /** 全部引力节点是否已拔除（核心数据舱解禁条件）。 */
    fun allNodesPulled(): Boolean = gravityNodesPulled.containsAll(StoryWorldIds.ASTER_NODE_IDS)

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
