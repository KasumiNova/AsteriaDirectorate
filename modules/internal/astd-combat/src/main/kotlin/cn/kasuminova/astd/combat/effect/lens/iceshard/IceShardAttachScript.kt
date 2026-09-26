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

/**
 * 源生冰晶「冰晶冻结」附着脚本（purple/30-superlative.md §机制）：每次附着一实例，
 * 由 [IceShardSubOnHitEffect] 在命中舰船装甲/船体时注册，存续
 * [IceShardMirvDifficulty.ATTACH_DURATION] 秒。
 *
 * 每帧职责：
 * 1. 钉住冰晶弹体——舰体局部坐标偏移（附着时刻记录，随舰船移动/转向回算世界坐标，
 *    CollapseShiftSystemStats 同款换算范式），速度跟随宿主，碰撞类置 NONE 防二次命中；
 * 2. 周期伤害——每个伤害周期（[IceShardMirvDifficulty.TICK_INTERVAL]）在附着点对宿主造成
 *    「子射弹伤害 ×难度缩放 ÷ 周期数」的能量伤害
 *    （bypassShields=true：冰晶已刺入舰体，护盾不再起效）；
 * 3. 星云特效——与伤害同节奏在附着点渲染一批淡蓝色星云；
 * 4. 增伤区——附着期间经 [AmpListener] 对命中点周围 [IceShardMirvDifficulty.AMP_RADIUS] su
 *    的承伤施加难度缩放增伤乘区；多枚冰晶共用同一增伤 statId，同一承伤事件至多生效一次。
 *
 * 终结路径：到期 / 宿主死亡或 hulk / 钉住弹体被外部移除 → 摘除增伤监听器并移除弹体，自注销。
 * 命中弹体在 onHit 结算后会被引擎移除，首帧以同规格纯视觉弹体（伤害 0、碰撞 NONE）重生顶替。
 */
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

    /** 附着时刻的舰体局部偏移与朝向基准（首帧初始化）。 */
    private var offsetLocal: Vector2f? = null
    private var facing0 = 0f
    private var shardRelativeFacing = 0f

    private var elapsed = 0f
    private var done = false
    private val tickInterval = IntervalUtil(IceShardMirvDifficulty.TICK_INTERVAL, IceShardMirvDifficulty.TICK_INTERVAL)

    /**
     * 钉住的冰晶弹体：命中弹体在 onHit 结算后被引擎移除，首帧检测不在场即以同规格
     * 纯视觉弹体（伤害 0、碰撞 NONE）重生顶替，附着期结束统一移除。
     */
    private var shardEntity: MissileAPI = shard

    /** 增伤监听器（首帧注册，终结时摘除；statId 全附着实例共用，多枚冰晶增伤互不叠加）。 */
    private val ampListener = AmpListener(
        ship = ship,
        amp = amp,
        statId = AMP_STAT_ID,
        worldPoint = { currentWorldPoint() },
    )

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (done) return
        val engine = Global.getCombatEngine() ?: run {
            // 引擎已销毁（战斗结束等）：摘除可能已注册的监听器后自终
            ship.removeListener(ampListener)
            done = true
            return
        }
        if (engine.isPaused) return

        // 宿主死亡/hulk → 终结（钉住弹体一并清理）
        if (!ship.isAlive || ship.isHulk) {
            finish(engine, removeShard = engine.isEntityInPlay(shardEntity))
            return
        }

        // 首帧初始化：记录局部偏移/朝向基准，接管弹体（关碰撞 + 续命到附着期满）
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
                // spawnProjectile 不经武器开火管线（onFireEffect 不触发）：拖尾/本体等弹体 VFX 在此显式登记
                ProjectileVfxDriverPlugin.track(engine, respawned, respawned.projectileSpecId)
                shardEntity = respawned
            }
            shardEntity.collisionClass = CollisionClass.NONE
            // 续命到附着期满：flightTime 是「已飞时间」，只能抬 maxFlightTime 上限
            // （误写 flightTime 会把已飞时间顶满导致弹体立即熄火淡出）
            shardEntity.maxFlightTime =
                shardEntity.flightTime + IceShardMirvDifficulty.ATTACH_DURATION + ATTACH_FLAMEOUT_MARGIN
            ship.addListener(ampListener)
        }

        // 钉住弹体被外部移除（战斗结束等）→ 终结
        if (!engine.isEntityInPlay(shardEntity)) {
            finish(engine, removeShard = false)
            return
        }

        elapsed += amount

        // 钉住弹体：随宿主移动/转向
        val worldPoint = currentWorldPoint()
        shardEntity.location.set(worldPoint.x, worldPoint.y)
        shardEntity.velocity.set(ship.velocity.x, ship.velocity.y)
        shardEntity.facing = facing0 + shardRelativeFacing + Misc.getAngleDiff(facing0, ship.facing)
        shardEntity.angularVelocity = 0f

        // 周期伤害 + 星云（同节奏）；到期判定置于结算之后，末周期不丢
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

    /** 当前附着点世界坐标：局部偏移随舰船**当前朝向**正旋后加舰心（未初始化时退回命中点）。 */
    private fun currentWorldPoint(): Vector2f {
        val local = offsetLocal ?: return Vector2f(hitPoint)
        return IceShardMirvMath.attachWorldPoint(ship.location, ship.facing, local)
    }

    /** 终结：摘监听器 + （可选）移除弹体 + 自注销。 */
    private fun finish(engine: CombatEngineAPI, removeShard: Boolean) {
        ship.removeListener(ampListener)
        if (removeShard && engine.isEntityInPlay(shardEntity)) engine.removeEntity(shardEntity)
        done = true
        engine.removePlugin(this)
    }

    /**
     * 冻结增伤监听器：命中点周围 [IceShardMirvDifficulty.AMP_RADIUS] su 范围内的承伤施加
     * 1+amp 乘区（护盾命中不增伤——冻结区在舰体上）。按命中点判定（DamageTakenModifier 携带
     * point），无需每帧范围扫描（反应式辐能装甲 VentGuardListener 同型先例）。
     *
     * 增伤不叠加：全部附着实例共用同一 [statId]，同一承伤事件内多个监听器的
     * modifyMult 同 id 互相覆盖，命中点附近无论附着多少枚冰晶至多生效一次增伤
     * （多来源 amp 值不同时取后写入者；同一承伤实例内不改写第二份乘区）。
     * 本效果的周期伤害同样落在附着点上，会经此乘区放大（与区域侵蚀语义一致，有意保留）。
     */
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

        /** 增伤 statId（全附着实例共用：同 id modifyMult 互相覆盖，增伤至多生效一次）。 */
        const val AMP_STAT_ID = "astd_ice_shard_amp"

        /** 遥测键：附着次数（命中舰船装甲/船体）。 */
        const val TELEMETRY_ATTACHES = "astd_ice_shard_attaches"

        /** 遥测键：冻结周期伤害结算次数。 */
        const val TELEMETRY_TICKS = "astd_ice_shard_ticks"

        /** 附着期弹体续命余量（秒）：附着 5s + 熄火淡出缓冲。 */
        private const val ATTACH_FLAMEOUT_MARGIN = 1.0f
    }
}
