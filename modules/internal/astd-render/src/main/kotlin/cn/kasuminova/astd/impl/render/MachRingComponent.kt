package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderPhase
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineLayers
import java.awt.Color

/**
 * 马赫环航迹发射器组件（持续型，spec 见 [MachRingSpec]）：弹体 Active 期间按节拍在弹体
 * 当前位置留一枚拍扁椭圆环，渲染走统一粒子池（[PooledCombatVfx] sprite 池，池槽位 CPU 侧
 * 积分尺寸增速实现 [MachRingSpec.growthStart]→[MachRingSpec.growthEnd] 线性扩大）。
 *
 * 环不跟弹：spawn 后锚在原地，由池包络自然存活超过弹体死亡，弹体淡出/移除即停喷，
 * 无需消亡移交（对齐 .wpn EveryFrame 旧实现的存活语义，但不再自行持有/推进实体表）。
 *
 * 朝向口径：环长轴垂直于飞行方向（facing + 90°）——旧实现 setStateVanilla 的 scale.x 沿
 * facing 渲染为「长轴顺飞行向」，实机观测与设计意图（环面法线沿飞行方向的马赫环 hoop 观感）
 * 差 90°，迁移时一并校准；贴图 256×256 旋转对称，旋转只影响拍扁轴向。
 *
 * 失败语义：池不可用由 spawnSprite 记 WARN，本枚环视觉缺席（无兜底）。
 */
class MachRingComponent(
    id: String,
    internal val spec: MachRingSpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER, RENDER_ORDER) {

    private val log = Global.getLogger(MachRingComponent::class.java)

    /** 环池键：同配色/贴图共享一池；容量 256 足够覆盖节拍 × 寿命的驻留数（0.1s 节拍 × 1s 寿命 = 10 枚/发射器）。 */
    private val poolKey = PooledCombatVfx.SpritePoolKey(
        spec.texturePath,
        CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
        glowPower = 0.5f,
        capacity = 256,
    )

    /** 节拍累积器（internal 供单测断言节拍语义）。 */
    internal var accumulator = 0f
        private set

    /** 池失败的一次性 WARN 闸（池损坏是粘滞态，不重试风暴）。 */
    private var poolFailureWarned = false

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        if (ctx.frame.phase != RenderPhase.Active) return
        accumulator += amount
        if (accumulator < spec.interval) return
        accumulator = 0f
        emit(ctx)
    }

    /** 留一枚环：锚在弹体当前位置、零速度、长轴 ⊥ 飞行向、出生尺寸 [MachRingSpec.growthStart] 倍并线性扩大。 */
    private fun emit(ctx: RenderContext) {
        val engine = ctx.engine ?: return
        val frame = ctx.frame
        val lifetime = spec.lifetime
        val scaleX = spec.halfSize * spec.growthStart
        val accepted = PooledCombatVfx.spawnSprite(
            engine = engine,
            key = poolKey,
            x = frame.origin.x,
            y = frame.origin.y,
            facingDeg = BoxUtilCombatVfx.normalizeFacingDeg(frame.facing + 90f),
            scaleX = scaleX,
            scaleY = scaleX * spec.flatten,
            scaleRateX = spec.halfSize * (spec.growthEnd - spec.growthStart) / lifetime,
            scaleRateY = spec.halfSize * (spec.growthEnd - spec.growthStart) / lifetime * spec.flatten,
            color = spec.color.toAwt(spec.alpha),
            emissiveColor = spec.color.toAwt(spec.alpha * 0.5f),
            fadeIn = spec.fadeIn,
            full = spec.full,
            fadeOut = spec.fadeOut,
        )
        if (!accepted && !poolFailureWarned) {
            poolFailureWarned = true
            log.warn("马赫环喷入粒子池失败（id=$id），后续同类失败不再重复告警")
        }
    }

    companion object {
        /** 发射器绘制序：组件自身不绘制（灌池即渲染），占位在拖尾叠层与光斑之间。 */
        const val RENDER_ORDER = StaticTrailComponent.RENDER_ORDER_BASE + 7
    }
}

/** ASTDColor（0..1 浮点）→ awt Color（0..255），[alpha] 覆盖 spec 自带 alpha（基准透明度口径）。 */
private fun ASTDColor.toAwt(alpha: Float): Color = Color(
    (red.coerceIn(0f, 1f) * 255f).toInt(),
    (green.coerceIn(0f, 1f) * 255f).toInt(),
    (blue.coerceIn(0f, 1f) * 255f).toInt(),
    (alpha.coerceIn(0f, 1f) * 255f).toInt(),
)
