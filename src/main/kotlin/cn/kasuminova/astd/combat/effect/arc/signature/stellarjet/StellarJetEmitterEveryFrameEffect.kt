package cn.kasuminova.astd.combat.effect.arc.signature.stellarjet

import cn.kasuminova.astd.impl.render.BeamHostImpl
import cn.kasuminova.astd.impl.render.SjBeam
import cn.kasuminova.astd.renderer.beam.driver.BeamFrame
import cn.kasuminova.astd.renderer.beam.driver.BeamVfxDriver
import cn.kasuminova.astd.renderer.beam.driver.BeamVfxDriverImpl
import cn.kasuminova.astd.renderer.beam.driver.BeamVfxSpecs
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamageType
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

    // 束体 VFX 现由 RenderEntity 树 + 光束驱动承担（旧 StellarJetBeamVfx 已删）；懒建、随武器插件常驻，
    // firing 帧喂 firing=true，停火帧喂 firing=false（束体节点自淡 0.14s + 自删），无需宿主逐帧维护句柄。
    private var beamDriver: BeamVfxDriver? = null

    private val chargeUpVfx = StellarJetChargeUpVfx(
        coreColor = CORE_COLOR,
        glowColor = GLOW_COLOR,
    )

    // 命中端周期性 EMP 伤害弧的节流累积器（gameplay：applyDamage + 选点，随旧 emitImpactEmpArc 迁回宿主）。
    private var impactEmpAcc = 0f

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

        // 命中端周期性 EMP 伤害弧（旧 StellarJetBeamVfx.emitImpactEmpArc/pickEmpArcTargetPoint，gameplay 随迁回宿主）。
        private const val EMP_ARC_INTERVAL = 0.33f
        private const val EMP_ARC_EMP_FRACTION = 0.67f
        private const val EMP_ARC_ENERGY_FRACTION = 0.33f
        private const val EMP_ARC_SHIELD_BASE_CHANCE = 0.50f
        private const val EMP_ARC_THICKNESS_MIN = 10f
        private const val EMP_ARC_THICKNESS_MAX = 28f
        private const val EMP_ARC_TARGET_JITTER = 18f
        private const val EMP_ARC_WEAPON_PICK_CHANCE = 0.78f

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

            // VFX：接入新 RenderEntity 管线（手写 DSL，projectileSpecId 直连），特殊伤害与射程逻辑继续由本 effect 维护。
            // 扫描器随后再调同一 track 会被 driver 的 containsKey 去重，故单份视觉、不与本处双登记。
            ProjectileVfxDriverPlugin.track(engine, dp, "astd_stellar_jet_bolt")

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

    /** 懒建光束驱动：束体基宽走核心束最小宽（strength 缩放由束体节点内部按 intensity 完成）。 */
    private fun ensureDriver(): BeamVfxDriver {
        beamDriver?.let { return it }
        return BeamVfxDriverImpl(
            BeamHostImpl("sjbeam@" + System.identityHashCode(this), baseWidth = BEAM_MIN_CORE_W),
            BeamVfxSpecs.stellarJet(),
        ).also { beamDriver = it }
    }

    /** firing 帧：把几何 + 强度 + 命中接触折成 [BeamFrame] 喂驱动。 */
    private fun driveBeam(
        engine: CombatEngineAPI, amount: Float, start: Vector2f, facing: Float, length: Float,
        strength: Float, endpoint: Vector2f, hitTarget: CombatEntityAPI?, isShieldHit: Boolean,
    ) {
        ensureDriver().advance(
            engine,
            BeamFrame(
                start = start, facing = facing, length = length, endpoint = endpoint,
                firing = true, strength = strength, fadeMul = 1f, hitTarget = hitTarget, isShieldHit = isShieldHit,
            ),
            amount,
        )
    }

    /** 停火帧：喂 firing=false，束体节点停心跳自淡（0.14s）并自删；几何随手取当前炮口即可（不 firing 时节点不读）。 */
    private fun fadeBeam(engine: CombatEngineAPI, weapon: WeaponAPI, amount: Float) {
        val driver = beamDriver ?: return
        val facing = weapon.currAngle
        val start = Vector2f(weapon.location)
        driver.advance(
            engine,
            BeamFrame(start = start, facing = facing, length = 16f, endpoint = start, firing = false, strength = 0f, fadeMul = 0f),
            amount,
        )
    }

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        if (amount <= 0f) return

        val ship = weapon.ship
        if (ship == null) {
            fadeBeam(engine, weapon, amount)
            chargeUpVfx.reset()
            return
        }
        if (ship.isHulk) {
            fadeBeam(engine, weapon, amount)
            chargeUpVfx.reset()
            return
        }

        val system = ship.system
        if (system == null || system.id != SYSTEM_ID) {
            fadeBeam(engine, weapon, amount)
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

            fadeBeam(engine, weapon, amount)
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
            fadeBeam(engine, weapon, amount)

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
            fadeBeam(engine, weapon, amount)
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
        val contactEnd = hitPoint ?: end

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

        // 额外渲染：系统开启且有燃料时保持束体存在（视觉走 RenderEntity 树；命中端 EMP 伤害弧为 gameplay，宿主自算）。
        if (jetting) {
            driveBeam(
                engine = engine, amount = amount, start = start, facing = facing, length = smoothLen,
                strength = wT, endpoint = contactEnd, hitTarget = hitTarget, isShieldHit = shieldHit,
            )
            emitImpactEmpArc(engine, amount, ship, contactEnd, hitTarget, shieldHit, wT, coreW, dynamicBeamDps)
        } else {
            fadeBeam(engine, weapon, amount)
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
                fadeBeam(engine, weapon, amount)
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
                            hitPoint ?: contactEnd,
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

    /**
     * 命中船体时周期性向其未瘫痪子系统打 EMP 伤害弧（旧 `StellarJetBeamVfx.emitImpactEmpArc`，gameplay 迁回宿主）。
     * 视觉弧与 applyDamage 打同一选点（[pickEmpArcTargetPoint]），故整体留宿主而非拆进渲染树。每 [EMP_ARC_INTERVAL] 触发一次。
     */
    private fun emitImpactEmpArc(
        engine: CombatEngineAPI, amount: Float, source: ShipAPI, contactEnd: Vector2f,
        hitTarget: CombatEntityAPI?, isShieldHit: Boolean, strength: Float, coreWidth: Float, panelDps: Float,
    ) {
        val target = hitTarget as? ShipAPI ?: run {
            // 未命中船体（空中/非船体实体）：清累积器，避免下次刚接触就立刻出弧。
            impactEmpAcc = 0f
            return
        }
        if (!engine.isEntityInPlay(target)) {
            impactEmpAcc = 0f
            return
        }
        if (target === source) return

        impactEmpAcc += amount
        if (impactEmpAcc < EMP_ARC_INTERVAL) return
        impactEmpAcc -= EMP_ARC_INTERVAL

        // 护盾命中：50% 基础概率触发，随目标硬幅能水平线性降低（最低 0%）。
        if (isShieldHit) {
            val hard = target.hardFluxLevel.coerceIn(0f, 1f)
            val chance = (EMP_ARC_SHIELD_BASE_CHANCE * (1f - hard)).coerceIn(0f, EMP_ARC_SHIELD_BASE_CHANCE)
            if (rand01() >= chance) return
        }

        val from = Vector2f(contactEnd)
        val to = pickEmpArcTargetPoint(target, from)

        val s = strength.coerceIn(0f, 1f)
        val thickness = (coreWidth * (0.38f + 0.22f * s)).coerceIn(EMP_ARC_THICKNESS_MIN, EMP_ARC_THICKNESS_MAX)
        engine.spawnEmpArcVisual(from, source, to, target, thickness, GLOW_COLOR, CORE_COLOR)

        // 伤害：以“面板 DPS”在 EMP_ARC_INTERVAL 窗口内折算为单次电弧伤害（护盾触发即穿透，bypassShields=true）。
        val dps = panelDps.coerceAtLeast(0f)
        if (dps <= 0f) return
        val total = (dps * EMP_ARC_INTERVAL).coerceAtLeast(0f)
        engine.applyDamage(
            target, to, total * EMP_ARC_ENERGY_FRACTION, DamageType.ENERGY, total * EMP_ARC_EMP_FRACTION,
            true, false, source, true,
        )
    }

    /** EMP 弧选点：优先未瘫痪武器，其次未瘫痪引擎，都无则命中点；带抖动避免“钉死”一点。 */
    private fun pickEmpArcTargetPoint(target: ShipAPI, fallback: Vector2f): Vector2f {
        val weapons = target.allWeapons.filter { !it.isDecorative && !it.isDisabled && !it.isPermanentlyDisabled }
        val engines = target.engineController.shipEngines.filter { !it.isDisabled && !it.isPermanentlyDisabled }

        val base = when {
            weapons.isNotEmpty() && engines.isNotEmpty() ->
                if (rand01() < EMP_ARC_WEAPON_PICK_CHANCE) Vector2f(pickRandom(weapons).location) else Vector2f(pickRandom(engines).location)

            weapons.isNotEmpty() -> Vector2f(pickRandom(weapons).location)
            engines.isNotEmpty() -> Vector2f(pickRandom(engines).location)
            else -> Vector2f(fallback)
        }

        val j = EMP_ARC_TARGET_JITTER
        base.x += (rand01() - 0.5f) * 2f * j
        base.y += (rand01() - 0.5f) * 2f * j
        return base
    }

    private fun <T> pickRandom(list: List<T>): T = list[(rand01() * list.size.toFloat()).toInt().coerceIn(0, list.size - 1)]
}
