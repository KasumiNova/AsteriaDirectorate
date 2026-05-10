package cn.kasuminova.astd.combat.effect.arc.signature.stasisfield

import cn.kasuminova.astd.renderer.effect.projectile.beam.BeamLineUtil
import cn.kasuminova.astd.renderer.effect.projectile.beam.OglEllipseRingRenderer
import cn.kasuminova.astd.renderer.effect.projectile.beam.AttachedBeamEllipseRingRenderer
import cn.kasuminova.astd.renderer.effect.projectile.beam.AttachedBeamSpriteRingRenderer
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.combat.effect.generic.projectile.TaperedBeamTrailsVfx

import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.util.Misc
import cn.kasuminova.astd.combat.effect.generic.gravitycollapse.GravityCollapseOnHitConfig
import cn.kasuminova.astd.combat.effect.generic.gravitycollapse.GravityCollapseOnHitHandler
import cn.kasuminova.astd.combat.effect.generic.gravitycollapse.GravityCollapseWeaponSpecs
import org.boxutil.manager.CombatRenderingManager
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `astd_stasis_collapse_emitter`：停滞场终结坍缩炮（内置 beam 武器）。
 *
 * 需求要点：
 * - 使用内置武器发射（原版 beam 负责伤害/加成计算）。
 * - 武器侧：1.5s 充能 + 5s 持续（主要由 weapon_data.csv 的 chargeup/chargedown 负责）。
 * - 更红少白、束宽整体 -40%。
 * - 椭圆装饰“围绕光束前进”（二维里用横向摆动 + 环自身旋转来模拟旋进感）。
 * - 充能完成时给一个爆发光锥；命中点给坍缩扭曲（DistortionEntity 优先）。
 */
class StasisFieldCollapseEmitterEveryFrameEffect : EveryFrameWeaponEffectPlugin {

    companion object {
        private const val FIRE_TIME = 5.0f
        private const val MAX_TOTAL_TIME = 9.0f

        // 结束后的视觉淡出：主束缓慢变细；光圈主要淡化。
        private const val END_FADE_TIME = 0.65f

        // 装饰环：按【距离】生成，避免“定时器闪烁”。
        // 这里的 travelSpeed 只影响“环沿束前进的视觉速度”。
        private const val RING_SPACING = 150f
        private const val RING_TRAVEL_SPEED = 2600f
        private const val RING_DURATION = 0.42f

        private val CORE_COLOR = Color(255, 55, 55, 235)
        private val GLOW_COLOR = Color(255, 25, 25, 190)
        private val HOT_COLOR = Color(255, 80, 80, 220)

        // “斩杀”用的小额伤害：用于目标已濒死（船体 <= 1）时，避免直接 setHitpoints 把它扣到 0。
        // 让引擎走一遍 applyDamage，有更正常的结算/死亡链路与 floaty 表现。
        private const val EXECUTE_DAMAGE = 100f

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private fun safeAngleDeg(a: Float): Float {
            var x = a % 360f
            if (x < 0f) x += 360f
            return x
        }
    }

    private var active: StasisFieldCollapseBeam.Request? = null
    private var requestStart = 0f
    private var lastConsumedCreatedAt = Float.NEGATIVE_INFINITY

    private var beamStartedAt: Float? = null
    private var muzzleBurstDone = false
    private var hitBurstDone = false

    private var fadeStartedAt: Float? = null
    private var lastLine: BeamLineUtil.BeamLine? = null

    private val nozzleSpec = GravityCollapseWeaponSpecs.forWeaponId(StasisFieldCollapseBeam.WEAPON_ID)

    /** 喷口自绘束（隐藏原版 beam 渲染后用于视觉替代）。 */
    private val nozzleBeamVfx = GravityCollapseBeamVfx(
        scale = nozzleSpec?.beamScale ?: 1f,
    )

    // 命中机制：抽离为可复用模块（AOE tick + 引力撕裂）。
    private val onHit = GravityCollapseOnHitHandler(
        config = GravityCollapseOnHitConfig(
            tickInterval = 0.5f,
            aoeRadiusBase = nozzleSpec?.aoeRadiusBase ?: 190f,
            requireDamageTarget = nozzleSpec?.aoeRequireDamageTarget ?: true,
            affectAlliesAndNeutral = nozzleSpec?.aoeAffectAlliesAndNeutral ?: false,
            affectNonShips = nozzleSpec?.aoeAffectNonShips ?: false,
            affectHulks = nozzleSpec?.aoeAffectHulks ?: false,
        )
    )

    // 动态 beam DPS：记录原始值，便于回滚。
    private var originalBeamDps: Float? = null

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        if (amount <= 0f) return

        val ship = weapon.ship ?: run {
            reset(engine, weapon)
            return
        }
        if (ship.isHulk) {
            reset(engine, weapon)
            return
        }

        // 永远把这个“系统武器”当成隐藏逻辑部件：尽量别被打瘫。
        keepWeaponHealthy(weapon)

        // 读取并消费系统请求
        val req = try {
            ship.customData[StasisFieldCollapseBeam.REQUEST_KEY] as? StasisFieldCollapseBeam.Request
        } catch (_: Throwable) {
            null
        }
        if (req != null && req.createdAt != lastConsumedCreatedAt) {
            try {
                ship.removeCustomData(StasisFieldCollapseBeam.REQUEST_KEY)
            } catch (_: Throwable) {
            }

            active = req
            lastConsumedCreatedAt = req.createdAt
            requestStart = try {
                engine.getTotalElapsedTime(false)
            } catch (_: Throwable) {
                0f
            }
            beamStartedAt = null
            muzzleBurstDone = false
            hitBurstDone = false
            nozzleBeamVfx.reset(engine)
            fadeStartedAt = null
            lastLine = null
            onHit.reset()

            // 新一轮开始前，先回滚到基础 DPS（随后再按 intensity 动态调整）
            restoreBeamDpsIfNeeded(weapon)
        }

        val a = active
        if (a == null) {
            reset(engine, weapon)
            return
        }

        val now = try {
            engine.getTotalElapsedTime(false)
        } catch (_: Throwable) {
            0f
        }
        val totalElapsed = now - requestStart
        if (totalElapsed > MAX_TOTAL_TIME) {
            active = null
            reset(engine, weapon)
            return
        }

        val intensity = a.intensity.coerceIn(0f, 1f)

        // 关键：VFX 朝向必须跟随“武器/beam 的实际朝向”，不要强行 setFacing。
        // 这与 StellarJetEmitterEveryFrameEffect 的处理一致。

        // 允许武器工作并强制按住扳机
        weapon.setForceDisabled(false)
        weapon.setForceNoFireOneFrame(false)
        weapon.setForceFireOneFrame(true)

        // 伤害：用内置 beam 武器的 damage 作为基底，然后按“捕获能量强度”缩放
        applyDynamicBeamDps(weapon, intensity)

        val chargeLevel = try {
            weapon.chargeLevel
        } catch (_: Throwable) {
            0f
        }
        if (beamStartedAt == null) {
            // 充能期：补充“吸入/聚能”粒子（避免只靠原版 chargeup 显得偏素）
            if (chargeLevel > 0f && chargeLevel < 1f) {
                val facingNow = try {
                    weapon.currAngle
                } catch (_: Throwable) {
                    safeAngleDeg(a.aimFacing)
                }
                emitCharge(engine, weapon, facingNow, chargeLevel, intensity)
            }
        }

        val beam = weapon.beams?.firstOrNull()
        if (beamStartedAt == null && beam != null) {
            beamStartedAt = now
            nozzleBeamVfx.reset(engine)

            if (!muzzleBurstDone) {
                muzzleBurstDone = true
                val line = BeamLineUtil.fromBeamOrWeapon(weapon, beam)
                val facingNow = line?.facing ?: (try {
                    weapon.currAngle
                } catch (_: Throwable) {
                    safeAngleDeg(a.aimFacing)
                })
                spawnMuzzleConeBurst(engine, weapon, facingNow, intensity)
            }
        }

        val fireStart = beamStartedAt
        if (fireStart != null) {
            val fireElapsed = (now - fireStart).coerceAtLeast(0f)

            if (fireElapsed <= FIRE_TIME) {
                fadeStartedAt = null
                // 命中点：坍缩扭曲 + 爆发（只做一次）
                if (!hitBurstDone && beam != null) {
                    hitBurstDone = true
                    spawnHitBurst(engine, beam, intensity)
                    spawnHitCollapseDistortion(engine, Vector2f(beam.to), intensity)
                }

                // 视觉主束 + 沿束前进的环：参考图的“圈圈沿束前进”，不做随机角度偏移。
                val line = BeamLineUtil.fromBeamOrWeapon(weapon, beam)
                if (line != null) {
                    lastLine = line
                    // 命中点持续坍缩 + 周期性额外伤害（只在确实命中实体时触发）
                    handleOnHitSustainedEffects(engine, amount, weapon, beam, intensity)
                    nozzleBeamVfx.advance(engine, amount, line.from, line.to, intensity)
                }
            } else {
                // 到点收束：停止强制开火，并禁用武器（原版 chargedown 会负责慢慢消散）
                try {
                    weapon.stopFiring()
                } catch (_: Throwable) {
                }
                weapon.setForceNoFireOneFrame(true)
                weapon.setForceDisabled(true)

                val line = lastLine
                val fs = fadeStartedAt ?: run {
                    fadeStartedAt = now
                    now
                }

                val t = ((now - fs) / END_FADE_TIME).coerceIn(0f, 1f)
                val fade = (1f - t).coerceIn(0f, 1f)
                if (line != null && fade > 0f) {
                    nozzleBeamVfx.advance(engine, amount, line.from, line.to, intensity, fade)
                }

                if (fade <= 0f) {
                    // 结束：彻底清理
                    active = null
                    restoreBeamDpsIfNeeded(weapon)
                    nozzleBeamVfx.reset(engine)
                    fadeStartedAt = null
                    lastLine = null
                }
            }
        }
    }

    private fun handleOnHitSustainedEffects(
        engine: CombatEngineAPI,
        amount: Float,
        weapon: WeaponAPI,
        beam: BeamAPI?,
        intensity: Float,
    ) {
        val panelDps = (originalBeamDps ?: run {
            try {
                weapon.damage.damage
            } catch (_: Throwable) {
                0f
            }
        }).coerceAtLeast(0f)

        onHit.advance(
            engine = engine,
            amount = amount,
            weapon = weapon,
            beam = beam,
            intensity = intensity,
            panelDps = panelDps,
        )
    }

    private fun applyDynamicBeamDps(weapon: WeaponAPI, intensity: Float) {
        if (originalBeamDps == null) {
            originalBeamDps = try {
                weapon.damage.damage
            } catch (_: Throwable) {
                null
            }
        }
        val base = originalBeamDps ?: return

        // 让低强度也有存在感，但不至于爆表：0.35~1.35 倍
        val mul = lerp(0.35f, 1.35f, intensity.coerceIn(0f, 1f))
        val dps = (base * mul).coerceAtLeast(1f)

        try {
            weapon.damage.setDamage(dps)
        } catch (_: Throwable) {
        }
        // 已建立的 beam：尽量同步（避免“开火后第一帧还是旧伤害”）
        try {
            weapon.beams?.forEach { b -> b.damage?.setDamage(dps) }
        } catch (_: Throwable) {
        }
    }

    private fun restoreBeamDpsIfNeeded(weapon: WeaponAPI) {
        val base = originalBeamDps ?: return
        try {
            weapon.damage.setDamage(base)
        } catch (_: Throwable) {
        }
    }

    private fun reset(engine: CombatEngineAPI, weapon: WeaponAPI) {
        // 默认：不允许常规开火
        weapon.setForceDisabled(true)
        weapon.setForceNoFireOneFrame(true)
        // 不再 override 转向；由武器自身/舰船控制决定实际朝向。
        restoreBeamDpsIfNeeded(weapon)

        // 清理自绘束相关的“永久环”渲染
        try {
            nozzleBeamVfx.reset(engine)
        } catch (_: Throwable) {
        }
        fadeStartedAt = null
        lastLine = null
    }

    private fun keepWeaponHealthy(weapon: WeaponAPI) {
        try {
            if (weapon.isDisabled || weapon.isPermanentlyDisabled) {
                weapon.repair()
            }
        } catch (_: Throwable) {
        }

        try {
            val max = weapon.maxHealth
            if (max > 0f && weapon.currHealth < max) {
                weapon.currHealth = max
            }
        } catch (_: Throwable) {
        }
    }

    private fun emitCharge(engine: CombatEngineAPI, weapon: WeaponAPI, facing: Float, chargeLevel: Float, intensity: Float) {
        val t = chargeLevel.coerceIn(0f, 1f)

        val center = try {
            weapon.getFirePoint(0)
        } catch (_: Throwable) {
            null
        } ?: return
        val radius = lerp(220f, 80f, t)
        val count = lerp(6f, 14f, t).toInt().coerceIn(3, 16)

        for (i in 0 until count) {
            val ang = MathUtils.getRandomNumberInRange(0f, 360f)
            val spawn = MathUtils.getPointOnCircumference(center, radius * MathUtils.getRandomNumberInRange(0.55f, 1.05f), ang)
            val dir = Vector2f(center.x - spawn.x, center.y - spawn.y)
            val dist = sqrt((dir.x * dir.x + dir.y * dir.y).coerceAtLeast(0.001f))
            dir.x /= dist
            dir.y /= dist

            val speed = lerp(420f, 1200f, t) * (0.85f + 0.45f * intensity)
            val vel = Vector2f(dir.x * speed, dir.y * speed)

            val size = lerp(12f, 18f, t) * MathUtils.getRandomNumberInRange(0.75f, 1.30f)
            val dur = MathUtils.getRandomNumberInRange(0.10f, 0.18f)
            val bright = MathUtils.getRandomNumberInRange(0.75f, 1.25f) * (0.60f + 0.55f * t)
            val c = if (Math.random() < 0.45) CORE_COLOR else GLOW_COLOR
            try {
                engine.addSmoothParticle(spawn, vel, size, bright, dur, c)
            } catch (_: Throwable) {
            }
        }

        // 炮口“热斑”
        try {
            val v = MathUtils.getPointOnCircumference(Vector2f(0f, 0f), MathUtils.getRandomNumberInRange(10f, 40f), facing)
            engine.addHitParticle(center, v, lerp(20f, 44f, t), 1.2f, 0.06f, HOT_COLOR)
        } catch (_: Throwable) {
        }
    }

    private fun spawnMuzzleConeBurst(engine: CombatEngineAPI, weapon: WeaponAPI, facing: Float, intensity: Float) {
        val center = try {
            weapon.getFirePoint(0)
        } catch (_: Throwable) {
            null
        } ?: return

        // “光锥”：密集粒子沿束方向喷涌，像爆发式点火
        val count = lerp(40f, 95f, intensity).toInt().coerceIn(30, 110)
        val spread = lerp(18f, 10f, intensity)
        val baseSpeed = lerp(520f, 1550f, intensity)

        for (i in 0 until count) {
            val ang = facing + (Math.random().toFloat() - 0.5f) * spread
            val rad = Math.toRadians(ang.toDouble())
            val dir = Vector2f(cos(rad).toFloat(), sin(rad).toFloat())

            val speed = baseSpeed * MathUtils.getRandomNumberInRange(0.55f, 1.10f)
            val vel = Vector2f(dir.x * speed, dir.y * speed)

            val size = lerp(30f, 70f, intensity) * MathUtils.getRandomNumberInRange(0.55f, 1.25f)
            val dur = MathUtils.getRandomNumberInRange(0.15f, 0.35f)
            val bright = MathUtils.getRandomNumberInRange(0.85f, 1.45f)
            val c = if (Math.random() < 0.25) HOT_COLOR else if (Math.random() < 0.55) CORE_COLOR else GLOW_COLOR

            try {
                engine.addSmoothParticle(center, vel, size, bright, dur, c)
            } catch (_: Throwable) {
            }
        }

        // 炮口爆发
        try {
            engine.spawnExplosion(center, Vector2f(0f, 0f), CORE_COLOR, lerp(160f, 280f, intensity), 0.25f)
        } catch (_: Throwable) {
        }
        try {
            engine.addSmoothParticle(center, Vector2f(0f, 0f), lerp(260f, 480f, intensity), 1.25f, 0.30f, GLOW_COLOR)
        } catch (_: Throwable) {
        }
    }

    private fun spawnHitBurst(engine: CombatEngineAPI, beam: BeamAPI, intensity: Float) {
        val point = Vector2f(beam.to)
        try {
            engine.spawnExplosion(point, Vector2f(0f, 0f), GLOW_COLOR, lerp(140f, 240f, intensity), 0.22f)
        } catch (_: Throwable) {
        }
        try {
            engine.addSmoothParticle(point, Vector2f(0f, 0f), lerp(220f, 340f, intensity), 1.20f, 0.28f, HOT_COLOR)
        } catch (_: Throwable) {
        }

        // 命中环
        try {
            val facing = try {
                VectorUtils.getAngle(beam.from, beam.to)
            } catch (_: Throwable) {
                0f
            }
            OglEllipseRingRenderer.spawn(
                engine,
                OglEllipseRingRenderer.RingSpec(
                    center = point,
                    facing = facing,
                    aSideHalf = lerp(55f, 110f, intensity),
                    bAlongHalf = lerp(40f, 85f, intensity),
                    duration = 0.42f,
                    color = Color(255, 60, 60, (85f + 95f * intensity).toInt().coerceIn(0, 255)),
                    lineWidthPx = 1.65f * 0.60f,
                    segments = 96,
                    expandSpeed = 120f,
                    tangentialSpeed = 2.35f,
                )
            )
        } catch (_: Throwable) {
        }
    }

    private fun spawnHitCollapseDistortion(engine: CombatEngineAPI, point: Vector2f, intensity: Float) {
        // 优先：BoxUtil DistortionEntity
        val ok = try {
            val e = DistortionEntity()
            e.setGlobalTimer(0.05f, 0.10f, 0.55f)

            e.setInnerFull(0.30f, 0.30f)
            e.setInnerHardness(0.85f)
            e.setRingHardness(0.62f)

            e.setSizeIn(80f, 80f)
            e.setSizeFull(220f, 220f)
            e.setSizeOut(520f, 520f)

            e.setPowerIn(0.00f)
            e.setPowerFull(lerp(0.85f, 1.25f, intensity))
            e.setPowerOut(0f)

            e.setLocation(point)
            CombatRenderingManager.addEntity(e)
            true
        } catch (_: Throwable) {
            false
        }

        if (ok) return

        // 回退：nebula 近似
        try {
            val c = Color(255, 45, 45, 55)
            engine.addNebulaParticle(point, Vector2f(0f, 0f), 240f, 2.8f, 0.06f, 0.22f, 0.65f, c, true)
            engine.addNebulaParticle(point, Vector2f(0f, 0f), 360f, 3.1f, 0.05f, 0.20f, 0.75f, c, true)
        } catch (_: Throwable) {
        }
    }
}
