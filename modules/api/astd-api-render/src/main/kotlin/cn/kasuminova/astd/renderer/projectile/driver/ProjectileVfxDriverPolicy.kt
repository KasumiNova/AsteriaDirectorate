package cn.kasuminova.astd.renderer.projectile.driver

/** 弹体特效树的淡出时长与锚点前移量。 */
data class ProjectileVfxDriverPolicy(
    val hitFadeOutSeconds: Float,
    val expireFadeOutSeconds: Float,
    val removedFadeOutSeconds: Float,
    /** 树原点与拖尾锚点沿弹体朝向前移的距离；null 表示不偏移。 */
    val headLeadWorld: Float? = null,
)
