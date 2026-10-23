package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ProjectileHost
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.api.render.RenderPhase
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

class AnchorArcComponent(
    id: String,
    internal val spec: AnchorArcSpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES) {
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
        val toAnchor = (ctx.host as? ProjectileHost)?.projectile
        val to = if (toAnchor != null) Vector2f(toAnchor.location) else ctx.frame.origin
        engine.spawnEmpArcVisual(
            start, null, to, toAnchor,
            spec.thickness, spec.fringeColor.toAwt(), spec.coreColor.toAwt(),
        ).setUpdateFromOffsetEveryFrame(true)
    }
}

private fun ASTDColor.toAwt(): Color = Color(
    (red.coerceIn(0f, 1f) * 255f).toInt(),
    (green.coerceIn(0f, 1f) * 255f).toInt(),
    (blue.coerceIn(0f, 1f) * 255f).toInt(),
    (alpha.coerceIn(0f, 1f) * 255f).toInt(),
)
