package cn.kasuminova.astd.impl.render

/**
 * 弹体本体贴图层（DSL `spriteBody{}`）的规格：由 BoxUtil SpriteEntity 逐帧跟随弹体渲染本体
 * （normal alpha 混合，对齐原版 Missile.render 的弹体贴图路径），取代原版弹体贴图渲染
 * （原版视觉由 .proj 的 `sprite=BUtil_NONE.png` 屏蔽）。
 *
 * 与 Box 螺栓（[BoltSpec]）的分工：bolt 是能量螺栓弹头（additive 彗形贴图），
 * 本层是有实体贴图的弹体本体（如导弹/冰晶碎片），渲染语义对齐原版 Missile.render：
 * - 位置/朝向逐帧取弹体 API 真值（location/facing），normal alpha 混合，ABOVE_SHIPS 层；
 * - alpha 对齐原版：弹体为导弹时取 `MissileAPI.getCurrentBaseAlpha()`（熄火淡出/相位渐变同款），
 *   `spriteAlphaOverride >= 0` 时优先；非导弹弹体取 `getBrightness()`；
 * - 贴图朝向约定同 SpriteEntity：文件右（+u）= 飞行正向。
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
