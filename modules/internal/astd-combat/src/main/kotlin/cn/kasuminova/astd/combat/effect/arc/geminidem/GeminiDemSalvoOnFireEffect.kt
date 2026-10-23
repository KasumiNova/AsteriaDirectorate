package cn.kasuminova.astd.combat.effect.arc.geminidem

import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
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
import java.util.IdentityHashMap

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

        engine.removeEntity(projectile)

        // burst=2 时引擎对两发 dummy 各回调一次 onFire，仅首发生成齐射。
        val now = engine.getTotalElapsedTime(false)
        val lastSalvos = lastSalvoOf(engine)
        val lastAt = lastSalvos[weapon]
        if (lastAt != null && now - lastAt < SALVO_DEDUP_WINDOW_SECONDS) {
            log.debug("双子星 DEM 齐射：回声发（burst 第 2 发），仅移除 dummy（weapon=${weapon.id}）")
            return
        }
        lastSalvos[weapon] = now

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
        val warheads = if (weapon.spec?.weaponId == GeminiDemDifficulty.FIGHTER_WEAPON_ID) {
            WARHEADS_FIGHTER
        } else {
            WARHEADS_SHIP
        }

        for ((weaponId, projId, lateralSign) in warheads) {
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
            // 脚本 spawn 的弹体不触发 onFireEffect，拖尾必须显式登记。
            ProjectileVfxDriverPlugin.track(engine, missile, projId)
            missile.source = ship
            missile.armingTime = GeminiDemDifficulty.WARHEAD_ARMING_TIME
            missile.missileAI = GeminiDemTrackAI(missile, target)
            engine.customData[TELEMETRY_TRACK_AI_CREATED] = trackAiCreated(engine) + 1
            if (target != null) {
                engine.customData[TELEMETRY_TRACK_AI_TARGET_NONNULL] = trackAiTargetNonNull(engine) + 1
            }
            missile.setCustomData(GeminiDemDifficulty.SALVO_KEY, salvoId)
            engine.addPlugin(demPluginFactory(missile, ship, weapon))
            warheadsOf(engine) += WarheadRef(salvoId, weaponId, missile, engine.getTotalElapsedTime(false))
            engine.customData[TELEMETRY_WARHEADS_SPAWNED] = warheadsSpawned(engine) + 1
        }
        engine.customData[TELEMETRY_SALVO] = salvoCount(engine) + 1
        engine.customData[TELEMETRY_LAST_SALVO_ID] = salvoId
        log.info("双子星 DEM 齐射：salvo=$salvoId target=${target?.id ?: "无（直飞）"}")
    }

    companion object {
        private val log = Global.getLogger(GeminiDemSalvoOnFireEffect::class.java)

        private val WARHEADS_SHIP = listOf(
            Triple(GeminiDemDifficulty.KINETIC_WEAPON_ID, GeminiDemDifficulty.KINETIC_PROJ_ID, -1f),
            Triple(GeminiDemDifficulty.HE_WEAPON_ID, GeminiDemDifficulty.HE_PROJ_ID, 1f),
        )

        private val WARHEADS_FIGHTER = listOf(
            Triple(GeminiDemDifficulty.KINETIC_FIGHTER_WEAPON_ID, GeminiDemDifficulty.KINETIC_FIGHTER_PROJ_ID, -1f),
            Triple(GeminiDemDifficulty.HE_FIGHTER_WEAPON_ID, GeminiDemDifficulty.HE_FIGHTER_PROJ_ID, 1f),
        )

        const val TELEMETRY_SALVO = "astd_gemini_dem_salvo_count"

        const val TELEMETRY_WARHEADS_SPAWNED = "astd_gemini_dem_warheads_spawned"

        const val TELEMETRY_LAST_SALVO_ID = "astd_gemini_dem_last_salvo_id"

        const val TELEMETRY_TRACK_AI_CREATED = "astd_gemini_dem_track_ai_created"

        const val TELEMETRY_TRACK_AI_TARGET_NONNULL = "astd_gemini_dem_track_ai_target_nonnull"

        const val TELEMETRY_WARHEAD_REGISTRY = "astd_gemini_dem_warhead_registry"

        const val TELEMETRY_LAST_SALVO_AT = "astd_gemini_dem_last_salvo_at"

        const val SALVO_DEDUP_WINDOW_SECONDS = 0.5f

        fun salvoCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_SALVO] as? Int ?: 0
        fun warheadsSpawned(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_WARHEADS_SPAWNED] as? Int ?: 0
        fun trackAiCreated(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_TRACK_AI_CREATED] as? Int ?: 0
        fun trackAiTargetNonNull(engine: CombatEngineAPI): Int =
            engine.customData[TELEMETRY_TRACK_AI_TARGET_NONNULL] as? Int ?: 0

        data class WarheadRef(val salvoId: String, val weaponId: String, val missile: MissileAPI, val spawnedAt: Float)

        // 脚本 spawn 的弹头经 addObject 入队，引擎 flush 后进入 engine.getMissiles()；登记簿保存原始引用供直接读取。
        @Suppress("UNCHECKED_CAST")
        fun warheadsOf(engine: CombatEngineAPI): MutableList<WarheadRef> =
            engine.customData.getOrPut(TELEMETRY_WARHEAD_REGISTRY) { mutableListOf<WarheadRef>() }
                    as MutableList<WarheadRef>

        @Suppress("UNCHECKED_CAST")
        private fun lastSalvoOf(engine: CombatEngineAPI): IdentityHashMap<WeaponAPI, Float> =
            engine.customData.getOrPut(TELEMETRY_LAST_SALVO_AT) { IdentityHashMap<WeaponAPI, Float>() }
                    as IdentityHashMap<WeaponAPI, Float>
    }
}
