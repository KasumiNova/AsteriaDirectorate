package cn.kasuminova.astd.combat.effect.lens.signature.singularity

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileTracerManager
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileVisual
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 奇点导弹“重定向提示”视觉：每当 AI 重定向（默认 2s 一次）就闪一下短促的扭曲+光刺，帮助玩家读到“它又转向了”。
 *
 * 约束：必须纯 BoxUtil（TrailEntity + DistortionEntity），不使用原版粒子。
 */
internal class SingularityRetargetPulseVisual(
    private val engine: CombatEngineAPI,
    private val variant: SingularityDetonationFx.Variant,
) : ProjectileVisual {

    private var fadeStarted = false
    private var fadeOutSeconds = 0.10f
    private var fadeTimer = 0f

    // 用“最近一次重定向发生时刻”做触发源，避免 retargetCount 在某些异常情况下快速抖动导致刷屏。
    private var lastSeenRetargetAt = Float.NaN
    private var lastSpawnAt = -999f

    private val coreSprite by lazy { Global.getSettings().getSprite("graphics/fx/beamcoreb.png") }
    private val fringeSprite by lazy { Global.getSettings().getSprite("graphics/fx/beamfringeb.png") }

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        if (amount > 0f && fadeStarted) {
            fadeTimer += amount
            return
        }

        val now = try {
            engine.getTotalElapsedTime(false)
        } catch (_: Throwable) {
            0f
        }

        val retargetAt = (projectile.customData[SingularityKeys.MISSILE_LAST_RETARGET_AT] as? Float)
        if (retargetAt == null) return

        if (lastSeenRetargetAt.isNaN()) {
            lastSeenRetargetAt = retargetAt
            return
        }

        if (retargetAt <= lastSeenRetargetAt + 0.0001f) return

        // 频率控制：即使 AI 在极端情况下频繁更新 retargetAt，这里也做节流。
        val minInterval = when (variant) {
            SingularityDetonationFx.Variant.NOVA -> 0.90f
            SingularityDetonationFx.Variant.EVENT_HORIZON -> 1.10f
        }
        if (now - lastSpawnAt < minInterval) {
            lastSeenRetargetAt = retargetAt
            return
        }
        lastSeenRetargetAt = retargetAt
        lastSpawnAt = now

        if (fadeStarted) return

        try {
            spawnPulse(projectile.location)
        } catch (_: Throwable) {
        }
    }

    private fun spawnPulse(loc: Vector2f) {
        // BoxUtil 依赖未就绪时静默失败（不做原版粒子兜底）。
        BoxUtilCombatVfx.ensureReady(engine)

        val (coreColor, fringeColor, rays, sizeMul) = when (variant) {
            SingularityDetonationFx.Variant.NOVA -> Quad(Color(255, 245, 245, 205), Color(255, 90, 90, 185), 3, 1.0f)
            SingularityDetonationFx.Variant.EVENT_HORIZON -> Quad(Color(255, 250, 255, 215), Color(235, 120, 255, 195), 4, 1.25f)
        }

        // 小扭曲：很短促，强调“瞬间折转”。
        try {
            val e = DistortionEntity()
            // 需求：降低频率 + 增加存活时间，让“光锥/光刺”更易被读到。
            e.setGlobalTimer(0.04f, 0.10f, 0.30f)
            e.setInnerFull(0.22f, 0.22f)
            e.setInnerHardness(0.82f)
            e.setRingHardness(0.62f)

            val inSize = 16f * sizeMul
            val fullSize = 34f * sizeMul
            val outSize = 10f * sizeMul
            e.setSizeIn(inSize, inSize)
            e.setSizeFull(fullSize, fullSize)
            e.setSizeOut(outSize, outSize)

            e.setPowerIn(0.20f)
            e.setPowerFull(0.35f)
            e.setPowerOut(0.06f)
            e.setLocation(loc)

            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_DISTORTION, e)
            if (state != 0) e.delete()
        } catch (_: Throwable) {
        }

        // 光刺：数量很少，避免蜂群刷屏。
        for (i in 0 until rays) {
            val ang = MathUtils.getRandomNumberInRange(0f, 360f)
            val len = MathUtils.getRandomNumberInRange(36f, 54f) * sizeMul
            val baseW = MathUtils.getRandomNumberInRange(4.8f, 6.8f) * sizeMul
            val tipW = (baseW * 0.16f).coerceIn(0.55f, 1.8f)

            try {
                val ent = BoxUtilCombatVfx.createTaperedBeamTrailFromCenter(
                    location = Vector2f(0f, 0f),
                    facing = ang,
                    length = len,
                    baseWidth = baseW,
                    tipWidth = tipW,
                    coreColor = coreColor,
                    fringeColor = fringeColor,
                    coreSprite = coreSprite,
                    fringeSprite = fringeSprite,
                    layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    full = 9999f,
                    baseAlphaMul = 0.40f,
                    tipAlphaMul = 0.06f,
                    baseEmissiveAlphaMul = 2.6f,
                    tipEmissiveAlphaMul = 0.9f,
                    mixPower = 2.4f,
                )

                ent.setGlobalTimer(0.04f, 0.10f, 0.30f)
                ent.setStateVanilla(loc, ang)

                val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_TRAIL, ent)
                if (state != 0) ent.delete()
            } catch (_: Throwable) {
            }
        }
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        if (!fadeStarted) {
            fadeStarted = true
            this.fadeOutSeconds = fadeOutSeconds.coerceAtLeast(0.01f)
            fadeTimer = 0f
        }
    }

    override fun isFadeOutOver(): Boolean {
        return fadeStarted && fadeTimer >= fadeOutSeconds
    }

    override fun delete() {
        // 全部实体都是一次性触发，不需要回收。
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
