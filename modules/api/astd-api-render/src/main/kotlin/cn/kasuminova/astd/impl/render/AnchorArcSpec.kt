package cn.kasuminova.astd.impl.render

/**
 * 在固定世界位置与弹体之间生成一次 EMP 电弧。电弧末端每帧跟随弹体，寿命由原版电弧实体控制。
 */
data class AnchorArcSpec(
    /** 电弧粗细（世界单位，`spawnEmpArcVisual` thickness）。 */
    val thickness: Float = 10f,
    /** 边缘色（签名色光晕）。 */
    val fringeColor: ASTDColor,
    /** 核心色（近白亮核）。 */
    val coreColor: ASTDColor,
)
