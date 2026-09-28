package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
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
 * - 变距折跃与虚空裂隙：IN 边沿解析折跃目标点（AI 决策通道 SYSTEM_TARGET_COORDS 优先——
 *   原版 MineStrikeStats 同款；玩家通道取鼠标世界坐标 getMouseTarget；都拿不到按满距），
 *   折跃长度 = clamp(舰心到目标点距离, 25%×最大, 最大)，最大 = [RiftShiftTuning.SHIFT_DISTANCE]
 *   × systemRangeBonus；方向恒为飞行向量/朝向（[RiftShiftTuning.shiftDirection]）。
 *   引擎级共享每帧插件推进三相位（伤害拍随相位切换，相位边界清零节拍器）：
 *   成形掠过（0.7s 缓动插值舰位，裂隙段随舰位拉长，0.1s 一拍 × 400 能量）→
 *   驻留接触（5s，0.2s 一拍 × 200 能量）→
 *   闭合收拢（1s，裂隙段自末端反向收回起点——「拉上」观感，0.1s 一拍 × 400 能量）；
 *   接触判定 = 目标心到裂隙段 ≤ 100su，目标面 = 敌舰（含 hulk/相位）+ 敌导弹 + 中立陨石，
 *   单点 applyDamage（落点 = 裂隙段最近点），EMP 无；裂隙星云逐帧特效走 [RiftShiftVfx]；
 * - 虚空锚雷（AI 规避）：开裂隙时沿路径每 100su 布一枚隐藏 PHASE_MINE（高面板伤害让原版
 *   AI 判危险规避；永不引爆、不可见、不可碰撞），裂隙闭合/宿主舰离场时清除；
 * - HUD 状态行中文化：原版 [PhaseCloakStats.maintainStatus] 硬编码英文状态文本，
 *   这里按相同结构输出 I18n 文本，并为玩家船维持裂隙闭合倒计时行。
 *
 * applyDamage 落点口径（坠星残翼同款判例注记）：盾覆盖 → 盾面落点 + bypass=false；
 * 穿船体 → 裂隙段最近点（压回碰撞圈内，界外落点恒 0 伤害）+ bypass=true。
 */
class RiftShiftSystemStats : PhaseCloakStats() {

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        super.apply(stats, id, state, effectLevel)
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk) return
        if (state != ShipSystemStatsScript.State.IN) return
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
        val remaining = rift.startTime + RiftShiftTuning.SHIFT_DURATION +
            RiftShiftTuning.CLOSURE_DELAY_SECONDS + RiftShiftTuning.CLOSURE_DURATION_SECONDS -
            engine.getTotalElapsedTime(false)
        if (remaining <= 0f) return
        engine.maintainStatusForPlayerShip(
            STATUSKEY3, icon, cloak.displayName,
            I18n[I18n.Categories.MOD, "system.rift_shift.status.1"]
                .replace("%seconds%", "%.1f".format(remaining)),
            true,
        )
    }

    /** IN 边沿：解析变距目标点、记录裂隙状态、布设虚空锚雷并确保共享推进插件在场。 */
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
        val to = Vector2f(from.x + dir.x * dist, from.y + dir.y * dist)
        val rift = RiftState(ship, from, to, engine.getTotalElapsedTime(false))
        engine.customData[RIFT_KEY_PREFIX + System.identityHashCode(ship)] = rift
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
     * 虚空锚雷布设：沿裂隙路径每 [RiftShiftTuning.MINE_SPACING]su 一枚隐藏 PHASE_MINE——
     * 高面板伤害 + 预置 primed/引爆倒计时（1e6s 永不到期）让原版 AI 按致命地雷规避；
     * 引信为 PROXIMITY_FUSE range=0（永不触发，behaviorSpec 为原版必填块）、
     * collisionClass=NONE（不可碰撞/不可被拦截）、贴图全隐（视觉融入裂隙星云），永不引爆；
     * spawn 失败记 WARN（缺席不阻断裂隙本体）。
     */
    private fun spawnAnchorMines(engine: CombatEngineAPI, rift: RiftState) {
        for (point in RiftShiftTuning.mineAnchorPoints(rift.from, rift.to)) {
            val mine = engine.spawnProjectile(
                rift.ship, null, RiftShiftTuning.MINE_WEAPON_ID, point, 0f, null,
            ) as? MissileAPI
            if (mine == null) {
                log.warn("[ASTD] 裂隙折跃虚空锚雷生成失败：spawnProjectile 未产出 MissileAPI: weapon=${RiftShiftTuning.MINE_WEAPON_ID}")
                continue
            }
            mine.source = rift.ship
            mine.damageAmount = RiftShiftTuning.MINE_PANEL_DAMAGE
            mine.velocity.set(0f, 0f)
            mine.armingTime = 0f
            mine.maxFlightTime = RiftShiftTuning.SHIFT_DURATION + RiftShiftTuning.CLOSURE_DELAY_SECONDS +
                RiftShiftTuning.CLOSURE_DURATION_SECONDS + 1f
            mine.setMineExplosionRange(RiftShiftTuning.CONTACT_RANGE)
            mine.setMinePrimed(true)
            mine.setUntilMineExplosion(1e6f)
            mine.setNoGlowTime(999f)
            mine.isNoFlameoutOnFizzling = true
            mine.interruptContrail()
            mine.spriteAlphaOverride = 0f
            mine.glowRadius = 0f
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
                val now = engine.getTotalElapsedTime(false)
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
                    if (advanceRift(engine, key, rift, amount, now)) {
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
     * 单条裂隙的逐帧推进。三相位（伤害拍口径随相位切换，相位边界清零节拍器）：
     * 成形掠过（0.7s 缓动插值舰位）→ 驻留接触（5s）→ 闭合收拢（1s，末端反向收回起点）。
     * @return true = 闭合收拢完毕，调用方走 [discardRift] 终结。
     */
    private fun advanceRift(engine: CombatEngineAPI, riftKey: String, rift: RiftState, amount: Float, now: Float): Boolean {
        val ship = rift.ship
        val elapsed = now - rift.startTime
        val phase: Int
        val tickInterval: Float
        val tickDamage: Float
        when {
            elapsed <= RiftShiftTuning.SHIFT_DURATION -> {
                // 缓动曲线插值（smoothstep）：起步/到达速度为零，加减速自然成立
                val progress = RiftShiftTuning.easeProgress(elapsed / RiftShiftTuning.SHIFT_DURATION)
                val loc = Vector2f(
                    rift.from.x + (rift.to.x - rift.from.x) * progress,
                    rift.from.y + (rift.to.y - rift.from.y) * progress,
                )
                ship.location.set(loc)
                rift.formedTo.set(loc)
                phase = PHASE_FORMING
                tickInterval = RiftShiftTuning.GRAZE_TICK_SECONDS
                tickDamage = RiftShiftTuning.GRAZE_DAMAGE
            }

            elapsed <= RiftShiftTuning.SHIFT_DURATION + RiftShiftTuning.CLOSURE_DELAY_SECONDS -> {
                rift.formedTo.set(rift.to)
                phase = PHASE_LINGERING
                tickInterval = RiftShiftTuning.CONTACT_TICK_SECONDS
                tickDamage = RiftShiftTuning.CONTACT_DAMAGE
            }

            else -> {
                val closureT = (elapsed - RiftShiftTuning.SHIFT_DURATION - RiftShiftTuning.CLOSURE_DELAY_SECONDS) /
                    RiftShiftTuning.CLOSURE_DURATION_SECONDS
                if (closureT >= 1f) return true
                // 闭合收拢：存续段恒为 from→tip，tip 自末端反向缓动收回起点（「拉上」观感）
                rift.formedTo.set(RiftShiftTuning.closureTip(rift.from, rift.to, closureT))
                phase = PHASE_CLOSING
                tickInterval = RiftShiftTuning.GRAZE_TICK_SECONDS
                tickDamage = RiftShiftTuning.GRAZE_DAMAGE
            }
        }

        if (rift.phase != phase) {
            rift.phase = phase
            rift.damageTimer = 0f
        }
        rift.damageTimer += amount
        while (rift.damageTimer >= tickInterval) {
            rift.damageTimer -= tickInterval
            settleSegmentTick(engine, rift.ship, rift.from, rift.formedTo, tickDamage)
        }

        RiftShiftVfx.riftBodyFrame(engine, riftKey, rift.from, rift.formedTo, RiftShiftVfx.bodyIntensity(elapsed))
        RiftShiftVfx.riftFrame(engine, rift.from, rift.formedTo, amount)
        return false
    }

    /**
     * 裂隙段伤害拍：对接触 from→to 段的所有目标单点结算一次 [damage] 能量伤害
     * （落点 = 裂隙段最近点）。目标面 = 敌舰（含 hulk 残骸与相位中的舰船）+ 敌导弹 +
     * 中立陨石（均按 owner 过滤友军）；舰船盾覆盖方向走盾面落点结算。
     */
    internal fun settleSegmentTick(
        engine: CombatEngineAPI,
        source: ShipAPI,
        from: Vector2f,
        to: Vector2f,
        damage: Float,
    ) {
        val midX = (from.x + to.x) * 0.5f
        val midY = (from.y + to.y) * 0.5f
        val halfLen = MathUtils.getDistance(from, to) * 0.5f
        for (candidate in engine.ships) {
            val ship = candidate as? ShipAPI ?: continue
            if (ship === source || ship.owner == source.owner) continue
            if (!ship.isAlive && !ship.isHulk) continue
            // 粗筛：目标心到段中点超过 半程 + 接触范围 时不可能接触
            if (!withinSegmentReach(ship.location, midX, midY, halfLen)) continue
            if (!RiftShiftTuning.contactsRift(ship.location, from, to)) continue
            applyRiftDamage(engine, source, ship, RiftShiftTuning.closestPointOnSegment(ship.location, from, to), damage)
        }
        for (missile in engine.missiles) {
            if (missile.owner == source.owner || missile.isExpired || missile.isFading) continue
            if (!withinSegmentReach(missile.location, midX, midY, halfLen)) continue
            if (!RiftShiftTuning.contactsRift(missile.location, from, to)) continue
            engine.applyDamage(
                missile, RiftShiftTuning.closestPointOnSegment(missile.location, from, to), damage,
                DamageType.ENERGY, 0f,
                false, false, source, true,
            )
        }
        for (asteroid in engine.asteroids) {
            if (asteroid.owner == source.owner) continue
            if (!withinSegmentReach(asteroid.location, midX, midY, halfLen)) continue
            if (!RiftShiftTuning.contactsRift(asteroid.location, from, to)) continue
            engine.applyDamage(
                asteroid, RiftShiftTuning.closestPointOnSegment(asteroid.location, from, to), damage,
                DamageType.ENERGY, 0f,
                false, false, source, true,
            )
        }
    }

    /** 粗筛：目标心到段中点距离的平方 ≤ (半程 + 接触范围)²（避免开方）。 */
    private fun withinSegmentReach(loc: Vector2f, midX: Float, midY: Float, halfLen: Float): Boolean {
        val reach = halfLen + RiftShiftTuning.CONTACT_RANGE
        val mdx = loc.x - midX
        val mdy = loc.y - midY
        return mdx * mdx + mdy * mdy <= reach * reach
    }

    /**
     * 裂隙伤害结算（舰船）：接触方向被开启的护盾覆盖 → 盾面落点结算；
     * 否则裂隙段最近点落点 + bypassShields（落点压回碰撞圈内，见 [clampIntoHull] 判例）。
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

    /** 一条未闭合裂隙的状态：存续段为 from→formedTo（成形随舰位拉长 / 驻恒等于 to / 闭合自末端收拢）。 */
    private class RiftState(
        val ship: ShipAPI,
        val from: Vector2f,
        val to: Vector2f,
        val startTime: Float,
        val formedTo: Vector2f = Vector2f(from),
        var damageTimer: Float = 0f,
        var phase: Int = PHASE_FORMING,
        /** 虚空锚雷实体（裂隙存续绑定，闭合/消散时清除）。 */
        val mines: MutableList<MissileAPI> = mutableListOf(),
    )

    companion object {
        private const val TRIGGER_KEY_PREFIX = "astd_rift_shift_trigger:"
        private const val RIFT_KEY_PREFIX = "astd_rift_shift_rift:"
        private const val PLUGIN_KEY = "astd_rift_shift_plugin"

        /** 裂隙相位：成形掠过 / 驻留接触 / 闭合收拢（伤害拍口径随相位切换）。 */
        private const val PHASE_FORMING = 0
        private const val PHASE_LINGERING = 1
        private const val PHASE_CLOSING = 2

        private val log = AstdLog.logger
    }
}
