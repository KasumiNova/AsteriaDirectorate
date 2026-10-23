package cn.kasuminova.astd.impl.render

import com.fs.starfarer.api.combat.CombatEngineLayers

/**
 * 一枚 BoxUtil 光斑的尺寸、颜色与渲染参数。
 *
 * 长轴沿 [facingDeg] 方向，短轴垂直于该方向。
 */
data class BloomFlareSpec(
    /** 光斑形态（柔/锐 × streak/盘）。 */
    val style: BoxFlareStyle,
    /** 长轴起止尺寸（世界单位 su，沿 [facingDeg] 方向铺开）。 */
    val sizeStart: Float,
    val sizeEnd: Float,
    /** 短轴起止尺寸（世界单位 su）。 */
    val heightStart: Float,
    val heightEnd: Float,
    /** 朝向角（度，世界坐标；允许负值/超 360，实现侧归一化）。 */
    val facingDeg: Float = 0f,
    /** 核心色（近白亮核）。 */
    val coreColor: ASTDColor,
    /** 边缘色（光晕）。 */
    val fringeColor: ASTDColor,
    /** emissive 输出倍率（bloom 强度）。 */
    val glowPower: Float = 1f,
    /** 边缘 fbm 噪点强度（0 = 关闭）。 */
    val noisePower: Float = 0f,
    /** 随机闪烁（BoxUtil FlareEntity 内建闪烁，同步码按实体实例区分即逐枚随机）。 */
    val flicker: Boolean = false,
    /** BoxUtil 实体渲染层。 */
    val layer: CombatEngineLayers = CombatEngineLayers.ABOVE_PARTICLES,
) {
    /** 按 [aspect] 与长轴尺寸的乘积设置短轴起止尺寸。 */
    constructor(
        style: BoxFlareStyle,
        sizeStart: Float,
        sizeEnd: Float,
        aspect: Float,
        facingDeg: Float,
        coreColor: ASTDColor,
        fringeColor: ASTDColor,
        glowPower: Float = 1f,
        noisePower: Float = 0f,
        flicker: Boolean = false,
        layer: CombatEngineLayers = CombatEngineLayers.ABOVE_PARTICLES,
    ) : this(
        style, sizeStart, sizeEnd,
        sizeStart * aspect, sizeEnd * aspect,
        facingDeg, coreColor, fringeColor, glowPower, noisePower, flicker, layer,
    )
}
