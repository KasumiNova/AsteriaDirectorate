package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ProjectileHost
import cn.kasuminova.astd.api.render.RenderContext
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLease
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLeaseKey
import cn.kasuminova.astd.renderer.boxutil.pool.SpriteLeaseSpec
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.sqrt

class BoltRenderComponent(
    id: String,
    internal val spec: BoltSpec,
) : RenderEntityImpl(id, CombatEngineLayers.ABOVE_SHIPS_LAYER, RENDER_ORDER_BOLT) {

    private val log = Global.getLogger(BoltRenderComponent::class.java)
    private var boltLease: SpriteLease? = null
    private var disabled = false
    private var hitGlowSpawned = false

    private var specLength = 0f
    private var specWidth = 0f
    private var hitGlowRadius = 0f
    private var missileMode = false

    override fun onAttachSelf(ctx: RenderContext): Boolean {
        val engine = ctx.engine ?: return false
        val projectile = (ctx.host as? ProjectileHost)?.projectile ?: return false
        if (projectile is MissileAPI && !spec.allowMissile) {
            disabled = true
            return true
        }
        missileMode = projectile is MissileAPI
        // 脚本生成的 MissileAPI 可能没有 projectileSpec，显式尺寸必须能独立完成判定。
        val dims = resolveBoltDimensions(
            spec.lengthOverride, spec.widthOverride,
            projectile.projectileSpec?.length, projectile.projectileSpec?.width,
            projectile.projectileSpec?.hitGlowRadius,
        )
        if (dims == null) {
            log.warn(
                "ASTD box bolt 尺寸裁定失败（hasSpec=${projectile.projectileSpec != null} " +
                    "lengthOverride=${spec.lengthOverride} widthOverride=${spec.widthOverride}）：id=$id，" +
                    "本弹体螺栓层缺失，其余特效层照常",
            )
            disabled = true
            return true
        }
        specLength = dims.length
        specWidth = dims.width
        hitGlowRadius = dims.hitGlowRadius
        try {
            Global.getSettings().openStream(spec.texturePath).use { }
        } catch (e: Exception) {
            log.warn("ASTD box bolt texture unreadable: id=$id path=${spec.texturePath}", e)
            disabled = true
            return true
        }

        val lease = checkoutBolt(engine, projectile)
        if (lease == null) {
            disabled = true
            return true
        }
        boltLease = lease
        syncBolt(ctx, projectile)
        return true
    }

    override fun advanceSelf(ctx: RenderContext, amount: Float) {
        if (disabled) return
        val engine = ctx.engine ?: return
        val projectile = (ctx.host as? ProjectileHost)?.projectile ?: return
        if (!engine.isEntityInPlay(projectile)) {
            releaseBolt()
            disabled = true
            return
        }
        syncBolt(ctx, projectile)
        if (!hitGlowSpawned && projectile.didDamage()) {
            hitGlowSpawned = true
            spawnHitGlow(engine, projectile)
        }
    }

    private fun checkoutBolt(engine: CombatEngineAPI, projectile: DamagingProjectileAPI): SpriteLease? {
        val tint = spec.color.toAwtColor()
        // 材质参数对齐 new SpriteEntity(path) 默认值：alphaToEmissive=1（emissive alpha 跟随
        // 逐帧 colorAlpha）、additionEmissive / ignoreIllumination=true
        return PooledCombatVfx.checkoutSprite(
            engine,
            SpriteLeaseKey(
                layer = CombatEngineLayers.ABOVE_SHIPS_LAYER,
                textureKey = spec.texturePath,
                additive = true,
                instanced = false,
                capacity = 64,
            ),
            SpriteLeaseSpec(
                location = Vector2f(projectile.location),
                facingDeg = BoxUtilCombatVfx.normalizeFacingDeg(projectile.facing),
                spritePath = spec.texturePath,
                bindEmissive = true,
                baseSizeHalfWidth = specLength / 2f,
                baseSizeHalfHeight = specWidth / 2f,
                color = tint,
                emissiveColor = tint,
                glowPower = 0.5f,
                alphaToEmissive = 1f,
                isAdditionEmissive = true,
                isIgnoreIllumination = true,
                watchdogHeartbeat = WATCHDOG_HEARTBEAT_SECONDS,
                watchdogFadeOut = 0f,
            ),
        )
    }

    private fun syncBolt(ctx: RenderContext, projectile: DamagingProjectileAPI) {
        val brightness = projectile.brightness
        if (!brightness.isFinite()) return
        // 朝向取 frame 位移方向：弹体继承发射舰速度时位移方向偏离 projectile.facing，贴图跟 facing 会与拖尾产生角偏差
        val facing = BoxUtilCombatVfx.normalizeFacingDeg(ctx.frame.facing)
        // 原版 TrailExtender 把尾点沿 projectile.facing 摆放，位移方向偏离 facing 时直接取用会把螺栓中心
        // 拽离真实弹道；|头−尾| 距离有效（上限 spec.length），取距离沿位移朝向重构尾点
        val tailTruth = projectile.tailEnd
        val tail = when {
            tailTruth != null -> {
                val dx = projectile.location.x - tailTruth.x
                val dy = projectile.location.y - tailTruth.y
                boltTailBehindHead(projectile.location, facing, sqrt(dx * dx + dy * dy))
            }
            // 导弹无 TrailExtender，getTailEnd 恒 null：沿朝向反推 specLength 合成尾点，螺栓按全长渲染
            missileMode -> boltTailBehindHead(projectile.location, facing, specLength)
            else -> null
        }
        val frame = boltFrame(
            head = projectile.location,
            tail = tail,
            facingDeg = facing,
            specLength = specLength,
        )
        val alpha = brightness * brightness * spec.color.alpha
        val scale = Vector2f(frame.scaleX, 1f)
        val lease = boltLease ?: return
        lease.entity.setStateVanilla(frame.center, frame.facingDeg, scale)
        lease.entity.materialData.colorAlpha = alpha
        lease.touch()
    }

    // 原版屏蔽后 fringeColor alpha=0，引擎自发 hit particle 不可见，需手动补偿命中光晕
    private fun spawnHitGlow(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
        val scale = hitGlowScale(projectile.damageAmount)
        val tint = Color(
            (spec.color.red.coerceIn(0f, 1f) * 255f).toInt(),
            (spec.color.green.coerceIn(0f, 1f) * 255f).toInt(),
            (spec.color.blue.coerceIn(0f, 1f) * 255f).toInt(),
        )
        val at = Vector2f(projectile.location)
        val zero = Vector2f(0f, 0f)
        engine.addHitParticle(at, zero, hitGlowRadius * 3f * scale, 1f, 0.4f, tint)
        engine.addHitParticle(at, zero, hitGlowRadius * 0.5f * scale, 1f, 0.8f, Color.WHITE)
    }

    private fun releaseBolt() {
        boltLease?.release(0f)
        boltLease = null
    }

    override fun onDetachSelf() {
        releaseBolt()
    }

    companion object {
        const val RENDER_ORDER_BOLT = 200

        private const val WATCHDOG_HEARTBEAT_SECONDS = 0.5f
    }
}

private fun ASTDColor.toAwtColor(): Color = Color(
    (red.coerceIn(0f, 1f) * 255f).toInt(),
    (green.coerceIn(0f, 1f) * 255f).toInt(),
    (blue.coerceIn(0f, 1f) * 255f).toInt(),
    (alpha.coerceIn(0f, 1f) * 255f).toInt(),
)

internal data class BoltFrame(val center: Vector2f, val facingDeg: Float, val scaleX: Float)

internal data class BoltDimensions(val length: Float, val width: Float, val hitGlowRadius: Float)

internal fun resolveBoltDimensions(
    lengthOverride: Float?,
    widthOverride: Float?,
    specLength: Float?,
    specWidth: Float?,
    specHitGlowRadius: Float?,
): BoltDimensions? {
    val length = lengthOverride ?: specLength ?: return null
    val width = widthOverride ?: specWidth ?: return null
    if (length <= 0f || width <= 0f) return null
    return BoltDimensions(length, width, specHitGlowRadius ?: width)
}

internal fun boltFrame(
    head: Vector2f,
    tail: Vector2f?,
    facingDeg: Float,
    specLength: Float,
): BoltFrame {
    val rad = Math.toRadians(facingDeg.toDouble())
    val dirX = kotlin.math.cos(rad).toFloat()
    val dirY = kotlin.math.sin(rad).toFloat()
    val realTail = tail ?: head
    val span = (head.x - realTail.x) * dirX + (head.y - realTail.y) * dirY
    val scaleX = (span / specLength).coerceIn(0.02f, 1.2f)
    val center = Vector2f(
        (head.x + realTail.x) / 2f,
        (head.y + realTail.y) / 2f,
    )
    return BoltFrame(center, facingDeg, scaleX)
}

internal fun hitGlowScale(damageAmount: Float): Float =
    (sqrt((damageAmount.coerceAtLeast(0f)) / 250f)).coerceIn(0.8f, 2.5f)

internal fun boltTailBehindHead(head: Vector2f, facingDeg: Float, tailDistance: Float): Vector2f {
    val rad = Math.toRadians(facingDeg.toDouble())
    return Vector2f(
        head.x - (kotlin.math.cos(rad) * tailDistance).toFloat(),
        head.y - (kotlin.math.sin(rad) * tailDistance).toFloat(),
    )
}
