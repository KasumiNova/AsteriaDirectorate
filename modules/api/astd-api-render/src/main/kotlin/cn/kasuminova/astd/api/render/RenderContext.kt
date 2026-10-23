package cn.kasuminova.astd.api.render

import com.fs.starfarer.api.combat.CombatEngineAPI
import java.util.Deque

/**
 * 一次渲染树遍历的上下文。驱动负责在每帧提供宿主、帧状态与当前父节点栈。
 */
interface RenderContext {

    /** 当前战斗引擎；无引擎测试环境中为 null。 */
    val engine: CombatEngineAPI?

    /** 当前特效树的弹体或光束宿主。 */
    val host: RenderHost

    /** 当前遍历路径的父节点栈，进入节点时压入，退出时弹出。 */
    val stack: Deque<RenderEntity>

    /** 当前帧的宿主状态。 */
    val frame: FrameState
}
