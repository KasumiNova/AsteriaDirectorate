package cn.kasuminova.astd.api.render

/**
 * 一次性淡出的触发原因。由宿主传给 [RenderEntity.beginFadeOut]。
 */
enum class FadeReason {
    /** 弹体命中 / 光束命中目标。 */
    Hit,

    /** 弹体自然过期 / 光束达最大存续。 */
    Expire,

    /** 宿主被外部移除（如 cutTrails、武器卸载）。 */
    Removed,

    /** 战斗结束等销毁清理。 */
    Dispose,
}
