package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ProjectileHost
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.manager.CombatRenderingManager

/**
 * Static Trail 拖尾组件（RenderEntity 叶子）：attach 时把本层注册进 BoxUtil Static Trail 系统
 * （[CombatRenderingManager.addStaticTrail] + [ASTDProjectileTrailTracker]），此后拖尾的节点采样、
 * 生命推进、消亡播完全部由 BoxUtil 托管——组件自身无每帧工作，detach 不销毁拖尾（tracker 自然走完）。
 *
 * BoxUtil 未就绪/注册失败时 WARN 一次并禁用自身（不重试风暴），弹体其余特效层不受影响。
 */
class StaticTrailComponent(
    id: String,
    /** 所属树 id（StaticTrailData 池 id 前缀）。 */
    private val treeId: String,
    /** 层名（StaticTrailData 池 id 后缀）。 */
    private val layerName: String,
    internal val spec: StaticTrailSpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_PARTICLES, RENDER_ORDER_BASE + spec.layer) {

    private val log = Global.getLogger(StaticTrailComponent::class.java)

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        val projectile = (ctx.host as? ProjectileHost)?.projectile ?: return false
        // 校验贴图资源可读（Static Trail 经游戏贴图系统加载，缺失时 getSprite 仅给占位纹理——提前 WARN 定位）
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
        /** 拖尾绘制序基线：对齐旧 ribbon/texTrail 层位；实际序 = 基线 + [StaticTrailSpec.layer]。 */
        const val RENDER_ORDER_BASE = 360
    }
}
