package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ProjectileHost
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderPhase
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 锚点电弧组件：见 [AnchorArcSpec] 头部文档。attach 时捕获固定锚点（此刻 frame.origin
 * 即发射点侧位置），首次 advance 生成唯一一道弧后不再动作。
 */
class AnchorArcComponent(
    id: String,
    internal val spec: AnchorArcSpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES) {

    /** 固定锚点（世界坐标，attach 时捕获的发射点侧位置）。 */
    private var anchor: Vector2f? = null
    private var spawned = false

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        if (ctx.engine == null) return false
        anchor = Vector2f(ctx.frame.origin)
        return true
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        if (spawned) return
        val engine = ctx.engine ?: return
        val start = anchor ?: return
        if (ctx.frame.phase != RenderPhase.Active) return
        spawned = true
        // 弹体侧绑弹体中心（零偏移烘焙）；无弹体宿主时退化为当前 origin 的静态端点
        val toAnchor = (ctx.host as? ProjectileHost)?.projectile
        val to = if (toAnchor != null) Vector2f(toAnchor.location) else ctx.frame.origin
        engine.spawnEmpArcVisual(
            start, null, to, toAnchor,
            spec.thickness, spec.fringeColor.toAwt(), spec.coreColor.toAwt(),
        ).setUpdateFromOffsetEveryFrame(true)
    }
}

/** ASTDColor（0..1 浮点）→ awt Color（0..255）。 */
private fun ASTDColor.toAwt(): Color = Color(
    (red.coerceIn(0f, 1f) * 255f).toInt(),
    (green.coerceIn(0f, 1f) * 255f).toInt(),
    (blue.coerceIn(0f, 1f) * 255f).toInt(),
    (alpha.coerceIn(0f, 1f) * 255f).toInt(),
)
