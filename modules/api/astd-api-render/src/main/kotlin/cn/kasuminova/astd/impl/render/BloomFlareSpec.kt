package cn.kasuminova.astd.impl.render

import com.fs.starfarer.api.combat.CombatEngineLayers

/**
 * 「绽放辉星」单枚光斑的规格（纯数据蓝图）：一枚钉住生命周期的 BoxUtil FlareEntity，
 * 由 [cn.kasuminova.astd.api.render.BloomFlareVfx] 建实体并推进「扩散 + 渐隐 + 到期销毁」动画。
 *
 * 尺寸语义：[sizeStart]/[sizeEnd] 为长轴（沿朝向）起止，[heightStart]/[heightEnd] 为短轴起止；
 * 短轴随长轴等比的场景用次构造的 aspect 口径，短轴固定（只长轴扩散）的场景直接给相等起止值。
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
    /** BoxUtil 实体渲染层。 */
    val layer: CombatEngineLayers = CombatEngineLayers.ABOVE_PARTICLES,
) {
    /**
     * 短轴随长轴等比的便捷构造：短轴 = 长轴 × [aspect]（十字光柱类观感的主用口径）。
     */
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
        layer: CombatEngineLayers = CombatEngineLayers.ABOVE_PARTICLES,
    ) : this(
        style, sizeStart, sizeEnd,
        sizeStart * aspect, sizeEnd * aspect,
        facingDeg, coreColor, fringeColor, glowPower, noisePower, layer,
    )
}
