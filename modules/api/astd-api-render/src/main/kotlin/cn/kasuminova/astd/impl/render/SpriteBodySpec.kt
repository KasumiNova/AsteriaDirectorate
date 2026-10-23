package cn.kasuminova.astd.impl.render

/**
 * 由 BoxUtil SpriteEntity 渲染的弹体本体参数。位置、朝向与透明度每帧取自弹体。
 *
 * 调用方必须屏蔽原版弹体贴图，并预加载 [texturePath]。
 */
data class SpriteBodySpec(
    /** 本体贴图路径（须注册进 settings.json 的 graphics 段，SpriteEntity 走预载纹理）。 */
    val texturePath: String,
    /** 世界尺寸（su）：全宽 = 沿飞行向长度，全高 = 横向宽度（对齐 .proj 的 size 字段）。 */
    val width: Float,
    val height: Float,
    /** bloom 发光强度（0 = 不发光；>0 时 emissive 复用 diffuse 贴图原色发光）。 */
    val glowPower: Float = 0f,
)
