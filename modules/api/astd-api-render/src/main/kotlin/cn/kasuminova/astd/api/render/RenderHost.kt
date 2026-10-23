package cn.kasuminova.astd.api.render

/** 弹体或光束特效树的宿主。 */
interface RenderHost {

    /** 宿主唯一标识，用于日志定位与驱动侧去重。 */
    val hostId: String
}
