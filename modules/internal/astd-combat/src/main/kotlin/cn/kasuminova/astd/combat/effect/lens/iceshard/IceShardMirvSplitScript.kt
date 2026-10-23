package cn.kasuminova.astd.combat.effect.lens.iceshard

import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.GuidedMissileAI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class IceShardMirvSplitScript(
    private val missile: MissileAPI,
    private val source: ShipAPI?,
    private val random: Random = Random.Default,
) : BaseEveryFrameCombatPlugin() {
    private var done = false

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (done) return
        val engine = Global.getCombatEngine() ?: run {
            done = true
            return
        }
        if (engine.isPaused) return
        if (!engine.isEntityInPlay(missile)) {
            done = true
            return
        }

        val target = (missile.unwrappedMissileAI as? GuidedMissileAI)?.target ?: return
        if (!engine.isEntityInPlay(target)) return
        if (target is ShipAPI && target.isHulk) return
        val dist = MathUtils.getDistance(missile.location, target.location)
        if (!IceShardMirvMath.canSplit(missile.flightTime, dist)) return

        split(engine, missile, source, random, dist)
        done = true
    }

    companion object {
        private val log = Global.getLogger(IceShardMirvSplitScript::class.java)

        const val SUB_WEAPON_ID = "astd_ice_shard_sub"

        // 必须先 spawn 子弹再移除母弹，保证分裂帧母弹仍是合法来源上下文。
        fun split(
            engine: CombatEngineAPI,
            missile: MissileAPI,
            source: ShipAPI?,
            random: Random,
            splitDist: Float,
        ) {
            // 分裂一次性 claim：同帧竞态只爆一次。
            val claimKey = TELEMETRY_SPLIT_CLAIM_PREFIX + System.identityHashCode(missile)
            if (engine.customData[claimKey] == true) return
            engine.customData[claimKey] = true

            val damages = IceShardMirvMath.allocateShardDamages(random)
            val baseFacing = missile.facing
            val origin = Vector2f(missile.location)
            val halfCone = IceShardMirvDifficulty.SPLIT_CONE_DEG / 2f
            var spawned = 0
            for (damage in damages) {
                val angle = baseFacing + (random.nextFloat() * 2f - 1f) * halfCone
                val speed = IceShardMirvMath.shardSpeed(damage)
                val rad = Math.toRadians(angle.toDouble())
                val vel = Vector2f((cos(rad) * speed).toFloat(), (sin(rad) * speed).toFloat())
                val entity = engine.spawnProjectile(source, null, SUB_WEAPON_ID, origin, angle, vel)
                val shard = entity as? MissileAPI
                if (shard == null) {
                    log.error("源生冰晶分裂：spawnProjectile($SUB_WEAPON_ID) 返回非 MissileAPI（$entity），跳过本枚（理论不可达）")
                    continue
                }
                shard.source = source
                shard.damageAmount = damage
                // flightTime 是已飞时间（新生弹为 0），只能抬 maxFlightTime；误写会把已飞时间顶满，导弹出生即熄火淡出。
                shard.maxFlightTime = IceShardMirvDifficulty.SHARD_RANGE / speed
                // 脚本 spawn 的弹体不触发 onFireEffect，拖尾必须显式登记。
                ProjectileVfxDriverPlugin.track(engine, shard, shard.projectileSpecId)
                spawned++
            }

            IceShardMirvVfx.spawnSplitBurst(engine, origin, random)
            Global.getSoundPlayer().playSound("hurricane_mirv_split", 1f, 1f, origin, ZERO)
            engine.removeEntity(missile)
            // claim 键用完即回收，避免 customData 键在长战斗中堆积。
            engine.customData.remove(claimKey)

            bump(engine, TELEMETRY_SPLITS)
            engine.customData[TELEMETRY_SHARDS_SPAWNED] = shardsSpawned(engine) + spawned
            engine.customData[TELEMETRY_LAST_SPLIT_DIST] = splitDist
            log.info("[源生冰晶] 母弹分裂：冰晶 $spawned/${damages.size} 枚，分裂距离 ${splitDist.toInt()}su")
        }

        private const val TELEMETRY_SPLIT_CLAIM_PREFIX = "astd_ice_shard_mirv_split_claim:"

        const val TELEMETRY_SPLITS = "astd_ice_shard_mirv_splits"

        const val TELEMETRY_SHARDS_SPAWNED = "astd_ice_shard_mirv_shards_spawned"

        const val TELEMETRY_LAST_SPLIT_DIST = "astd_ice_shard_mirv_last_split_dist"

        private fun bump(engine: CombatEngineAPI, key: String) {
            engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
        }

        fun splits(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_SPLITS] as? Int ?: 0
        fun shardsSpawned(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_SHARDS_SPAWNED] as? Int ?: 0

        private val ZERO = Vector2f()
    }
}
