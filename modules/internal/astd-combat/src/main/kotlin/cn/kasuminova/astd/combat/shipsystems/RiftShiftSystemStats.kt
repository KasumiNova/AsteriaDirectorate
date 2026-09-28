package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.combat.PhaseCloakStats
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

/**
 * 裂隙折跃（astd_rift_shift，XC-002 淬刃舰船系统）的 stats 脚本。
 *
 * 相位机制与原版相位线圈（[PhaseCloakStats]）完全一致——继承即全部。本类额外承担：
 * - 折跃位移与虚空裂隙：IN 边沿记录裂隙状态（起点/终点 = [RiftShiftTuning.shiftDirection]
 *   × 800su），引擎级共享每帧插件推进——1s 折跃窗内按进度插值舰位（裂隙成形段随舰位
 *   拉长）、每 0.2s 一拍对接触成形段的敌舰结算接触伤害、折跃完成后 5s 沿路径每 100su
 *   一个爆点闭合爆炸；裂隙星云逐帧特效走 [RiftShiftVfx]；
 * - HUD 状态行中文化：原版 [PhaseCloakStats.maintainStatus] 硬编码英文状态文本，
 *   这里按相同结构输出 I18n 文本，并为玩家船维持裂隙闭合倒计时行。
 *
 * 伤害数值走 D13 三锚点（[RiftShiftTuning.CONTACT_DAMAGE_PER_SECOND] /
 * [RiftShiftTuning.CLOSURE_BLAST_DAMAGE]，isPlayer = 来源舰 owner==0）。
 * applyDamage 落点口径（坠星残翼同款判例注记）：盾覆盖 → 盾面落点 + bypass=false；
 * 穿船体 → 舰心落点 + bypass=true。
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
        engine.customData[TRIGGER_KEY_PREFIX + System.identityHashCode(ship)] = null
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
            RiftShiftTuning.CLOSURE_DELAY_SECONDS - engine.getTotalElapsedTime(false)
        if (remaining <= 0f) return
        engine.maintainStatusForPlayerShip(
            STATUSKEY3, icon, cloak.displayName,
            I18n[I18n.Categories.MOD, "system.rift_shift.status.1"]
                .replace("%seconds%", "%.1f".format(remaining)),
            true,
        )
    }

    /** IN 边沿：记录裂隙状态并确保共享推进插件在场。 */
    private fun openRift(engine: CombatEngineAPI, ship: ShipAPI) {
        val from = Vector2f(ship.location)
        val dir = RiftShiftTuning.shiftDirection(ship.velocity, ship.facing)
        val to = Vector2f(
            from.x + dir.x * RiftShiftTuning.SHIFT_DISTANCE,
            from.y + dir.y * RiftShiftTuning.SHIFT_DISTANCE,
        )
        engine.customData[RIFT_KEY_PREFIX + System.identityHashCode(ship)] =
            RiftState(ship, from, to, engine.getTotalElapsedTime(false))
        ensureRiftPlugin(engine)
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
                    if (!engine.isEntityInPlay(ship) || ship.isHulk || !ship.isAlive) {
                        engine.customData[key] = null
                        continue
                    }
                    advanceRift(engine, rift, amount, now)
                    if (now >= rift.startTime + RiftShiftTuning.SHIFT_DURATION + RiftShiftTuning.CLOSURE_DELAY_SECONDS) {
                        closeRift(engine, rift)
                        engine.customData[key] = null
                    }
                }
                if (engine.customData.keys.none { it.startsWith(RIFT_KEY_PREFIX) }) {
                    engine.customData[PLUGIN_KEY] = null
                    engine.removePlugin(this)
                }
            }
        })
    }

    /** 单条裂隙的逐帧推进：折跃位移（1s 插值）、成形段跟踪、接触结算拍、裂隙星云。 */
    private fun advanceRift(engine: CombatEngineAPI, rift: RiftState, amount: Float, now: Float) {
        val ship = rift.ship
        val elapsed = now - rift.startTime
        if (elapsed <= RiftShiftTuning.SHIFT_DURATION) {
            val progress = (elapsed / RiftShiftTuning.SHIFT_DURATION).coerceIn(0f, 1f)
            val loc = Vector2f(
                rift.from.x + (rift.to.x - rift.from.x) * progress,
                rift.from.y + (rift.to.y - rift.from.y) * progress,
            )
            ship.location.set(loc)
            rift.formedTo.set(loc)
        } else {
            rift.formedTo.set(rift.to)
        }

        rift.contactTimer += amount
        while (rift.contactTimer >= RiftShiftTuning.CONTACT_TICK_SECONDS) {
            rift.contactTimer -= RiftShiftTuning.CONTACT_TICK_SECONDS
            contactTick(engine, rift)
        }

        RiftShiftVfx.riftFrame(engine, rift.from, rift.formedTo, amount)
    }

    /** 接触结算拍：对接触成形段的敌舰结算 0.2s 份接触伤害。 */
    private fun contactTick(engine: CombatEngineAPI, rift: RiftState) {
        val source = rift.ship
        val damage = DifficultyTuningImpl.valueFor(
            RiftShiftTuning.CONTACT_DAMAGE_PER_SECOND, source.owner == 0,
        ) * RiftShiftTuning.CONTACT_TICK_SECONDS
        val midX = (rift.from.x + rift.formedTo.x) * 0.5f
        val midY = (rift.from.y + rift.formedTo.y) * 0.5f
        val halfLen = MathUtils.getDistance(rift.from, rift.formedTo) * 0.5f
        for (candidate in engine.ships) {
            val ship = candidate as? ShipAPI ?: continue
            if (ship === source || ship.owner == source.owner) continue
            if (!ship.isAlive || ship.isHulk || ship.isPhased) continue
            // 粗筛：舰心到成形段中点超过 半程 + 接触半径上界 时不可能接触
            val reach = halfLen + RiftShiftTuning.RIFT_HALF_WIDTH + ship.collisionRadius
            val mdx = ship.location.x - midX
            val mdy = ship.location.y - midY
            if (mdx * mdx + mdy * mdy > reach * reach) continue
            if (!RiftShiftTuning.contactsRift(ship.location, ship.collisionRadius, rift.from, rift.formedTo)) continue
            val contact = RiftShiftTuning.closestPointOnSegment(ship.location, rift.from, rift.formedTo)
            applyRiftDamage(engine, source, ship, contact, damage)
        }
    }

    /** 闭合：沿路径每 100su 一个爆点，半径内敌舰结算闭合伤害 + 爆发特效。 */
    private fun closeRift(engine: CombatEngineAPI, rift: RiftState) {
        val source = rift.ship
        val damage = DifficultyTuningImpl.valueFor(RiftShiftTuning.CLOSURE_BLAST_DAMAGE, source.owner == 0)
        for (point in RiftShiftTuning.closureBlastPoints(rift.from, rift.to)) {
            for (candidate in engine.ships) {
                val ship = candidate as? ShipAPI ?: continue
                if (ship === source || ship.owner == source.owner) continue
                if (!ship.isAlive || ship.isHulk || ship.isPhased) continue
                if (MathUtils.getDistance(point, ship.location) > RiftShiftTuning.BLAST_RADIUS + ship.collisionRadius) continue
                applyRiftDamage(engine, source, ship, Vector2f(point), damage)
            }
            RiftShiftVfx.closureBlast(engine, point)
        }
    }

    /**
     * 裂隙伤害结算（接触/闭合共用）：接触方向被开启的护盾覆盖 → 盾面落点结算；
     * 否则舰心落点 + bypassShields（脚本 applyDamage 的界内边缘点恒 0 伤害）。
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
                ship, Vector2f(ship.location), damage,
                DamageType.ENERGY, 0f,
                true, false, source, true,
            )
        }
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

    /** 一条未闭合裂隙的状态：成形段为 from→formedTo（折跃中随舰位拉长，折跃后恒等于 to）。 */
    private class RiftState(
        val ship: ShipAPI,
        val from: Vector2f,
        val to: Vector2f,
        val startTime: Float,
        val formedTo: Vector2f = Vector2f(from),
        var contactTimer: Float = 0f,
    )

    companion object {
        private const val TRIGGER_KEY_PREFIX = "astd_rift_shift_trigger:"
        private const val RIFT_KEY_PREFIX = "astd_rift_shift_rift:"
        private const val PLUGIN_KEY = "astd_rift_shift_plugin"
    }
}
