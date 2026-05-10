package cn.kasuminova.astd.combat.effect.arc.omega

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.boxutil.util.CurveUtil
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.entities.SimpleEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * DRV-Ω：隐形弹体的“落点预计算 + 近似传送”。
 *
 * 目的：
 * - 伤害/音效/命中反馈尽量走原版 projectile collision 链路（避免手动补音效的不现实方案）；
 * - 视觉弹道效果放到 onHit 一次性渲染（见 [DrvOmegaOnHitEffect] / [DrvOmegaImpactVfx]）。
 */
internal object DrvOmegaSlugInstantOnSpawn {

    const val KEY_FROM: String = "astd_drv_omega_from"
    const val KEY_TO: String = "astd_drv_omega_to"
    const val KEY_FACING: String = "astd_drv_omega_facing"
    const val KEY_FINISHER: String = "astd_drv_omega_finisher"
    const val KEY_BASE_DAMAGE: String = "astd_drv_omega_base_damage"

    private const val DIRECT_BEAM_MAX_CHECK_RANGE_OFFSET = 200f
    // 命中可靠性：backoff 太小 + 速度太大容易出现 tunneling/擦过。
    // 这里把 backoff 调大，并且不再强制把速度抬到离谱值（只利用“传送到命中点前方”的机制）。
    private const val TELEPORT_BACKOFF = 55f

    private val FINISHER_ARC_FRINGE = Color(255, 70, 70, 185)
    private val FINISHER_ARC_CORE = Color(255, 210, 210, 205)

    // 调试：在预判落点渲染浮动文字（仅 devMode）。
    private const val DEBUG_FLOATING_TEXT = true

    fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, weaponFromDispatcher: WeaponAPI?) {
        val weapon = weaponFromDispatcher ?: projectile.weapon
        val ship = weapon?.ship

        // 终结技判定：开火后 ammo 已扣除时，最后一发通常表现为 weapon.ammo == 0。
        // 若扣除时机与预期不一致（极少见），此判定最多会导致“终结技颜色/EMP”与实际错位一发，
        // 但不会造成崩溃。
        val ammoRemaining = try { weapon?.ammo ?: -1 } catch (_: Throwable) { -1 }
        val isFinisher = ammoRemaining == 0

        // 保存 base damage（供 onHit 额外 EMP 电弧使用）
        val baseDamage = try { projectile.damageAmount } catch (_: Throwable) { 0f }
        try {
            projectile.customData[KEY_FINISHER] = isFinisher
            projectile.customData[KEY_BASE_DAMAGE] = baseDamage
        } catch (_: Throwable) {
        }

        // 终结技：最后一发 300% 伤害
        if (isFinisher && baseDamage > 0.01f) {
            try {
                projectile.damageAmount = baseDamage * 3f
            } catch (_: Throwable) {
            }
        }

        val start = Vector2f(projectile.location)

        val facing = try {
            projectile.facing
        } catch (_: Throwable) {
            try {
                weapon?.currAngle ?: 0f
            } catch (_: Throwable) {
                0f
            }
        }

        val dir = Vector2f(
            cos(Math.toRadians(facing.toDouble())).toFloat(),
            sin(Math.toRadians(facing.toDouble())).toFloat(),
        )

        val range = try {
            weapon?.range ?: 1400f
        } catch (_: Throwable) {
            1400f
        }
        val maxEnd = Vector2f(start.x + dir.x * range, start.y + dir.y * range)

        var hitTarget: CombatEntityAPI? = null
        var hitPoint: Vector2f? = null

        val dealtController = object : CurveUtil.DealtController {
            override fun applyEffect(target: CombatEntityAPI, point: Vector2f, beamT: Float, isShieldHit: Boolean) {
                // 穿透低血战机/导弹：立即击杀并继续扫描
                val isFighter = (target as? ShipAPI)?.isFighter == true
                val isMissile = target is MissileAPI
                if (isFighter || isMissile) {
                    val hp = try { target.hitpoints } catch (_: Throwable) { Float.MAX_VALUE }
                    if (hp < baseDamage) {
                        try {
                            engine.applyDamage(
                                target, point, hp + 100f, DamageType.ENERGY, 0f,
                                false, false, ship,
                            )
                        } catch (_: Throwable) {}
                        try {
                            engine.addHitParticle(point, Vector2f(), 50f, 1.2f, 0.10f, Color(120, 220, 255, 200))
                        } catch (_: Throwable) {}
                        return // 不设 hitTarget，继续扫描
                    }
                }
                if (hitTarget != null) return
                hitTarget = target
                hitPoint = Vector2f(point)
            }

            override fun isIgnore(target: CombatEntityAPI): Boolean {
                if (ship != null && target === ship) return true
                if (!engine.isEntityInPlay(target)) return true
                val cc = try {
                    target.collisionClass
                } catch (_: Throwable) {
                    null
                }
                if (cc == CollisionClass.NONE) return true
                return false
            }

            override fun isPierceShield(target: ShipAPI): Boolean = false

            override fun isPierce(target: CombatEntityAPI, point: Vector2f, beamT: Float, isShieldHit: Boolean): Boolean {
                val isFighter = (target as? ShipAPI)?.isFighter == true
                val isMissile = target is MissileAPI
                if (isFighter || isMissile) {
                    val hp = try { target.hitpoints } catch (_: Throwable) { Float.MAX_VALUE }
                    return hp < baseDamage
                }
                return false
            }
        }

        try {
            CurveUtil.spawnDirectBeam(engine, start, maxEnd, DIRECT_BEAM_MAX_CHECK_RANGE_OFFSET, dealtController)
        } catch (_: Throwable) {
        }

        val predictedHit = (hitTarget != null && hitPoint != null)
        val end = if (predictedHit) hitPoint!! else maxEnd

        if (DEBUG_FLOATING_TEXT) {
            val dev = try {
                Global.getSettings().isDevMode
            } catch (_: Throwable) {
                false
            }
            if (dev) {
                try {
                    val s = if (hitTarget != null) "[DRV-Ω] hit" else "[DRV-Ω] max"
                    // 注意：不要绑定到 projectile（否则文本会随“传送后的弹体”产生偏移/相对坐标错觉）。
                    engine.addFloatingText(end, s, 16f, Color(120, 220, 255, 255), null, 0f, 0f)
                    // 额外：给一个小闪点，确认 end 坐标确实落在预期位置。
                    engine.addHitParticle(end, Vector2f(), 26f, 1.0f, 0.25f, Color(120, 220, 255, 170))
                } catch (_: Throwable) {
                }
            }
        }

        // 给 onHit 用：
        // - KEY_FROM 始终写入（用于稳定炮口起点）
        // - KEY_TO 仅在“预判命中”时写入；否则让 onHit 回退到真实碰撞点，避免把 VFX 锁死到 max range
        try {
            projectile.customData[KEY_FROM] = Vector2f(start)
            if (predictedHit) {
                projectile.customData[KEY_TO] = Vector2f(end)
            } else {
                projectile.customData.remove(KEY_TO)
            }
            projectile.customData[KEY_FACING] = facing
        } catch (_: Throwable) {
        }

        // 近似传送：仅在“预判命中”时执行。
        // 若未预判命中（max），不传送，避免将弹体前移导致射程被动增加。
        try {
            if (predictedHit) {
                projectile.location.x = end.x - dir.x * TELEPORT_BACKOFF
                projectile.location.y = end.y - dir.y * TELEPORT_BACKOFF

                // 仅校正方向，保留速度模长
                projectile.velocity?.let { v ->
                    val spd2 = v.x * v.x + v.y * v.y
                    if (spd2 > 0.01f) {
                        val spd = kotlin.math.sqrt(spd2)
                        v.x = dir.x * spd
                        v.y = dir.y * spd
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // 终结技即时视觉反馈：在预判命中点立刻生成视觉电弧（无伤害）
        if (isFinisher && predictedHit) {
            spawnFinisherVisualArcs(engine, hitPoint!!, hitTarget!!)
        }
    }

    private fun spawnFinisherVisualArcs(engine: CombatEngineAPI, point: Vector2f, target: CombatEntityAPI) {
        val arcs = 3 + (Math.random() * 3).toInt()
        for (i in 0 until arcs) {
            val from = MathUtils.getRandomPointInCircle(point, 18f)
            val to = MathUtils.getRandomPointInCircle(point, 140f)
            try {
                engine.spawnEmpArcVisual(
                    from,
                    target,
                    to,
                    SimpleEntity(to),
                    MathUtils.getRandomNumberInRange(14f, 28f),
                    FINISHER_ARC_FRINGE,
                    FINISHER_ARC_CORE,
                )
            } catch (_: Throwable) {}
        }
    }
}
