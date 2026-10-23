package cn.kasuminova.astd.combat.effect.lens.iceshard

import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.random.Random

class IceShardAttachScript(
    private val ship: ShipAPI,
    private val shard: MissileAPI,
    private val hitPoint: Vector2f,
    private val shardDamage: Float,
    private val source: ShipAPI?,
    private val dotRatio: Float,
    private val amp: Float,
    private val random: Random = Random.Default,
) : BaseEveryFrameCombatPlugin() {

    private var offsetLocal: Vector2f? = null
    private var facing0 = 0f
    private var shardRelativeFacing = 0f

    private var elapsed = 0f
    private var done = false
    private val tickInterval = IntervalUtil(IceShardMirvDifficulty.TICK_INTERVAL, IceShardMirvDifficulty.TICK_INTERVAL)

    // 命中弹体在 onHit 结算后被引擎移除，首帧不在场即以同规格纯视觉弹体（伤害 0、碰撞 NONE）重生顶替。
    private var shardEntity: MissileAPI = shard

    // statId 全附着实例共用：同 id modifyMult 互相覆盖，多枚冰晶增伤互不叠加。
    private val ampListener = AmpListener(
        ship = ship,
        amp = amp,
        statId = AMP_STAT_ID,
        worldPoint = { currentWorldPoint() },
    )

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (done) return
        val engine = Global.getCombatEngine() ?: run {
            ship.removeListener(ampListener)
            done = true
            return
        }
        if (engine.isPaused) return

        if (!ship.isAlive || ship.isHulk) {
            finish(engine, removeShard = engine.isEntityInPlay(shardEntity))
            return
        }

        if (offsetLocal == null) {
            facing0 = ship.facing
            offsetLocal = IceShardMirvMath.attachLocalOffset(ship.location, facing0, hitPoint)
            shardRelativeFacing = Misc.getAngleDiff(facing0, shard.facing)
            if (!engine.isEntityInPlay(shardEntity)) {
                val respawned = engine.spawnProjectile(
                    source, null, IceShardMirvSplitScript.SUB_WEAPON_ID,
                    currentWorldPoint(), facing0 + shardRelativeFacing, Vector2f(ship.velocity),
                ) as? MissileAPI
                if (respawned == null) {
                    log.warn("源生冰晶附着：视觉弹体重生失败（spawnProjectile 返回非 MissileAPI），本次附着终止")
                    finish(engine, removeShard = false)
                    return
                }
                respawned.damageAmount = 0f
                // 脚本 spawn 的弹体不触发 onFireEffect，拖尾必须显式登记。
                ProjectileVfxDriverPlugin.track(engine, respawned, respawned.projectileSpecId)
                shardEntity = respawned
            }
            shardEntity.collisionClass = CollisionClass.NONE
            // flightTime 是已飞时间，续命只能抬 maxFlightTime；误写 flightTime 会把已飞时间顶满导致弹体立即熄火。
            shardEntity.maxFlightTime =
                shardEntity.flightTime + IceShardMirvDifficulty.ATTACH_DURATION + ATTACH_FLAMEOUT_MARGIN
            ship.addListener(ampListener)
        }

        if (!engine.isEntityInPlay(shardEntity)) {
            finish(engine, removeShard = false)
            return
        }

        elapsed += amount

        val worldPoint = currentWorldPoint()
        shardEntity.location.set(worldPoint.x, worldPoint.y)
        shardEntity.velocity.set(ship.velocity.x, ship.velocity.y)
        shardEntity.facing = facing0 + shardRelativeFacing + Misc.getAngleDiff(facing0, ship.facing)
        shardEntity.angularVelocity = 0f

        // 到期判定置于周期结算之后，否则末周期伤害丢失。
        tickInterval.advance(amount)
        if (tickInterval.intervalElapsed()) {
            val tick = IceShardMirvMath.tickDamage(shardDamage, dotRatio)
            if (tick > 0f) {
                engine.applyDamage(
                    ship, Vector2f(worldPoint), tick,
                    DamageType.ENERGY, 0f, true, false, source, true,
                )
                engine.customData[TELEMETRY_TICKS] = (engine.customData[TELEMETRY_TICKS] as? Int ?: 0) + 1
            }
            IceShardMirvVfx.spawnAttachNebula(engine, worldPoint, random)
        }

        if (elapsed >= IceShardMirvDifficulty.ATTACH_DURATION) {
            finish(engine, removeShard = true)
        }
    }

    private fun currentWorldPoint(): Vector2f {
        val local = offsetLocal ?: return Vector2f(hitPoint)
        return IceShardMirvMath.attachWorldPoint(ship.location, ship.facing, local)
    }

    private fun finish(engine: CombatEngineAPI, removeShard: Boolean) {
        ship.removeListener(ampListener)
        if (removeShard && engine.isEntityInPlay(shardEntity)) engine.removeEntity(shardEntity)
        done = true
        engine.removePlugin(this)
    }

    // DamageTakenModifier 回调携带命中点，按点判定无需每帧范围扫描。
    // 增伤不叠加：全实例共用 statId，同一承伤事件内同 id modifyMult 互相覆盖。
    // 本脚本的周期伤害同样落在附着点上，会经此乘区放大，有意保留。
    class AmpListener(
        private val ship: ShipAPI,
        private val amp: Float,
        private val statId: String,
        private val worldPoint: () -> Vector2f,
    ) : DamageTakenModifier, AdvanceableListener {

        override fun advance(amount: Float) {
            if (!ship.isAlive || ship.isHulk) {
                ship.removeListener(this)
            }
        }

        override fun modifyDamageTaken(
            param: Any?,
            target: CombatEntityAPI?,
            damage: DamageAPI?,
            point: Vector2f?,
            shieldHit: Boolean,
        ): String? {
            if (target !== ship || damage == null || point == null || shieldHit) return null
            if (MathUtils.getDistance(point, worldPoint()) > IceShardMirvDifficulty.AMP_RADIUS) return null
            damage.modifier.modifyMult(statId, 1f + amp)
            return statId
        }
    }

    companion object {
        private val log = Global.getLogger(IceShardAttachScript::class.java)

        const val AMP_STAT_ID = "astd_ice_shard_amp"

        const val TELEMETRY_ATTACHES = "astd_ice_shard_attaches"

        const val TELEMETRY_TICKS = "astd_ice_shard_ticks"

        private const val ATTACH_FLAMEOUT_MARGIN = 1.0f
    }
}
