package cn.kasuminova.astd.renderer.projectile.driver

/**
 * 驱动所需的策略标量：采样 / 生命周期 / 淡出 / 主拖尾几何输入。
 *
 * 动机：把驱动从"整个 preset"解耦——驱动只吃这十几个标量,既可 headless 构造测试,也是将来
 * DSL 根 `lifecycle {}` 块的落点(见设计 §5)。当前由 [ProjectileVfxDriverPlugin] 从既有 preset 派生。
 */
data class ProjectileVfxDriverPolicy(
    // 采样(喂 ASTDProjectileHistory)
    val minDistancePerNode: Float,
    val maxHistoryNodes: Int,
    val distanceWindow: Float,
    val historyFps: Float,
    // 生命周期(飞行布局 / 进度)
    val durationSeconds: Float,
    val dissolveStartRatio: Float,
    val layoutReferenceWidth: Float,
    // 淡出(按 FadeReason 取秒数)
    val hitFadeOutSeconds: Float,
    val expireFadeOutSeconds: Float,
    val removedFadeOutSeconds: Float,
    // 主拖尾几何输入(可视长度上限 = viewportTailCap(startWidth, layoutReferenceWidth))
    val primaryTrailLength: Float,
    val primaryTrailStartWidth: Float,
    /**
     * 拖尾锚点前移量（世界单位）：拖尾/弹头树的原点从弹体中心沿朝向提前本值，使带体亮头对齐
     * 原版螺栓贴图的视觉头部（贴图中心在弹体位置，头在 +length/2 处）——原版弹体命中瞬灭后
     * 带体亮头恰好在命中点，无截断观感。null = 自动取弹体 spec.length/2；显式 0 = 禁用
     * （如 aod7 隐藏原版弹体、代码弹头网格锚在弹体中心）。
     */
    val headLeadWorld: Float? = null,
)
