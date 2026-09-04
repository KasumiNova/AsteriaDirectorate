package cn.kasuminova.astd.impl.render

/**
 * 锚点电弧（泛用组件，首发：贯星之矛）：原版 EMP 电弧
 * （`CombatEngineAPI.spawnEmpArcVisual`）在**固定世界锚点**（attach 时捕获，如武器发射点）
 * 与弹体之间拉伸的一次性闪电。
 *
 * 观感语义：生成一次，后续不管——attach 捕获发射点为固定锚点，首次 advance 铺唯一一道弧：
 * 发射点侧传 null 锚 = 固定世界坐标（原版构造器对 null 锚逐侧判空）；弹体侧绑弹体为
 * `toAnchor` 且 `to` 取**弹体中心**（零偏移烘焙，免疫弹体首帧 facing 未稳定/不一致导致的
 * 偏移反转——偏移非零时一旦烘焙错误，整道弧存活期内末端都钉在弹体反侧），并开
 * `setUpdateFromOffsetEveryFrame(true)`，弧在存活期内每帧由原版 render 重算末端、实时拉伸。
 * 原版 `EmpArcVisual` 为一次性实体（内部 Flicker 约 0.2~0.3s 衰减归零后被引擎移除），
 * 寿命与淡出全由原版承担，本组件不重铺、不续命。
 */
data class AnchorArcSpec(
    /** 电弧粗细（世界单位，`spawnEmpArcVisual` thickness）。 */
    val thickness: Float = 10f,
    /** 边缘色（签名色光晕）。 */
    val fringeColor: ASTDColor,
    /** 核心色（近白亮核）。 */
    val coreColor: ASTDColor,
)
