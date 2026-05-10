package cn.kasuminova.astd.combat.effect.lens.rare

import cn.kasuminova.astd.renderer.effect.projectile.beam.OglEllipseRingRenderer
import cn.kasuminova.astd.internal.debug.CombatCaps
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileVfxUtil
import cn.kasuminova.astd.combat.effect.arc.signature.tsm.TsmTerminalStrikeFx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.util.Misc
import org.boxutil.manager.CombatRenderingManager
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.max
import kotlin.math.min

/**
 * FDP-4 延迟裂解投射器：定点投射，延迟后在空中触发裂解并生成短时范围场域。
 *
 * 设计稿参考：docs/design/weapons/purple/30-rare.md
 *
 * 目标（v0/MVP）：
 * - 导弹可被点防清弹（被清掉则不会触发）
 * - 延迟结束后触发：
 *   - 一次“裂解”爆发（AOE 伤害）
 *   - 生成短时场域：对盾持续施加硬幅（每秒封顶），并附带小剂量 EMP（单目标封顶）
 * - AOE 边界有可读环线
 */
internal object Fdp4DelayedFission {

    private const val PROJECTILE_SPEC_ID = "astd_fdp4_charge"

    // 设计建议：0.6–1.2s
    private const val DELAY_MIN = 0.60f
    private const val DELAY_MAX = 1.20f

    // 设计建议：200–320
    private const val RADIUS_MIN = 220f
    private const val RADIUS_MAX = 300f

    private const val FIELD_DURATION = 1.25f

    // 触发条件：延迟结束后不立刻引爆，而是仅在“附近确实有敌人”时才引爆。
    // 这样在测试/误射空域时不会看到 1 秒左右固定自爆。
    private const val DETONATION_CHECK_INTERVAL = 0.10f

    // “区域幅压（封顶）”：每秒最多额外增加的硬幅（所有 FDP-4 场域共享同一封顶桶）
    private const val SHIELD_PRESSURE_CAP_PER_SECOND = 320f
    private const val SHIELD_PRESSURE_WANT_PER_SECOND = 520f

    // EMP：脉冲施加，单目标封顶
    private const val EMP_PULSE_INTERVAL = 0.35f
    private const val EMP_CAP_PER_TARGET = 420f
    private const val EMP_ARC_PIERCE_SHIELDS = false

    // “电弧打击”规则：
    // - 打到盾：造成 20% 面板伤害（主要体现为盾幅压/硬幅）。
    // - 没有盾（或盾不覆盖落点）：造成 100% 面板的 EMP（但仍受单目标封顶约束）。
    private const val ARC_DAMAGE_TO_SHIELD_MUL = 0.20f
    private const val ARC_EMP_TO_HULL_MUL = 1.00f

    // 声音：打击电弧（使用原版 soundId，避免引入新资源）
    private const val STRIKE_SOUND_ID = "emp_arc"
    private const val STRIKE_SOUND_VOLUME = 0.55f

    private val CORE_WHITE = Color(255, 255, 255, 245)
    private val RIFT_CORE = Color(255, 170, 230, 235)
    private val RIFT_FRINGE = Color(110, 35, 190, 210)
    private val FIELD_RING = Color(210, 110, 255, 120)

    // 场域氛围/波动（纯视觉）
    // 频率 -50%：间隔翻倍（更克制，避免“高频闪烁”）。
    private const val FIELD_DISTORTION_PULSE_INTERVAL = 0.36f
    private const val FIELD_RIPPLE_INTERVAL = 0.22f
    private const val FIELD_AMBIENT_PARTICLE_INTERVAL = 0.06f
    private const val FIELD_EXTRA_ENEMY_ARC_INTERVAL = 0.16f

    fun onSpawn(engine: CombatEngineAPI, projectile: DamagingProjectileAPI) {
        val projId = try {
            projectile.projectileSpecId
        } catch (_: Throwable) {
            null
        }
        if (projId != PROJECTILE_SPEC_ID) return
        if (engine.isPaused) return

        val delay = MathUtils.getRandomNumberInRange(DELAY_MIN, DELAY_MAX)
        val radius = MathUtils.getRandomNumberInRange(RADIUS_MIN, RADIUS_MAX)

        // 引爆检测半径：略小于最终场域半径，避免“擦到边缘就爆”的尴尬，也更贴合“逼迫转盾/撤线”。
        val detonationRadius = radius * 0.80f

        engine.addPlugin(object : BaseEveryFrameCombatPlugin() {
            private var timer = delay
            private var armed = false
            private var checkTick = 0f

            override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
                if (engine.isPaused) return

                // 被点防清掉/命中提前移除：不触发（符合“可被点防清弹”的反制）。
                if (projectile.isExpired || !engine.isEntityInPlay(projectile)) {
                    engine.removePlugin(this)
                    return
                }

                if (!armed) {
                    timer -= amount
                    if (timer > 0f) return
                    // 延迟结束：进入武装状态。是否引爆由“附近敌人”决定。
                    armed = true
                    checkTick = 0f
                }

                checkTick += amount
                if (checkTick < DETONATION_CHECK_INTERVAL) return
                checkTick -= DETONATION_CHECK_INTERVAL

                // 没有敌人就不引爆：继续飞行/等待。
                if (!hasEnemyNearby(detonationRadius)) return

                triggerFission(engine, projectile, radius)
                engine.removePlugin(this)
            }

            private fun hasEnemyNearby(range: Float): Boolean {
                val center = try {
                    projectile.location
                } catch (_: Throwable) {
                    null
                } ?: return false
                val owner = try {
                    projectile.owner
                } catch (_: Throwable) {
                    null
                }

                val ships = CombatUtils.getShipsWithinRange(center, range)
                for (s in ships) {
                    if (s.isHulk) continue
                    if (s.hullSize == ShipAPI.HullSize.FIGHTER) continue
                    if (owner != null && s.owner == owner) continue
                    // 只要有一个敌方非战机目标在范围内，就认为“值得引爆”。
                    return true
                }
                return false
            }
        })
    }

    private fun triggerFission(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, radius: Float) {
        val center = try {
            Vector2f(projectile.location)
        } catch (_: Throwable) {
            null
        } ?: return
        val source = try {
            projectile.source
        } catch (_: Throwable) {
            null
        }
        val owner = try {
            projectile.owner
        } catch (_: Throwable) {
            null
        }

        // 尽量移除导弹本体：后续伤害由“裂解事件”承担。
        try {
            engine.removeEntity(projectile)
        } catch (_: Throwable) {
            // removeEntity 在某些实现/阶段可能失败；不强求，至少不要因此炸。
        }

        // --- 视觉：裂解瞬间 ---
        try {
            engine.addHitParticle(center, Vector2f(), radius * 0.55f, 1.0f, 0.08f, CORE_WHITE)
            engine.addSmoothParticle(center, Vector2f(), radius * 0.85f, 0.85f, 0.20f, RIFT_CORE)

            // “地面/背景极短折射波”：用一个短促的扩张扭曲波模拟。
            spawnFissionRefractionWave(engine, center, radius)

            // 裂解环（高亮、短寿命、略外扩）
            OglEllipseRingRenderer.spawn(
                engine,
                OglEllipseRingRenderer.RingSpec(
                    center = center,
                    facing = 0f,
                    aSideHalf = radius * 0.55f,
                    bAlongHalf = radius * 0.55f,
                    duration = 0.45f,
                    color = Color(RIFT_CORE.red, RIFT_CORE.green, RIFT_CORE.blue, 165),
                    lineWidthPx = 1.6f,
                    segments = 92,
                    expandSpeed = 120f,
                    tangentialSpeed = 0f,
                )
            )

            // 轻量碎光：边界刻度（让范围更可读）
            val ticks = 22
            for (i in 0 until ticks) {
                val ang = i * (360f / ticks.toFloat()) + MathUtils.getRandomNumberInRange(-6f, 6f)
                val p = MathUtils.getPointOnCircumference(center, radius, ang)
                val v = Vector2f(center.x - p.x, center.y - p.y)
                val len = max(1f, v.length())
                v.scale(140f / len)
                engine.addHitParticle(p, v, 26f, 1.45f, 0.70f, RIFT_FRINGE)
            }
        } catch (_: Throwable) {
        }

        // --- 数值：一次 AOE 裂解伤害（克制：有距离衰减） ---
        val baseDamage = sanitizeNonNegativeFinite(
            try {
                projectile.damageAmount
            } catch (_: Throwable) {
                0f
            }
        )
        val dmgType = try {
            projectile.damageType
        } catch (_: Throwable) {
            DamageType.ENERGY
        }

        if (baseDamage > 0f) {
            val ships = CombatUtils.getShipsWithinRange(center, radius)
            for (ship in ships) {
                if (ship.isHulk) continue
                if (ship.hullSize == ShipAPI.HullSize.FIGHTER) continue
                if (owner != null && ship.owner == owner) continue

                val dist = Misc.getDistance(center, ship.location)
                if (!dist.isFinitePositiveOrZero()) continue
                if (dist > radius) continue

                val t = (1f - (dist / radius)).coerceIn(0f, 1f)
                val dmg = baseDamage * (0.35f + 0.65f * t)
                if (dmg <= 0f) continue

                val toward = Misc.getAngleInDegrees(center, ship.location)
                val pointRadius = (ship.collisionRadius * 0.70f).coerceAtLeast(8f)
                val hitPoint = MathUtils.getPointOnCircumference(ship.location, pointRadius, toward)

                engine.applyDamage(
                    ship,
                    hitPoint,
                    dmg,
                    dmgType,
                    0f,
                    false,
                    false,
                    source,
                )
            }
        }

        // --- 场域：持续幅压 + EMP（封顶） ---
        spawnField(engine, center, radius, source, owner, baseDamage)
    }

    private fun spawnField(engine: CombatEngineAPI, center: Vector2f, radius: Float, source: ShipAPI?, owner: Int?, panelDamage: Float) {
        // 基础扭曲底纹（非常克制）：避免完全静态。
        // 注意：BoxUtil 不可用/玩家关闭扭曲/初始化失败时会抛异常；此处必须吞掉。
        spawnFieldDistortion(engine, center, radius, FIELD_DURATION)

        // AOE 边界可读环线（持续时间内保持存在；alpha 会随寿命衰减）
        try {
            OglEllipseRingRenderer.spawn(
                engine,
                OglEllipseRingRenderer.RingSpec(
                    center = center,
                    facing = 0f,
                    aSideHalf = radius,
                    bAlongHalf = radius,
                    duration = FIELD_DURATION,
                    color = FIELD_RING,
                    lineWidthPx = 1.35f,
                    segments = 96,
                    expandSpeed = 0f,
                    tangentialSpeed = 0f,
                )
            )

            // 额外：用粒子拼一圈“虚线辉光”（对不支持 OGL 的情况下也有可读性）
            ProjectileVfxUtil.spawnRing(
                engine = engine,
                center = center,
                baseVel = Vector2f(),
                radius = radius,
                particleCount = 28,
                size = 11f,
                brightness = 1.15f,
                duration = 0.30f,
                color = Color(210, 110, 255, 60),
            )
        } catch (_: Throwable) {
        }

        val endTime = engine.getTotalElapsedTime(false) + FIELD_DURATION

        // EMP 单目标封顶记录
        val appliedEmp = HashMap<Int, Float>(16)

        engine.addPlugin(object : BaseEveryFrameCombatPlugin() {
            private var empTick = 0f

            private var distortionTick = 0f
            private var rippleTick = 0f
            private var ambientTick = 0f
            private var extraEnemyArcTick = 0f

            override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
                if (engine.isPaused) return

                val now = engine.getTotalElapsedTime(false)
                if (now >= endTime) {
                    engine.removePlugin(this)
                    return
                }

                // --- 场域持续波动（视觉）：扭曲脉冲 + 涟漪环 + 氛围粒子 ---
                distortionTick += amount
                while (distortionTick >= FIELD_DISTORTION_PULSE_INTERVAL) {
                    distortionTick -= FIELD_DISTORTION_PULSE_INTERVAL
                    spawnFieldDistortionPulse(engine, center, radius)
                }

                rippleTick += amount
                if (rippleTick >= FIELD_RIPPLE_INTERVAL) {
                    rippleTick -= FIELD_RIPPLE_INTERVAL
                    spawnFieldRipple(engine, center, radius)
                }

                ambientTick += amount
                while (ambientTick >= FIELD_AMBIENT_PARTICLE_INTERVAL) {
                    ambientTick -= FIELD_AMBIENT_PARTICLE_INTERVAL
                    spawnFieldAmbientParticle(engine, center, radius)
                }

                // 只做一次范围扫描：后续盾压/EMP/额外电弧全部复用。
                val ships = CombatUtils.getShipsWithinRange(center, radius)

                // 额外：只要场域内有敌人，就低频生成一次“打击电弧”（纯视觉，增强反馈）。
                // 与 EMP 脉冲解耦，避免只在数值生效时才有“动静”。
                extraEnemyArcTick += amount
                if (extraEnemyArcTick >= FIELD_EXTRA_ENEMY_ARC_INTERVAL) {
                    extraEnemyArcTick -= FIELD_EXTRA_ENEMY_ARC_INTERVAL

                    val enemies = ships.filter { s -> !s.isHulk && s.hullSize != ShipAPI.HullSize.FIGHTER && (owner == null || s.owner != owner) }
                    if (enemies.isNotEmpty()) {
                        spawnExtraEnemyStrikeArc(engine, center, radius, source, enemies.random())
                    }
                }

                // 持续“盾幅压”：每帧按 dt 结算，但走每秒封顶桶
                for (ship in ships) {
                    if (ship.isHulk) continue
                    if (ship.hullSize == ShipAPI.HullSize.FIGHTER) continue
                    if (owner != null && ship.owner == owner) continue

                    val dist = Misc.getDistance(center, ship.location)
                    if (!dist.isFinitePositiveOrZero()) continue
                    if (dist > radius) continue

                    val shield = ship.shield
                    if (shield == null || !shield.isOn) continue

                    // 落点：面向场域中心的一侧
                    val toward = Misc.getAngleInDegrees(ship.location, center)
                    val pointRadius = shield.radius.coerceAtLeast(ship.collisionRadius * 0.65f).coerceAtLeast(12f)
                    val p = MathUtils.getPointOnCircumference(ship.location, pointRadius, toward)

                    val shieldCovers = try {
                        shield.isWithinArc(p)
                    } catch (_: Throwable) {
                        false
                    }
                    if (!shieldCovers) continue

                    val t = (1f - (dist / radius)).coerceIn(0f, 1f)
                    val want = (SHIELD_PRESSURE_WANT_PER_SECOND * (0.35f + 0.65f * t) * amount).coerceAtLeast(0f)
                    if (want <= 0f) continue

                    val cap = (SHIELD_PRESSURE_CAP_PER_SECOND * (0.45f + 0.55f * t)).coerceAtLeast(0f)
                    val bucketKey = "fdp4_pressure:${System.identityHashCode(ship)}"
                    val applied = CombatCaps.applyPerSecondCap(engine, bucketKey, cap, want)
                    if (applied <= 0f) continue

                    ship.fluxTracker.increaseFlux(applied, true)

                    // 轻量视觉：一点点“受压闪”
                    if (MathUtils.getRandomNumberInRange(0f, 1f) < 0.08f) {
                        engine.addSmoothParticle(p, Vector2f(), 22f, 0.55f, 0.12f, RIFT_FRINGE)
                    }
                }

                // EMP：按间隔脉冲，单目标封顶
                empTick += amount
                if (empTick < EMP_PULSE_INTERVAL) return
                empTick -= EMP_PULSE_INTERVAL

                // 每次脉冲给一个轻量环提示（增强“场域在工作”的反馈）
                try {
                    engine.addHitParticle(center, Vector2f(), radius * 0.72f, 0.75f, 0.10f, Color(160, 110, 255, 95))
                } catch (_: Throwable) {
                }

                val targets = ships.filter { s -> !s.isHulk && s.hullSize != ShipAPI.HullSize.FIGHTER && (owner == null || s.owner != owner) }

                if (targets.isEmpty()) return

                // 控制电弧数量：每跳只挑 1–2 个目标，避免屏幕/性能爆炸
                val pick = targets.shuffled().take(2)
                for (ship in pick) {
                    val dist = Misc.getDistance(center, ship.location)
                    if (!dist.isFinitePositiveOrZero()) continue
                    if (dist > radius) continue

                    val t = (1f - (dist / radius)).coerceIn(0f, 1f)
                    val scale = (0.35f + 0.65f * t)

                    val toward = Misc.getAngleInDegrees(ship.location, center)
                    val shield = ship.shield
                    val shieldOn = shield != null && shield.isOn
                    val pointRadius = if (shieldOn) {
                        shield!!.radius.coerceAtLeast(ship.collisionRadius * 0.65f).coerceAtLeast(12f)
                    } else {
                        (ship.collisionRadius * 0.75f).coerceAtLeast(12f)
                    }
                    val hitPoint = MathUtils.getPointOnCircumference(ship.location, pointRadius, toward)
                    val shieldCovers = if (shieldOn) {
                        try {
                            shield!!.isWithinArc(hitPoint)
                        } catch (_: Throwable) {
                            false
                        }
                    } else {
                        false
                    }

                    // 额外可读：从“场域中心”拉一条打击电弧到目标。
                    // 这条弧只做视觉（不额外施加数值），让玩家明确读到“场域正在打击目标”。
                    try {
                        engine.spawnEmpArcVisual(
                            center,
                            source ?: ship,
                            hitPoint,
                            ship,
                            MathUtils.getRandomNumberInRange(10f, 16f),
                            RIFT_FRINGE,
                            RIFT_CORE,
                        )
                    } catch (_: Throwable) {
                    }

                    // --- 数值：电弧打击规则 ---
                    // 面板伤害以“弹体面板 damage”作为基准（panelDamage）。
                    val panel = panelDamage.coerceAtLeast(0f)
                    if (panel > 0f) {
                        if (shieldCovers) {
                            val dmgToShield = (panel * ARC_DAMAGE_TO_SHIELD_MUL * scale).coerceAtLeast(0f)
                            if (dmgToShield > 0f) {
                                try {
                                    engine.applyDamage(
                                        ship,
                                        hitPoint,
                                        dmgToShield,
                                        DamageType.ENERGY,
                                        0f,
                                        false,
                                        false,
                                        source,
                                    )
                                    playStrikeSound(engine, hitPoint, ship.velocity ?: Vector2f())
                                } catch (_: Throwable) {
                                }
                            }
                        } else {
                            val wantEmp = (panel * ARC_EMP_TO_HULL_MUL * scale).coerceAtLeast(0f)
                            if (wantEmp > 0f) {
                                val key = System.identityHashCode(ship)
                                val already = appliedEmp[key] ?: 0f
                                val remaining = (EMP_CAP_PER_TARGET - already).coerceAtLeast(0f)
                                val emp = min(wantEmp, remaining)
                                if (emp > 0f) {
                                    TsmTerminalStrikeFx.spawnSubsystemEmpArcs(
                                        engine = engine,
                                        target = ship,
                                        center = hitPoint,
                                        totalEmp = emp,
                                        pierceShields = EMP_ARC_PIERCE_SHIELDS,
                                        source = source,
                                        coreColor = RIFT_CORE,
                                        fringeColor = RIFT_FRINGE,
                                        empPerArcDivisor = 280f,
                                        arcCountMin = 1,
                                        arcCountMax = 3,
                                        arcWidthMin = 8f,
                                        arcWidthMax = 14f,
                                    )
                                    appliedEmp[key] = already + emp
                                    playStrikeSound(engine, hitPoint, ship.velocity ?: Vector2f())
                                }
                            }
                        }
                    }

                    ship.setJitterUnder(this, RIFT_FRINGE, 0.16f, 2, 0f, 6f)
                }
            }
        })
    }

    private fun spawnFieldRipple(engine: CombatEngineAPI, center: Vector2f, radius: Float) {
        try {
            val r = radius * MathUtils.getRandomNumberInRange(0.32f, 0.62f)
            val c = Color(RIFT_CORE.red, RIFT_CORE.green, RIFT_CORE.blue, 80)

            OglEllipseRingRenderer.spawn(
                engine,
                OglEllipseRingRenderer.RingSpec(
                    center = center,
                    facing = 0f,
                    aSideHalf = r,
                    bAlongHalf = r,
                    duration = 0.26f,
                    color = c,
                    lineWidthPx = 1.25f,
                    segments = 80,
                    expandSpeed = 240f,
                    tangentialSpeed = MathUtils.getRandomNumberInRange(-20f, 20f),
                )
            )

            // 辅助：少量粒子形成“虚线涟漪”，提升非 OGL 场景可读
            ProjectileVfxUtil.spawnRing(
                engine = engine,
                center = center,
                baseVel = Vector2f(),
                radius = r,
                particleCount = 16,
                size = 9f,
                brightness = 1.05f,
                duration = 0.20f,
                color = Color(210, 110, 255, 45),
            )
        } catch (_: Throwable) {
        }
    }

    private fun spawnFieldAmbientParticle(engine: CombatEngineAPI, center: Vector2f, radius: Float) {
        try {
            // 在场域内部随机一点，给一个轻微切向速度，看起来像“空间在搅动”。
            val loc = MathUtils.getRandomPointInCircle(center, radius * 0.80f)
            val dx = loc.x - center.x
            val dy = loc.y - center.y
            val len = max(1f, kotlin.math.sqrt(dx * dx + dy * dy))

            // 切向单位向量
            val tx = -dy / len
            val ty = dx / len
            val speed = MathUtils.getRandomNumberInRange(18f, 55f)
            val vel = Vector2f(tx * speed, ty * speed)

            // 轻度“雾化”粒子（不透明度极低，避免糊屏）
            engine.addNebulaParticle(
                loc,
                vel,
                MathUtils.getRandomNumberInRange(38f, 72f),
                1.35f,
                0.15f,
                0.45f,
                MathUtils.getRandomNumberInRange(0.45f, 0.80f),
                Color(120, 60, 160, 28),
                true,
            )
        } catch (_: Throwable) {
        }
    }

    private fun spawnExtraEnemyStrikeArc(engine: CombatEngineAPI, center: Vector2f, radius: Float, source: ShipAPI?, ship: ShipAPI) {
        val toward = Misc.getAngleInDegrees(ship.location, center)
        val shield = ship.shield
        val pointRadius = if (shield != null && shield.isOn) shield.radius else ship.collisionRadius * 0.75f
        val p2 = MathUtils.getPointOnCircumference(ship.location, pointRadius.coerceAtLeast(12f), toward)

        // 起点不总在中心：略微随机偏移，避免电弧看起来“钉死在一个点”。
        val p1 = MathUtils.getRandomPointInCircle(center, (radius * 0.12f).coerceAtMost(28f))

        try {
            engine.spawnEmpArcVisual(
                p1,
                source ?: ship,
                p2,
                ship,
                MathUtils.getRandomNumberInRange(8f, 14f),
                Color(RIFT_FRINGE.red, RIFT_FRINGE.green, RIFT_FRINGE.blue, 185),
                Color(RIFT_CORE.red, RIFT_CORE.green, RIFT_CORE.blue, 205),
            )

            // 低概率播放一下，避免和主脉冲重复导致“哒哒哒”连发。
            if (MathUtils.getRandomNumberInRange(0f, 1f) < 0.35f) {
                playStrikeSound(engine, p2, ship.velocity ?: Vector2f())
            }
        } catch (_: Throwable) {
        }
    }

    private fun playStrikeSound(engine: CombatEngineAPI, loc: Vector2f, vel: Vector2f) {
        try {
            val pitch = MathUtils.getRandomNumberInRange(0.92f, 1.08f)
            Global.getSoundPlayer().playSound(STRIKE_SOUND_ID, pitch, STRIKE_SOUND_VOLUME, loc, vel)
        } catch (_: Throwable) {
        }
    }

    private fun spawnFissionRefractionWave(engine: CombatEngineAPI, center: Vector2f, radius: Float) {
        try {
            val e = DistortionEntity()
            // 极短“折射波”：快速扩张后消失
            e.setGlobalTimer(0.03f, 0.04f, 0.12f)
            e.setInnerFull(0.10f, 0.10f)
            e.setInnerHardness(0.85f)
            e.setRingHardness(0.55f)

            e.setSizeIn(radius * 0.35f, radius * 0.35f)
            e.setSizeFull(radius * 1.10f, radius * 1.10f)
            e.setSizeOut(radius * 1.45f, radius * 1.45f)

            e.setPowerIn(0.55f)
            e.setPowerFull(0.35f)
            e.setPowerOut(0f)

            e.setLocation(center)
            CombatRenderingManager.addEntity(e)
        } catch (_: Throwable) {
            // BoxUtil 不可用/玩家关闭扭曲等：静默跳过。
        }
    }

    private fun spawnFieldDistortion(engine: CombatEngineAPI, center: Vector2f, radius: Float, duration: Float) {
        try {
            val d = duration.coerceAtLeast(0.25f)
            val inTime = 0.10f
            val outTime = 0.18f
            val fullTime = (d - inTime - outTime).coerceAtLeast(0.02f)

            val e = DistortionEntity()

            // 轻微“呼吸”扭曲：从略大 -> 稳定 -> 略小
            e.setGlobalTimer(inTime, fullTime, outTime)
            e.setInnerFull(0.42f, 0.42f)
            e.setInnerHardness(0.65f)
            e.setRingHardness(0.52f)

            e.setSizeIn(radius * 1.05f, radius * 1.05f)
            e.setSizeFull(radius * 0.95f, radius * 0.95f)
            e.setSizeOut(radius * 0.80f, radius * 0.80f)

            // 强度要非常克制：底纹只负责“不是完全静止”。真正的持续波动由脉冲负责。
            e.setPowerIn(0.20f)
            e.setPowerFull(0.12f)
            e.setPowerOut(0f)

            e.setLocation(center)
            CombatRenderingManager.addEntity(e)
        } catch (_: Throwable) {
            // BoxUtil 不可用/玩家关闭扭曲等：静默跳过。
        }
    }

    private fun spawnFieldDistortionPulse(engine: CombatEngineAPI, center: Vector2f, radius: Float) {
        try {
            val e = DistortionEntity()

            // 短促脉冲：频繁但很轻，让背景一直“呼吸/起伏”。
            e.setGlobalTimer(0.04f, 0.08f, 0.14f)
            e.setInnerFull(0.44f, 0.44f)
            e.setInnerHardness(0.60f)
            e.setRingHardness(0.48f)

            val rIn = radius * MathUtils.getRandomNumberInRange(0.85f, 0.98f)
            val rFull = radius * MathUtils.getRandomNumberInRange(0.80f, 0.92f)
            val rOut = radius * MathUtils.getRandomNumberInRange(0.65f, 0.82f)
            e.setSizeIn(rIn, rIn)
            e.setSizeFull(rFull, rFull)
            e.setSizeOut(rOut, rOut)

            val pIn = MathUtils.getRandomNumberInRange(0.22f, 0.34f)
            val pFull = MathUtils.getRandomNumberInRange(0.14f, 0.24f)
            e.setPowerIn(pIn)
            e.setPowerFull(pFull)
            e.setPowerOut(0f)

            // 轻微中心抖动：看起来更“活”。
            val loc = MathUtils.getRandomPointInCircle(center, (radius * 0.03f).coerceAtMost(14f))
            e.setLocation(loc)

            CombatRenderingManager.addEntity(e)
        } catch (_: Throwable) {
            // BoxUtil 不可用/玩家关闭扭曲等：静默跳过。
        }
    }

    private fun sanitizeNonNegativeFinite(v: Float): Float {
        if (v.isNaN() || v.isInfinite()) return 0f
        if (v < 0f) return 0f
        return v
    }

    private fun Float.isFinitePositiveOrZero(): Boolean = !this.isNaN() && !this.isInfinite() && this >= 0f
}
