package cn.kasuminova.astd.combat.effect.arc.signature.stellarjet

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileVfxPresets

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.WeaponAPI
import org.boxutil.manager.CombatRenderingManager
import org.boxutil.units.standard.entity.DistortionEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 恒星喷射：系统 charge-up（IN 阶段）的“聚能/充能”视觉。
 *
 * 目标观感：武器周围大量光粒/光锥向武器中心收束。
 * - 目前按优化要求：仅保留“粒子”（使用原版粒子 API），避免光锥/短束带来的杂乱。
 */
internal class StellarJetChargeUpVfx(
    private val coreColor: Color,
    private val glowColor: Color,
    /** 仅缩放几何尺寸（半径/粒子大小/爆闪尺寸等），不缩放速度与频率。 */
    private val scale: Float = 1f,
    private val layer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
) {

    // rate limit accumulator（避免帧率波动导致“时快时慢”）
    private var particleAcc = 0f

    // 中心光点：用短寿命粒子持续刷新，避免在中心堆太多粒子
    private var centerDotAcc = 0f

    fun reset() {
        particleAcc = 0f
        centerDotAcc = 0f
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun rand01(): Float = Math.random().toFloat()

    fun advance(
        engine: CombatEngineAPI,
        amount: Float,
        weapon: WeaponAPI,
        // 0..1：来自 system.effectLevel（chargeUp 期间随时间增长）
        chargeLevel: Float,
    ) {
        if (engine.isPaused) return
        if (amount <= 0f) return

        val ship = weapon.ship ?: return
        if (ship.isHulk) return

        val t = chargeLevel.coerceIn(0f, 1f)
        // 充能阶段整体强度（非线性：前期也要“看得见”）
        val s = (0.20f + 0.80f * t.pow(0.65f)).coerceIn(0f, 1f)

        val sz = scale.coerceIn(0.35f, 2.25f)

        // 基于船速做一点点“抖动/动势”，但不要完全跟随（否则高速时会被拉扯得很怪）
        val shipVel = ship.velocity ?: Vector2f(0f, 0f)
        val velMul = (0.15f + 0.25f * s)

        val center = Vector2f(weapon.location)
        center.x += shipVel.x * velMul * amount
        center.y += shipVel.y * velMul * amount

        // ====== 中心光点（逐渐变亮） ======
        emitCenterDot(engine, amount, center, shipVel, s)

        // ====== 仅粒子：向中心吸入 ======
        // 需求：降低充能期间粒子数量（-40%）
        val particleRate = (PARTICLE_PER_SEC * PARTICLE_RATE_MUL * (0.25f + 0.75f * s)).coerceAtLeast(0f)
        particleAcc += particleRate * amount
        val pCount = particleAcc.toInt().coerceAtMost(PARTICLE_MAX_PER_FRAME)
        if (pCount > 0) particleAcc -= pCount

        // 需求：范围（-40%），速度（-30%）
        val radius = lerp(VANILLA_RADIUS_MAX, VANILLA_RADIUS_MIN, s) * sz * PARTICLE_RADIUS_MUL
        val speedBase = lerp(VANILLA_SPEED_MIN, VANILLA_SPEED_MAX, s) * PARTICLE_SPEED_MUL
        val sizeBase = lerp(VANILLA_SIZE_MIN, VANILLA_SIZE_MAX, s) * sz

        for (i in 0 until pCount) {
            val ang = rand01() * 360f
            val rad = Math.toRadians(ang.toDouble())
            val r = radius * (0.60f + 0.70f * rand01())
            val spawn = Vector2f(
                center.x + cos(rad).toFloat() * r,
                center.y + sin(rad).toFloat() * r,
            )

            val dx = center.x - spawn.x
            val dy = center.y - spawn.y
            val dist = sqrt((dx * dx + dy * dy).coerceAtLeast(0.001f))
            val ux = dx / dist
            val uy = dy / dist

            // 略带随机，让粒子“聚集”不那么死板
            val speed = speedBase * (0.65f + 0.70f * rand01())
            val vel = Vector2f(ux * speed, uy * speed)

            val size = sizeBase * (0.70f + 0.60f * rand01())
            val dur = lerp(VANILLA_DUR_MIN, VANILLA_DUR_MAX, rand01())
            val bright = lerp(0.60f, 1.15f, rand01()) * (0.85f + 0.35f * s)

            val c = if (rand01() < 0.35f) coreColor else glowColor
            try {
                engine.addSmoothParticle(spawn, vel, size, bright, dur, c)
            } catch (_: Throwable) {
            }
        }
    }

    fun onChargeComplete(engine: CombatEngineAPI, weapon: WeaponAPI) {
        val ship = weapon.ship ?: return
        if (ship.isHulk) return

        val center = Vector2f(weapon.location)
        val vel = ship.velocity ?: Vector2f(0f, 0f)

        val sz = scale.coerceIn(0.35f, 2.25f)

        // 核心闪光
        try {
            engine.spawnExplosion(center, vel, coreColor, 120f * sz, 0.18f)
        } catch (_: Throwable) {
        }
        try {
            engine.addHitParticle(center, vel, 160f * sz, 1.6f, 0.10f, coreColor)
        } catch (_: Throwable) {
        }
        try {
            engine.addSmoothParticle(center, vel, 260f * sz, 1.05f, 0.22f, glowColor)
        } catch (_: Throwable) {
        }

        // 范围扭曲：优先复用 GSP12 的 BoxUtil DistortionEntity（更像“透镜/折射”），失败再回退到 nebula 近似扭曲
        val spawnedDistortion = spawnChargeCompleteDistortion(center, engine)
        if (!spawnedDistortion) {
            // 近似“范围扭曲”：使用 negative nebula 粒子做一个逐渐扩大的扰动环
            // 参考本项目 Drv11OnFireEffect 的 addNebulaParticle(..., true)
            val distort = Color(110, 190, 255, 55)
            try {
                engine.addNebulaParticle(center, Vector2f(vel), 220f * sz, 2.6f, 0.06f, 0.22f, 0.60f, distort, true)
                engine.addNebulaParticle(center, Vector2f(vel), 320f * sz, 3.0f, 0.05f, 0.20f, 0.70f, distort, true)
                engine.addNebulaParticle(center, Vector2f(vel), 420f * sz, 3.3f, 0.04f, 0.18f, 0.80f, distort, true)
            } catch (_: Throwable) {
                // 某些环境下 nebula API 参数差异/不可用时，忽略即可（至少还有闪光/粒子）
            }
        }

        // 外扩火花：带一点“冲击”反馈
        val sparks = 14
        for (i in 0 until sparks) {
            val ang = rand01() * 360f
            val rad = Math.toRadians(ang.toDouble())
            val speed = lerp(220f, 720f, rand01())
            val v = Vector2f(cos(rad).toFloat() * speed + vel.x * 0.35f, sin(rad).toFloat() * speed + vel.y * 0.35f)
            val size = lerp(10f, 22f, rand01()) * sz
            val dur = lerp(0.18f, 0.32f, rand01())
            val c = if (rand01() < 0.35f) coreColor else glowColor
            try {
                engine.addSmoothParticle(center, v, size, 1.25f, dur, c)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 复用 GSP12 的扭曲实体：这里做“由小到大扩张”的透镜扰动环。
     * @return true 表示成功创建并加入渲染队列。
     */
    private fun spawnChargeCompleteDistortion(center: Vector2f, engine: CombatEngineAPI): Boolean {
        return try {
            val sz = scale.coerceIn(0.35f, 2.25f)
            val e = DistortionEntity()

            // 扩张波纹：出得快、全盛短、淡出稍长
            e.setGlobalTimer(0.06f, 0.10f, 0.48f)

            // 形状：中心较硬、外围较柔
            e.setInnerFull(0.35f, 0.35f)
            e.setInnerHardness(0.80f)
            e.setRingHardness(0.58f)

            // 逐渐变大：小 -> 中 -> 大（随后 power 归零）
            e.setSizeIn(90f * sz, 90f * sz)
            e.setSizeFull(240f * sz, 240f * sz)
            e.setSizeOut(520f * sz, 520f * sz)

            e.setPowerIn(0.00f)
            e.setPowerFull(1.10f)
            e.setPowerOut(0f)

            e.setLocation(center)
            CombatRenderingManager.addEntity(e)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun emitCenterDot(
        engine: CombatEngineAPI,
        amount: Float,
        center: Vector2f,
        shipVel: Vector2f,
        strength: Float,
    ) {
        val s = strength.coerceIn(0f, 1f)

        val sz = scale.coerceIn(0.35f, 2.25f)

        // 用 rate-limit 防止极端帧率下每帧都刷
        // 需求：降低充能期间粒子数量（-40%）（中心光点也一起收敛，避免“中心太吵”）
        val rate = (CENTER_DOT_PER_SEC * CENTER_DOT_RATE_MUL * (0.40f + 0.60f * s)).coerceAtLeast(0f)
        centerDotAcc += rate * amount
        val count = centerDotAcc.toInt().coerceAtMost(CENTER_DOT_MAX_PER_FRAME)
        if (count > 0) centerDotAcc -= count
        if (count <= 0) return

        for (i in 0 until count) {
            val size = lerp(CENTER_DOT_SIZE_MIN, CENTER_DOT_SIZE_MAX, s) * (0.85f + 0.35f * rand01()) * sz
            val dur = lerp(0.045f, 0.075f, rand01())
            val bright = lerp(0.55f, 1.45f, s) * (0.85f + 0.35f * rand01())
            // 轻微跟随舰速，避免高速下“点完全静止在屏幕空间”的违和
            val v = Vector2f(shipVel.x * 0.25f, shipVel.y * 0.25f)
            val c = if (rand01() < 0.40f) coreColor else glowColor
            try {
                engine.addSmoothParticle(center, v, size, bright, dur, c)
            } catch (_: Throwable) {
            }
        }
    }

    private companion object {
        private const val PARTICLE_RATE_MUL = 0.60f
        private const val PARTICLE_RADIUS_MUL = 0.60f
        private const val PARTICLE_SPEED_MUL = 0.70f
        private const val CENTER_DOT_RATE_MUL = 0.60f

        private const val PARTICLE_PER_SEC = 160f
        private const val PARTICLE_MAX_PER_FRAME = 12

        private const val VANILLA_RADIUS_MAX = 420f
        private const val VANILLA_RADIUS_MIN = 140f

        private const val VANILLA_SPEED_MIN = 220f
        private const val VANILLA_SPEED_MAX = 720f

        private const val VANILLA_SIZE_MIN = 12f
        private const val VANILLA_SIZE_MAX = 30f

        private const val VANILLA_DUR_MIN = 0.16f
        private const val VANILLA_DUR_MAX = 0.34f

        private const val CENTER_DOT_PER_SEC = 40f
        private const val CENTER_DOT_MAX_PER_FRAME = 3
        private const val CENTER_DOT_SIZE_MIN = 14f
        private const val CENTER_DOT_SIZE_MAX = 46f
    }
}
