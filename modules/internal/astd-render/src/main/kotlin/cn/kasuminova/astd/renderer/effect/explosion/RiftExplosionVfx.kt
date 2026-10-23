package cn.kasuminova.astd.renderer.effect.explosion

import cn.kasuminova.astd.renderer.effect.explosion.RiftExplosionPalette.Companion.BLUE
import cn.kasuminova.astd.renderer.effect.explosion.RiftExplosionVfx.DEFAULT_RADIUS
import cn.kasuminova.astd.renderer.effect.explosion.RiftExplosionVfx.NUM_RIFTS
import cn.kasuminova.astd.renderer.effect.explosion.RiftExplosionVfx.SIZE_SCALE
import cn.kasuminova.astd.renderer.effect.explosion.RiftExplosionVfx.SOUND_EXPLOSION
import cn.kasuminova.astd.renderer.effect.explosion.RiftExplosionVfx.SOUND_WINDUP
import cn.kasuminova.astd.renderer.effect.explosion.RiftExplosionVfx.THICKNESS_RATIO
import cn.kasuminova.astd.renderer.effect.explosion.RiftExplosionVfx.WINDUP_SECONDS
import cn.kasuminova.astd.renderer.effect.explosion.RiftExplosionVfx.buildRiftParams
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.impl.combat.NegativeExplosionVisual
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

data class RiftExplosionPalette(
    val border: Color,
    val underglow: Color,
    val windup: Color,
    val black: Color = Color.black,
) {
    companion object {
        @JvmField
        val BLUE: RiftExplosionPalette = RiftExplosionPalette(
            border = Color(70, 130, 255, 255),
            underglow = Color(10, 40, 140, 100),
            windup = Color(90, 150, 255, 60),
        )

        @JvmField
        val RED: RiftExplosionPalette = RiftExplosionPalette(
            border = Color(200, 20, 0, 255),
            underglow = Color(200, 20, 0, 100),
            windup = Color(200, 20, 0, 60),
        )
    }
}

object RiftExplosionVfx {
    private val log = Global.getLogger(RiftExplosionVfx::class.java)

    const val SOUND_EXPLOSION = "riftcascade_rift"

    const val SOUND_WINDUP = "riftcascade_windup"

    const val SIZE_SCALE = 0.7f

    const val VANILLA_BASE_RADIUS = 25f

    const val DEFAULT_RADIUS = VANILLA_BASE_RADIUS * SIZE_SCALE

    const val NUM_RIFTS = 2

    const val WINDUP_SECONDS = 0.5f

    const val DEFAULT_FADE_OUT = 1.0f

    private const val THICKNESS_RATIO = 1.0f

    private const val RADIUS_JITTER_MIN = 0.75f
    private const val RADIUS_JITTER_SPAN = 0.5f

    private const val SPAWN_SCATTER_RATIO = 0.4f

    private const val DRIFT_SPEED_RATIO = 0.7f

    internal fun buildRiftParams(
        palette: RiftExplosionPalette,
        radius: Float,
        fadeOut: Float,
        withHitGlow: Boolean,
    ): NegativeExplosionVisual.NEParams {
        val p = NegativeExplosionVisual.NEParams()
        p.hitGlowSizeMult = 0.75f
        p.spawnHitGlowAt = 0.0f
        p.noiseMag = 1.0f
        p.fadeIn = 0.1f
        p.fadeOut = fadeOut
        p.underglow = palette.underglow
        p.withHitGlow = withHitGlow
        p.radius = radius
        p.thickness = radius * THICKNESS_RATIO
        p.color = palette.border
        p.blackColor = palette.black
        return p
    }

    fun riftExplosion(
        engine: CombatEngineAPI,
        at: Vector2f,
        radius: Float = DEFAULT_RADIUS,
        fadeOut: Float = DEFAULT_FADE_OUT,
        palette: RiftExplosionPalette = BLUE,
    ): Int {
        if (radius.isNaN() || radius <= 0f || fadeOut.isNaN() || fadeOut <= 0f) {
            log.warn("裂隙爆炸参数非法（radius=$radius fadeOut=$fadeOut），属配置错误，本次不生成特效")
            return 0
        }
        // Misc 在单测 JVM 静态初始化失败，随机数必须用 MathUtils
        var prev: CombatEntityAPI? = null
        repeat(NUM_RIFTS) {
            val p = buildRiftParams(
                palette = palette,
                radius = radius * (RADIUS_JITTER_MIN + RADIUS_JITTER_SPAN * MathUtils.getRandom().nextFloat()),
                fadeOut = fadeOut,
                withHitGlow = prev == null,
            )
            val loc = MathUtils.getRandomPointOnCircumference(at, p.radius * SPAWN_SCATTER_RATIO)
            val entity = engine.addLayeredRenderingPlugin(NegativeExplosionVisual(p))
            entity.location.set(loc)
            val anchor = prev
            if (anchor != null) {
                val vel = Vector2f.sub(anchor.location, loc, null)
                val dist = vel.length()
                if (dist > 0f) {
                    vel.normalise()
                    vel.scale(dist / (p.fadeIn + p.fadeOut) * DRIFT_SPEED_RATIO)
                    entity.velocity.set(vel)
                }
            }
            prev = entity
        }
        return NUM_RIFTS
    }

    fun spawnWindup(
        engine: CombatEngineAPI,
        at: Vector2f,
        radius: Float = DEFAULT_RADIUS,
        palette: RiftExplosionPalette = BLUE,
    ) {
        engine.addSmoothParticle(at, ZERO_VEL, radius * 4f, 0.6f, WINDUP_SECONDS, palette.windup)
    }

    private val ZERO_VEL = Vector2f(0f, 0f)
}
