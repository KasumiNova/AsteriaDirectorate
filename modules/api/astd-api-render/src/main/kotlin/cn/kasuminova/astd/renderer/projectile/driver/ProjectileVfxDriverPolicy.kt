package cn.kasuminova.astd.renderer.projectile.driver

/**
 * 驱动所需的策略标量：淡出窗口 + 拖尾锚点前移。
 *
 * Static Trail 迁移（2026-09）后，拖尾的采样/寿命/几何全部由 BoxUtil Static Trail 系统托管，
 * 驱动只推进 RenderEntity 树的生命周期（boxFlare/anchorArc 等附加层仍吃 [cn.kasuminova.astd.api.render.FrameState]），
 * 故策略只剩淡出秒数与 headLead。旧采样/生命周期/主拖尾几何字段已随自研 texTrail 渲染栈删除。
 */
data class ProjectileVfxDriverPolicy(
    // 淡出(按 FadeReason 取秒数)
    val hitFadeOutSeconds: Float,
    val expireFadeOutSeconds: Float,
    val removedFadeOutSeconds: Float,
    /**
     * 拖尾锚点前移量（世界单位）：树原点（光斑锚点）与 Static Trail tracker 的锚点从弹体中心沿朝向
     * 提前本值，使视觉头部对齐原版螺栓贴图的视觉头部（贴图中心在弹体位置，头在 +length/2 处）。
     * null = 自动取弹体 spec.length/2；显式 0 = 锚回弹体中心。
     */
    val headLeadWorld: Float? = null,
)
