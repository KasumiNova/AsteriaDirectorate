package cn.kasuminova.astd.api.render

/**
 * 触发一次性淡出的原因。由驱动/宿主在宿主终止事件发生时传入 [RenderEntity.beginFadeOut]，
 * 供节点区分收束节奏（例如命中收束通常比自然过期更快）。
 *
 * 宿主中立（设计 D6）：弹体与光束共用同一组语义。
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
