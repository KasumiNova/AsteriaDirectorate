package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.api.buff.buffHost
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
 * 坠星残翼的武器级每帧效果（挂 `.wpn` 的 `everyFrameEffect`）：脚本碰撞结算中枢。
 *
 * 主弹与子射弹的 collisionClass 均为 NONE（原版触碰结算全关），以下判定全部由本插件承担：
 * - 穿透结算（目标面 = 全部敌对实体）：敌舰（含 hulk 残骸与战机）、敌方导弹（engine.missiles）、
 *   中立陨石（engine.asteroids）（均按 owner 过滤友军）。接触沿弹体扫掠路径逐帧判定
 *   （上一帧→当前位置按 20su 采样，0.2s 拍内 1500su/s 弹速位移 300su，点判/拍边界判都会隧穿）。
 *   - 护盾（仅舰船）：接触且目标「振频适应」层数 ≤ [StarfallWingTuning.PIERCE_SHIELD_STACK_THRESHOLD]
 *     时全额面板结算 + 1 层 + 弹体移除（阻挡属碰撞事件不吃拍率限）；层数超限穿盾，
 *     每 [StarfallWingTuning.PIERCE_TICK_SECONDS]s 拍结算 10% 面板——穿盾拍率限不变，
 *     首触补拍按目标闩锁（率限窗内切换目标时新目标仍补拍）。
 *   - 船体/装甲：按扫掠路径**逐装甲格**结算——采样点映射到的每个装甲格在单次穿越中
 *     各结算一次 10% 面板（[PiercePassTracker] 按目标闩锁已结算格，脱离接触一帧即重置），
 *     落点 = 格内表面接触点（applyDamage 落点自动走原版装甲格结算，不再舰心固定点）。
 *   - 导弹/陨石：无护盾无装甲格，按表面接触点结算一次 10% 面板（单穿越一次）。
 * - 子射弹散发：主弹飞行中每 [StarfallWingTuning.MOTE_INTERVAL_SECONDS]s 向两侧随机
 *   散发一枚追踪子射弹（真实 MissileAPI + [StarfallWingMoteAi]），伤害 = 主弹当前面板 ×20%；
 *   子射弹命中护盾结算全额 + 0.5 层并消散（不继承穿盾增益），穿船体同主弹口径。
 * - 代行 VFX bootstrap（`.wpn` 只有一个 everyFrame 槽，本武器独占）与子射弹分裂光斑推进。
 *
 * applyDamage 落点口径（坠星残响同款判例注记）：盾覆盖 → 盾面落点 + bypass=false；
 * 穿船体 → 格内表面接触点 + bypass=true（只跳过引擎的护盾弧判定，装甲格结算照走；
 * ArmorGrid.getCellAtLocation 与 ArmorGrid.applyDamage 的落点→格映射同源同式，已核对反编译源码）。
 */
class StarfallWingWeaponEffect : EveryFrameWeaponEffectPlugin {

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        CombatVfxBootstrap.ensureInstalled(engine)
        StarfallWingVfx.advance(engine, amount)

        val states = projectileStates(engine)
        // 快照迭代：子射弹散发（spawnMote）会向同一张表登记新条目，直接迭代会抛
        // ConcurrentModificationException（实机判例：WeaponGroup.advance 链路内崩战斗）。
        for ((proj, state) in states.entries.toList()) {
            if (state.ownerWeapon !== weapon) continue
            if (!engine.isEntityInPlay(proj)) {
                states.remove(proj)
                continue
            }
            // 弹体被护盾阻挡时 advanceProjectile 内已 removeEntity，此处同步摘表
            if (advanceProjectile(engine, weapon, proj, state, amount)) states.remove(proj)
        }
    }

    /**
     * @return true = 弹体已被移除（护盾阻挡），调用方停止后续推进并摘表。
     */
    private fun advanceProjectile(
        engine: CombatEngineAPI,
        weapon: WeaponAPI,
        proj: DamagingProjectileAPI,
        state: ProjectileState,
        amount: Float,
    ): Boolean {
        state.pierceTimer += amount
        val sweepFrom = state.lastPierceLocation ?: Vector2f(proj.location)
        val sweepTo = Vector2f(proj.location)
        state.lastPierceLocation = sweepTo
        // 接触逐帧判定；穿盾伤害 0.2s 拍率限，首触补拍按目标闩锁（passContacts，
        // 见 pierceSweep 注记）——全局闩锁在率限窗内换目标时会漏掉新目标的首触拍。
        val contacted = Collections.newSetFromMap<CombatEntityAPI>(IdentityHashMap())
        when (pierceSweep(engine, proj, state, sweepFrom, sweepTo, contacted)) {
            PierceOutcome.BLOCKED -> return true
            PierceOutcome.DAMAGED -> state.pierceTimer = 0f
            else -> {}
        }
        // 帧末清理穿越闩锁：本帧脱离接触的目标整项移除，下次接触算新穿越（装甲格重新可结算）
        state.passContacts.retainContacts(contacted)

        if (!state.isMote) {
            state.moteTimer += amount
            while (state.moteTimer >= StarfallWingTuning.MOTE_INTERVAL_SECONDS) {
                state.moteTimer -= StarfallWingTuning.MOTE_INTERVAL_SECONDS
                spawnMote(engine, weapon, proj)
            }
        }
        return false
    }

    private enum class PierceOutcome { NONE, CONTACT, DAMAGED, BLOCKED }

    /**
     * 扫掠段接触判定：沿段采样判定所有敌对实体的接触，本帧接触到的目标记入 [contactedTargets]。
     * 舰船：护盾接触优先（盾覆盖即走护盾结算，本帧不再结算船体格）；未触盾时采样点逐个映射
     * 装甲格，本穿越未结算的格各结算一次（格内表面接触点落点）。导弹/陨石：碰撞圈接触，
     * 按表面接触点一次性结算。穿盾伤害全局 0.2s 拍率限（[ProjectileState.pierceTimer]），
     * 目标上帧未接触（[PiercePassTracker.isFirstContact]）时首触补拍——率限窗内弹体从 A 舰
     * 切换到 B 舰时 B 仍吃首触拍，高速弹不会整段穿越一艘船零结算（审查判例）。
     * 护盾阻挡属碰撞事件不吃拍率限。
     * @return BLOCKED = 弹体已被移除（护盾阻挡/子射弹撞盾），调用方停止后续推进；
     *   DAMAGED = 本帧有伤害结算（穿透节拍清零）。
     */
    private fun pierceSweep(
        engine: CombatEngineAPI,
        proj: DamagingProjectileAPI,
        state: ProjectileState,
        from: Vector2f,
        to: Vector2f,
        contactedTargets: MutableSet<CombatEntityAPI>,
    ): PierceOutcome {
        val source = proj.source
        val owner = source?.owner ?: 0
        val projRadius = proj.collisionRadius
        val swept = MathUtils.getDistance(from, to)
        val samples = ceil(swept / SWEEP_SAMPLE_SPACING).toInt().coerceAtLeast(1)
        val tickDueGlobal = state.pierceTimer >= StarfallWingTuning.PIERCE_TICK_SECONDS
        val midX = (from.x + to.x) * 0.5f
        val midY = (from.y + to.y) * 0.5f

        var damaged = false

        // —— 敌舰（含 hulk 残骸与战机；相位中的目标不可触及）——
        for (candidate in engine.ships) {
            val ship = candidate as? ShipAPI ?: continue
            if (ship === source || ship.owner == owner) continue
            if ((!ship.isAlive && !ship.isHulk) || ship.isPhased) continue
            // 粗筛：舰心到扫掠段中点超过 最大接触半径 + 半程 + 弹体半径 时不可能接触
            val shieldRadius = ship.shield?.radius ?: 0f
            val reach = maxOf(shieldRadius, ship.collisionRadius) + swept * 0.5f + projRadius + CONTACT_MARGIN
            val mdx = ship.location.x - midX
            val mdy = ship.location.y - midY
            if (mdx * mdx + mdy * mdy > reach * reach) continue

            // 逐采样点：护盾接触优先（首个盾覆盖点即停，走护盾结算）；未触盾的采样点
            // 映射装甲格并按格号帧内去重（格号 → 格内表面接触点）
            var shieldContact: Vector2f? = null
            val cellHits = LinkedHashMap<Int, Vector2f>(4)
            for (i in 0..samples) {
                val t = i / samples.toFloat()
                val p = Vector2f(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
                if (shieldCoversAt(ship, p, projRadius)) {
                    shieldContact = p
                    break
                }
                val surface = closestHullSurfacePoint(ship, p, projRadius) ?: continue
                val cell = ship.armorGrid?.getCellAtLocation(surface) ?: continue
                cellHits.putIfAbsent(state.passContacts.packCell(cell[0], cell[1]), surface)
            }
            if (shieldContact == null && cellHits.isEmpty()) continue
            contactedTargets.add(ship)
            val firstContact = state.passContacts.isFirstContact(ship)
            state.passContacts.touch(ship)

            if (shieldContact != null) {
                // 穿透伤害拍级判定：全局拍到点，或该目标上帧未接触（首触补拍）
                val tickDue = tickDueGlobal || firstContact
                val stacks = ship.starfallWingAdaptationStacks()?.stacks ?: 0f
                val pierce = !state.isMote && StarfallWingTuning.piercesShields(stacks)
                if (pierce && !tickDue) continue
                // 护盾阻挡属碰撞事件不吃拍率限；穿盾伤害只在到期拍结算
                if (resolveShieldContact(engine, proj, state, ship, shieldContact)) {
                    return PierceOutcome.BLOCKED
                }
                damaged = true
            } else {
                // 装甲格逐格结算：本穿越未结算的格各结算一次 10% 面板，落点 = 格内表面接触点
                for ((packed, point) in cellHits) {
                    if (!state.passContacts.trySettleCell(ship, packed)) continue
                    engine.applyDamage(
                        ship, point, StarfallWingTuning.pierceTickDamage(proj.damageAmount),
                        DamageType.ENERGY, 0f,
                        true, false, proj.source, true,
                    )
                    damaged = true
                }
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

        return when {
            damaged -> PierceOutcome.DAMAGED
            contactedTargets.isNotEmpty() -> PierceOutcome.CONTACT
            else -> PierceOutcome.NONE
        }
    }

    /**
     * 非舰船目标（导弹/陨石）的穿透结算：碰撞圈与扫掠段相交即接触，
     * 单次穿越只结算一次 10% 面板（[PiercePassTracker.trySettleOnce] 闩锁），落点 = 表面接触点。
     * @return true = 本帧发生了结算。
     */
    private fun pierceSimpleTarget(
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
            DamageType.ENERGY, 0f,
            false, false, proj.source, true,
        )
        return true
    }

    /**
     * 护盾接触结算。
     * @return true = 弹体移除（阻挡/撞盾）。
     */
    private fun resolveShieldContact(
        engine: CombatEngineAPI,
        proj: DamagingProjectileAPI,
        state: ProjectileState,
        ship: ShipAPI,
        contactPoint: Vector2f,
    ): Boolean {
        val stacks = ship.starfallWingAdaptationStacks()?.stacks ?: 0f
        val pierce = !state.isMote && StarfallWingTuning.piercesShields(stacks)
        val damagePoint = shieldSurfacePoint(ship, contactPoint)
        val damage = if (pierce) StarfallWingTuning.pierceTickDamage(proj.damageAmount) else proj.damageAmount
        engine.applyDamage(
            ship, damagePoint, damage,
            DamageType.ENERGY, 0f,
            false, false, proj.source, true,
        )
        addAdaptation(engine, proj, ship, if (state.isMote) StarfallWingTuning.MOTE_STACKS_ON_SHIELD else 1f)
        spawnShieldSpark(engine, contactPoint)
        if (pierce) return false
        engine.removeEntity(proj)
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
        projectileStates(engine)[spawned] = ProjectileState(
            ownerWeapon = weapon, isMote = true, lastPierceLocation = Vector2f(spawned.location),
        )
    }

    /** 振频适应叠层（不存在即创建注册）；攻击方为玩家船时打开目标侧 HUD。 */
    private fun addAdaptation(engine: CombatEngineAPI, proj: DamagingProjectileAPI, ship: ShipAPI, stacks: Float) {
        val host = ship.buffHost()
        val buff = host.find(StarfallWingAdaptationStacks.BUFF_ID) as? StarfallWingAdaptationStacks
            ?: StarfallWingAdaptationStacks(ship, engine, host).also { host.register(it) }
        buff.addStacks(stacks)
        if (proj.source != null && proj.source == engine.playerShip) buff.showOnPlayerHud = true
    }

    /**
     * 采样点护盾覆盖判定：盾开启、点在盾半径内（含弹体半径余量）且在盾弧内。
     * （坠星残响 shieldCovers 同型注记：盾开 + isWithinArc；此处加半径接触项——穿透弹逐点采样。）
     */
    private fun shieldCoversAt(ship: ShipAPI, point: Vector2f, projRadius: Float): Boolean {
        val shield = ship.shield ?: return false
        if (!shield.isOn) return false
        if (MathUtils.getDistance(point, ship.location) > shield.radius + projRadius) return false
        return shield.isWithinArc(point)
    }

    /**
     * 采样点贴面船体接触点：舰体真实碰撞箱边界段上离采样点最近的点，
     * 距离 ≤ 弹体半径 + 余量时视为接触并返回该表面点（装甲格落点的真相来源）；
     * 无碰撞箱时按碰撞圈近似（表面点 = 舰心方向压回圈面），粗筛已通过故恒返回非空。
     */
    private fun closestHullSurfacePoint(ship: ShipAPI, point: Vector2f, projRadius: Float): Vector2f? {
        val limit = projRadius + CONTACT_MARGIN
        val bounds = ship.exactBounds
        if (bounds == null) {
            // 无碰撞箱按碰撞圈近似：圈面最近点
            val dist = MathUtils.getDistance(point, ship.location)
            if (dist > ship.collisionRadius + limit) return null
            return surfaceContactPoint(ship.location, ship.collisionRadius, point, point)
        }
        bounds.update(ship.location, ship.facing)
        var best: Vector2f? = null
        var bestDist = Float.MAX_VALUE
        for (segment in bounds.segments) {
            val closest = StarfallWingTuning.closestPointOnSegment(point, segment.p1, segment.p2)
            val dist = StarfallWingTuning.distanceToSegment(point, segment.p1, segment.p2)
            if (dist < bestDist) {
                bestDist = dist
                best = closest
            }
        }
        return if (bestDist <= limit) best else null
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
     * 舰船护盾伤害落点（坠星残响 resolveShipDamagePoint 同型注记）：
     * 盾面落点 = 舰心沿命中方向外推盾半径；落点仅影响装甲格选择与浮字位置，不影响伤害量。
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
