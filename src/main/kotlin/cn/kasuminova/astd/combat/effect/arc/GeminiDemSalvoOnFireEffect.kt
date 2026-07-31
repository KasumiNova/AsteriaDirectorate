package cn.kasuminova.astd.combat.effect.arc

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.impl.combat.dem.DEMScript
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

/**
 * 双子星 DEM 齐射发射回调（规格 10 §2.2，主武器 dummy .proj 的 `onFireEffect`，唯一插件入口）。
 *
 * 结算顺序固定：
 * 1. 移除 dummy（同帧）；
 * 2. 定目标：`ship.shipTarget`（存活/非残骸/敌对）→ 空则 2500su 内最近敌舰 → 仍空则双弹直飞（定义行为）；
 * 3. `spawnProjectile` ×2（动能 + 高爆，±12su 垂直错位、±2° 朝向散布）；
 * 4. 每枚弹头：source=发射舰、`setArmingTime(0.3)`、`setMissileAI(GeminiDemTrackAI)`、
 *    写齐射批次号（仅日志/调试关联）、`addPlugin(DEMScript)`——等价原版 DEMEffect 全部逻辑
 *    （规格 §0.1 事实 #3/#4：DEMScript 行为参数全部读自弹头 .proj 的 behaviorSpec）。
 *
 * 0 值与边界（规格 §2.4）：
 * - `weapon.ship == null`：记 WARN 放行 dummy 正常飞行（只兜异常调用，不改变 vanilla 路径外行为）；
 * - `spawnProjectile` 返回非 [MissileAPI]：记 ERROR 跳过该枚，另一枚不受影响（理论不可达）。
 *
 * @param demPluginFactory DEM 打击段插件装配（默认真实 DEMScript；测试注入记录桩断言装配发生）
 */
class GeminiDemSalvoOnFireEffect(
    private val demPluginFactory: (MissileAPI, ShipAPI, WeaponAPI) -> EveryFrameCombatPlugin = { missile, ship, weapon ->
        DEMScript(missile, ship, weapon)
    },
) : OnFireEffectPlugin {

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        val ship = weapon.ship
        if (ship == null) {
            log.warn("双子星 DEM 齐射：weapon.ship 为 null（weapon=${weapon.id}），放行 dummy 正常飞行，不生成双弹头")
            return
        }

        // dummy 同帧移除（规格 §2.2 第 2 步）
        engine.removeEntity(projectile)

        val shipTarget = ship.shipTarget?.takeIf {
            it.isAlive && !it.isHulk && it.owner != ship.owner
        }
        val target = shipTarget
            ?: geminiDemFindNearestEnemyShip(engine, projectile.location, ship.owner, GeminiDemDifficulty.TRACK_TARGET_RANGE)
        if (target == null) {
            log.debug("双子星 DEM 齐射：无目标（shipTarget 空且 ${GeminiDemDifficulty.TRACK_TARGET_RANGE}su 内无敌舰），双弹直飞")
        }

        val salvoId = "astd_gemini_salvo:${ship.id}:${engine.getTotalElapsedTime(false)}"
        val facing = projectile.facing
        val baseLoc = projectile.location

        for ((weaponId, lateralSign) in WARHEADS) {
            // 垂直错位：沿 facing 垂直方向偏移 lateralSign × 12su（规格 §2.2 第 5 步）
            val loc = MathUtils.getPointOnCircumference(
                Vector2f(baseLoc),
                GeminiDemDifficulty.SALVO_LATERAL_OFFSET * lateralSign,
                facing + 90f,
            )
            val ang = facing + GeminiDemDifficulty.SALVO_FACING_SPREAD_DEG * lateralSign
            val vel = Vector2f(ship.velocity)
            val spawned = engine.spawnProjectile(ship, null, weaponId, loc, ang, vel)
            val missile = spawned as? MissileAPI
            if (missile == null) {
                log.error("双子星 DEM 齐射：spawnProjectile($weaponId) 返回非 MissileAPI（$spawned），跳过本枚（理论不可达）")
                continue
            }
            missile.source = ship
            missile.setArmingTime(GeminiDemDifficulty.WARHEAD_ARMING_TIME)
            missile.setMissileAI(GeminiDemTrackAI(missile, target))
            // 供给侧 R1 证据（规格 §4.2 检查点 3）：TrackAI 装配即 GuidedMissileAI 供目标；
            // 实机判例：API 侧包装弹头的 getAI 读回是引擎包装对象，读回路径不可作观测面。
            engine.customData[TELEMETRY_TRACK_AI_CREATED] = trackAiCreated(engine) + 1
            if (target != null) {
                engine.customData[TELEMETRY_TRACK_AI_TARGET_NONNULL] = trackAiTargetNonNull(engine) + 1
            }
            missile.customData[GeminiDemDifficulty.SALVO_KEY] = salvoId
            engine.addPlugin(demPluginFactory(missile, ship, weapon))
            // 出生登记（实机判例：engine.getMissiles() 不含脚本 spawn 的弹头，场景观测只能走出生登记簿）
            warheadsOf(engine) += WarheadRef(salvoId, weaponId, missile, engine.getTotalElapsedTime(false))
            engine.customData[TELEMETRY_WARHEADS_SPAWNED] = warheadsSpawned(engine) + 1
        }
        engine.customData[TELEMETRY_SALVO] = salvoCount(engine) + 1
        engine.customData[TELEMETRY_LAST_SALVO_ID] = salvoId
        log.info("双子星 DEM 齐射：salvo=$salvoId target=${target?.id ?: "无（直飞）"}")
    }

    companion object {
        private val log = Global.getLogger(GeminiDemSalvoOnFireEffect::class.java)

        /** 齐射编成：动能 -1 舷 / 高爆 +1 舷。 */
        private val WARHEADS = listOf(
            GeminiDemDifficulty.KINETIC_WEAPON_ID to -1f,
            GeminiDemDifficulty.HE_WEAPON_ID to 1f,
        )

        /** 遥测键：齐射次数（automation 场景观测面，对齐既有组遥测先例）。 */
        const val TELEMETRY_SALVO = "astd_gemini_dem_salvo_count"

        /** 遥测键：已生成弹头总数（应为齐射次数 ×2；非 ×2 即有生成失败）。 */
        const val TELEMETRY_WARHEADS_SPAWNED = "astd_gemini_dem_warheads_spawned"

        /** 遥测键：最近一次齐射批次号（日志关联）。 */
        const val TELEMETRY_LAST_SALVO_ID = "astd_gemini_dem_last_salvo_id"

        /** 遥测键：已装配 TrackAI 的弹头数（供给侧 R1：应为齐射次数 ×2）。 */
        const val TELEMETRY_TRACK_AI_CREATED = "astd_gemini_dem_track_ai_created"

        /** 遥测键：装配时目标非空的 TrackAI 弹头数（DEMScript WAIT 段触发前提的供给侧证据）。 */
        const val TELEMETRY_TRACK_AI_TARGET_NONNULL = "astd_gemini_dem_track_ai_target_nonnull"

        /** customData 键：弹头出生登记簿（脚本 spawn 弹头的唯一可靠观测面，见 warheadsOf 注释）。 */
        const val TELEMETRY_WARHEAD_REGISTRY = "astd_gemini_dem_warhead_registry"

        fun salvoCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_SALVO] as? Int ?: 0
        fun warheadsSpawned(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_WARHEADS_SPAWNED] as? Int ?: 0
        fun trackAiCreated(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_TRACK_AI_CREATED] as? Int ?: 0
        fun trackAiTargetNonNull(engine: CombatEngineAPI): Int =
            engine.customData[TELEMETRY_TRACK_AI_TARGET_NONNULL] as? Int ?: 0

        /** 弹头出生登记条目：齐射批次号、弹头武器 id、导弹引用（配置完成态）、出生时刻。 */
        data class WarheadRef(val salvoId: String, val weaponId: String, val missile: MissileAPI, val spawnedAt: Float)

        /**
         * 本场战斗弹头出生登记簿（engine.customData 惰性创建，战斗结束自然销毁）。
         * 动机（实机判例）：`engine.getMissiles()` 不含脚本 spawn 的弹头，customData/weaponSpec
         * 扫描观测面全部落空；场景与诊断改用出生时的原始引用。
         */
        @Suppress("UNCHECKED_CAST")
        fun warheadsOf(engine: CombatEngineAPI): MutableList<WarheadRef> =
            engine.customData.getOrPut(TELEMETRY_WARHEAD_REGISTRY) { mutableListOf<WarheadRef>() }
                as MutableList<WarheadRef>
    }
}
