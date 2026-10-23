package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.BeamHost

class BeamHostImpl(
    override val hostId: String,
    override val baseWidth: Float,
) : BeamHost
