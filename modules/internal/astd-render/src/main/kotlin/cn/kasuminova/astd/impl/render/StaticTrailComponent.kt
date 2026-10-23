package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ProjectileHost
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.manager.CombatRenderingManager

class StaticTrailComponent(
    id: String,
    private val treeId: String,
    private val layerName: String,
    internal val spec: StaticTrailSpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES, RENDER_ORDER_BASE + spec.layer) {

    private val log = Global.getLogger(StaticTrailComponent::class.java)

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        val projectile = (ctx.host as? ProjectileHost)?.projectile ?: return false
        try {
            Global.getSettings().openStream(spec.texturePath).use { }
        } catch (e: Exception) {
            log.warn("ASTD static trail texture unreadable: id=$id path=${spec.texturePath}", e)
            return false
        }
        BoxUtilCombatVfx.ensureReady(engine)
        val data = StaticTrailDataFactory.trailData(treeId, layerName, spec, projectile.moveSpeed)
        val added = CombatRenderingManager.addStaticTrail(
            data,
            projectile,
            CombatEngineLayers.ABOVE_PARTICLES,
            ASTDProjectileTrailTracker(projectile, spec),
        )
        if (!added) {
            log.warn("ASTD static trail 注册失败（addStaticTrail 返回 false，系统被关闭或时长非法）：id=$id，本弹体该拖尾层缺失，其余特效层照常")
        }
        return true
    }

    companion object {
        const val RENDER_ORDER_BASE = 360
    }
}
