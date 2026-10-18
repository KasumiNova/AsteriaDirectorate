package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingTable
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.campaign.ids.Personalities
import kotlin.math.roundToInt

/**
 * SecondInCommand 联动「菀星智能」天赋树的机制数值声明与纯函数
 * （docs/design/skills/10-sic-skills.md；插件实现见本包各 Skill 类）。
 *
 * 全部三锚点 [ScalingEntry] / 五档 [ScalingTable] 与固定值集中在此，
 * 技能插件运行时经 DifficultyTuningImpl 按来源阵营（SCData.isPlayer）取值。
 *
 * SIC 侧约定（SecondInCommand 1.8.0 源码口径）：
 * - 无人舰点数写在玩家 Person 的 dynamic mod [SC_AUTO_DP_MOD]，
 *   sourceId 含 [EXTERNAL_MOD_SUFFIX] 时在 SIC 的自动化舰船界面显示为外部来源；
 * - AI 核心点数倍率为舰船 stats dynamic stat [SC_AUTO_POINTS_MULT_STAT]（基础 1），
 *   与舰长 memory `$autoPointsMult` 相乘得到最终倍率。
 */
object SicTuning {

    // ─── SIC / 原版 stat 契约 ───

    /** SIC 无人舰点数池的 dynamic mod key（玩家 Person stats；AutomatedShipsManager 消费）。 */
    const val SC_AUTO_DP_MOD = "sc_auto_dp"

    /** SIC AI 核心点数倍率的舰船 dynamic stat key（MutableStat，基础 1）。 */
    const val SC_AUTO_POINTS_MULT_STAT = "sc_auto_points_mult"

    /** 外部来源后缀：SIC UtilitySkill 以此识别并单独展示外部提供的无人舰点数。 */
    const val EXTERNAL_MOD_SUFFIX = "_external"

    /** SIC 尖端技术天赋的「相位深潜」技能 id（引力调谐共存屏蔽判定）。 */
    const val DEEP_DIVE_SKILL_ID = "sc_technology_deep_dive"

    /** AI 核心性格跟随标记（Person memory key）：由天幕网络打标，停用时据此恢复无畏。 */
    const val DOCTRINE_PERSONALITY_MEM_KEY = "\$astd_sc_doctrine_personality"

    // ─── 天幕网络（astd_sc_skydome_network，固有技能） ───

    /** +无人舰点数（设计 80/120/240）。 */
    val SKYDOME_NETWORK_AUTO_POINTS = ScalingEntry(80f, 120f, 240f)

    /** 菀星制式核心点数倍率降低量（设计 0.5x/1x/2.5x，降低后最低 1x）。 */
    val SKYDOME_NETWORK_CORE_MULT_REDUCTION = ScalingEntry(0.5f, 1f, 2.5f)

    // ─── 引力调谐（astd_sc_grav_tuning） ───

    /** +峰值时间百分比（设计 15%/20%/35%；与相位深潜共存时不生效）。 */
    val GRAV_PEAK_CR_PERCENT = ScalingEntry(15f, 20f, 35f)

    /**
     * 相位时流降低的五档系数（设计 100%/50%/25%/0%/0%，逐档表；
     * 与相位深潜共存时不生效）。实际写入量 = -[GRAV_PHASE_TIME_FLOW_BASE] × 本表取值。
     */
    val GRAV_PHASE_TIME_FLOW_TABLE = ScalingTable(1f, 0.5f, 0.25f, 0f, 0f)

    /** 相位时流降低基准（设计 -50%；砺刃档 50% 系数 → 实际 -25%）。 */
    const val GRAV_PHASE_TIME_FLOW_BASE = 0.5f

    /** 相位失速辐能阈值提升百分比（设计 15%/20%/35%）。 */
    val GRAV_FLUX_THRESHOLD_PERCENT = ScalingEntry(15f, 20f, 35f)

    /** 相位硬辐能产出降低（设计 20%/25%/40%）。 */
    val GRAV_PHASE_UPKEEP_REDUCTION = ScalingEntry(0.20f, 0.25f, 0.40f)

    /** 相位下峰值时间与 CR 消耗速度降低（设计 20%/25%/40%）。 */
    val GRAV_PHASE_CR_REDUCTION = ScalingEntry(0.20f, 0.25f, 0.40f)

    // ─── 六相冰辐能网络（astd_sc_frost_flux_grid） ───

    /** 辐能耗散提升百分比（设计 4%/8%/20%）。 */
    val FROST_DISSIPATION_PERCENT = ScalingEntry(4f, 8f, 20f)

    /** 硬辐能耗散最大提升（设计 10%/15%/30%；随辐能水平斜坡生效）。 */
    val FROST_HARD_DISSIPATION_BONUS = ScalingEntry(0.10f, 0.15f, 0.30f)

    /** 硬辐能斜坡起点（辐能水平 30% 开始提升）。 */
    const val FROST_RAMP_START = 0.30f

    /** 硬辐能斜坡宽度（30% → 85% 线性到满）。 */
    const val FROST_RAMP_SPAN = 0.55f

    /** 强制排辐速度提升百分比（设计 20%/25%/40%）。 */
    val FROST_VENT_RATE_PERCENT = ScalingEntry(20f, 25f, 40f)

    /** 过载时间降低（设计 15%/25%/55%）。 */
    val FROST_OVERLOAD_REDUCTION = ScalingEntry(0.15f, 0.25f, 0.55f)

    /** 受 EMP 伤害降低（设计 15%/25%/55%）。 */
    val FROST_EMP_REDUCTION = ScalingEntry(0.15f, 0.25f, 0.55f)

    // ─── 维度分流（astd_sc_dim_shunt） ───

    /** 装甲减伤计算值：护卫舰（设计 25/50/125）。 */
    val DIM_ARMOR_FRIGATE = ScalingEntry(25f, 50f, 125f)

    /** 装甲减伤计算值：驱逐舰（设计 50/100/250）。 */
    val DIM_ARMOR_DESTROYER = ScalingEntry(50f, 100f, 250f)

    /** 装甲减伤计算值：巡洋舰（设计 100/200/500）。 */
    val DIM_ARMOR_CRUISER = ScalingEntry(100f, 200f, 500f)

    /** 装甲减伤计算值：主力舰（设计 200/400/1000）。 */
    val DIM_ARMOR_CAPITAL = ScalingEntry(200f, 400f, 1000f)

    /** 计算值加成上限：舰船最大装甲值（armorRating）的比例（设计 25%；排辐/过载中同样翻倍）。 */
    const val DIM_ARMOR_CAP_FRACTION = 0.25f

    /** 强制排辐/过载中的效果倍率（设计：效果翻倍，上限同样翻倍）。 */
    const val DIM_VENT_OVERLOAD_MULT = 2f

    /** 能量伤害吸收比例（设计 10%/20%/50%）：减免等额伤害并转化为硬辐能耗散。 */
    val DIM_ENERGY_ABSORB = ScalingEntry(0.10f, 0.20f, 0.50f)

    // ─── 天幕升格（astd_sc_skydome_ascend） ───

    /** +无人舰点数（设计 90/120/210；与天幕强化同值，技能 id 不同各自独立叠加——设计如此）。 */
    val ASCEND_AUTO_POINTS = ScalingEntry(90f, 120f, 210f)

    /** 菀星设计局舰船部署点降低（设计 10%/15%/30%）。 */
    val ASCEND_DP_REDUCTION = ScalingEntry(0.10f, 0.15f, 0.30f)

    /** 菀星设计局制造商名（ship_data.csv tech/manufacturer 列）：星坠。 */
    const val MANUFACTURER_STARFALL = "菀星设计局-星坠"

    /** 菀星设计局制造商名：紫菀。 */
    const val MANUFACTURER_ASTER = "菀星设计局-紫菀"

    // ─── 天幕强化（astd_sc_skydome_reinforce） ───

    /** +无人舰点数（设计 90/120/210）。 */
    val REINFORCE_AUTO_POINTS = ScalingEntry(90f, 120f, 210f)

    // ─── 精英狼群（astd_sc_elite_wolfpack） ───

    /** 部署点提升（负面平衡，固定值无缩放）：护卫舰 +20%。 */
    const val WOLFPACK_DP_FRIGATE = 0.20f

    /** 部署点提升：驱逐舰 +15%。 */
    const val WOLFPACK_DP_DESTROYER = 0.15f

    /** 部署点提升：巡洋舰 +10%。 */
    const val WOLFPACK_DP_CRUISER = 0.10f

    /** 全面属性加成：护卫舰（设计 10%/15%/30%）。 */
    val WOLFPACK_BONUS_FRIGATE = ScalingEntry(0.10f, 0.15f, 0.30f)

    /** 全面属性加成：驱逐舰（设计 5%/10%/25%）。 */
    val WOLFPACK_BONUS_DESTROYER = ScalingEntry(0.05f, 0.10f, 0.25f)

    /** 全面属性加成：巡洋舰（设计 5%/5%/20%）。 */
    val WOLFPACK_BONUS_CRUISER = ScalingEntry(0.05f, 0.05f, 0.20f)

    // ─── 智能联结（astd_sc_intel_link，阶段四） ───

    /** 智能联结技能 id（SCSkills.csv；改造按钮的天赋生效判定）。 */
    const val INTEL_LINK_SKILL_ID = "astd_sc_intel_link"

    /** 智能联结标记船插 id（hull_mods.csv；改造后 addPermaMod 的永久标记，与技能 id 同字符串、不同命名空间）。 */
    const val INTEL_LINK_HULLMOD_ID = "astd_sc_intel_link"

    /** 改造消耗的制式核心 commodity id（裁定：「集成一颗菀星制式 Beta 核心」按字面语义消耗货舱 1× astd_ai_core_b）。 */
    const val INTEL_LINK_CORE_COMMODITY = "astd_ai_core_b"

    /** 改造消耗的故事点数。 */
    const val INTEL_LINK_STORY_POINT_COST = 1

    // ─── 天幕解限（astd_sc_skydome_unbound，阶段四） ───

    /** 全部 AI 核心等级提升（设计五档表 1/1/2/2/2）。 */
    val UNBOUND_LEVEL_BONUS = ScalingTable(1f, 1f, 2f, 2f, 2f)

    /** 菀星制式核心额外等级提升（设计五档表 1/1/1/1/2）。 */
    val UNBOUND_STANDARD_CORE_EXTRA = ScalingTable(1f, 1f, 1f, 1f, 2f)

    /**
     * 已发放等级总额的 AI 核心 person memory key（幂等高水位：额度变化只补差，不回扣；
     * 与 SIC LimitBreaker 玩家侧 onDeactivation 回收的口径不同，见 SkydomeUnboundSkill KDoc）。
     */
    const val UNBOUND_GRANTED_MEM_KEY = "\$astd_sc_unbound_levels"

    /** 舰船精通系统（SMS）模组 id（软联动门控，缓存检测同 isEnabled 范式）。 */
    const val SMS_MOD_ID = "shipmasterysystem"

    /**
     * SMS 全局精通强度的 dynamic mod key（字面字符串常量，出自 SMS
     * `shipmastery/mastery/MasteryEffect.GLOBAL_MASTERY_STRENGTH_MOD`；
     * 本技能在无 SMS 环境也会被 SIC 加载，禁止 import 任何 shipmastery 类）。
     */
    const val SMS_GLOBAL_MASTERY_MOD = "sms_global_mastery_strength_mod"

    /** 精通效果提升百分比（设计 10%/20%/50%；modifyPercent 的百分比数值口径）。 */
    val UNBOUND_MASTERY_PERCENT = ScalingEntry(10f, 20f, 50f)

    // ─── 赏金副官（docs/design/skills/10-sic-skills.md 赏金适配） ───

    /** 赏金菀星副官天赋 id（SCAptitudes.csv）。 */
    const val BOUNTY_OFFICER_APTITUDE_ID = "astd_sc_wanxing"

    /** 赏金副官头像（菀星智能人格化设定，制式 A 档核心头像）。 */
    const val BOUNTY_OFFICER_PORTRAIT = "graphics/portraits/astd_portrait_core_a.png"

    /** SIC 关闭 NPC 舰队随机副官生成的舰队 tag（SIC SCData.init 的判定字符串，源码已核实）。 */
    const val SC_NO_RANDOM_OFFICERS_TAG = "sc_do_not_generate_skills"

    /**
     * 赏金副官固定携带的全部天赋技能（除基石天幕网络外的 9 个，与 WanxingAptitude
     * 三个 section 的技能一一对应；SCOfficer.addSkill 不检查技能点上限）。
     */
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

    // ─── 纯函数（单元测试直接驱动） ───

    /**
     * 菀星核心点数倍率乘区：倍率降低 [reduction] 后最低不低于 1，
     * 输出写入 [SC_AUTO_POINTS_MULT_STAT] 的 modifyMult 因子（新倍率 / 原倍率）。
     */
    fun coreMultFactor(tierMult: Float, reduction: Float): Float =
        maxOf(1f, tierMult - reduction) / tierMult

    /**
     * 舰队学说侵略度 → AI 核心性格映射（设计：1→谨慎、2→稳健、3/4→激进、5→无畏；
     * 越界按稳健处理）。返回原版 Personalities id。
     */
    fun doctrinePersonality(aggression: Int): String = when (aggression) {
        1 -> Personalities.CAUTIOUS
        2 -> Personalities.STEADY
        3, 4 -> Personalities.AGGRESSIVE
        5 -> Personalities.RECKLESS
        else -> Personalities.STEADY
    }

    /** 硬辐能水平占比（防 0 除：maxFlux 非正时按 0 处理）。 */
    fun hardFluxFraction(hardFlux: Float, maxFlux: Float): Float =
        if (maxFlux > 0f) (hardFlux / maxFlux).coerceIn(0f, 1f) else 0f

    /** 硬辐能耗散斜坡进度：辐能水平 [FROST_RAMP_START] 起线性提升，跨过起点 + [FROST_RAMP_SPAN] 后满载。 */
    fun hardFluxRampT(hardFluxFraction: Float): Float =
        ((hardFluxFraction - FROST_RAMP_START) / FROST_RAMP_SPAN).coerceIn(0f, 1f)

    /** 狼群全面属性加成 entry 按舰级选择；主力舰/战机/未知舰级返回 null（不生效）。 */
    fun wolfpackBonusEntry(hullSize: ShipAPI.HullSize?): ScalingEntry? = when (hullSize) {
        ShipAPI.HullSize.FRIGATE -> WOLFPACK_BONUS_FRIGATE
        ShipAPI.HullSize.DESTROYER -> WOLFPACK_BONUS_DESTROYER
        ShipAPI.HullSize.CRUISER -> WOLFPACK_BONUS_CRUISER
        else -> null
    }

    /** 狼群部署点提升（乘区增量；不适用舰级返回 0）。 */
    fun wolfpackDeploymentBonus(hullSize: ShipAPI.HullSize?): Float = when (hullSize) {
        ShipAPI.HullSize.FRIGATE -> WOLFPACK_DP_FRIGATE
        ShipAPI.HullSize.DESTROYER -> WOLFPACK_DP_DESTROYER
        ShipAPI.HullSize.CRUISER -> WOLFPACK_DP_CRUISER
        else -> 0f
    }

    /** 维度分流装甲减伤计算值 entry 按舰级选择；战机/未知舰级按护卫舰档（与上浮减伤同一口径）。 */
    fun dimArmorEntry(hullSize: ShipAPI.HullSize?): ScalingEntry = when (hullSize) {
        ShipAPI.HullSize.DESTROYER -> DIM_ARMOR_DESTROYER
        ShipAPI.HullSize.CRUISER -> DIM_ARMOR_CRUISER
        ShipAPI.HullSize.CAPITAL_SHIP -> DIM_ARMOR_CAPITAL
        else -> DIM_ARMOR_FRIGATE
    }

    /**
     * 维度分流装甲减伤计算值（纯函数）：排辐/过载中按 [DIM_VENT_OVERLOAD_MULT] 翻倍；
     * 上限为最大装甲值 × [DIM_ARMOR_CAP_FRACTION]（排辐/过载中上限同样翻倍），负值钳到 0。
     */
    fun dimArmorBonus(baseArmor: Float, ventingOrOverloaded: Boolean, maxArmor: Float): Float {
        val mult = if (ventingOrOverloaded) DIM_VENT_OVERLOAD_MULT else 1f
        val cap = maxArmor.coerceAtLeast(0f) * DIM_ARMOR_CAP_FRACTION * mult
        return (baseArmor.coerceAtLeast(0f) * mult).coerceAtMost(cap)
    }

    /** 维度分流能量伤害吸收额度（纯函数）：被减免的伤害量 = 伤害 × 吸收比例，负伤害钳到 0。 */
    fun dimEnergyAbsorbed(damage: Float, absorbFraction: Float): Float =
        damage.coerceAtLeast(0f) * absorbFraction.coerceIn(0f, 1f)

    /**
     * 天幕解限等级加成合成（纯函数）：难度表基础值与制式核心追加值各自取整后相加，
     * 非制式核心不追加。取整在相加之前，避免两个 0.5 档值凑出额外 1 级。
     */
    fun unboundTargetBonus(base: Float, standardCoreExtra: Float, standardCore: Boolean): Int =
        base.roundToInt() + if (standardCore) standardCoreExtra.roundToInt() else 0

    /**
     * 天幕解限幂等补差（纯函数）：目标额度相对已发放额度（高水位）的待补差值；
     * 目标不高于已发放时返回 0（不回扣）。
     */
    fun unboundPendingDelta(target: Int, granted: Int): Int = (target - granted).coerceAtLeast(0)

    /** 智能联结改造按钮可见性（纯函数）：天赋生效、本舰未改造且非战机联队时显示。 */
    fun intelLinkButtonVisible(skillActive: Boolean, hasMarker: Boolean, fighter: Boolean): Boolean =
        skillActive && !hasMarker && !fighter

    /** 智能联结改造按钮可点击（纯函数）：货舱制式 Beta 核心 ≥1 且故事点不低于 [INTEL_LINK_STORY_POINT_COST]。 */
    fun intelLinkButtonClickable(cores: Float, storyPoints: Int): Boolean =
        cores >= 1f && storyPoints >= INTEL_LINK_STORY_POINT_COST

    /** 菀星设计局制造商判定（[MANUFACTURER_STARFALL] / [MANUFACTURER_ASTER] 二值其一）。 */
    fun isWanxingManufacturer(manufacturer: String?): Boolean =
        manufacturer == MANUFACTURER_STARFALL || manufacturer == MANUFACTURER_ASTER
}
