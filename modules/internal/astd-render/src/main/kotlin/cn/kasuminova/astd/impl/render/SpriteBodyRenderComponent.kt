package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ProjectileHost
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLease
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLeaseKey
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLeaseSpec
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 弹体本体贴图组件（RenderEntity 叶子）：池化 BoxUtil SpriteEntity（[PooledCombatVfx.checkoutSprite]
 * 租约）逐帧跟随弹体渲染本体，取代原版弹体贴图渲染路径（原版视觉由 .proj 的
 * `sprite=BUtil_NONE.png` 屏蔽）。
 *
 * 渲染语义对齐原版 Missile.render 的弹体贴图：
 * - normal alpha 混合（实体贴图非加色），层位 ABOVE_SHIPS（原版导弹同层）；
 * - 位置/朝向逐帧取弹体 API 真值（location/facing——不用 frame 移动朝向：钉住的附着弹体
 *   朝向随宿主转向而非位移方向）；
 * - alpha 对齐原版：导弹取 `MissileAPI.getCurrentBaseAlpha()`（熄火淡出/相位渐变同款），
 *   `spriteAlphaOverride >= 0` 时优先；非导弹弹体取 `getBrightness()`；
 * - 贴图约定同 SpriteEntity：文件右（+u）= 飞行正向。
 *
 * 租约口径：手动 alpha + 心跳看门狗（0.5s 未触活即泊车兜底）；弹体移出引擎/组件 detach
 * 即 release(0f) 归还。BoxUtil 未就绪/贴图不可读/检出失败时 WARN 一次并禁用自身
 * （不重试风暴），弹体其余特效层不受影响。
 */
class SpriteBodyRenderComponent(
    id: String,
    internal val spec: SpriteBodySpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_SHIPS_LAYER, RENDER_ORDER_SPRITE_BODY) {

    private val log = Global.getLogger(SpriteBodyRenderComponent::class.java)
    private var spriteLease: SpriteLease? = null
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

        // 材质口径对齐出厂 new SpriteEntity(path)：alphaToEmissive=1（emissive alpha 跟随
        // 逐帧 colorAlpha）、additionEmissive / ignoreIllumination=true
        val lease = PooledCombatVfx.checkoutSprite(
            engine,
            SpriteLeaseKey(
                layer = CombatEngineLayers.ABOVE_SHIPS_LAYER,
                textureKey = spec.texturePath,
                additive = false,
                instanced = false,
                capacity = 64,
            ),
            SpriteLeaseSpec(
                location = Vector2f(projectile.location),
                facingDeg = BoxUtilCombatVfx.normalizeFacingDeg(projectile.facing),
                spritePath = spec.texturePath,
                bindEmissive = spec.glowPower > 0f,
                baseSizeHalfWidth = spec.width / 2f,
                baseSizeHalfHeight = spec.height / 2f,
                color = Color.WHITE,
                emissiveColor = Color.WHITE,
                glowPower = spec.glowPower,
                alphaToEmissive = 1f,
                isAdditionEmissive = true,
                isIgnoreIllumination = true,
                watchdogHeartbeat = WATCHDOG_HEARTBEAT_SECONDS,
                watchdogFadeOut = 0f,
            ),
        )
        if (lease == null) {
            disabled = true
            return true
        }
        spriteLease = lease
        syncBody(lease, projectile)
        return true
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        val lease = spriteLease ?: return
        if (disabled) return
        val engine = ctx.engine ?: return
        val projectile = (ctx.host as? ProjectileHost)?.projectile ?: return
        if (!engine.isEntityInPlay(projectile)) {
            lease.release(0f)
            spriteLease = null
            disabled = true
            return
        }
        syncBody(lease, projectile)
    }

    /** 逐帧同步：位置/朝向取弹体真值；alpha 对齐原版 Missile.render 的弹体贴图淡出语义。 */
    private fun syncBody(lease: SpriteLease, projectile: DamagingProjectileAPI) {
        val alpha = bodyAlpha(projectile)
        if (!alpha.isFinite()) return
        val entity = lease.entity
        entity.setStateVanilla(
            projectile.location,
            BoxUtilCombatVfx.normalizeFacingDeg(projectile.facing),
            UNIT_SCALE,
        )
        entity.materialData.colorAlpha = alpha
        lease.touch()
    }

    override fun onDetachSelf() {
        spriteLease?.release(0f)
        spriteLease = null
    }

    companion object {
        /** 本体绘制序：原版导弹同层（ABOVE_SHIPS）；绘制序压在螺栓（200）之下，弹体本体先于弹头光效铺底。 */
        const val RENDER_ORDER_SPRITE_BODY = 190

        /** 看门狗心跳（秒）：弹体宿主停更超此时长即自动泊车归还（实体不滞留兜底）。 */
        private const val WATCHDOG_HEARTBEAT_SECONDS = 0.5f

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
