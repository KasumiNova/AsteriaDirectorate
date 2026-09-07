package cn.kasuminova.astd.impl.render

/**
 * Box 螺栓弹头层（DSL `bolt{}`）的规格：原版能量螺栓视觉（彗星形弹头）的 BoxUtil 接管渲染。
 *
 * 渲染模型：两颗 SpriteEntity（默认贴图 `graphics/fx/projbody.png`，additive，ABOVE_SHIPS 层）——
 * 外缘层染 [fringeColor]、核心层染 [coreColor] 且宽度收细（弹体 spec 的 coreWidthMult）。
 * 每帧由组件从弹体实时同步：位置/朝向 = 弹体中心与速度方向，alpha = 弹体 getBrightness()
 * （外缘一次方、核心平方，对齐原版 ProjectileRenderer 的两趟叠加），X 向缩放 = 出生伸入比
 * （|头−tailEnd| / spec.length，对齐原版 TrailExtender distanceRatio）。
 *
 * 弹体侧真相全部取 API 逐帧值（location/tailEnd/brightness），无任何自算时间状态，
 * 暂停/恢复天然与原版弹体一致。
 */
data class BoltSpec(
    /** 弹头贴图路径（彗星形白图，头亮尾散；X=飞行向）。 */
    val texturePath: String = DEFAULT_TEXTURE,
    /** 核心层染色（内芯高亮，通常近白）。alpha 参与亮度乘算。 */
    val coreColor: ASTDColor = ASTDColor(1f, 1f, 1f, 1f),
    /** 外缘层染色（通常取弹体主色）。alpha 参与亮度乘算。 */
    val fringeColor: ASTDColor = ASTDColor(1f, 1f, 1f, 1f),
) {
    companion object {
        /** 原版彗星形弹体贴图（原版启动期预载，直接按路径取 sprite）。 */
        const val DEFAULT_TEXTURE = "graphics/fx/projbody.png"
    }
}
