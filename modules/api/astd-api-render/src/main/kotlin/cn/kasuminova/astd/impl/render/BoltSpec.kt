package cn.kasuminova.astd.impl.render

/**
 * Box 螺栓弹头层（DSL `bolt{}`）的规格：原版能量螺栓视觉（彗星形弹头）的 BoxUtil 接管渲染。
 *
 * 渲染模型（对齐原版 ProjectileRenderer 的 built-in 螺栓路径）：
 * - 贴图 `graphics/fx/astd_bolt_body.png`：projbody 彗形 + 原版逐顶点 alpha 梯度
 *   （头全亮 → 尾透明）与几何收窄（头全宽 → 尾半宽）已烘焙进 alpha 通道
 *   （生成脚本 `tools/build_bolt_body_texture.py`）；
 * - 两颗相同 SpriteEntity 双趟叠加（= 原版 body 双 pass），统一染 [color]
 *   （原版弹体只用 coreColor 染弹头；fringeColor 属 projtrail 外带语义，已由 Static Trail 接替）；
 * - 每帧从弹体 API 实时同步：贴图跨 [tailEnd → 弹体位置]，alpha = brightness²（原版同款平方），
 *   X 向缩放 = 出生伸入比（|头−尾| / spec.length，对齐原版 TrailExtender distanceRatio）。
 *
 * 弹体侧真相全部取 API 逐帧值（location/tailEnd/brightness），无任何自算时间状态，
 * 暂停/恢复天然与原版弹体一致。
 *
 * 导弹（MissileAPI）默认不接管（原版导弹贴图渲染保留）；[allowMissile]=true 时接管——
 * 此时原版贴图须由脚本/贴图屏蔽（如 spriteAlphaOverride=0 或 BUtil_NONE），且导弹 spec
 * 无 length/width 键，必须用 [lengthOverride]/[widthOverride] 显式给尺寸；导弹无
 * TrailExtender 尾迹真值（getTailEnd 恒 null），螺栓按全长渲染、无出生伸入。
 */
data class BoltSpec(
    /** 弹头贴图路径（彗形白图，渐隐/收窄已烘焙；X=飞行向，头在贴图左侧）。 */
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
