package cn.kasuminova.astd.impl.render

import com.fs.starfarer.api.combat.CombatEngineLayers

/** BoxUtil FlareEntity 四种光斑形态（对应其 _SMOOTH/_SHARP/_SMOOTH_DISC/_SHARP_DISC 样式位）。 */
enum class BoxFlareStyle {
    /** 柔边 streak 光斑。 */
    SMOOTH,

    /** 锐边 streak 光斑（anamorphic 细长亮条，镜头光斑观感）。 */
    SHARP,

    /** 柔边盘（沿朝向铺开、横向薄）。 */
    SMOOTH_DISC,

    /** 锐边盘。 */
    SHARP_DISC,
}

/**
 * 跟随弹体头部的 BoxUtil 光斑参数。
 *
 * 位置与朝向每帧取自 [cn.kasuminova.astd.api.render.FrameState]，弹体淡出时同步渐隐。
 */
data class BoxFlareSpec(
    /** 光斑全尺寸（世界单位）：width 沿朝向、height 横向；width >> height 即水平光条。 */
    val width: Float,
    val height: Float,
    /** 核心色（近白亮核）。 */
    val coreColor: ASTDColor,
    /** 边缘色（签名色光晕）。 */
    val fringeColor: ASTDColor,
    /** emissive 输出倍率（0..1+，bloom 强度）。 */
    val glowPower: Float = 1f,
    /** 盘厚参数（BoxUtil discRatio，越大越薄）。 */
    val discRatio: Float = 4f,
    /** 是否闪烁（宽度脉动 + 明灭同步）；默认关闭，DSL 调用任意 flicker 方法即启用。 */
    val flick: Boolean = false,
    /** 闪烁速度倍率（1 = BoxUtil 默认），仅 [flick] 开启时生效。 */
    val flickerRate: Float = 1.2f,
    /** 边缘 fbm 噪点强度（0 = 关闭）。 */
    val noisePower: Float = 0.1f,
    /** 光斑形态（柔/锐 × streak/盘）；锐边 streak（[BoxFlareStyle.SHARP]）即镜头光斑式细长亮条。 */
    val style: BoxFlareStyle = BoxFlareStyle.SMOOTH_DISC,
    /**
     * 朝向偏移（度，加在宿主 facing 上）：弹体光斑常取 90（垂直于飞行方向的横向亮条，
     * 不随飞行方向旋转成顺向）。
     */
    val facingOffsetDeg: Float = 0f,
    /**
     * 固定世界朝向（度，非空时忽略宿主 facing 与 [facingOffsetDeg]）：
     * 用于「无论弹体飞向哪个角度都保持同一朝向」的镜头光斑，0 = 恒水平。
     */
    val fixedFacingDeg: Float? = null,
    /**
     * 局部 x 偏移（世界单位，负 = 向尾）：光斑相对树锚点（弹体前端提前 headLead 处）的偏移。
     */
    val offsetX: Float = 0f,
    /** BoxUtil 实体渲染层。 */
    val boxLayer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
)
