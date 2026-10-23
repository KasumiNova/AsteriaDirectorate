package cn.kasuminova.astd.impl.render

/**
 * BoxUtil 能量螺栓弹头的渲染参数。
 *
 * 每帧从弹体读取位置、尾端和亮度。默认不处理导弹；[allowMissile] 为 true 时，
 * 调用方必须屏蔽原版导弹贴图，并通过 [lengthOverride] 与 [widthOverride] 提供尺寸。
 */
data class BoltSpec(
    /** 弹头贴图路径。贴图头端必须位于右侧，alpha 包含渐隐与收窄。 */
    val texturePath: String = DEFAULT_TEXTURE,
    /** 弹头染色（原版 coreColor 语义，通常近白）。alpha 参与亮度乘算。 */
    val color: ASTDColor = ASTDColor(1f, 1f, 1f, 0.78f),
    /** 导弹弹体接管开关：默认 false（MissileAPI 保留原版贴图渲染，组件 attach 时自禁用）。 */
    val allowMissile: Boolean = false,
    /** 螺栓全长显式覆盖（su，飞行向）：null = 读弹体 spec.length；导弹 spec 无此键时必须显式给值。 */
    val lengthOverride: Float? = null,
    /** 螺栓全宽显式覆盖（su，横向）：null = 读弹体 spec.width；导弹 spec 无此键时必须显式给值。 */
    val widthOverride: Float? = null,
) {
    companion object {
        /** 烘焙版螺栓弹体贴图（渐隐/收窄已进 alpha，见 tools/build_bolt_body_texture.py）。 */
        const val DEFAULT_TEXTURE = "graphics/fx/astd_bolt_body.png"
    }
}
