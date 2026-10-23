package cn.kasuminova.astd.impl.render

/**
 * 弹体飞行期间周期性留下的椭圆环。环生成后不跟随弹体，由粒子池推进尺寸、位置和透明度。
 */
data class MachRingSpec(
    /** 发射节拍（秒）：每满一拍留一枚环。 */
    val interval: Float,
    /** 环基准半径（世界半尺寸，约为弹体宽度的 2 倍观感）。 */
    val halfSize: Float,
    /** 环染色（additive 混合；emissive 同色 alpha 减半接原生泛光）。 */
    val color: ASTDColor,
    /** 环基准透明度 0..1（逐帧再乘包络）。 */
    val alpha: Float = 0.6f,
    /** 横向拍扁比（椭圆短轴 = 长轴 × 本值）。 */
    val flatten: Float = 0.45f,
    /** 出生/寿终尺寸倍率（寿命内线性扩大）。 */
    val growthStart: Float = 0.7f,
    val growthEnd: Float = 1.6f,
    /** 寿命包络三段时长（秒，合计即环寿命）。 */
    val fadeIn: Float = 0.06f,
    val full: Float = 0.5f,
    val fadeOut: Float = 0.44f,
    /** 环贴图（256×256 旋转对称圆环，形在 alpha）。 */
    val texturePath: String = "graphics/fx/astd_generated_ring.png",
    /** 前飞动量速度域（世界单位/秒，方向 = 发射瞬间弹体 facing，逐枚随机取值）；0 = 锚在原地。 */
    val speedMin: Float = 0f,
    val speedMax: Float = 0f,
) {
    /** 环寿命合计（秒）。 */
    val lifetime: Float get() = fadeIn + full + fadeOut
}
