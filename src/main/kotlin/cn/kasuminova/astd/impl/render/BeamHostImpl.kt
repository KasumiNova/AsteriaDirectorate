package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.BeamHost

/**
 * [BeamHost] 实现：包裹一条光束/一把武器的身份与束体基宽。
 * [hostId] 用宿主身份定位（供日志与驱动去重），[baseWidth] 由宿主在构造时给出（原版 `beam.width`）。
 */
class BeamHostImpl(
    override val hostId: String,
    override val baseWidth: Float,
) : BeamHost
