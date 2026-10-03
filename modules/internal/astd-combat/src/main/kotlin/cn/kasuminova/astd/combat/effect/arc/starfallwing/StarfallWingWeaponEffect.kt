package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingOnFireEffect.Companion.projectileStates
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingOnFireEffect.ProjectileState
import cn.kasuminova.astd.combat.effect.generic.CombatVfxBootstrap
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.ceil

/**
 * 坠星残翼的武器级每帧效果（挂 `.wpn` 的 `everyFrameEffect`）：主弹脚本碰撞结算中枢。
 *
 * 主弹的 collisionClass 为 NONE（原版触碰结算全关），以下判定全部由本插件承担；
 * 子射弹已回归原版导弹口径（collisionClass=MISSILE_NO_FF，撞盾/撞船体/消散全走原版，
 * 撞盾附加振频适应用 spec 的 onHitEffect，见 [StarfallWingMoteOnHitEffect]），不经此路：
 * - 穿透结算（目标面 = 全部敌对实体，**含相位中的舰船**）：敌舰（含 hulk 残骸与战机）、
 *   敌方导弹（engine.missiles）、中立陨石（engine.asteroids）（均按 owner 过滤友军）。
 *   接触沿弹体扫掠路径逐帧判定（上一帧→当前位置按 20su 采样，0.1s 拍内 1500su/s 弹速
 *   位移 150su，点判/拍边界判都会隧穿）；舰舰判定半径 = 弹体碰撞半径（仅判定口径，
 *   不动任何渲染参数；实机判例：×2 放大接触面过大，已回滚 1x）。
 *   - 护盾（仅舰船）：主弹**恒穿盾**——接触且穿透拍到期（[StarfallWingTuning.PIERCE_TICK_SECONDS]
 *     秒一拍，首触补拍按目标闩锁：率限窗内切换目标时新目标仍补拍）时对护盾结算 20% 面板
 *     + 20% EMP 面板。
 *   - 船体/装甲：拍到期时对每个接触目标**一次** applyDamage（[settleHullPierce]），
 *     落点 = 射弹当前位置，伤害 = 20% 面板 + 20% EMP 面板，bypassShields=true。
 *     接触口径 = 采样点在碰撞箱多边形内（深内部位穿越）或距边界段 ≤ 半径+余量（贴面）。
 *     装甲格分摊交给原版 applyDamage 内部装甲池机制：射弹高速穿过舰体时沿途各帧落点不同，
 *     装甲/结构伤害自然分摊到内部格子。**不要再手动遍历装甲格逐格结算**——逐格 applyDamage
 *     会让每格都吃一整拍伤害，伤害量级随格数爆炸（实机判例：8c91b09 全格口径浮字上万，
 *     参考实现 PLSP_WeaponPlugin.UniversalByPassPlugin 同为单点口径）。
 *   - 导弹/陨石：无护盾无装甲，碰撞圈接触按表面接触点结算一次 20% 面板 + EMP
 *     （单穿越一次，[PiercePassTracker.trySettleOnce] 闩锁）。
 * - 振频适应附加：每枚主弹全局闩锁——首个接触目标附加 1 层后，该弹后续对任何目标
 *   都不再附加（[PiercePassTracker.tryLatchAdaptation]）。
 * - 子射弹散发：主弹飞行中每 [StarfallWingTuning.MOTE_INTERVAL_SECONDS]s 向两侧随机
 *   散发一枚追踪子射弹（真实 MissileAPI + [StarfallWingMoteAi]），伤害 = 主弹当前面板 ×20%。
 * - 代行 VFX bootstrap（`.wpn` 只有一个 everyFrame 槽，本武器独占）与子射弹分裂光斑推进。
 *
 * applyDamage 落点口径（坠星残响同款判例注记）：盾覆盖 → 盾面落点 + bypass=false；
 * 穿船体 → 射弹当前位置 + bypass=true（只跳过引擎的护盾弧判定，装甲池结算照走）。
 */
class StarfallWingWeaponEffect : EveryFrameWeaponEffectPlugin {

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        CombatVfxBootstrap.ensureInstalled(engine)
        StarfallWingVfx.advance(engine, amount)

        val states = projectileStates(engine)
        for ((proj, state) in states.entries.toList()) {
            if (state.ownerWeapon !== weapon) continue
            if (!engine.isEntityInPlay(proj)) {
                states.remove(proj)
                continue
            }
            advanceProjectile(engine, weapon, proj, state, amount)
        }
    }

    /** 单枚主弹的逐帧推进：穿透扫掠结算 + 子射弹散发节拍。 */
    private fun advanceProjectile(
        engine: CombatEngineAPI,
        weapon: WeaponAPI,
        proj: DamagingProjectileAPI,
        state: ProjectileState,
        amount: Float,
    ) {
        state.pierceTimer += amount
        val sweepFrom = state.lastPierceLocation ?: Vector2f(proj.location)
        val sweepTo = Vector2f(proj.location)
        state.lastPierceLocation = sweepTo
        // 接触逐帧判定；穿透伤害 0.1s 拍率限，首触补拍按目标闩锁（passContacts，
        // 见 pierceSweep 注记）——全局闩锁在率限窗内换目标时会漏掉新目标的首触拍。
        val contacted = Collections.newSetFromMap<CombatEntityAPI>(IdentityHashMap())
        if (pierceSweep(engine, proj, state, sweepFrom, sweepTo, contacted)) {
            state.pierceTimer = 0f
        }
        // 帧末清理穿越闩锁：本帧脱离接触的目标整项移除，下次接触算新穿越
        state.passContacts.retainContacts(contacted)

        state.moteTimer += amount
        while (state.moteTimer >= StarfallWingTuning.MOTE_INTERVAL_SECONDS) {
            state.moteTimer -= StarfallWingTuning.MOTE_INTERVAL_SECONDS
            spawnMote(engine, weapon, proj)
        }
    }

    /**
     * 扫掠段接触判定：沿段采样判定所有敌对实体的接触，本帧接触到的目标记入 [contactedTargets]。
     * 舰船：护盾接触优先（盾覆盖即走护盾结算，本帧不再结算船体）；未触盾且触到船体
     * （贴面或深内部位，[touchesHull]）时，拍到期对该舰单点结算一次（[settleHullPierce]，
     * 落点 = 射弹当前位置）。导弹/陨石：碰撞圈接触，按表面接触点一次性结算。穿透伤害全局 0.1s 拍率限（[ProjectileState.pierceTimer]），
     * 目标上帧未接触（[PiercePassTracker.isFirstContact]）时首触补拍——率限窗内弹体从 A 舰
     * 切换到 B 舰时 B 仍吃首触拍，高速弹不会整段穿越一艘船零结算（审查判例）。
     * @return true = 本帧有伤害结算（穿透节拍清零）。
     */
    private fun pierceSweep(
        engine: CombatEngineAPI,
        proj: DamagingProjectileAPI,
        state: ProjectileState,
        from: Vector2f,
        to: Vector2f,
        contactedTargets: MutableSet<CombatEntityAPI>,
    ): Boolean {
        val source = proj.source
        val owner = source?.owner ?: 0
        // 舰舰判定半径 = 弹体碰撞半径（实机判例：×2 放大接触面过大，已回滚 1x）；导弹/陨石同径
        val projRadius = proj.collisionRadius
        val shipTouchRadius = projRadius
        val swept = MathUtils.getDistance(from, to)
        val samples = ceil(swept / SWEEP_SAMPLE_SPACING).toInt().coerceAtLeast(1)
        val tickDueGlobal = state.pierceTimer >= StarfallWingTuning.PIERCE_TICK_SECONDS
        val midX = (from.x + to.x) * 0.5f
        val midY = (from.y + to.y) * 0.5f

        var damaged = false

        // —— 敌舰（含 hulk 残骸、战机与相位中的目标）——
        for (candidate in engine.ships) {
            val ship = candidate as? ShipAPI ?: continue
            if (ship === source || ship.owner == owner) continue
            if (!ship.isAlive && !ship.isHulk) continue
            // 粗筛：舰心到扫掠段中点超过 最大接触半径 + 半程 + 判定半径 时不可能接触
            val shieldRadius = ship.shield?.radius ?: 0f
            val reach = maxOf(shieldRadius, ship.collisionRadius) + swept * 0.5f + shipTouchRadius + CONTACT_MARGIN
            val mdx = ship.location.x - midX
            val mdy = ship.location.y - midY
            if (mdx * mdx + mdy * mdy > reach * reach) continue

            // 碰撞箱姿态每舰每帧只刷新一次（采样循环逐点复用，勿逐采样点重复 update）；
            // 边界段成对快照同频刷新（touchesHull 的内部点/贴面判定复用，勿逐采样点重建）
            val bounds = ship.exactBounds
            bounds?.update(ship.location, ship.facing)
            val hullSegments = bounds?.segments?.map { Vector2f(it.p1) to Vector2f(it.p2) }

            // 逐采样点：护盾接触优先（首个盾覆盖点即停，走护盾结算）；否则记录首个船体接触点
            var shieldContact: Vector2f? = null
            var hullContact: Vector2f? = null
            for (i in 0..samples) {
                val t = i / samples.toFloat()
                val p = Vector2f(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
                if (shieldCoversAt(ship, p, shipTouchRadius)) {
                    shieldContact = p
                    break
                }
                if (hullContact == null && touchesHull(ship, p, shipTouchRadius, hullSegments)) hullContact = p
            }
            if (shieldContact == null && hullContact == null) continue
            contactedTargets.add(ship)
            val firstContact = state.passContacts.isFirstContact(ship)
            state.passContacts.touch(ship)
            // 穿透伤害拍级判定：全局拍到点，或该目标上帧未接触（首触补拍）
            val tickDue = tickDueGlobal || firstContact

            if (shieldContact != null) {
                if (resolveShieldContact(engine, proj, state, ship, shieldContact, tickDue)) damaged = true
            } else if (tickDue) {
                // 单点穿透结算：拍到期对该舰结算一次 20% 面板 + EMP，落点 = 射弹当前位置
                settleHullPierce(engine, proj, state, ship)
                damaged = true
            }
        }

        // —— 敌方导弹与中立陨石：无护盾无装甲格，碰撞圈接触按表面接触点一次性结算 ——
        for (missile in engine.missiles) {
            if (missile === proj || missile.owner == owner || missile.isExpired) continue
            if (pierceSimpleTarget(engine, proj, state, missile, from, to, midX, midY, swept, projRadius, contactedTargets)) {
                damaged = true
            }
        }
        for (asteroid in engine.asteroids) {
            if (asteroid.owner == owner || !engine.isEntityInPlay(asteroid)) continue
            if (pierceSimpleTarget(engine, proj, state, asteroid, from, to, midX, midY, swept, projRadius, contactedTargets)) {
                damaged = true
            }
        }

        return damaged
    }

    /**
     * 船体穿透单点结算：拍到期对目标结算一次 20% 面板 + 20% EMP 面板
     * （[StarfallWingTuning.pierceTickDamage] / [StarfallWingTuning.pierceTickEmp]），
     * 落点 = 射弹当前位置（UniversalByPassPlugin 同款口径）。
     *
     * 装甲格分摊由原版 applyDamage 的装甲池机制承担：射弹高速穿体时各帧落点不同，伤害
     * 自然分摊到沿途格子——不手动遍历装甲格（逐格 applyDamage 会让每格各吃一整拍，
     * 伤害随格数爆炸，实机判例见类头注记）。
     */
    internal fun settleHullPierce(
        engine: CombatEngineAPI,
        proj: DamagingProjectileAPI,
        state: ProjectileState,
        ship: ShipAPI,
    ) {
        engine.applyDamage(
            ship, Vector2f(proj.location), StarfallWingTuning.pierceTickDamage(proj.damageAmount),
            DamageType.ENERGY, StarfallWingTuning.pierceTickEmp(proj.empAmount),
            true, false, proj.source, true,
        )
        attachAdaptationOnce(engine, proj, state, ship)
    }

    /**
     * 非舰船目标（导弹/陨石）的接触结算：碰撞圈与扫掠段相交即接触，落点 = 表面接触点。
     * 单次穿越只结算一次 20% 面板 + EMP（[PiercePassTracker.trySettleOnce] 闩锁）。
     * @return true = 本帧发生了结算。
     */
    internal fun pierceSimpleTarget(
        engine: CombatEngineAPI,
        proj: DamagingProjectileAPI,
        state: ProjectileState,
        target: CombatEntityAPI,
        from: Vector2f,
        to: Vector2f,
        midX: Float,
        midY: Float,
        swept: Float,
        projRadius: Float,
        contactedTargets: MutableSet<CombatEntityAPI>,
    ): Boolean {
        // 粗筛：目标心到扫掠段中点超过 碰撞半径 + 半程 + 弹体半径 时不可能接触
        val reach = target.collisionRadius + swept * 0.5f + projRadius + CONTACT_MARGIN
        val mdx = target.location.x - midX
        val mdy = target.location.y - midY
        if (mdx * mdx + mdy * mdy > reach * reach) return false
        if (StarfallWingTuning.distanceToSegment(target.location, from, to) > target.collisionRadius + projRadius) {
            return false
        }
        contactedTargets.add(target)
        if (!state.passContacts.trySettleOnce(target)) return false
        val contact = surfaceContactPoint(target.location, target.collisionRadius, from, to)
        engine.applyDamage(
            target, contact, StarfallWingTuning.pierceTickDamage(proj.damageAmount),
            DamageType.ENERGY, StarfallWingTuning.pierceTickEmp(proj.empAmount),
            false, false, proj.source, true,
        )
        return true
    }

    /**
     * 护盾接触结算：主弹恒穿盾——到期拍（含首触补拍）结算 20% 面板 + 20% EMP 面板，
     * 并经全局闩锁附加 1 层振频适应。
     * @return true = 本帧发生了结算（到期拍）。
     */
    internal fun resolveShieldContact(
        engine: CombatEngineAPI,
        proj: DamagingProjectileAPI,
        state: ProjectileState,
        ship: ShipAPI,
        contactPoint: Vector2f,
        tickDue: Boolean,
    ): Boolean {
        // 主弹穿盾伤害 0.1s 拍率限（首触补拍由调用侧并入 tickDue）
        if (!tickDue) return false
        val damagePoint = shieldSurfacePoint(ship, contactPoint)
        engine.applyDamage(
            ship, damagePoint, StarfallWingTuning.pierceTickDamage(proj.damageAmount),
            DamageType.ENERGY, StarfallWingTuning.pierceTickEmp(proj.empAmount),
            false, false, proj.source, true,
        )
        attachAdaptationOnce(engine, proj, state, ship)
        spawnShieldSpark(engine, contactPoint)
        return true
    }

    /** 散发一枚追踪子射弹（主弹两侧随机 ±90°；脚本 spawn 不触发 onFireEffect，VFX 显式 track）。 */
    private fun spawnMote(engine: CombatEngineAPI, weapon: WeaponAPI, proj: DamagingProjectileAPI) {
        val source = weapon.ship ?: proj.source ?: return
        val sideSign = if (MathUtils.getRandomNumberInRange(0f, 1f) < 0.5f) 1f else -1f
        val dir = proj.facing + 90f * sideSign
        val launchVel = MathUtils.getPointOnCircumference(
            Vector2f(proj.velocity), StarfallWingTuning.MOTE_SIDE_SPEED, dir,
        )
        val spawned = engine.spawnProjectile(
            source, null, StarfallWingTuning.MOTE_WEAPON_ID, Vector2f(proj.location), dir, launchVel,
        ) as? MissileAPI
        if (spawned == null) {
            log.warn("[ASTD] 坠星残翼子射弹生成失败：spawnProjectile 未产出 MissileAPI: weapon=${StarfallWingTuning.MOTE_WEAPON_ID}")
            return
        }
        spawned.source = source
        spawned.missileAI = StarfallWingMoteAi(
            spawned, StarfallWingMoteAi.nearestEnemyShip(engine, source, spawned.location, MOTE_INITIAL_TARGET_RANGE),
        )
        spawned.empResistance = 10000
        spawned.armingTime = 0f
        spawned.damageAmount = StarfallWingTuning.moteDamage(proj.damageAmount)
        spawned.maxFlightTime = MOTE_FLIGHT_TIME
        spawned.setNoGlowTime(999f)
        spawned.isNoFlameoutOnFizzling = true
        spawned.interruptContrail()
        spawned.spriteAlphaOverride = 0f
        spawned.glowRadius = 0f
        ProjectileVfxDriverPlugin.track(engine, spawned, StarfallWingTuning.MOTE_SPEC_ID)
        StarfallWingVfx.spawnMoteSplitFlare(engine, Vector2f(spawned.location))
    }

    /**
     * 主弹振频适应全局闩锁附加：每枚主弹只对首个接触目标附加一次 1 层，
     * 闩锁后该弹对任何目标都不再附加；子射弹走原版 onHit 钩子，不经此路。
     */
    private fun attachAdaptationOnce(engine: CombatEngineAPI, proj: DamagingProjectileAPI, state: ProjectileState, ship: ShipAPI) {
        if (!state.passContacts.tryLatchAdaptation()) return
        StarfallWingAdaptationStacks.attachStacks(ship, engine, 1f, proj.source)
    }

    /**
     * 采样点护盾覆盖判定：盾开启、点在盾半径内（含判定半径余量）且在盾弧内。
     * （坠星残响 shieldCovers 同型注记：盾开 + isWithinArc；此处加半径接触项——穿透弹逐点采样。）
     */
    private fun shieldCoversAt(ship: ShipAPI, point: Vector2f, touchRadius: Float): Boolean {
        val shield = ship.shield ?: return false
        if (!shield.isOn) return false
        if (MathUtils.getDistance(point, ship.location) > shield.radius + touchRadius) return false
        return shield.isWithinArc(point)
    }

    /**
     * 采样点船体接触判定（touchesHull）：采样点在碰撞箱多边形**内**（深内部位穿越，
     * [StarfallWingTuning.pointInPolygon]）或距最近边界段 ≤ 判定半径 + 余量（贴面）即接触；
     * 无碰撞箱时按碰撞圈近似（粗筛已通过，圈面接触即算贴面）。
     * 内部点口径是中段漏拍闸门修复：只算贴面时射弹穿越大舰中段深内部位不算接触，
     * 到期拍被跳过（巡洋舰约漏 1 拍，空间站级漏多拍，审查判例）。
     * [hullSegments] = 已按当前舰位/朝向刷新过的碰撞箱边界段（调用方每舰每帧只 update 一次）。
     */
    private fun touchesHull(ship: ShipAPI, point: Vector2f, touchRadius: Float, hullSegments: List<Pair<Vector2f, Vector2f>>?): Boolean {
        val limit = touchRadius + CONTACT_MARGIN
        if (hullSegments == null) {
            return MathUtils.getDistance(point, ship.location) <= ship.collisionRadius + limit
        }
        if (StarfallWingTuning.pointInPolygon(point, hullSegments)) return true
        for ((a, b) in hullSegments) {
            if (StarfallWingTuning.distanceToSegment(point, a, b) <= limit) return true
        }
        return false
    }

    /**
     * 圆形/贴面目标的表面接触点：扫掠段上距目标心最近点；最近点在目标外则压回圈面，
     * 穿心（距离≈0）时最近点即接触点。
     */
    private fun surfaceContactPoint(center: Vector2f, radius: Float, from: Vector2f, to: Vector2f): Vector2f {
        val closest = StarfallWingTuning.closestPointOnSegment(center, from, to)
        val dx = closest.x - center.x
        val dy = closest.y - center.y
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        if (dist <= radius || dist <= 1e-3f) return closest
        val scale = radius / dist
        return Vector2f(center.x + dx * scale, center.y + dy * scale)
    }

    /**
     * 舰船护盾伤害落点（统一爆炸入口 [cn.kasuminova.astd.api.combat.ExplosionStrike]
     * 盾面点同款算法注记）：盾面落点 = 舰心沿命中方向外推盾半径；落点仅影响装甲格
     * 选择与浮字位置，不影响伤害量。本函数为扫掠接触语境专用，不走统一入口。
     */
    private fun shieldSurfacePoint(ship: ShipAPI, contactPoint: Vector2f): Vector2f {
        val shield = ship.shield ?: return Vector2f(ship.location)
        val radius = shield.radius
        if (radius <= 0f) return Vector2f(ship.location)
        // 不取 Misc.getAngleInDegrees：Misc 类初始化依赖游戏运行时（无头/单测直接
        // ExceptionInInitializerError），此处语义等价于 atan2 直出角度，就地计算
        val angle = Math.toDegrees(
            kotlin.math.atan2(
                (contactPoint.y - ship.location.y).toDouble(),
                (contactPoint.x - ship.location.x).toDouble(),
            ),
        ).toFloat()
        return MathUtils.getPointOnCircumference(ship.location, radius, angle)
    }

    /** 护盾命中火花：两条同色小电弧（紫），命中视觉最小集。 */
    private fun spawnShieldSpark(engine: CombatEngineAPI, point: Vector2f) {
        repeat(2) {
            val dir = MathUtils.getRandomNumberInRange(0f, 360f)
            val to = MathUtils.getPointOnCircumference(
                point, MathUtils.getRandomNumberInRange(24f, 56f), dir,
            )
            engine.spawnEmpArcVisual(
                point, null, to, null,
                MathUtils.getRandomNumberInRange(3f, 5f),
                Color(170, 110, 255, 210), Color(240, 225, 255, 235),
            ).setFadedOutAtStart(true)
        }
    }

    companion object {
        /** 扫掠采样间距（su）：弹体宽度 34 的一半量级，兼顾隧穿与开销。 */
        private const val SWEEP_SAMPLE_SPACING = 20f

        /** 接触余量（su）：碰撞箱边界段判定的贴面宽容度。 */
        private const val CONTACT_MARGIN = 4f

        /** 子射弹初目标搜索半径（su）。 */
        private const val MOTE_INITIAL_TARGET_RANGE = 1400f

        /** 子射弹最大飞行时间（秒，规格 .proj 口径）。 */
        private const val MOTE_FLIGHT_TIME = 2.0f

        private val log = AstdLog.logger
    }
}
