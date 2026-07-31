package cn.kasuminova.astd.api.render

import com.fs.starfarer.api.combat.CombatEngineAPI
import java.util.Deque

/**
 * 逐帧渲染上下文。由驱动构造，贯穿一次 advance/render 遍历，为整棵树提供引擎、宿主、父节点栈与本帧数据源。
 */
interface RenderContext {

    /**
     * 战斗引擎；无引擎的单元测试下为 null，此时后端节点的 [RenderEntity.onAttach] 应返回 false（设计 D1）。
     */
    val engine: CombatEngineAPI?

    /** 宿主中立入口：弹体或光束（设计 D6）。节点通常不直接用它，而是读 [frame]。 */
    val host: RenderHost

    /** 父节点栈，供子节点读取父的变换/状态（设计 D4）。遍历进入节点时压入、退出时弹出。 */
    val stack: Deque<RenderEntity>

    /** 本帧唯一数据源（设计 D2）。 */
    val frame: FrameState
}
