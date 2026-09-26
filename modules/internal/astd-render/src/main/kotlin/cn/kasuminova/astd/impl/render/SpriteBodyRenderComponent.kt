package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ProjectileHost
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx.addEntity
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import org.boxutil.units.standard.entity.SpriteEntity
import org.lwjgl.util.vector.Vector2f

/**
 * 弹体本体贴图组件（RenderEntity 叶子）：BoxUtil SpriteEntity 逐帧跟随弹体渲染本体，
 * 取代原版弹体贴图渲染路径（原版视觉由 .proj 的 `sprite=BUtil_NONE.png` 屏蔽）。
 *
 * 渲染语义对齐原版 Missile.render 的弹体贴图：
 * - normal alpha 混合（实体贴图非加色），层位 ABOVE_SHIPS（原版导弹同层）；
 * - 位置/朝向逐帧取弹体 API 真值（location/facing——不用 frame 移动朝向：钉住的附着弹体
 *   朝向随宿主转向而非位移方向）；
 * - alpha 对齐原版：导弹取 `MissileAPI.getCurrentBaseAlpha()`（熄火淡出/相位渐变同款），
 *   `spriteAlphaOverride >= 0` 时优先；非导弹弹体取 `getBrightness()`；
 * - 贴图约定同 SpriteEntity：文件右（+u）= 飞行正向。
 *
 * 弹体移出引擎即删除实体。BoxUtil 未就绪/贴图不可读/建实体失败时 WARN 一次并禁用自身
 * （不重试风暴），弹体其余特效层不受影响。
 */
class SpriteBodyRenderComponent(
    id: String,
    internal val spec: SpriteBodySpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_SHIPS_LAYER, RENDER_ORDER_SPRITE_BODY) {

    private val log = Global.getLogger(SpriteBodyRenderComponent::class.java)
    private var sprite: SpriteEntity? = null
    private var disabled = false

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        val projectile = (ctx.host as? ProjectileHost)?.projectile ?: return false
        try {
            Global.getSettings().openStream(spec.texturePath).use { }
        } catch (e: Exception) {
            log.warn("ASTD sprite body texture unreadable: id=$id path=${spec.texturePath}", e)
            disabled = true
            return true
        }

        val entity = SpriteEntity(spec.texturePath)
        entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_LAYER)
        entity.setNormalBlend()
        entity.setBaseSizePerTiles(spec.width / 2f, spec.height / 2f)
        entity.materialData.setColor(1f, 1f, 1f, 1f)
        if (spec.glowPower > 0f) {
            entity.materialData.emissive = entity.materialData.diffuse
            entity.materialData.setEmissiveColor(1f, 1f, 1f, 1f)
            entity.materialData.glowPower = spec.glowPower
        }
        // 常驻：消亡由组件按弹体状态显式 delete，不走全局计时器
        entity.setGlobalTimer(0f, BODY_FULL_SECONDS, 0f)
        BoxUtilCombatVfx.ensureReady(engine)
        val state = addEntity(engine, entity)
        if (state != 0) {
            log.warn("ASTD sprite body 注册失败（addEntity 返回 $state）：id=$id，本弹体本体层缺失，其余特效层照常")
            entity.delete()
            disabled = true
            return true
        }
        sprite = entity
        syncBody(projectile)
        return true
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val entity = sprite ?: return
        if (disabled) return
        val engine = ctx.engine ?: return
        val projectile = (ctx.host as? ProjectileHost)?.projectile ?: return
        if (!engine.isEntityInPlay(projectile)) {
            entity.delete()
            sprite = null
            disabled = true
            return
        }
        syncBody(projectile)
    }

    /** 逐帧同步：位置/朝向取弹体真值；alpha 对齐原版 Missile.render 的弹体贴图淡出语义。 */
    private fun syncBody(projectile: DamagingProjectileAPI) {
        val entity = sprite ?: return
        val alpha = bodyAlpha(projectile)
        if (!alpha.isFinite()) return
        entity.setStateVanilla(
            projectile.location,
            BoxUtilCombatVfx.normalizeFacingDeg(projectile.facing),
            UNIT_SCALE,
        )
        entity.materialData.colorAlpha = alpha
    }

    override fun onDetachSelf() {
        sprite?.delete()
        sprite = null
    }

    companion object {
        /** 本体绘制序：原版导弹同层（ABOVE_SHIPS）；绘制序压在螺栓（200）之下，弹体本体先于弹头光效铺底。 */
        const val RENDER_ORDER_SPRITE_BODY = 190

        /** 本体常驻时长（秒）：生命周期由弹体状态显式驱动，这里给一个永不自然到期的值。 */
        private const val BODY_FULL_SECONDS = 1e7f

        /** 单位缩放（本体贴图无出生伸入，对齐原版导弹贴图恒定尺寸）。 */
        private val UNIT_SCALE = Vector2f(1f, 1f)

        /** 本体 alpha：导弹 = 原版熄火淡出/覆盖语义；其余弹体 = brightness（出生伸入/命中淡出同款）。 */
        internal fun bodyAlpha(projectile: DamagingProjectileAPI): Float {
            if (projectile is MissileAPI) {
                val override = projectile.spriteAlphaOverride
                return if (override >= 0f) override else projectile.currentBaseAlpha
            }
            return projectile.brightness
        }
    }
}
