package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FrameState
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.api.render.RenderHost
import com.fs.starfarer.api.combat.CombatEngineAPI
import java.util.ArrayDeque
import java.util.Deque

class RenderContextImpl(
    override val engine: CombatEngineAPI?,
    override val host: RenderHost,
    override val frame: FrameState,
    override val stack: Deque<RenderEntity> = ArrayDeque(),
) : RenderContext
