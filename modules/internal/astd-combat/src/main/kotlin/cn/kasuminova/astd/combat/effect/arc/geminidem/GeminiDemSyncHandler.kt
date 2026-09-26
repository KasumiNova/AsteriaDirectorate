package cn.kasuminova.astd.combat.effect.arc.geminidem

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 双子星 DEM 同步共振结算器（规格 10 §2.2，object 无状态形态，对齐 `ConeImpactHandler` 先例）。
 *
 * 判定链（全部满足才触发）：
 * 1. 同一目标已有异种弹头首击记录（registry 键 = 目标 id；规格 §0.2 裁定 2：批次号不作主键）；
 * 2. 时间差 ≤ [GeminiDemDifficulty.SYNC_WINDOW_SECONDS]（含边界；仅访问时惰性过期，无每帧扫描）；
 * 3. 同源（可判时严格）：两侧 sourceId 均可判且不同 → 不触发；任一不可判 → 按可触发处理（已知近似，§2.4-10）。
 *
 * 触发（2026-09 机制重做：弃一次性爆发伤害，改持续增伤 + 视觉共振）：
 * - 对双弹头 demDrone（beam.source = DEMScript 创建的 FX 无人机，payload 武器实际挂载在其上，
 *   光束伤害走 drone stats）的 `energyWeaponDamageMult` 施加乘区 `1 + 难度加成`
 *   （payload 光束 type=ENERGY，加成覆盖触发后的剩余照射伤害）；
 * - 在两侧 drone 实体 customData 写入紫色视觉到期时刻（[GeminiDemDifficulty.SYNC_VISUAL_KEY]，
 *   供 `GeminiDemPayloadBeamVfx` 渐变转紫；写在实体自身表上——FX drone id 为空串，引擎级表按 id 会碰撞）；
 * - `spawnExplosion` 白闪保留为触发反馈；触发即清，不重复触发。
 *
 * 未触发：覆盖为新首击记录。
 *
 * 难度取值调用点（规格 §2.2）：来源为玩家舰（[ShipAPI] 且 owner == 0）固定 v2；
 * 敌方/友军 AI 走轨一 [DifficultyTuning.value]；来源解析不到记 WARN 并保守取 v2（不静默，§2.4-7）。
 *
 * 引擎依赖收敛为 registry / now / tuning / onWarn 四个注入点（默认实参取真实引擎），
 * 单元测试用 fake 直接驱动真实判定逻辑（规格 §4.1）。
 */
object GeminiDemSyncHandler {

    /** 弹头种类（异种配对判定维度）。 */
    enum class WarheadKind { KINETIC, HE }

    /** 首击记录：命中时刻（战斗总秒）、弹头种类、来源弹头实体（施加增伤乘区用）、命中点。 */
    data class SyncRecord(
        val hitTime: Float,
        val kind: WarheadKind,
        val source: CombatEntityAPI?,
        val point: Vector2f,
    )

    /** 遥测键：同步共振触发次数（automation 场景观测面）。 */
    const val TELEMETRY_SYNC_TRIGGER = "astd_gemini_dem_sync_trigger_count"

    /** 遥测键：最近一次同步共振施加的伤害乘区（1 + 难度加成；玩家恒 v2=2.0 / 敌版轨一）。 */
    const val TELEMETRY_SYNC_LAST_MULT = "astd_gemini_dem_sync_last_mult"

    /** 遥测键：同步登记累计次数（首击写入；与触发次数配对观测「击落一枚无同步」）。 */
    const val TELEMETRY_HIT_REGISTERED = "astd_gemini_dem_hit_registered_count"

    private val log = Global.getLogger(GeminiDemSyncHandler::class.java)

    /** 同步共振闪光配色（白闪，规格 §2.3；不遮挡战场的克制量级）。 */
    private val SYNC_FLASH_COLOR = Color(235, 245, 255, 220)
    private const val SYNC_FLASH_SIZE = 90f
    private const val SYNC_FLASH_DURATION = 0.5f

    /**
     * 登记一次 payload 光束首伤帧命中；满足同步条件时施加共振增伤并点亮紫色视觉。
     *
     * @return true = 本次命中触发了同步共振
     */
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

                sourceShip.owner == 0 -> GeminiDemDifficulty.SYNC_DAMAGE_BONUS.v2
                else -> tuning.value(GeminiDemDifficulty.SYNC_DAMAGE_BONUS)
            }
            val mult = 1f + bonus
            applySyncMult(prev.source, mult)
            applySyncMult(source, mult)
            markSyncVisual(prev.source, now)
            markSyncVisual(source, now)
            registry.remove(target.id)
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

    /** 对弹头 demDrone 施加共振增伤乘区（drone 是 ShipAPI；不可判时记 WARN，不静默跳过）。 */
    private fun applySyncMult(source: CombatEntityAPI?, mult: Float) {
        val drone = source as? ShipAPI
        if (drone == null) {
            log.warn("双子星 DEM 同步共振：弹头实体不是 ShipAPI（$source），本侧增伤乘区未施加")
            return
        }
        drone.mutableStats.energyWeaponDamageMult.modifyMult(GeminiDemDifficulty.SYNC_STAT_MOD_ID, mult)
    }

    /** 点亮一枚弹头的紫色共振视觉（写在 drone 实体自身 customData：FX drone id 为空串，不能按 id 进引擎级表）。 */
    private fun markSyncVisual(source: CombatEntityAPI?, now: Float) {
        val drone = source as? ShipAPI ?: return
        drone.customData[GeminiDemDifficulty.SYNC_VISUAL_KEY] = now + GeminiDemDifficulty.SYNC_VISUAL_DURATION
    }

    /** 本场战斗同步登记表（engine.customData 惰性创建；战斗结束随表自然销毁，§2.4-4）。 */
    @Suppress("UNCHECKED_CAST")
    fun registryOf(engine: CombatEngineAPI): MutableMap<String, SyncRecord> =
        engine.customData.getOrPut(GeminiDemDifficulty.SYNC_REGISTRY_KEY) { mutableMapOf<String, SyncRecord>() }
                as MutableMap<String, SyncRecord>

    fun syncTriggerCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_SYNC_TRIGGER] as? Int ?: 0
    fun hitRegisteredCount(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_HIT_REGISTERED] as? Int ?: 0

    private val ZERO = Vector2f(0f, 0f)
}
