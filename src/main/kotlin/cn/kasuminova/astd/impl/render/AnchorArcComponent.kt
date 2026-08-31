package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.FadeReason
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderPhase
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 锚点电弧（泛用组件，首发：贯星之矛）：原版 EMP 电弧
 * （`CombatEngineAPI.spawnEmpArcVisual`）在**固定世界锚点**（attach 时捕获，如武器发射点）
 * 与跟随端（每帧 [RenderContext] 的 frame.origin，如弹体头部）之间拉伸。
 *
 * 观感语义：一条走到底的连续电弧——原版电弧实体自带折线噪声与明灭闪烁，按
 * [AnchorArcSpec.respawnSeconds] 间隔重铺新弧顶替自然淡出的旧弧，两端点随每次重铺
 * 重新取当前位置（锚点固定、跟随端拉伸）。弹体消亡（树进入淡出）后停止重铺，
 * 残余电弧由原版实体自行快速淡出，不跟随冻结帧的头部前飞拉长。
 *
 * 本组件只做「锚点捕获 + 间隔重铺」；折线几何、闪烁与绘制完全由原版电弧实体承担。
 */
data class AnchorArcSpec(
    /** 电弧粗细（世界单位，`spawnEmpArcVisual` thickness）。 */
    val thickness: Float = 10f,
    /** 边缘色（签名色光晕）。 */
    val fringeColor: ASTDColor,
    /** 核心色（近白亮核）。 */
    val coreColor: ASTDColor,
    /** 重铺间隔（秒）：间隔内旧弧靠原版淡出接续，间隔到点铺新弧刷新两端点与折线噪声。 */
    val respawnSeconds: Float = 0.1f,
)

/**
 * 锚点电弧组件：见 [AnchorArcSpec] 头部文档。attach 时捕获固定锚点（此刻 frame.origin
 * 即发射点侧位置），之后每次重铺以当前 frame.origin 为跟随端。
 */
class AnchorArcComponent(
    id: String,
    internal val spec: AnchorArcSpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES) {

    /** 固定锚点（世界坐标，attach 时捕获的发射点侧位置）。 */
    private var anchor: Vector2f? = null
    // 初始为满间隔：attach 后首帧 advance 立即铺第一道弧。
    private var respawnTimer = spec.respawnSeconds
    private var fading = false

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        if (ctx.engine == null) return false
        anchor = Vector2f(ctx.frame.origin)
        return true
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val engine = ctx.engine ?: return
        val start = anchor ?: return
        if (fading || ctx.frame.phase != RenderPhase.Active) return
        respawnTimer += amount.coerceAtLeast(0f)
        if (respawnTimer < spec.respawnSeconds) return
        respawnTimer = 0f
        engine.spawnEmpArcVisual(
            start, null, ctx.frame.origin, null,
            spec.thickness, spec.fringeColor.toAwt(), spec.coreColor.toAwt(),
        )
    }

    /** 消亡后停止重铺：残余原版电弧自行淡出，不向冻结的头部位置续铺。 */
    override fun beginFadeOutSelf(reason: FadeReason, seconds: Float) {
        fading = true
    }
}

/** ASTDColor（0..1 浮点）→ awt Color（0..255）。 */
private fun ASTDColor.toAwt(): Color = Color(
    (red.coerceIn(0f, 1f) * 255f).toInt(),
    (green.coerceIn(0f, 1f) * 255f).toInt(),
    (blue.coerceIn(0f, 1f) * 255f).toInt(),
    (alpha.coerceIn(0f, 1f) * 255f).toInt(),
)
