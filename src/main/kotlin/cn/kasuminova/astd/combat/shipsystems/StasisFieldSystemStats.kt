package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.renderer.effect.projectile.beam.OglEllipseRingRenderer
import cn.kasuminova.astd.combat.effect.arc.signature.stasisfield.StasisFieldCollapseBeam
import cn.kasuminova.astd.combat.effect.arc.signature.stasisfield.StasisFieldEnergySiphonVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.max

/**
 * astd_stasis_field（时空停滞场）- 方案A MVP：
 * - 激活时在鼠标/前方位置生成一个“停滞场”（仅对非 beam 投射物生效）。
 * - 进入场内的【敌方】投射物被减速并被“捕获”（作为蓄能）。
 * - 系统结束时：清除被捕获投射物，并将蓄能转化为一次性“引力坍缩炮”光束表现（BoxUtil）。
 *
 * 说明：
 * - 这是“可测可看”的最小实现；数值、AI 行为、完整反制面后续再补。
 */
class StasisFieldSystemStats : BaseShipSystemScript() {

    companion object {
        private const val KEY = "astd_stasis_field:data"

        /** 用于对导弹 engineStats 做 modify/unmodify 的 id（必须稳定一致）。 */
        private const val STASIS_MOD_ID = "astd_stasis_field:proj_stasis"

        // 场半径（su）
        private const val FIELD_RADIUS = 800f

        // 玩家鼠标落点的最大距离（避免点到地图外）
        private const val MAX_TARGET_RANGE_FROM_SHIP = 1400f

        // 捕获投射物减速倍率（1=不变；0.05=几近静止）
        private const val PROJECTILE_SPEED_MULT = 0.25f

        /**
         * 场内“新发射弹体”免减速窗口（秒）：
         * - 仅对 spawnLocation 在场内的弹体生效（即“场内发射”）；
         * - 窗口内不会被立场减速/锁位，但仍会被跟踪用于后续“消失收集”。
         */
        private const val SPAWN_IN_FIELD_IMMUNE_TIME = 0.5f

        /** 是否影响友方投射物（仅减速、不会被移除/计入蓄能）。 */
        private const val AFFECT_FRIENDLY_PROJECTILES = true

        // 坍缩炮束长（su）
        private const val COLLAPSE_BEAM_RANGE = 3400f

        // 坍缩炮“威力”归一化：累计捕获伤害达到该值时，intensity≈1
        private const val ENERGY_FOR_FULL_INTENSITY = 16000f

        // 坍缩炮的最小强度（即使没吸到东西，也给个“可见”的束）
        private const val MIN_INTENSITY = 0.22f

        // 视觉：场边界刷新的频率
        private val RING_INTERVAL = IntervalUtil(0.09f, 0.12f)

        // 视觉：场边界颜色
        private val FIELD_RING_COLOR = Color(190, 130, 255, 95)
        private val FIELD_RING_COLOR_BRIGHT = Color(235, 205, 255, 120)
    }

    private data class Captured(
        val origVel: Vector2f,
        /**
         * 模拟位置：
         * - 进入场内后，按 origVel * PROJECTILE_SPEED_MULT 推进 simLoc，并每帧把 projectile.location 写回。
         * - 这样既能实现“缓慢飞行（-75%）”，也能对付某些弹体 velocity 会被引擎/脚本回写的情况。
         * - 且不会污染共享 spec，从而不影响场外弹体/新发射弹体。
         */
        val simLoc: Vector2f,
        /** 是否为敌方投射物（可计入蓄能、并在系统结束时被移除）。 */
        val enemy: Boolean,
        /** 该投射物被计入蓄能的值（只在首次进入时结算一次）。 */
        val capturedDamage: Float,
    )

    private class Data {
        var activeCycle = false
        var cycleStarted = false

        var center: Vector2f = Vector2f(0f, 0f)

        /** 捕获能量（按投射物 damageAmount 近似）。 */
        var energy = 0f

        /** 仅用于避免重复计入能量。 */
        val captured: MutableMap<DamagingProjectileAPI, Captured> = LinkedHashMap()

        /** 每帧扫描：记录当前仍在场内的投射物（用于离场恢复）。 */
        val inFieldThisFrame: MutableSet<DamagingProjectileAPI> = LinkedHashSet()

        /** 视觉：边界环刷新 interval（每艘船独立）。 */
        val ringInterval: IntervalUtil = IntervalUtil(RING_INTERVAL.minInterval, RING_INTERVAL.maxInterval)
    }

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk) return

        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return

        val data = getOrCreateData(ship)

        val level = effectLevel.coerceIn(0f, 1f)
        val cycleOn = level > 0f

        if (cycleOn && !data.activeCycle) {
            // 新的一轮系统激活：确定场中心并清空上次状态
            data.activeCycle = true
            data.cycleStarted = true
            data.energy = 0f
            data.captured.clear()
            data.ringInterval.forceIntervalElapsed()

            data.center = pickFieldCenter(engine, ship)
        }

        // 系统未开启时：不做任何工作（但保留 unapply 里的“结束触发”）
        if (!cycleOn) {
            return
        }

        // MVP：在系统开启期间让舰自身更“定格”，便于体验/观察。
        // （这是自损式代价；后续可以换成“场内敌方被减速/投射物被停滞”的完整机制。）
        val selfSlow = lerp(1.0f, 0.65f, level)
        stats.maxSpeed.modifyMult(id, selfSlow)
        stats.acceleration.modifyMult(id, selfSlow)
        stats.deceleration.modifyMult(id, selfSlow)
        stats.turnAcceleration.modifyMult(id, lerp(1.0f, 0.75f, level))

        // 视觉：停滞场边界环（每帧不必刷，按 interval 生成）
        val amount = safeElapsed(engine)
        data.ringInterval.advance(amount)
        if (data.ringInterval.intervalElapsed()) {
            spawnFieldRing(engine, data.center, ship.facing, level)
        }

        // 捕获/减速：对场内投射物生效（敌方可被“收集能量”；友方仅减速不吞）
        applyStasisToProjectiles(engine, ship, data, amount)
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        stats.maxSpeed.unmodify(id)
        stats.acceleration.unmodify(id)
        stats.deceleration.unmodify(id)
        stats.turnAcceleration.unmodify(id)

        val ship = stats.entity as? ShipAPI ?: return
        val data = ship.customData[KEY] as? Data ?: return

        // 如果这轮系统确实开启过，那么在结束时触发“坍缩炮释放”。
        if (data.activeCycle) {
            data.activeCycle = false
            if (!ship.isHulk) {
                val engine = Global.getCombatEngine()
                if (engine != null && !engine.isPaused) {
                    onSystemEnded(engine, ship, data)
                }
            }

            // 清理：避免引用导致的泄漏（同时也防止下一轮残留）
            // 注意：onSystemEnded() 已负责恢复友方投射物；这里再兜底清一次。
            data.captured.clear()
            data.inFieldThisFrame.clear()
            data.energy = 0f
        }
    }

    override fun getStatusData(index: Int, state: ShipSystemStatsScript.State, effectLevel: Float): ShipSystemStatsScript.StatusData? {
        if (index == 0) return ShipSystemStatsScript.StatusData(I18n[I18n.Categories.MOD, "system.stasis_field.status.0"], false)
        if (index == 1) return ShipSystemStatsScript.StatusData(I18n[I18n.Categories.MOD, "system.stasis_field.status.1"], false)
        return null
    }

    private fun getOrCreateData(ship: ShipAPI): Data {
        val existing = ship.customData[KEY] as? Data
        if (existing != null) return existing
        val d = Data()
        ship.setCustomData(KEY, d)
        return d
    }

    private fun safeElapsed(engine: CombatEngineAPI): Float {
        return try {
            engine.elapsedInLastFrame.coerceIn(0f, 0.25f)
        } catch (_: Throwable) {
            0.0167f
        }
    }

    private fun pickFieldCenter(engine: CombatEngineAPI, ship: ShipAPI): Vector2f {
        // 玩家舰：优先取鼠标落点，并限制最大距离
        val isPlayer = engine.playerShip === ship
        if (isPlayer) {
            try {
                val mt = ship.mouseTarget
                if (mt != null) {
                    val dist = MathUtils.getDistance(ship.location, mt)
                    if (dist <= MAX_TARGET_RANGE_FROM_SHIP) return Vector2f(mt)
                    val ang = VectorUtils.getAngle(ship.location, mt)
                    return MathUtils.getPointOnCircumference(ship.location, MAX_TARGET_RANGE_FROM_SHIP, ang)
                }
            } catch (_: Throwable) {
                // fallthrough
            }
        }

        // AI / 无鼠标：优先选一个前方敌舰附近，否则固定放在正前方
        val forward = MathUtils.getPointOnCircumference(ship.location, MAX_TARGET_RANGE_FROM_SHIP * 0.72f, ship.facing)
        val candidates = try {
            CombatUtils.getShipsWithinRange(forward, FIELD_RADIUS * 1.2f)
        } catch (_: Throwable) {
            emptyList()
        }

        var best: ShipAPI? = null
        var bestDist = Float.MAX_VALUE
        for (s in candidates) {
            if (s.owner == ship.owner) continue
            if (s.isHulk) continue
            val d = MathUtils.getDistance(forward, s.location)
            if (d < bestDist) {
                bestDist = d
                best = s
            }
        }
        return if (best != null) Vector2f(best.location) else Vector2f(forward)
    }

    private fun spawnFieldRing(engine: CombatEngineAPI, center: Vector2f, facing: Float, level: Float) {
        val c = if (level > 0.75f) FIELD_RING_COLOR_BRIGHT else FIELD_RING_COLOR
        try {
            // 外圈
            OglEllipseRingRenderer.spawn(
                engine,
                OglEllipseRingRenderer.RingSpec(
                    center = center,
                    facing = facing,
                    aSideHalf = FIELD_RADIUS,
                    bAlongHalf = FIELD_RADIUS,
                    duration = 0.28f,
                    color = c,
                    lineWidthPx = 1.55f,
                    segments = 96,
                    expandSpeed = 18f,
                    tangentialSpeed = 0.85f,
                )
            )
            // 内圈（轻微椭圆，增加“透镜”错觉）
            OglEllipseRingRenderer.spawn(
                engine,
                OglEllipseRingRenderer.RingSpec(
                    center = center,
                    facing = facing,
                    aSideHalf = FIELD_RADIUS * 0.55f,
                    bAlongHalf = FIELD_RADIUS * 0.38f,
                    duration = 0.22f,
                    color = Color(235, 205, 255, (c.alpha * 0.65f).toInt().coerceIn(0, 255)),
                    lineWidthPx = 1.25f,
                    segments = 84,
                    expandSpeed = 22f,
                    tangentialSpeed = 1.25f,
                )
            )
        } catch (_: Throwable) {
        }
    }

    private fun applyStasisToProjectiles(engine: CombatEngineAPI, ship: ShipAPI, data: Data, amount: Float) {
        val center = data.center
        val r = FIELD_RADIUS

        data.inFieldThisFrame.clear()

        // 1) 扫描场内投射物（projectiles + missiles）
        // 说明：有些实现会让导弹只出现在 missiles 列表中；而能量弹/炮弹一般在 projectiles。
        val projList = try {
            engine.projectiles
        } catch (_: Throwable) {
            emptyList()
        }
        val missileList = try {
            engine.missiles
        } catch (_: Throwable) {
            emptyList()
        }

        fun process(p: DamagingProjectileAPI) {
            if (!safeInPlay(engine, p)) return

            // 友方是否受影响：只做减速/离场恢复；不计入蓄能、也不会在结束时被移除。
            val enemy = p.owner != ship.owner
            if (!enemy && !AFFECT_FRIENDLY_PROJECTILES) return

            // 场内判定
            val dist = try {
                MathUtils.getDistance(center, p.location)
            } catch (_: Throwable) {
                Float.MAX_VALUE
            }
            if (dist > r) return

            data.inFieldThisFrame.add(p)

            // 首次进入：记录原速度 + （若为敌方）计入能量
            val existing = data.captured[p]
            val cap = if (existing == null) {
                val ov = try {
                    Vector2f(p.velocity)
                } catch (_: Throwable) {
                    Vector2f(0f, 0f)
                }
                val dmg = if (enemy) (try {
                    p.damageAmount
                } catch (_: Throwable) {
                    0f
                }) else 0f
                val sim = try {
                    Vector2f(p.location)
                } catch (_: Throwable) {
                    Vector2f(0f, 0f)
                }
                Captured(origVel = ov, simLoc = sim, enemy = enemy, capturedDamage = dmg)
                    .also { data.captured[p] = it }
            } else {
                existing
            }

            // 需求：场内发射弹体 0.5s 免减速（不被立场减速/锁位）。
            // 判定方式：spawnLocation 在场内 + elapsed < 0.5s。
            val immune = try {
                val spawnedInField = try {
                    MathUtils.getDistance(center, p.spawnLocation) <= r
                } catch (_: Throwable) {
                    false
                }
                spawnedInField && (p.elapsed < SPAWN_IN_FIELD_IMMUNE_TIME)
            } catch (_: Throwable) {
                false
            }

            if (immune) {
                // 仍然更新 simLoc（用于“场内消失/结算时回流束”的起点）
                try {
                    val loc = p.location
                    cap.simLoc.x = loc.x
                    cap.simLoc.y = loc.y
                } catch (_: Throwable) {
                }
                return
            }

            // 每帧维持减速（近似停滞）
            // 说明：不要改 projectileSpec.moveSpeed（共享对象，会波及场外弹体与新弹）。
            // 实现方式：
            // - 非导弹：模拟位置推进（慢速）并强制写回 location（只影响场内实例）。
            // - 导弹：尽力压制 engineStats（更像“减速场”，不强行锁位置避免制导抖动）。
            try {
                if (p is MissileAPI) {
                    // engineStats 侧：尽力而为（有些版本/实现可能不支持全部字段）
                    try {
                        p.engineStats.maxSpeed.modifyMult(STASIS_MOD_ID, PROJECTILE_SPEED_MULT)
                    } catch (_: Throwable) {
                    }
                    try {
                        p.engineStats.acceleration.modifyMult(STASIS_MOD_ID, PROJECTILE_SPEED_MULT)
                    } catch (_: Throwable) {
                    }
                    try {
                        p.engineStats.deceleration.modifyMult(STASIS_MOD_ID, PROJECTILE_SPEED_MULT)
                    } catch (_: Throwable) {
                    }
                    try {
                        p.engineStats.turnAcceleration.modifyMult(STASIS_MOD_ID, PROJECTILE_SPEED_MULT)
                    } catch (_: Throwable) {
                    }
                    try {
                        p.engineStats.maxTurnRate.modifyMult(STASIS_MOD_ID, PROJECTILE_SPEED_MULT)
                    } catch (_: Throwable) {
                    }
                    // 导弹：不锁位置，只靠 engineStats 减速（保留制导/转向）。
                    return
                }

                // 非导弹：模拟“慢速前进”，然后写回位置/速度。
                val dt = amount.coerceIn(0f, 0.25f)
                cap.simLoc.x += cap.origVel.x * PROJECTILE_SPEED_MULT * dt
                cap.simLoc.y += cap.origVel.y * PROJECTILE_SPEED_MULT * dt

                try {
                    val loc = p.location
                    loc.x = cap.simLoc.x
                    loc.y = cap.simLoc.y
                } catch (_: Throwable) {
                }

                try {
                    val v = p.velocity
                    v.x = cap.origVel.x * PROJECTILE_SPEED_MULT
                    v.y = cap.origVel.y * PROJECTILE_SPEED_MULT
                } catch (_: Throwable) {
                }
            } catch (_: Throwable) {
            }
        }

        for (p in projList) process(p)
        for (m in missileList) process(m)

        // 2) 离场/消失恢复：不在场内的投射物恢复原速度，并从 tracking 中移除
        if (data.captured.isNotEmpty()) {
            val it = data.captured.entries.iterator()
            while (it.hasNext()) {
                val (p, cap) = it.next()

                // 若已不在 play 或本帧不在场内：恢复并移除
                val stillInPlay = safeInPlay(engine, p)
                val stillInField = data.inFieldThisFrame.contains(p)
                if (!stillInPlay || !stillInField) {
                    // 若敌方弹体在场内“消失/被摧毁”，视作被立场收集：生成能量束，并计入蓄能。
                    if (!stillInPlay && cap.enemy) {
                        collectEnergy(engine, ship, data, cap.simLoc, cap.capturedDamage)
                    }
                    restoreProjectileIfNeeded(p, cap)
                    it.remove()
                }
            }
        }
    }

    private fun restoreProjectileIfNeeded(p: DamagingProjectileAPI, cap: Captured) {
        // 友方/敌方都恢复速度；敌方是否会在系统结束时被 remove 由 onSystemEnded 决定。
        try {
            // 若我们在场内用 simLoc 写回过位置，这里先把位置对齐到最后的 simLoc，避免离场瞬间跳变。
            try {
                val loc = p.location
                loc.x = cap.simLoc.x
                loc.y = cap.simLoc.y
            } catch (_: Throwable) {
            }

            val v = p.velocity
            v.x = cap.origVel.x
            v.y = cap.origVel.y
        } catch (_: Throwable) {
        }

        if (p is MissileAPI) {
            try {
                p.engineStats.maxSpeed.unmodify(STASIS_MOD_ID)
            } catch (_: Throwable) {
            }
            try {
                p.engineStats.acceleration.unmodify(STASIS_MOD_ID)
            } catch (_: Throwable) {
            }
            try {
                p.engineStats.deceleration.unmodify(STASIS_MOD_ID)
            } catch (_: Throwable) {
            }
            try {
                p.engineStats.turnAcceleration.unmodify(STASIS_MOD_ID)
            } catch (_: Throwable) {
            }
            try {
                p.engineStats.maxTurnRate.unmodify(STASIS_MOD_ID)
            } catch (_: Throwable) {
            }
        }
    }

    private fun safeInPlay(engine: CombatEngineAPI, p: DamagingProjectileAPI): Boolean {
        return try {
            engine.isEntityInPlay(p)
        } catch (_: Throwable) {
            false
        }
    }

    private fun onSystemEnded(engine: CombatEngineAPI, ship: ShipAPI, data: Data) {
        // 结束时：
        // - 敌方被捕获投射物：移除（“让敌人的炮火成为我们的弹药”）
        // - 友方投射物：恢复速度并放行（否则用系统会把自己火力也吃掉，体验很怪）
        if (data.captured.isNotEmpty()) {
            for ((p, cap) in data.captured) {
                if (cap.enemy) {
                    try {
                        // 结算：敌方弹体被“收集”为能量——生成能量束并计能量。
                        collectEnergy(engine, ship, data, cap.simLoc, cap.capturedDamage)
                        restoreProjectileIfNeeded(p, cap)
                        if (engine.isEntityInPlay(p)) {
                            engine.removeEntity(p)
                        }
                    } catch (_: Throwable) {
                    }
                } else {
                    restoreProjectileIfNeeded(p, cap)
                }
            }
        }

        // 计算坍缩炮强度
        val raw = (data.energy / ENERGY_FOR_FULL_INTENSITY)
        val intensity = max(MIN_INTENSITY, raw.coerceIn(0f, 1f))

        // 坍缩炮方向：从船指向停滞场中心；若中心太近则回退到船朝向。
        val from = muzzleApprox(ship)
        val toCenter = data.center
        val facing = try {
            val d = MathUtils.getDistance(from, toCenter)
            if (d > 50f) VectorUtils.getAngle(from, toCenter) else ship.facing
        } catch (_: Throwable) {
            ship.facing
        }

        // 用内置武器发射坍缩炮（用于伤害/加成计算）。
        val hasEmitter = try {
            ship.allWeapons.any { w ->
                try {
                    w.spec?.weaponId == StasisFieldCollapseBeam.WEAPON_ID
                } catch (_: Throwable) {
                    false
                }
            }
        } catch (_: Throwable) {
            false
        }
        if (hasEmitter) {
            try {
                ship.setCustomData(
                    StasisFieldCollapseBeam.REQUEST_KEY,
                    StasisFieldCollapseBeam.Request(
                        aimFacing = facing,
                        intensity = intensity,
                        createdAt = try {
                            engine.getTotalElapsedTime(false)
                        } catch (_: Throwable) {
                            0f
                        },
                    )
                )
            } catch (_: Throwable) {
            }
        } else {
            // 不再提供回退渲染：没有 emitter 时就不触发（避免“表现与结算不一致”）。
            try {
                Global.getLogger(StasisFieldSystemStats::class.java).warn(
                    "[StasisField] collapse emitter missing on ship=${ship.hullSpec?.hullId}; skip collapse beam."
                )
            } catch (_: Throwable) {
            }
        }

        // 视觉：场中心额外一个“收束环”，表现能量塌缩到束线上
        try {
            OglEllipseRingRenderer.spawn(
                engine,
                OglEllipseRingRenderer.RingSpec(
                    center = Vector2f(toCenter),
                    facing = facing,
                    aSideHalf = FIELD_RADIUS * 0.72f,
                    bAlongHalf = FIELD_RADIUS * 0.48f,
                    duration = 0.42f,
                    color = Color(255, 220, 255, (140f * intensity).toInt().coerceIn(0, 255)),
                    lineWidthPx = 1.85f,
                    segments = 96,
                    expandSpeed = 65f,
                    tangentialSpeed = 2.10f,
                )
            )
        } catch (_: Throwable) {
        }

        // 伤害：由坍缩炮内置 beam 武器负责（原版 beam 机制 + 武器/船体加成）。
    }

    private fun muzzleApprox(ship: ShipAPI): Vector2f {
        val r = try {
            ship.collisionRadius
        } catch (_: Throwable) {
            0f
        }
        return MathUtils.getPointOnCircumference(ship.location, max(20f, r * 0.55f), ship.facing)
    }

    private fun findClosestEnemyShipNear(engine: CombatEngineAPI, ship: ShipAPI, center: Vector2f, range: Float): ShipAPI? {
        val ships = try {
            CombatUtils.getShipsWithinRange(center, range)
        } catch (_: Throwable) {
            emptyList()
        }
        var best: ShipAPI? = null
        var bestDist = Float.MAX_VALUE
        for (s in ships) {
            if (s.owner == ship.owner) continue
            if (s.isHulk) continue

            // 排除相位态：相位单位在视觉上可能仍可见，但不应被此 MVP 直接命中
            val phased = try {
                s.isPhased
            } catch (_: Throwable) {
                false
            }
            if (phased) continue

            val d = MathUtils.getDistance(center, s.location)
            if (d < bestDist) {
                bestDist = d
                best = s
            }
        }
        return best
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun collectEnergy(engine: CombatEngineAPI, ship: ShipAPI, data: Data, from: Vector2f, damage: Float) {
        val dmg = max(0f, damage)
        if (dmg <= 0f) return

        // 计能量：只在“弹体被收集/结算”时累积（而不是入场就累积），避免飞出场外也白嫖能量。
        data.energy += dmg

        // 视觉：能量束回流到视界线（用船前方作为“汇聚点”更直观）。
        val to = muzzleApprox(ship)
        val s = (dmg / ENERGY_FOR_FULL_INTENSITY).coerceIn(0.05f, 1f)
        try {
            StasisFieldEnergySiphonVfx.spawn(engine, from, to, s)
        } catch (_: Throwable) {
        }
    }
}
