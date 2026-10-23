package cn.kasuminova.astd.combat.effect.arc.geminidem

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

object GeminiDemSyncHandler {

    enum class WarheadKind { KINETIC, HE }

    data class SyncRecord(
        val hitTime: Float,
        val kind: WarheadKind,
        val source: CombatEntityAPI?,
        val point: Vector2f,
    )

    const val TELEMETRY_SYNC_TRIGGER = "astd_gemini_dem_sync_trigger_count"

    const val TELEMETRY_SYNC_LAST_MULT = "astd_gemini_dem_sync_last_mult"

    const val TELEMETRY_HIT_REGISTERED = "astd_gemini_dem_hit_registered_count"

    private val log = Global.getLogger(GeminiDemSyncHandler::class.java)

    private val SYNC_FLASH_COLOR = Color(235, 245, 255, 220)
    private const val SYNC_FLASH_SIZE = 90f
    private const val SYNC_FLASH_DURATION = 0.5f

    fun recordHit(
        engine: CombatEngineAPI,
        target: ShipAPI,
        kind: WarheadKind,
        point: Vector2f,
        source: CombatEntityAPI?,
        tuning: DifficultyTuning = DifficultyTuningImpl,
        now: Float = engine.getTotalElapsedTime(false),
        registry: MutableMap<String, SyncRecord> = registryOf(engine),
        onWarn: (String) -> Unit = { log.warn(it) },
    ): Boolean {
        val curSourceId = (source as? ShipAPI)?.id
        val prev = registry[target.id]?.takeIf { now - it.hitTime <= GeminiDemDifficulty.SYNC_WINDOW_SECONDS }
        val prevSourceId = (prev?.source as? ShipAPI)?.id

        val sameSource = prev == null || prevSourceId == null || curSourceId == null || prevSourceId == curSourceId
        if (prev != null && prev.kind != kind && sameSource) {
            val sourceShip = source as? ShipAPI
            val bonus = when {
                sourceShip == null -> {
                    onWarn("双子星 DEM 同步共振：beam.source 解析失败（$source），保守取 v2 加成（目标=${target.id}）")
                    GeminiDemDifficulty.SYNC_DAMAGE_BONUS.v2
                }

                sourceShip.owner == 0 -> tuning.valueFor(GeminiDemDifficulty.SYNC_DAMAGE_BONUS, true)
                else -> tuning.value(GeminiDemDifficulty.SYNC_DAMAGE_BONUS)
            }
            val mult = 1f + bonus
            applySyncMult(prev.source, mult)
            applySyncMult(source, mult)
            markSyncVisual(prev.source, now)
            markSyncVisual(source, now)
            registry.remove(target.id)
            resonatedOf(engine)[target.id] = now
            engine.spawnExplosion(point, ZERO, SYNC_FLASH_COLOR, SYNC_FLASH_SIZE, SYNC_FLASH_DURATION)
            engine.customData[TELEMETRY_SYNC_TRIGGER] = syncTriggerCount(engine) + 1
            engine.customData[TELEMETRY_SYNC_LAST_MULT] = mult
            log.info(
                "双子星 DEM 同步共振：target=${target.id} mult=$mult " +
                        "source=${sourceShip?.id ?: "不可判"} owner=${sourceShip?.owner ?: "不可判"}（配对=${prev.kind}->$kind，Δt=${now - prev.hitTime}s）",
            )
            return true
        }

        registry[target.id] = SyncRecord(now, kind, source, Vector2f(point))
        engine.customData[TELEMETRY_HIT_REGISTERED] = hitRegisteredCount(engine) + 1
        return false
    }

    // payload 光束挂在 DEMScript 新建的 FX drone 上，光束伤害走 drone stats。
    private fun applySyncMult(source: CombatEntityAPI?, mult: Float) {
        val drone = source as? ShipAPI
        if (drone == null) {
            log.warn("双子星 DEM 同步共振：弹头实体不是 ShipAPI（$source），本侧增伤乘区未施加")
            return
        }
        drone.mutableStats.energyWeaponDamageMult.modifyMult(GeminiDemDifficulty.SYNC_STAT_MOD_ID, mult)
    }

    // FX drone 的 id 为空串，视觉状态只能写在实体自身 customData。
    private fun markSyncVisual(source: CombatEntityAPI?, now: Float) {
        val drone = source as? ShipAPI ?: return
        drone.customData[GeminiDemDifficulty.SYNC_VISUAL_KEY] = now + GeminiDemDifficulty.SYNC_VISUAL_DURATION
    }

    @Suppress("UNCHECKED_CAST")
    fun registryOf(engine: CombatEngineAPI): MutableMap<String, SyncRecord> =
        engine.customData.getOrPut(GeminiDemDifficulty.SYNC_REGISTRY_KEY) { mutableMapOf<String, SyncRecord>() }
                as MutableMap<String, SyncRecord>

    @Suppress("UNCHECKED_CAST")
    fun resonatedOf(engine: CombatEngineAPI): MutableMap<String, Float> =
        engine.customData.getOrPut(GeminiDemDifficulty.SYNC_RESONATED_KEY) { mutableMapOf<String, Float>() }
                as MutableMap<String, Float>

    fun wasResonated(engine: CombatEngineAPI, target: ShipAPI): Boolean =
        resonatedOf(engine).containsKey(target.id)

    fun syncTriggerCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_SYNC_TRIGGER] as? Int ?: 0
    fun hitRegisteredCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_HIT_REGISTERED] as? Int ?: 0

    private val ZERO = Vector2f(0f, 0f)
}
