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

        // 材质必须对齐 SpriteEntity 默认初始化，避免池化复用后 emissive 透明度丢失。
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
        const val RENDER_ORDER_SPRITE_BODY = 190

        private const val WATCHDOG_HEARTBEAT_SECONDS = 0.5f

        private val UNIT_SCALE = Vector2f(1f, 1f)

        internal fun bodyAlpha(projectile: DamagingProjectileAPI): Float {
            if (projectile is MissileAPI) {
                val override = projectile.spriteAlphaOverride
                return if (override >= 0f) override else projectile.currentBaseAlpha
            }
            return projectile.brightness
        }
    }
}
