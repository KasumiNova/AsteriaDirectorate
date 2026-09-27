package cn.kasuminova.astd.impl.render

/**
 * 马赫环航迹发射器（持续型，首发：坠星残响）：弹体飞行中按节拍在弹体当前位置留一枚
 * 拍扁椭圆环（定向沿飞行方向、横向压扁 [flatten]），环不跟弹——借统一粒子池包络自然
 * 存活超过弹体死亡（缓慢扩大渐透明），无需消亡移交。
 *
 * 渲染后端为统一粒子池（PooledCombatVfx sprite 池）：池槽位 CPU 侧积分尺寸增速
 * （[growthStart]→[growthEnd] 线性扩大折成 scaleRate），alpha 由池三段包络
 * （[fadeIn]/[full]/[fadeOut]）驱动；贴图须 loadTexture 预加载（池绑定侧负责）。
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
) {
    /** 环寿命合计（秒）。 */
    val lifetime: Float get() = fadeIn + full + fadeOut
}
