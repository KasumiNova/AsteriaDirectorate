package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 双子星 DEM 同步冲击结算器（规格 10 §2.2，object 无状态形态，对齐 `ConeImpactHandler` 先例）。
 *
 * 判定链（全部满足才触发）：
 * 1. 同一目标已有异种弹头首击记录（registry 键 = 目标 id；规格 §0.2 裁定 2：批次号不作主键）；
 * 2. 时间差 ≤ [GeminiDemDifficulty.SYNC_WINDOW_SECONDS]（含边界；仅访问时惰性过期，无每帧扫描）；
 * 3. 同源（可判时严格）：两侧 sourceId 均可判且不同 → 不触发；任一不可判 → 按可触发处理（已知近似，§2.4-10）。
 *
 * 触发：`applyDamage(ENERGY, 2500 × 难度倍率, showDamageFloaty = true)` 原生伤害数字 +
 * `spawnExplosion` 白色闪光（2026-07-29 审批裁定：原生已弹字，不另绘自定义浮字）；触发即清，不重复触发。
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

    /** 首击记录：命中时刻（战斗总秒）、弹头种类、来源舰 id（可判缺失为 null）、命中点。 */
    data class SyncRecord(
        val hitTime: Float,
        val kind: WarheadKind,
        val sourceId: String?,
        val point: Vector2f,
    )

    /** 遥测键：同步冲击触发次数（automation 场景观测面）。 */
    const val TELEMETRY_SYNC_TRIGGER = "astd_gemini_dem_sync_trigger_count"

    /** 遥测键：最近一次同步冲击的难度倍率（玩家恒 v2=0.4375 / 敌版轨一）。 */
    const val TELEMETRY_SYNC_LAST_MULT = "astd_gemini_dem_sync_last_mult"

    /** 遥测键：同步登记累计次数（首击写入；与触发次数配对观测「击落一枚无同步」）。 */
    const val TELEMETRY_HIT_REGISTERED = "astd_gemini_dem_hit_registered_count"

    private val log = Global.getLogger(GeminiDemSyncHandler::class.java)

    /** 同步冲击闪光配色（白闪，规格 §2.3；不遮挡战场的克制量级）。 */
    private val SYNC_FLASH_COLOR = Color(235, 245, 255, 220)
    private const val SYNC_FLASH_SIZE = 90f
    private const val SYNC_FLASH_DURATION = 0.5f

    /**
     * 登记一次 payload 光束首伤帧命中；满足同步条件时追加结算同步冲击。
     *
     * @return true = 本次命中触发了同步冲击
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

        val sameSource = prev == null || prev.sourceId == null || curSourceId == null || prev.sourceId == curSourceId
        if (prev != null && prev.kind != kind && sameSource) {
            val sourceShip = source as? ShipAPI
            val mult = when {
                sourceShip == null -> {
                    onWarn("双子星 DEM 同步冲击：beam.source 解析失败（$source），保守取 v2 倍率（目标=${target.id}）")
                    GeminiDemDifficulty.SYNC_MULT.v2
                }
                sourceShip.owner == 0 -> GeminiDemDifficulty.SYNC_MULT.v2
                else -> tuning.value(GeminiDemDifficulty.SYNC_MULT)
            }
            val damage = GeminiDemDifficulty.SYNC_BASE_DAMAGE * mult
            engine.applyDamage(target, point, damage, DamageType.ENERGY, 0f, true, false, source, true)
            registry.remove(target.id)
            engine.spawnExplosion(point, ZERO, SYNC_FLASH_COLOR, SYNC_FLASH_SIZE, SYNC_FLASH_DURATION)
            engine.customData[TELEMETRY_SYNC_TRIGGER] = syncTriggerCount(engine) + 1
            engine.customData[TELEMETRY_SYNC_LAST_MULT] = mult
            log.info(
                "双子星 DEM 同步冲击：target=${target.id} damage=$damage mult=$mult " +
                    "source=${sourceShip?.id ?: "不可判"} owner=${sourceShip?.owner ?: "不可判"}（配对=${prev.kind}->$kind，Δt=${now - prev.hitTime}s）",
            )
            return true
        }

        registry[target.id] = SyncRecord(now, kind, curSourceId, Vector2f(point))
        engine.customData[TELEMETRY_HIT_REGISTERED] = hitRegisteredCount(engine) + 1
        return false
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
