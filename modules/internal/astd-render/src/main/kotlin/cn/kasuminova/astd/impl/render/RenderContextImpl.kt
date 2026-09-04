package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FrameState
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderHost
import com.fs.starfarer.api.combat.CombatEngineAPI
import java.util.ArrayDeque
import java.util.Deque

/**
 * [RenderContext] 实现。驱动每帧构造一份贯穿本次遍历；[stack] 每帧新建，遍历进出节点时压弹。
 */
class RenderContextImpl(
    override val engine: CombatEngineAPI?,
    override val host: RenderHost,
    override val frame: FrameState,
    override val stack: Deque<RenderEntity> = ArrayDeque(),
) : RenderContext
