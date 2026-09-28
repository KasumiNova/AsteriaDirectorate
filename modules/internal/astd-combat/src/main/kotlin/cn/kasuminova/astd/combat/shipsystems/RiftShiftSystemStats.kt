package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.impl.combat.PhaseCloakStats
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

/**
 * 裂隙折跃（astd_rift_shift，XC-002 星翼舰船系统）的 stats 脚本。
 *
 * 相位机制与原版相位线圈（[PhaseCloakStats]）完全一致——继承即全部。本类额外承担：
 * - 变距折跃与虚空裂隙：ACTIVE 边沿解析折跃目标点（AI 决策通道 SYSTEM_TARGET_COORDS 优先——
 *   原版 MineStrikeStats 同款；玩家通道取鼠标世界坐标 getMouseTarget；都拿不到按满距），
 *   折跃长度 = clamp(舰心到目标点距离, 25%×最大, 最大)，最大 = [RiftShiftTuning.SHIFT_DISTANCE]
 *   × systemRangeBonus；方向恒为飞行向量/朝向（[RiftShiftTuning.shiftDirection]）。
 *   拉开时长按距离占比线性映射（[RiftShiftTuning.shiftDurationSeconds]，满距 1s 与
 *   .system active 名义最大值对齐），成形完毕即 forceState(OUT) 收尾相位窗口，短距折跃
 *   相位窗口随之缩短；
 * - 裂隙时钟与相位状态机同一时钟（舰船时间）：原版系统的 IN/ACTIVE/OUT 计时随舰船
 *   timeMult 走（相位三倍时流），裂隙推进插件每帧按 amount × timeMult 累计，两者天然对齐——
 *   世界时钟计时会与相位窗口最多差三倍（实机判例：相位提前解除、成形中后段舰体脱相位）。
 *   引擎级共享每帧插件推进三相位点位状态机：
 *   成形拉开（缓动插值舰位并清零速度——折跃不保留动量，点位随拉开进度逐个激活，
 *   0.1s 拍 × 400 能量）→ 驻留（5s，全部点位 0.2s 拍 × 200 能量）→
 *   闭合拉上（与拉开同向同耗时：扫掠头自起点向终点推进，被扫过点位即席结算一拍 400 后失效，
 *   未扫到点位维持驻留节拍）；
 *   伤害点位 = 沿路径每 100su 一个（与锚雷同序列 [RiftShiftTuning.anchorPoints]），
 *   接触判定 = 目标心到点位 ≤ 100su，目标面 = 敌舰（含 hulk/相位）+ 敌导弹 + 中立陨石，
 *   施放舰与同 owner 友军恒排除，单目标单拍只按最近点位结算一次，EMP 无；
 *   裂隙星云逐帧特效走 [RiftShiftVfx]；
 * - 虚空锚雷（AI 规避）：开裂隙时沿路径每 100su 布一枚隐藏 PHASE_MINE（高面板伤害让原版
 *   AI 判危险规避；不可见、不可碰撞）。spawn 后即以无引信 AI（[SilentMineAI]）替换原版自动
 *   挂载的 GuidedProximityFuseAI——原版引信在弹体消退时走 trigger→primed→windup→explode
 *   全链（mine_ping/mine_windup_heavy/mine_explosion 沿路径十几枚叠加，实机噪音判例），
 *   替换后消退静默且永不引爆；behaviorSpec 仍是原版 spawn 必填块（缺块 NPE 判例），不动；
 * - HUD 状态行中文化：原版 [PhaseCloakStats.maintainStatus] 硬编码英文状态文本，
 *   这里按相同结构输出 I18n 文本，并为玩家船维持裂隙闭合倒计时行。
 *
 * applyDamage 落点口径（坠星残翼同款判例注记）：盾覆盖 → 盾面落点 + bypass=false；
 * 穿船体 → 接触点位（压回碰撞圈内，界外落点恒 0 伤害）+ bypass=true。
 */
class RiftShiftSystemStats : PhaseCloakStats() {

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        super.apply(stats, id, state, effectLevel)
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk) return
        // ACTIVE 边沿触发：成形窗口与 .system active 窗口同起点（IN 边沿会恒差一个 chargeUp——
        // 实机判例：充能期计时计入成形导致相位窗口与折跃位移错位）
        if (state != ShipSystemStatsScript.State.ACTIVE) return
        val engine = Global.getCombatEngine() ?: return
        val triggerKey = TRIGGER_KEY_PREFIX + System.identityHashCode(ship)
        if (engine.customData[triggerKey] == true) return
        engine.customData[triggerKey] = true
        openRift(engine, ship)
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        super.unapply(stats, id)
        val ship = stats.entity as? ShipAPI ?: return
        val engine = Global.getCombatEngine() ?: return
        engine.customData.remove(TRIGGER_KEY_PREFIX + System.identityHashCode(ship))
    }

    override fun maintainStatus(playerShip: ShipAPI, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val cloak = playerShip.phaseCloak ?: playerShip.system ?: return
        val engine = Global.getCombatEngine()
        val icon = cloak.specAPI.iconSpriteName

        if (effectLevel > PhaseCloakStats.VULNERABLE_FRACTION) {
            engine.maintainStatusForPlayerShip(
                STATUSKEY2, icon, cloak.displayName,
                I18n[I18n.Categories.MOD, "system.rift_shift.status.0"], false,
            )
        }

        val rift = engine.customData[RIFT_KEY_PREFIX + System.identityHashCode(playerShip)] as? RiftState ?: return
        val remaining = RiftShiftTuning.riftLifetimeSeconds(rift.formingDuration) - rift.elapsed
        if (remaining <= 0f) return
        engine.maintainStatusForPlayerShip(
            STATUSKEY3, icon, cloak.displayName,
            I18n[I18n.Categories.MOD, "system.rift_shift.status.1"]
                .replace("%seconds%", "%.1f".format(remaining)),
            true,
        )
    }

    /** ACTIVE 边沿：解析变距目标点、折算动态拉开时长、记录裂隙状态、布设虚空锚雷并确保共享推进插件在场。 */
    private fun openRift(engine: CombatEngineAPI, ship: ShipAPI) {
        val from = Vector2f(ship.location)
        val dir = RiftShiftTuning.shiftDirection(ship.velocity, ship.facing)
        val maxDist = ship.mutableStats.systemRangeBonus.computeEffective(RiftShiftTuning.SHIFT_DISTANCE)
        val target = resolveShiftTargetPoint(ship)
        val dist = if (target != null) {
            RiftShiftTuning.resolveShiftDistance(maxDist, MathUtils.getDistance(from, target))
        } else {
            maxDist
        }
        if (dist < RiftShiftTuning.ANCHOR_SPACING) {
            log.warn("[ASTD] 裂隙折跃折跃长度不足一个点位间距（${dist}su，maxDist=${maxDist}su），本次不开裂隙")
            return
        }
        val to = Vector2f(from.x + dir.x * dist, from.y + dir.y * dist)
        val formingDuration = RiftShiftTuning.shiftDurationSeconds(maxDist, dist)
        val riftKey = RIFT_KEY_PREFIX + System.identityHashCode(ship)
        // 生命周期卫生：同舰旧裂隙（冷却缩短类改装可令再激活早于旧裂隙寿终）先走完整终结流程再覆盖
        (engine.customData[riftKey] as? RiftState)?.let { discardRift(engine, riftKey, it) }
        val rift = RiftState(
            ship = ship,
            from = from,
            to = to,
            formingDuration = formingDuration,
            length = dist,
            points = RiftShiftTuning.anchorPoints(from, to).mapIndexed { index, pos ->
                RiftPoint(pos, RiftShiftTuning.ANCHOR_SPACING * (index + 1))
            },
        )
        engine.customData[riftKey] = rift
        spawnAnchorMines(engine, rift)
        ensureRiftPlugin(engine)
    }

    /**
     * 折跃目标点解析：AI 决策通道（SYSTEM_TARGET_COORDS，原版 MineStrikeStats 同款；
     * 不要求 shipAI 在场——自动化舞台与自动驾驶旗舰也经此注入）优先；否则玩家通道取鼠标
     * 世界坐标（getMouseTarget）；都拿不到返回 null（调用方按满距折跃）。
     */
    private fun resolveShiftTargetPoint(ship: ShipAPI): Vector2f? {
        val flags = ship.aiFlags
        if (flags != null && flags.hasFlag(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS)) {
            val custom = flags.getCustom(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS) as? Vector2f
            if (custom != null) return Vector2f(custom)
        }
        val mouse = ship.mouseTarget ?: return null
        return Vector2f(mouse)
    }

    /**
     * 虚空锚雷布设：沿裂隙路径每 [RiftShiftTuning.ANCHOR_SPACING]su 一枚隐藏 PHASE_MINE——
     * 高面板伤害 + 预置 primed/引爆倒计时（1e6s 永不到期）让原版 AI 按致命地雷规避；
     * collisionClass=NONE（不可碰撞/不可被拦截）、贴图全隐（视觉融入裂隙星云）；
     * spawn 后立即以 [SilentMineAI] 替换原版自动挂载的 GuidedProximityFuseAI（消退即引信全链
     * 放音 + 爆炸，实机噪音判例），替换后锚雷静默驻留、永不引爆；spawn 失败记 WARN。
     */
    private fun spawnAnchorMines(engine: CombatEngineAPI, rift: RiftState) {
        for (point in rift.points) {
            val mine = engine.spawnProjectile(
                rift.ship, null, RiftShiftTuning.MINE_WEAPON_ID, point.pos, 0f, null,
            ) as? MissileAPI
            if (mine == null) {
                log.warn("[ASTD] 裂隙折跃虚空锚雷生成失败：spawnProjectile 未产出 MissileAPI: weapon=${RiftShiftTuning.MINE_WEAPON_ID}")
                continue
            }
            mine.source = rift.ship
            mine.damageAmount = RiftShiftTuning.MINE_PANEL_DAMAGE
            mine.velocity.set(0f, 0f)
            mine.armingTime = 0f
            mine.maxFlightTime = RiftShiftTuning.mineFlightTimeSeconds(rift.formingDuration)
            mine.setMineExplosionRange(RiftShiftTuning.CONTACT_RANGE)
            mine.setMinePrimed(true)
            mine.setUntilMineExplosion(1e6f)
            mine.setNoGlowTime(999f)
            mine.isNoFlameoutOnFizzling = true
            mine.interruptContrail()
            mine.spriteAlphaOverride = 0f
            mine.glowRadius = 0f
            mine.missileAI = SilentMineAI
            rift.mines.add(mine)
        }
    }

    /** 引擎级共享每帧插件：推进所有未闭合裂隙；无存活裂隙时自卸。 */
    private fun ensureRiftPlugin(engine: CombatEngineAPI) {
        if (engine.customData[PLUGIN_KEY] == true) return
        engine.customData[PLUGIN_KEY] = true
        engine.addPlugin(object : BaseEveryFrameCombatPlugin() {
            override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
                if (engine.isPaused || amount <= 0f) return
                val entries = engine.customData.entries
                    .filter { it.key.startsWith(RIFT_KEY_PREFIX) }
                    .toList()
                for ((key, value) in entries) {
                    val rift = value as? RiftState ?: continue
                    val ship = rift.ship
                    // 宿主舰离场/hulk：裂隙立即消散（连带锚雷清除）
                    if (!engine.isEntityInPlay(ship) || ship.isHulk || !ship.isAlive) {
                        discardRift(engine, key, rift)
                        continue
                    }
                    if (advanceRift(engine, key, rift, amount)) {
                        discardRift(engine, key, rift)
                    }
                }
                // 自卸条件按键存在性判定：清理必须 remove 移除键（置 null 会残留键导致恒 false）
                if (engine.customData.keys.none { it.startsWith(RIFT_KEY_PREFIX) }) {
                    engine.customData.remove(PLUGIN_KEY)
                    engine.removePlugin(this)
                }
            }
        })
    }

    /** 裂隙终结：回收裂隙本体实体 + 清除全部虚空锚雷 + 摘除状态键。 */
    private fun discardRift(engine: CombatEngineAPI, riftKey: String, rift: RiftState) {
        RiftShiftVfx.closeRiftBody(engine, riftKey)
        for (mine in rift.mines) {
            if (engine.isEntityInPlay(mine)) engine.removeEntity(mine)
        }
        rift.mines.clear()
        engine.customData.remove(riftKey)
    }

    /**
     * 单条裂隙的逐帧推进。裂隙时钟 = 舰船时间（每帧累计 amount × timeMult，与原版系统
     * ChargeTracker 同一时钟，相位三倍时流下成形窗口与相位 ACTIVE 窗口天然对齐）。
     * 三相位点位状态机（相位边界清零节拍器）：
     * 成形拉开（缓动插值舰位并清零速度，点位随拉开进度逐个激活，0.1s 拍 × 400）→
     * 驻留（全部点位 0.2s 拍 × 200）→ 闭合拉上（扫掠头自起点向终点同向推进，被扫过点位
     * 即席结算一拍 400 后失效，未扫到点位维持驻留节拍）。
     * @return true = 闭合拉上完毕，调用方走 [discardRift] 终结。
     */
    private fun advanceRift(engine: CombatEngineAPI, riftKey: String, rift: RiftState, amount: Float): Boolean {
        val ship = rift.ship
        val shipTime = amount * ship.mutableStats.timeMult.modifiedValue
        rift.elapsed += shipTime
        val formingEnd = rift.formingDuration
        val lingeringEnd = formingEnd + RiftShiftTuning.CLOSURE_DELAY_SECONDS

        val phase: Int
        val tickInterval: Float
        val tickDamage: Float
        // 存续段（特效与观感的裂隙本体）：成形 = from→拉开末端；驻留 = from→to；闭合 = 扫掠头→to
        val segFrom = Vector2f(rift.from)
        val segTo = Vector2f(rift.to)
        when {
            rift.elapsed < formingEnd -> {
                phase = PHASE_FORMING
                tickInterval = RiftShiftTuning.GRAZE_TICK_SECONDS
                tickDamage = RiftShiftTuning.GRAZE_DAMAGE
                val progress = rift.elapsed / formingEnd
                val tip = RiftShiftTuning.pathPointAt(rift.from, rift.to, progress)
                ship.location.set(tip)
                ship.velocity.set(0f, 0f)
                segTo.set(tip)
                // 点位随拉开进度逐个激活（缓动后的路径里程占比）
                val front = RiftShiftTuning.easedPathFraction(progress) * rift.length
                for (point in rift.points) {
                    if (point.state == POINT_PENDING && point.distAlong <= front) point.state = POINT_ACTIVE
                }
            }

            rift.elapsed < lingeringEnd -> {
                phase = PHASE_LINGERING
                tickInterval = RiftShiftTuning.CONTACT_TICK_SECONDS
                tickDamage = RiftShiftTuning.CONTACT_DAMAGE
            }

            else -> {
                val closureT = (rift.elapsed - lingeringEnd) / rift.formingDuration
                if (closureT >= 1f) return true
                phase = PHASE_CLOSING
                tickInterval = RiftShiftTuning.CONTACT_TICK_SECONDS
                tickDamage = RiftShiftTuning.CONTACT_DAMAGE
                // 闭合拉上：扫掠头与拉开同向（起点→终点）同曲线推进，存续段 = 扫掠头→to
                val headFraction = RiftShiftTuning.easedPathFraction(closureT)
                segFrom.set(RiftShiftTuning.pathPointAt(rift.from, rift.to, closureT))
                // 被扫过的点位：在扫过时刻即席结算一拍掠过伤害后失效
                val sweptPositions = mutableListOf<Vector2f>()
                for (point in rift.points) {
                    if (point.state == POINT_ACTIVE && point.distAlong <= headFraction * rift.length) {
                        point.state = POINT_CLOSED
                        sweptPositions.add(point.pos)
                    }
                }
                if (sweptPositions.isNotEmpty()) {
                    settlePointsTick(engine, ship, sweptPositions, RiftShiftTuning.GRAZE_DAMAGE)
                }
            }
        }

        if (rift.phase != phase) {
            if (phase == PHASE_LINGERING) {
                // 拉开完毕：残余未激活点位（缓动端点量化）全部激活，并收尾相位窗口——
                // 短距折跃的 ACTIVE 段随之提前结束（.system active 为满距名义最大值）
                for (point in rift.points) {
                    if (point.state == POINT_PENDING) point.state = POINT_ACTIVE
                }
                endPhaseWindow(ship)
            }
            rift.phase = phase
            rift.damageTimer = 0f
        }
        rift.damageTimer += shipTime
        while (rift.damageTimer >= tickInterval) {
            rift.damageTimer -= tickInterval
            val activePositions = rift.points.filter { it.state == POINT_ACTIVE }.map { it.pos }
            settlePointsTick(engine, ship, activePositions, tickDamage)
        }

        RiftShiftVfx.riftBodyFrame(engine, riftKey, segFrom, segTo, RiftShiftVfx.bodyIntensity(rift.elapsed))
        RiftShiftVfx.riftFrame(engine, segFrom, segTo, amount)
        return false
    }

    /** 成形完毕收尾相位窗口：仅在 ACTIVE 段内提前转 OUT（chargeDown 0.25s 淡出），其余状态不打扰。 */
    private fun endPhaseWindow(ship: ShipAPI) {
        val cloak = ship.phaseCloak ?: ship.system ?: return
        if (cloak.state == ShipSystemAPI.SystemState.ACTIVE) {
            cloak.forceState(ShipSystemAPI.SystemState.OUT, 0f)
        }
    }

    /**
     * 裂隙点位伤害拍：对接触 [points] 中任一点位的所有目标单点结算一次 [damage] 能量伤害
     * （落点 = 最近点位）。目标面 = 敌舰（含 hulk 残骸与相位中的舰船）+ 敌导弹 + 中立陨石；
     * 施放舰与同 owner 友军（含本方锚雷）恒排除；单目标单拍只按最近点位结算一次
     * （邻近点位半径交叠不重复结算）；舰船盾覆盖方向走盾面落点结算。
     */
    internal fun settlePointsTick(
        engine: CombatEngineAPI,
        source: ShipAPI,
        points: List<Vector2f>,
        damage: Float,
    ) {
        if (points.isEmpty()) return
        for (candidate in engine.ships) {
            val ship = candidate as? ShipAPI ?: continue
            if (ship === source || ship.owner == source.owner) continue
            if (!ship.isAlive && !ship.isHulk) continue
            val point = RiftShiftTuning.nearestAnchorInRange(ship.location, points) ?: continue
            applyRiftDamage(engine, source, ship, point, damage)
        }
        for (missile in engine.missiles) {
            if (missile.owner == source.owner || missile.isExpired || missile.isFading) continue
            val point = RiftShiftTuning.nearestAnchorInRange(missile.location, points) ?: continue
            engine.applyDamage(
                missile, point, damage,
                DamageType.ENERGY, 0f,
                false, false, source, true,
            )
        }
        for (asteroid in engine.asteroids) {
            if (asteroid.owner == source.owner) continue
            val point = RiftShiftTuning.nearestAnchorInRange(asteroid.location, points) ?: continue
            engine.applyDamage(
                asteroid, point, damage,
                DamageType.ENERGY, 0f,
                false, false, source, true,
            )
        }
    }

    /**
     * 裂隙伤害结算（舰船）：接触点位被开启的护盾覆盖 → 盾面落点结算；
     * 否则点位落点 + bypassShields（落点压回碰撞圈内，见 [clampIntoHull] 判例）。
     */
    private fun applyRiftDamage(
        engine: CombatEngineAPI,
        source: ShipAPI,
        ship: ShipAPI,
        contact: Vector2f,
        damage: Float,
    ) {
        val shield = ship.shield
        val shieldCovers = shield != null && shield.isOn &&
            MathUtils.getDistance(contact, ship.location) <= shield.radius &&
            shield.isWithinArc(contact)
        if (shieldCovers) {
            engine.applyDamage(
                ship, shieldSurfacePoint(ship, contact), damage,
                DamageType.ENERGY, 0f,
                false, false, source, true,
            )
        } else {
            engine.applyDamage(
                ship, clampIntoHull(ship, contact), damage,
                DamageType.ENERGY, 0f,
                true, false, source, true,
            )
        }
    }

    /**
     * 船体落点压回碰撞圈内（0.9×半径）：脚本 applyDamage 的界外落点恒 0 伤害（坠星残翼同款
     * 判例注记）；落点仅影响装甲格选择与浮字位置，不影响伤害量。
     */
    private fun clampIntoHull(ship: ShipAPI, contact: Vector2f): Vector2f {
        val dx = contact.x - ship.location.x
        val dy = contact.y - ship.location.y
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        val limit = ship.collisionRadius * 0.9f
        if (dist <= limit || dist <= 1e-3f) return Vector2f(contact)
        val scale = limit / dist
        return Vector2f(ship.location.x + dx * scale, ship.location.y + dy * scale)
    }

    /**
     * 舰船护盾伤害落点（坠星残翼 shieldSurfacePoint 同型注记）：
     * 盾面落点 = 舰心沿命中方向外推盾半径；落点仅影响装甲格选择与浮字位置，不影响伤害量。
     */
    private fun shieldSurfacePoint(ship: ShipAPI, contact: Vector2f): Vector2f {
        val shield = ship.shield ?: return Vector2f(ship.location)
        val radius = shield.radius
        if (radius <= 0f) return Vector2f(ship.location)
        // 不取 Misc.getAngleInDegrees：Misc 类初始化依赖游戏运行时（无头/单测直接
        // ExceptionInInitializerError），此处语义等价于 atan2 直出角度，就地计算
        val angle = Math.toDegrees(
            kotlin.math.atan2(
                (contact.y - ship.location.y).toDouble(),
                (contact.x - ship.location.x).toDouble(),
            ),
        ).toFloat()
        return MathUtils.getPointOnCircumference(ship.location, radius, angle)
    }

    /**
     * 虚空锚雷的静默 AI：替换原版为 PHASE_MINE 自动挂载的 GuidedProximityFuseAI——
     * 原版引信在弹体消退（isFading）时走 trigger→primed→windup→explode 全链
     * （pingSound/windupSound/explosion sound 全部非空，实机噪音判例），
     * 本 AI 不推进任何引信逻辑：锚雷永不引爆、消退静默。威慑由弹体属性
     *（isMine + primed + 面板伤害 + 告警半径）承担，不依赖 AI。
     */
    private object SilentMineAI : MissileAIPlugin {
        override fun advance(amount: Float) {
        }
    }

    /** 一个伤害点位：位置固定于开裂隙时刻的路径上，状态随机位相位机推进。 */
    private class RiftPoint(
        val pos: Vector2f,
        /** 距裂隙起点的路径里程（su）：拉开激活与闭合扫掠的判定基准。 */
        val distAlong: Float,
        var state: Int = POINT_PENDING,
    )

    /** 一条未闭合裂隙的状态：路径/动态拉开时长/点位序列/锚雷实体，时钟为舰船时间。 */
    private class RiftState(
        val ship: ShipAPI,
        val from: Vector2f,
        val to: Vector2f,
        /** 拉开（成形）时长（秒，舰船时间）：按折跃距离占比动态折算；闭合拉上同耗时。 */
        val formingDuration: Float,
        /** 路径全长（su）。 */
        val length: Float,
        val points: List<RiftPoint>,
        var elapsed: Float = 0f,
        var damageTimer: Float = 0f,
        var phase: Int = PHASE_FORMING,
        /** 虚空锚雷实体（裂隙存续绑定，闭合/消散时清除）。 */
        val mines: MutableList<MissileAPI> = mutableListOf(),
    )

    companion object {
        private const val TRIGGER_KEY_PREFIX = "astd_rift_shift_trigger:"
        private const val RIFT_KEY_PREFIX = "astd_rift_shift_rift:"
        private const val PLUGIN_KEY = "astd_rift_shift_plugin"

        /** 裂隙相位：成形拉开 / 驻留接触 / 闭合拉上（伤害拍口径随相位切换）。 */
        private const val PHASE_FORMING = 0
        private const val PHASE_LINGERING = 1
        private const val PHASE_CLOSING = 2

        /** 点位状态：待拉开激活 / 生效中 / 已被闭合扫掠失效。 */
        private const val POINT_PENDING = 0
        private const val POINT_ACTIVE = 1
        private const val POINT_CLOSED = 2

        private val log = AstdLog.logger
    }
}
