package cn.kasuminova.astd.combat.effect.arc.geminidem

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingMap
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl

object GeminiDemDifficulty {

    const val KINETIC_WEAPON_ID = "astd_gemini_dem_kinetic"

    const val HE_WEAPON_ID = "astd_gemini_dem_he"

    const val KINETIC_PROJ_ID = "astd_gemini_dem_kinetic_msl"

    const val HE_PROJ_ID = "astd_gemini_dem_he_msl"

    const val KINETIC_PAYLOAD_ID = "astd_gemini_dem_kinetic_payload"

    const val HE_PAYLOAD_ID = "astd_gemini_dem_he_payload"

    const val FIGHTER_WEAPON_ID = "astd_gemini_dem_fighter"

    // DEMScript 新建 FX drone 时不复制弹头 customData，战机变体必须通过独立 spec 识别。
    const val KINETIC_FIGHTER_WEAPON_ID = "astd_gemini_dem_kinetic_fighter"
    const val HE_FIGHTER_WEAPON_ID = "astd_gemini_dem_he_fighter"
    const val KINETIC_FIGHTER_PROJ_ID = "astd_gemini_dem_kinetic_fighter_msl"
    const val HE_FIGHTER_PROJ_ID = "astd_gemini_dem_he_fighter_msl"
    const val KINETIC_PAYLOAD_FIGHTER_ID = "astd_gemini_dem_kinetic_payload_fighter"
    const val HE_PAYLOAD_FIGHTER_ID = "astd_gemini_dem_he_payload_fighter"

    const val WARHEAD_PANEL_DAMAGE = 1000f

    const val SYNC_WINDOW_SECONDS = 1f

    const val EMP_ARC_INTERVAL = 0.2f

    const val EMP_ARC_COUNT = 5

    const val EMP_ARC_SHARE_OF_TOTAL = 0.2f

    val EMP_TOTAL_FRACTION = ScalingEntry(1.0f, 2.0f, 5.0f, ScalingMap.LINEAR)

    val SYNC_DAMAGE_BONUS = ScalingEntry(0.5f, 1.0f, 2.5f, ScalingMap.LINEAR)

    const val SYNC_STAT_MOD_ID = "astd_gemini_dem_sync"

    const val FIGHTER_DAMAGE_MULT = 0.75f

    const val SYNC_VISUAL_DURATION = 1.2f

    const val ARC_PIERCE_RAMP_START = 0.25f
    const val ARC_PIERCE_RAMP_FULL = 0.90f
    const val ARC_PIERCE_MAX_CHANCE = 0.5f

    fun arcShieldPierceChance(hardFluxFraction: Float): Float =
        ((hardFluxFraction - ARC_PIERCE_RAMP_START) / (ARC_PIERCE_RAMP_FULL - ARC_PIERCE_RAMP_START))
            .coerceIn(0f, 1f) * ARC_PIERCE_MAX_CHANCE

    fun hardFluxFraction(hardFlux: Float, maxFlux: Float): Float =
        if (maxFlux > 0f) (hardFlux / maxFlux).coerceIn(0f, 1f) else 0f

    const val TRACK_TARGET_RANGE = 2500f

    const val SALVO_LATERAL_OFFSET = 12f

    const val SALVO_FACING_SPREAD_DEG = 2f

    const val WARHEAD_ARMING_TIME = 0.3f

    const val SALVO_KEY = "astd_gemini_salvo"

    const val SYNC_REGISTRY_KEY = "astd_gemini_sync_registry"

    const val SYNC_RESONATED_KEY = "astd_gemini_sync_resonated"

    const val SYNC_VISUAL_KEY = "astd_gemini_dem_sync_visual"

    fun resolve(entry: ScalingEntry, sourceOwner: Int): Float =
        resolve(DifficultyTuningImpl, entry, sourceOwner)

    fun resolve(tuning: DifficultyTuning, entry: ScalingEntry, sourceOwner: Int): Float =
        tuning.valueFor(entry, sourceOwner == 0)
}
