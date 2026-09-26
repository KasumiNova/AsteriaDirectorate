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

/**
 * 源生冰晶 MIRV 母弹分裂引信脚本（purple/30-superlative.md §机制）：每发母弹一个实例，
 * 由 [IceShardMirvOnFireEffect] 在发射时注册。
 *
 * 每帧判定：母弹追踪目标（原版引导 AI 经 [GuidedMissileAI] 暴露）存活、距目标进入分裂距离
 * 且已过发射豁免期（[IceShardMirvDifficulty.SPLIT_IMMUNITY_SECONDS]，贴脸甩射的载舰安全窗口）→
 * 立即分裂：沿飞行方向 [IceShardMirvDifficulty.SPLIT_CONE_DEG] 锥内随机射出
 * [IceShardMirvDifficulty.SHARD_COUNT] 枚冰晶射弹（单枚伤害随机、合计恒定、
 * 弹速随伤害 ±25% 浮动），随后移除母弹。母弹自然淡出/无目标时不分裂。
 *
 * 0 值防线：分裂一次性 claim（同帧多路径竞态只爆一次）；`spawnProjectile` 返回非 MissileAPI
 * 记 ERROR 跳过该枚，其余不受影响（理论不可达）；母弹被外部移除（战斗结束/被击毁）静默回收。
 */
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
        // 引擎暂停时本不回调，显式防线双保险
        if (engine.isPaused) return
        if (!engine.isEntityInPlay(missile)) {
            // 母弹在飞行中被移除（战斗结束/被击毁）：静默回收，非分裂路径
            done = true
            return
        }

        val target = (missile.unwrappedMissileAI as? GuidedMissileAI)?.target ?: return
        if (!engine.isEntityInPlay(target)) return
        if (target is ShipAPI && target.isHulk) return
        val dist = MathUtils.getDistance(missile.location, target.location)
        // 分裂门控（纯函数，单测直驱）：发射豁免期内不分裂 + 距目标进入分裂距离才分裂
        if (!IceShardMirvMath.canSplit(missile.flightTime, dist)) return

        split(engine, missile, source, random, dist)
        done = true
    }

    companion object {
        private val log = Global.getLogger(IceShardMirvSplitScript::class.java)

        /** 冰晶子射弹的隐藏武器 id（weapon_data.csv / .wpn 同名）。 */
        const val SUB_WEAPON_ID = "astd_ice_shard_sub"

        /**
         * 分裂共享实现（引信脚本唯一调用点）：分配伤害 → 逐枚 spawn 冰晶 → 分裂 VFX/音效 →
         * 移除母弹（先 spawn 后移除，保证分裂帧母弹仍是合法来源上下文）。
         */
        fun split(
            engine: CombatEngineAPI,
            missile: MissileAPI,
            source: ShipAPI?,
            random: Random,
            splitDist: Float,
        ) {
            // 一次性声明：分裂只发生一次（先 claim 者胜出）
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
                // 射程恒 1000su：寿命上限 = 射程 / 本枚弹速（弹速随伤害 ±25% 浮动）。
                // 注意 flightTime 是「已飞时间」（新生弹为 0），只能动 maxFlightTime 上限——
                // 误写 flightTime 会把已飞时间顶满，导弹出生即熄火淡出（实机 attaches=0 的根因）
                shard.maxFlightTime = IceShardMirvDifficulty.SHARD_RANGE / speed
                // spawnProjectile 不经武器开火管线（onFireEffect 不触发）：拖尾/本体等弹体 VFX 在此显式登记
                ProjectileVfxDriverPlugin.track(engine, shard, shard.projectileSpecId)
                spawned++
            }

            IceShardMirvVfx.spawnSplitBurst(engine, origin, random)
            Global.getSoundPlayer().playSound("hurricane_mirv_split", 1f, 1f, origin, ZERO)
            engine.removeEntity(missile)
            // 一次性声明键随分裂完成回收，避免长战斗下 customData 键线性堆积
            engine.customData.remove(claimKey)

            bump(engine, TELEMETRY_SPLITS)
            engine.customData[TELEMETRY_SHARDS_SPAWNED] = shardsSpawned(engine) + spawned
            engine.customData[TELEMETRY_LAST_SPLIT_DIST] = splitDist
            log.info("[源生冰晶] 母弹分裂：冰晶 $spawned/${damages.size} 枚，分裂距离 ${splitDist.toInt()}su")
        }

        /** 分裂一次性声明键前缀（engine.customData，全键 = 前缀 + 母弹 identityHashCode）。 */
        private const val TELEMETRY_SPLIT_CLAIM_PREFIX = "astd_ice_shard_mirv_split_claim:"

        /** 遥测键：分裂次数。 */
        const val TELEMETRY_SPLITS = "astd_ice_shard_mirv_splits"

        /** 遥测键：已生成冰晶总数（应为分裂次数 ×15；不足即有生成失败）。 */
        const val TELEMETRY_SHARDS_SPAWNED = "astd_ice_shard_mirv_shards_spawned"

        /** 遥测键：最近一次分裂距离（su）。 */
        const val TELEMETRY_LAST_SPLIT_DIST = "astd_ice_shard_mirv_last_split_dist"

        /** 遥测计数自增（缺省 0 起）。 */
        private fun bump(engine: CombatEngineAPI, key: String) {
            engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
        }

        fun splits(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_SPLITS] as? Int ?: 0
        fun shardsSpawned(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_SHARDS_SPAWNED] as? Int ?: 0

        /** 静止速度矢量（音效用，避免逐次分配）。 */
        private val ZERO = Vector2f()
    }
}
