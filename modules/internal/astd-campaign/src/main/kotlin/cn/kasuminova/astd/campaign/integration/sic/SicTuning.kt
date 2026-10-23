package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingTable
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.campaign.ids.Personalities
import kotlin.math.roundToInt

/** SIC 联动数值声明与纯函数。SIC 约定：sourceId 含 [EXTERNAL_MOD_SUFFIX] 的无人舰点数在 SIC 界面显示为外部来源。 */
object SicTuning {

    const val SC_AUTO_DP_MOD = "sc_auto_dp"

    const val SC_AUTO_POINTS_MULT_STAT = "sc_auto_points_mult"

    const val EXTERNAL_MOD_SUFFIX = "_external"

    const val DEEP_DIVE_SKILL_ID = "sc_technology_deep_dive"

    const val DOCTRINE_PERSONALITY_MEM_KEY = "\$astd_sc_doctrine_personality"

    val SKYDOME_NETWORK_AUTO_POINTS = ScalingEntry(80f, 120f, 240f)

    val SKYDOME_NETWORK_CORE_MULT_REDUCTION = ScalingEntry(0.5f, 1f, 2.5f)

    val GRAV_PEAK_CR_PERCENT = ScalingEntry(15f, 20f, 35f)

    val GRAV_PHASE_TIME_FLOW_TABLE = ScalingTable(1f, 0.5f, 0.25f, 0f, 0f)

    const val GRAV_PHASE_TIME_FLOW_BASE = 0.5f

    val GRAV_FLUX_THRESHOLD_PERCENT = ScalingEntry(15f, 20f, 35f)

    val GRAV_PHASE_UPKEEP_REDUCTION = ScalingEntry(0.20f, 0.25f, 0.40f)

    val GRAV_PHASE_CR_REDUCTION = ScalingEntry(0.20f, 0.25f, 0.40f)

    val FROST_DISSIPATION_PERCENT = ScalingEntry(4f, 8f, 20f)

    val FROST_HARD_DISSIPATION_BONUS = ScalingEntry(0.10f, 0.15f, 0.30f)

    const val FROST_RAMP_START = 0.30f

    const val FROST_RAMP_SPAN = 0.55f

    val FROST_VENT_RATE_PERCENT = ScalingEntry(20f, 25f, 40f)

    val FROST_OVERLOAD_REDUCTION = ScalingEntry(0.15f, 0.25f, 0.55f)

    val FROST_EMP_REDUCTION = ScalingEntry(0.15f, 0.25f, 0.55f)

    val DIM_ARMOR_FRIGATE = ScalingEntry(25f, 50f, 125f)

    val DIM_ARMOR_DESTROYER = ScalingEntry(50f, 100f, 250f)

    val DIM_ARMOR_CRUISER = ScalingEntry(100f, 200f, 500f)

    val DIM_ARMOR_CAPITAL = ScalingEntry(200f, 400f, 1000f)

    const val DIM_ARMOR_CAP_FRACTION = 0.25f

    const val DIM_VENT_OVERLOAD_MULT = 2f

    val DIM_ENERGY_ABSORB = ScalingEntry(0.10f, 0.20f, 0.50f)

    val ASCEND_AUTO_POINTS = ScalingEntry(90f, 120f, 210f)

    val ASCEND_DP_REDUCTION = ScalingEntry(0.10f, 0.15f, 0.30f)

    const val MANUFACTURER_STARFALL = "菀星设计局-星坠"

    const val MANUFACTURER_ASTER = "菀星设计局-紫菀"

    val REINFORCE_AUTO_POINTS = ScalingEntry(90f, 120f, 210f)

    const val WOLFPACK_DP_FRIGATE = 0.20f

    const val WOLFPACK_DP_DESTROYER = 0.15f

    const val WOLFPACK_DP_CRUISER = 0.10f

    val WOLFPACK_BONUS_FRIGATE = ScalingEntry(0.10f, 0.15f, 0.30f)

    val WOLFPACK_BONUS_DESTROYER = ScalingEntry(0.05f, 0.10f, 0.25f)

    val WOLFPACK_BONUS_CRUISER = ScalingEntry(0.05f, 0.05f, 0.20f)

    const val INTEL_LINK_SKILL_ID = "astd_sc_intel_link"

    const val INTEL_LINK_HULLMOD_ID = "astd_sc_intel_link"

    const val INTEL_LINK_CORE_COMMODITY = "astd_ai_core_b"

    const val INTEL_LINK_STORY_POINT_COST = 1

    val UNBOUND_LEVEL_BONUS = ScalingTable(1f, 1f, 2f, 2f, 2f)

    val UNBOUND_STANDARD_CORE_EXTRA = ScalingTable(1f, 1f, 1f, 1f, 2f)

    const val UNBOUND_GRANTED_MEM_KEY = "\$astd_sc_unbound_levels"

    const val SMS_MOD_ID = "shipmasterysystem"

    /** SMS 全局精通强度 mod key（字面常量）：本类在无 SMS 环境也会被 SIC 加载，禁止 import 任何 shipmastery 类。 */
    const val SMS_GLOBAL_MASTERY_MOD = "sms_global_mastery_strength_mod"

    val UNBOUND_MASTERY_PERCENT = ScalingEntry(10f, 20f, 50f)

    const val BOUNTY_OFFICER_APTITUDE_ID = "astd_sc_wanxing"

    const val BOUNTY_OFFICER_PORTRAIT = "graphics/portraits/astd_portrait_core_a.png"

    const val SC_NO_RANDOM_OFFICERS_TAG = "sc_do_not_generate_skills"

    val BOUNTY_OFFICER_SKILL_IDS: List<String> = listOf(
        "astd_sc_armament_upgrade",
        "astd_sc_grav_tuning",
        "astd_sc_frost_flux_grid",
        "astd_sc_skydome_reinforce",
        "astd_sc_dim_shunt",
        "astd_sc_elite_wolfpack",
        "astd_sc_skydome_ascend",
        "astd_sc_intel_link",
        "astd_sc_skydome_unbound",
    )

    fun coreMultFactor(tierMult: Float, reduction: Float): Float =
        maxOf(1f, tierMult - reduction) / tierMult

    fun doctrinePersonality(aggression: Int): String = when (aggression) {
        1 -> Personalities.CAUTIOUS
        2 -> Personalities.STEADY
        3, 4 -> Personalities.AGGRESSIVE
        5 -> Personalities.RECKLESS
        else -> Personalities.STEADY
    }

    fun hardFluxFraction(hardFlux: Float, maxFlux: Float): Float =
        if (maxFlux > 0f) (hardFlux / maxFlux).coerceIn(0f, 1f) else 0f

    fun hardFluxRampT(hardFluxFraction: Float): Float =
        ((hardFluxFraction - FROST_RAMP_START) / FROST_RAMP_SPAN).coerceIn(0f, 1f)

    fun wolfpackBonusEntry(hullSize: ShipAPI.HullSize?): ScalingEntry? = when (hullSize) {
        ShipAPI.HullSize.FRIGATE -> WOLFPACK_BONUS_FRIGATE
        ShipAPI.HullSize.DESTROYER -> WOLFPACK_BONUS_DESTROYER
        ShipAPI.HullSize.CRUISER -> WOLFPACK_BONUS_CRUISER
        else -> null
    }

    fun wolfpackDeploymentBonus(hullSize: ShipAPI.HullSize?): Float = when (hullSize) {
        ShipAPI.HullSize.FRIGATE -> WOLFPACK_DP_FRIGATE
        ShipAPI.HullSize.DESTROYER -> WOLFPACK_DP_DESTROYER
        ShipAPI.HullSize.CRUISER -> WOLFPACK_DP_CRUISER
        else -> 0f
    }

    fun dimArmorEntry(hullSize: ShipAPI.HullSize?): ScalingEntry = when (hullSize) {
        ShipAPI.HullSize.DESTROYER -> DIM_ARMOR_DESTROYER
        ShipAPI.HullSize.CRUISER -> DIM_ARMOR_CRUISER
        ShipAPI.HullSize.CAPITAL_SHIP -> DIM_ARMOR_CAPITAL
        else -> DIM_ARMOR_FRIGATE
    }

    fun dimArmorBonus(baseArmor: Float, ventingOrOverloaded: Boolean, maxArmor: Float): Float {
        val mult = if (ventingOrOverloaded) DIM_VENT_OVERLOAD_MULT else 1f
        val cap = maxArmor.coerceAtLeast(0f) * DIM_ARMOR_CAP_FRACTION * mult
        return (baseArmor.coerceAtLeast(0f) * mult).coerceAtMost(cap)
    }

    fun dimEnergyAbsorbed(damage: Float, absorbFraction: Float): Float =
        damage.coerceAtLeast(0f) * absorbFraction.coerceIn(0f, 1f)

    fun unboundTargetBonus(base: Float, standardCoreExtra: Float, standardCore: Boolean): Int =
        base.roundToInt() + if (standardCore) standardCoreExtra.roundToInt() else 0

    fun unboundPendingDelta(target: Int, granted: Int): Int = (target - granted).coerceAtLeast(0)

    fun intelLinkButtonVisible(skillActive: Boolean, hasMarker: Boolean, fighter: Boolean): Boolean =
        skillActive && !hasMarker && !fighter

    fun intelLinkButtonClickable(cores: Float, storyPoints: Int): Boolean =
        cores >= 1f && storyPoints >= INTEL_LINK_STORY_POINT_COST

    fun isWanxingManufacturer(manufacturer: String?): Boolean =
        manufacturer == MANUFACTURER_STARFALL || manufacturer == MANUFACTURER_ASTER
}
