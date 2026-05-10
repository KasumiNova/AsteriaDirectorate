package cn.kasuminova.astd.combat.effect.arc.signature.stellarjet

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileVfxPresets
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.util.IntervalUtil
import org.boxutil.util.CurveUtil
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * astd_stellar_jet_emitter：恒星喷射“喷射口”签名武器的每帧逻辑。
 *
 * 约定：
 * - 系统 `astd_stellar_jet` 激活时，由系统脚本负责护盾整形；
 * - 本 effect 负责：系统联动（强制开火/禁用）、幅能作为“燃料”的消耗 + 后坐力、以及额外 VFX 叠加。
 * - 伤害/命中使用原版 beam 机制（参考速子长矛等 beam 武器），这里不再做脚本伤害计算。
 */
class StellarJetEmitterEveryFrameEffect : EveryFrameWeaponEffectPlugin {

    private val vfx = StellarJetBeamVfx(
        coreColor = CORE_COLOR,
        glowColor = GLOW_COLOR,
    )

    private val chargeUpVfx = StellarJetChargeUpVfx(
        coreColor = CORE_COLOR,
        glowColor = GLOW_COLOR,
    )

    private val debugInterval = IntervalUtil(0.5f, 0.5f)

    // 视觉用束长平滑：避免束长突变时“瞬间闪现在目标上”
    private var smoothLenInited = false
    private var smoothLen = 0f

    // 系统期间能量弹发射：按时间累积（避免帧率波动导致“时快时慢”）
    private var boltAcc = 0f

    // 系统单次激活的“辐能预算”（最大消耗=100% maxFlux）
    private var activationBudgetLeft = 0f
    private var wasActiveLastFrame = false

    // 动态 beam DPS：记录原始值，便于系统关闭时回滚。
    private var originalBeamDps: Float? = null

    // 充能完成检测：用于在 IN->ACTIVE 时触发一次性爆发特效
    private var wasChargingLastFrame = false

    companion object {
        private const val SYSTEM_ID = "astd_stellar_jet"

        // 机制参数（MVP，可后续调参）
        private const val MAX_RANGE = 4200f

        // 束长变化的平滑速度（su/s）：增长更慢、缩短更快
        private const val LEN_GROW_PER_SEC = 9500f
        private const val LEN_SHRINK_PER_SEC = 35000f

        // 需求：辐能消耗为百分比
        // - 每秒消耗=最大辐能的 10%
        // - 每次系统激活最大消耗=100% 最大辐能（耗尽后强制关闭）
        private const val FLUX_DRAIN_MAX_FLUX_FRACTION_PER_SEC = 0.20f

        // 需求：单次系统激活最大消耗=150% 最大辐能（预算上限，实际仍受 currFlux 与外部流入影响）
        private const val ACTIVATION_BUDGET_MAX_FLUX_MULT = 1.5f

        // 需求：beam DPS = maxFlux(非基础最大) 的 15%
        private const val BEAM_DPS_MAX_FLUX_FRACTION = 0.15f

        // 系统期间额外发射的“能量弹”（仅强化表现；不影响原版 beam 结算）
        private const val BOLT_WEAPON_SPEC_ID = "astd_stellar_jet_bolt_emitter"
        private const val BOLT_RATE_PER_SEC = 20f
        private const val BOLT_MAX_PER_FRAME = 12
        private const val BOLT_SPREAD_DEG = 10f
        private const val BOLT_SPEED_MIN = 2000f
        private const val BOLT_SPEED_MAX = 4000f
        private const val BOLT_DAMAGE_MIN_MUL = 0.10f
        private const val BOLT_DAMAGE_MAX_MUL = 0.20f

        // 需求调整：弹体视觉大小过大，按 DRV-11 的量级收敛（joinWidth≈10）
        private const val BOLT_VFX_SIZE_MIN = 8.5f
        private const val BOLT_VFX_SIZE_MAX = 12.5f

        // 兜底 applyDamage 的倍率：用于“原版 beam 未结算但我们确认命中”的边界帧。
        // （主伤害仍以原版 beam 结算为主；这里仅保证不出现完全不掉血。）
        private const val BEAM_DAMAGE_MUL = 1.0f

        // 仅用于“算束长（命中到哪里）”：不做任何伤害。
        private const val DIRECT_BEAM_MAX_CHECK_RANGE_OFFSET = 320f

        // 后坐力：以“幅能消耗量”为基准的冲量系数（越大越倒飞）
        private const val RECOIL_IMPULSE_PER_FLUX = 55f

        // 视觉参数
        private val CORE_COLOR = Color(255, 250, 235, 220)
        private val GLOW_COLOR = Color(120, 200, 255, 160)

        // 额外叠加束体：刻意比“原版 beam”更细一些，避免把边缘卷曲丝带吃掉。
        // 调参：主束宽度 +40%
        private const val BEAM_MIN_CORE_W = 12f * 1.4f
        private const val BEAM_MAX_CORE_W = 24f * 1.4f
        private const val BEAM_MIN_GLOW_W = 20f * 1.4f
        private const val BEAM_MAX_GLOW_W = 56f * 1.4f

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private fun rand01(): Float = Math.random().toFloat()

        private fun applyRecoil(ship: ShipAPI, dirUnit: Vector2f, fluxSpent: Float) {
            if (fluxSpent <= 0f) return

            val mass = try {
                ship.mass
            } catch (_: Throwable) {
                0f
            }
            if (mass <= 0.1f) return

            val impulse = fluxSpent * RECOIL_IMPULSE_PER_FLUX
            // 后坐力方向：与光束方向相反
            ship.velocity.x -= dirUnit.x * (impulse / mass)
            ship.velocity.y -= dirUnit.y * (impulse / mass)
        }
    }

    private fun spawnJetBolts(
        engine: CombatEngineAPI,
        amount: Float,
        ship: ShipAPI,
        start: Vector2f,
        facing: Float,
        baseBeamDps: Float,
    ) {
        // 累积发射
        boltAcc += amount * BOLT_RATE_PER_SEC
        val spawnCount = boltAcc.toInt().coerceAtMost(BOLT_MAX_PER_FRAME)
        if (spawnCount <= 0) return
        boltAcc -= spawnCount

        repeat(spawnCount) {
            val ang = facing + (rand01() - 0.5f) * BOLT_SPREAD_DEG
            val rad = Math.toRadians(ang.toDouble())
            val dir = Vector2f(cos(rad).toFloat(), sin(rad).toFloat())

            // 避免在炮口/舰体内部生成导致的奇怪碰撞/立即失效
            val spawn = Vector2f(
                start.x + dir.x * 28f,
                start.y + dir.y * 28f,
            )

            val speed = lerp(BOLT_SPEED_MIN, BOLT_SPEED_MAX, rand01())
            val desiredVel = Vector2f(
                ship.velocity.x + dir.x * speed,
                ship.velocity.y + dir.y * speed,
            )

            val proj = try {
                engine.spawnProjectile(
                    ship,
                    // 重要：不要把 beam WeaponAPI 作为发射源传进来（有概率导致弹体结算异常）。
                    // 这里允许 weapon=null，随后可手动调整 damage/velocity。
                    null,
                    BOLT_WEAPON_SPEC_ID,
                    spawn,
                    ang,
                    // 重要：这里的参数是“继承速度”（通常传 ship.velocity），不是“弹体最终速度”。
                    // 之前把 desiredVel 传进来会导致引擎再次叠加 proj speed，速度过高时可能穿模不结算伤害。
                    Vector2f(ship.velocity),
                )
            } catch (_: Throwable) {
                null
            }

            val dp = proj as? com.fs.starfarer.api.combat.DamagingProjectileAPI ?: return@repeat

            // 伤害：beam DPS 的 10~20%（每发）。
            val base = if (baseBeamDps > 0f) baseBeamDps else 900f
            val dmgMul = lerp(BOLT_DAMAGE_MIN_MUL, BOLT_DAMAGE_MAX_MUL, rand01())
            // 仍然给一个极小下限，避免边界情况下出现 0。
            val dmg = (base * dmgMul).coerceAtLeast(1f)
            try {
                dp.setDamageAmount(dmg)
            } catch (_: Throwable) {
            }

            // 速度：强制到 2000~4000（叠加继承速度后可能略超出，但总体观感更自然）
            try {
                val v = dp.velocity
                v.x = desiredVel.x
                v.y = desiredVel.y
            } catch (_: Throwable) {
            }

            // VFX：用本模组拖尾，大小随机 100~200（纯视觉）。
            val vfxSize = lerp(BOLT_VFX_SIZE_MIN, BOLT_VFX_SIZE_MAX, rand01())
            try {
                dp.setCustomData(ProjectileVfxPresets.StellarJetBolt.VFX_SIZE_KEY, vfxSize)
            } catch (_: Throwable) {
            }
            try {
                ProjectileVfxPresets.StellarJetBolt.onSpawn(engine, dp)
            } catch (_: Throwable) {
            }

            // 兜底：若引擎侧出现“碰撞移除但不结算伤害”的边界情况，离开 play 时补一次 applyDamage。
            try {
                StellarJetBoltDamageFixer.track(engine, dp)
            } catch (_: Throwable) {
            }

            // 射程：强制限制为与 beam 相同（避免速度随机后射程漂移）。
            try {
                StellarJetBoltRangeLimiter.track(engine, dp, MAX_RANGE)
            } catch (_: Throwable) {
            }
        }
    }

    private fun applyDynamicBeamDps(weapon: WeaponAPI, beam: BeamAPI?, maxFlux: Float): Float {
        val dps = (maxFlux * BEAM_DPS_MAX_FLUX_FRACTION).coerceAtLeast(0f)

        if (originalBeamDps == null) {
            originalBeamDps = try {
                weapon.damage.damage
            } catch (_: Throwable) {
                null
            }
        }

        // 同时改 weapon.damage 与现存 beam.damage，确保“已建立的 beam”与后续帧保持一致。
        try {
            weapon.damage.setDamage(dps)
        } catch (_: Throwable) {
        }
        try {
            beam?.damage?.setDamage(dps)
        } catch (_: Throwable) {
        }

        return dps
    }

    private fun restoreBeamDpsIfNeeded(weapon: WeaponAPI) {
        val base = originalBeamDps ?: return
        try {
            weapon.damage.setDamage(base)
        } catch (_: Throwable) {
        }
    }

    private fun keepWeaponImmuneDuringSystem(weapon: WeaponAPI) {
        // “免疫 EMP 与武器伤害”的近似实现：每帧维持满血，若被停用则立刻 repair。
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

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        if (amount <= 0f) return

        val ship = weapon.ship
        if (ship == null) {
            vfx.fadeOut()
            chargeUpVfx.reset()
            return
        }
        if (ship.isHulk) {
            vfx.fadeOut()
            chargeUpVfx.reset()
            return
        }

        val system = ship.system
        if (system == null || system.id != SYSTEM_ID) {
            vfx.fadeOut()
            chargeUpVfx.reset()
            return
        }

        val level = system.effectLevel.coerceIn(0f, 1f)
        val state = try {
            system.state
        } catch (_: Throwable) {
            null
        }

        // IN->ACTIVE：触发一次爆发（带近似扭曲环）
        if (wasChargingLastFrame && state == ShipSystemAPI.SystemState.ACTIVE) {
            try {
                chargeUpVfx.onChargeComplete(engine, weapon)
            } catch (_: Throwable) {
            }
        }

        // 充能（IN）阶段：
        // - 不启用武器与自动射弹（不强制开火、不建立 beam、不发射 bolts）
        // - 不启用辐能消耗机制（避免“充能期也在烧燃料”）
        // - 只播放聚能粒子
        if (state == ShipSystemAPI.SystemState.IN) {
            wasChargingLastFrame = true
            weapon.setForceDisabled(true)
            weapon.setForceNoFireOneFrame(true)

            // 保守：确保系统 IN 时不会残留上一轮 active 的 beam DPS 修改
            restoreBeamDpsIfNeeded(weapon)

            vfx.fadeOut()
            boltAcc = 0f
            activationBudgetLeft = 0f
            wasActiveLastFrame = false
            smoothLenInited = false

            chargeUpVfx.advance(engine, amount, weapon, level)
            return
        }

        // 避免下次进入 IN 时 accumulator "憋一波" 突然喷发。
        // 若从 IN 退出但并未进入 ACTIVE（比如取消/被迫停机），则不触发爆发。
        wasChargingLastFrame = false
        chargeUpVfx.reset()
        // 注意：不要把 COOLDOWN 当成 active，否则会在系统冷却时仍然强制开火。
        // 这里不再把 IN 当成 active：充能期不允许开火/耗辐能。
        val active = (level > 0f) || (state == ShipSystemAPI.SystemState.ACTIVE)

        val ft = ship.fluxTracker
        val currFlux = ft.currFlux.coerceAtLeast(0f)

        // devMode 调试：确认引擎侧是否真的建立了 beam，以及武器是否进入 firing。
        // 仅玩家舰 + 低频输出，避免刷屏。
        if (Global.getSettings().isDevMode && engine.playerShip === ship) {
            debugInterval.advance(amount)
            if (debugInterval.intervalElapsed()) {
                val beamsCount = try {
                    weapon.beams?.size ?: 0
                } catch (_: Throwable) {
                    0
                }
                val isFiring = try {
                    weapon.isFiring
                } catch (_: Throwable) {
                    false
                }
                val charge = try {
                    weapon.chargeLevel
                } catch (_: Throwable) {
                    -1f
                }
                Global.getLogger(StellarJetEmitterEveryFrameEffect::class.java).info(
                    "[StellarJet] active=$active level=${"%.2f".format(level)} fuelFlux=${"%.0f".format(currFlux)} " +
                        "forceDisabled=${
                            try {
                                weapon.isDisabled
                            } catch (_: Throwable) {
                                false
                            }
                        } " +
                        "isFiring=$isFiring charge=${"%.2f".format(charge)} beams=$beamsCount"
                )
            }
        }

        // 默认禁用该“喷射口”武器：只允许在系统开启时工作
        weapon.setForceDisabled(!active)
        if (!active) {
            weapon.setForceNoFireOneFrame(true)

            restoreBeamDpsIfNeeded(weapon)

            // 触发淡出/回收
            vfx.fadeOut()

            // 关闭时重置能量弹累积
            boltAcc = 0f
            activationBudgetLeft = 0f
            wasActiveLastFrame = false

            // 关闭时重置平滑，避免下次点火继承上一次的束长
            smoothLenInited = false

            chargeUpVfx.reset()
            return
        }

        // 系统开启时：强制点火（但在过载/散热时不喷射，避免怪异表现）。
        val overloadedOrVenting = try {
            ft.isOverloadedOrVenting
        } catch (_: Throwable) {
            false
        }
        if (!overloadedOrVenting) {
            keepWeaponImmuneDuringSystem(weapon)
            weapon.setForceFireOneFrame(true)
        } else {
            weapon.setForceNoFireOneFrame(true)
            vfx.fadeOut()
            boltAcc = 0f
            activationBudgetLeft = 0f
            wasActiveLastFrame = false
            return
        }

        // 原版 beam 实例（可能在某些时机/某些 hook 下拿不到；因此不要把它当作“唯一真相”）
        val beam: BeamAPI? = try {
            weapon.beams?.firstOrNull()
        } catch (_: Throwable) {
            null
        }

        // 需求：动态 beam DPS（maxFlux 的 15%）
        val maxFluxNow = try {
            ft.maxFlux
        } catch (_: Throwable) {
            0f
        }
        val dynamicBeamDps = applyDynamicBeamDps(weapon, beam, maxFluxNow)

        // 系统开启：认为在喷射（用于 VFX 与辐能成本）。
        // 注意：不要依赖 weapon.isFiring/weapon.beams 来决定辐能成本；否则会出现“系统 ACTIVE 但不耗辐能”。
        val jetting = active && !overloadedOrVenting

        // 角度/方向：跟随武器当前朝向
        val facing = weapon.currAngle
        val rad = Math.toRadians(facing.toDouble())
        val dir = Vector2f(cos(rad).toFloat(), sin(rad).toFloat())

        val start = Vector2f(beam?.from ?: weapon.location)
        val maxEnd = Vector2f(start.x + dir.x * MAX_RANGE, start.y + dir.y * MAX_RANGE)

        // 视觉参数：宽度/强度只随 system level/武器 charge 变化（需求：不受幅能水平影响）
        val charge = try {
            weapon.chargeLevel.coerceIn(0f, 1f)
        } catch (_: Throwable) {
            1f
        }
        val wT = (level * (0.15f + 0.85f * charge)).coerceIn(0f, 1f)
        val coreW = lerp(BEAM_MIN_CORE_W, BEAM_MAX_CORE_W, wT)
        val glowW = lerp(BEAM_MIN_GLOW_W, BEAM_MAX_GLOW_W, wT)

        // 计算束长/命中点：统一使用 BoxUtil 的直线判定（与原版 beam 的 ray 命中方式一致），
        // 这样：
        // - VFX 的命中点稳定；
        // - 也可用于“原版 beam 不结算伤害时”的兜底伤害。
        var hitTarget: CombatEntityAPI? = null
        var hitPoint: Vector2f? = null
        var shieldHit = false

        val dealtController = object : CurveUtil.DealtController {
            override fun applyEffect(target: CombatEntityAPI, point: Vector2f, beamT: Float, isShieldHit: Boolean) {
                if (hitTarget != null) return
                hitTarget = target
                hitPoint = Vector2f(point)
                shieldHit = isShieldHit
            }

            override fun isIgnore(target: CombatEntityAPI): Boolean {
                if (target === ship) return true
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

            override fun isPierce(target: CombatEntityAPI, point: Vector2f, beamT: Float, isShieldHit: Boolean): Boolean = false
        }

        val result = try {
            CurveUtil.spawnDirectBeam(engine, start, maxEnd, DIRECT_BEAM_MAX_CHECK_RANGE_OFFSET, dealtController)
        } catch (_: Throwable) {
            null
        }

        val beamLen = if (result?.one != null) sqrt(result.one.z.coerceAtLeast(0f)).coerceAtMost(MAX_RANGE) else MAX_RANGE
        val end = if (result?.one != null) Vector2f(result.one.x, result.one.y) else Vector2f(maxEnd)
        val contact = StellarJetBeamVfx.BeamContact(end = hitPoint ?: end, hitTarget = hitTarget, isShieldHit = shieldHit)

        // 平滑束长（仅视觉）：增长慢一点，缩短快一点
        val targetLen = beamLen.coerceAtLeast(0f)
        if (!smoothLenInited) {
            smoothLenInited = true
            smoothLen = targetLen
        } else {
            val d = targetLen - smoothLen
            val rate = if (d >= 0f) LEN_GROW_PER_SEC else LEN_SHRINK_PER_SEC
            val maxStep = rate * amount
            smoothLen += d.coerceIn(-maxStep, maxStep)
        }

        // 额外渲染：系统开启且有燃料时保持束体存在。
        if (jetting) {
            vfx.update(
                engine = engine,
                amount = amount,
                source = ship,
                start = start,
                facing = facing,
                length = smoothLen,
                strength = wT,
                panelDps = dynamicBeamDps,
                coreWidth = coreW,
                glowWidth = glowW,
                firing = true,
                contact = contact,
            )
        } else {
            vfx.fadeOut()
            smoothLenInited = false
        }

        // 固定辐能消耗：系统开启即持续“耗 currFlux”，耗尽自动停机。
        // NOTE：这里的“消耗”不是产出；不会调用 increaseFlux。
        if (jetting) {
            // 单次激活预算初始化：max 100% maxFlux
            if (!wasActiveLastFrame) {
                activationBudgetLeft = (maxFluxNow * ACTIVATION_BUDGET_MAX_FLUX_MULT).coerceAtLeast(0f)
            }
            wasActiveLastFrame = true

            val want = (maxFluxNow * FLUX_DRAIN_MAX_FLUX_FRACTION_PER_SEC * amount).coerceAtLeast(0f)
            val before = try {
                ft.currFlux
            } catch (_: Throwable) {
                0f
            }
            val budgeted = want.coerceAtMost(activationBudgetLeft.coerceAtLeast(0f))
            val spent = budgeted.coerceAtMost(before.coerceAtLeast(0f))
            if (spent > 0f) {
                try {
                    ft.decreaseFlux(spent)
                } catch (_: Throwable) {
                }
                activationBudgetLeft -= spent
                applyRecoil(ship, dir, spent)
            }

            // 耗尽：停机 + 禁止本帧继续喷射
            if (activationBudgetLeft <= 0.01f || before <= 0.01f || spent + 0.01f < budgeted) {
                try {
                    system.deactivate()
                } catch (_: Throwable) {
                }
                weapon.setForceNoFireOneFrame(true)
                vfx.fadeOut()
                boltAcc = 0f
                smoothLenInited = false
                activationBudgetLeft = 0f
                wasActiveLastFrame = false
                return
            }

            // 系统期间的额外能量弹
            spawnJetBolts(
                engine = engine,
                amount = amount,
                ship = ship,
                start = start,
                facing = facing,
                baseBeamDps = dynamicBeamDps,
            )
        } else {
            wasActiveLastFrame = false
            activationBudgetLeft = 0f
        }

        // 伤害兜底：如果原版 beam 没有在这一帧结算伤害，但我们通过 raycast 确认命中点存在，则手动 applyDamage。
        // 目的：修复“看得到束/命中效果正常，但完全不掉血”的情况。
        if (jetting && hitTarget != null) {
            val vanillaDidDamage = try {
                beam?.didDamageThisFrame() == true
            } catch (_: Throwable) {
                false
            }
            if (!vanillaDidDamage) {
                val dmg: DamageAPI = try {
                    beam?.damage ?: weapon.damage
                } catch (_: Throwable) {
                    weapon.damage
                }
                val frameDamageRaw = if (try {
                        dmg.isDps
                    } catch (_: Throwable) {
                        true
                    }
                ) {
                    (try {
                        dmg.damage
                    } catch (_: Throwable) {
                        0f
                    }) * amount
                } else {
                    try {
                        dmg.damage
                    } catch (_: Throwable) {
                        0f
                    }
                }

                val frameDamage = frameDamageRaw * BEAM_DAMAGE_MUL

                if (frameDamage > 0f) {
                    try {
                        engine.applyDamage(
                            hitTarget,
                            hitPoint ?: contact.end,
                            frameDamage,
                            try {
                                dmg.type
                            } catch (_: Throwable) {
                                weapon.damageType
                            },
                            0f,
                            false,
                            try {
                                dmg.isSoftFlux
                            } catch (_: Throwable) {
                                false
                            },
                            ship,
                            true,
                        )
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }
}
