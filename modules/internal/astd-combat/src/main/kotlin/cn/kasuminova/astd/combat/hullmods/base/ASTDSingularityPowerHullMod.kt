package cn.kasuminova.astd.combat.hullmods.base

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.ui.dsl.HullmodTheme
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.v
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

/**
 * 奇点能源（全 ASTD 非战机舰船内置船插，蓝 [ID_ARC] / 紫 [ID_LENS] 双变体共用本效果类，
 * 仅 spec 贴图与设计类型不同）。
 *
 * 四件效果：
 * 1. 强制排辐速率 +10%/+20%/+50%（随难度）——`ventRateMult` 乘区（项目术语口径：词缀体系
 *    「强制排辐速率」同走该 stat，见 AffixCryoFluxNetworkHullMod；原版中文译名为「主动排幅速率」，
 *    FluxBreakers 判例）。
 * 2. 过载持续时间 -10%/-20%/-50%（随难度）——`overloadTimeMod` 乘区。
 * 3. 负面环境对战备值与峰值时间的影响 -100%（固定）——复刻原版环境抗性实现（SolarShielding 判例）：
 *    dynamic stat `corona_resistance` 乘 0，该值被原版日冕/耀斑/超空间风暴/脉冲星束地形
 *    （StarCoronaTerrainPlugin、HyperspaceTerrainPlugin 等）读取并缩放 CR 损失与峰值时间惩罚。
 *    战役侧 stat，须在装配期写入（战斗内逐帧写无法覆盖战役读取面）。
 * 4. 武器辐能产出 -5%/-10%/-25%（随难度）——实弹/能量/导弹三类 `weaponFluxCostMod` 乘区。
 *
 * 难度缩放（2026-10 裁定）：1/2/4 号效果的取值随难度系数——玩家侧（owner==0）按我方档位映射
 * （默认砺刃=v2），敌方/友军 AI 按轨一 k_s 映射。战斗内 stat 走 advanceInCombat 逐帧
 * modifyMult（同 id 覆盖，幂等，无需 unmodify），装配期无法感知 owner 故不在
 * applyEffectsBeforeShipCreation 写入缩放值；tooltip 按「无舰上下文=我方档位」展示。
 *
 * 附带职责：本船插是全 ASTD 非战机舰唯一必装的内置船插，因此兼作双模式切换器缺失的
 * 自动补装引导点（见 [ensureDualModeSwitcherPresent]）——任何 ASTD 舰的 variant 即便漏声明
 * 切换器，首次 stats 重建即补装并自举到缺省载人模式，不会落入无法切换模式的死态。
 */
class ASTDSingularityPowerHullMod : BaseHullMod() {

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        stats.dynamic.getStat(Stats.CORONA_EFFECT_MULT).modifyMult(id, ENVIRONMENT_EFFECT_MULT)
        // 双模式切换器缺失自动补装：本船插是全 ASTD 非战机舰唯一必装的内置船插，
        // 兼作补装引导点（守护语义见 ensureDualModeSwitcherPresent，拆即切在途不会被抢先补装）
        val variant = stats.variant
        if (variant != null) {
            val config = ASTDDualModeRegistry.configForVariant(variant)
            if (config != null) variant.ensureDualModeSwitcherPresent(config)
        }
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val values = Values.resolve(ship.owner == 0)
        val stats = ship.mutableStats
        stats.ventRateMult.modifyMult(MOD_ID, values.ventRateMult)
        stats.overloadTimeMod.modifyMult(MOD_ID, values.overloadTimeMult)
        stats.ballisticWeaponFluxCostMod.modifyMult(MOD_ID, values.weaponFluxCostMult)
        stats.energyWeaponFluxCostMod.modifyMult(MOD_ID, values.weaponFluxCostMult)
        stats.missileWeaponFluxCostMod.modifyMult(MOD_ID, values.weaponFluxCostMult)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        // tooltip 展示口径：无舰上下文（装配面板）按我方档位展示（默认砺刃 v2）
        val values = Values.resolve(ship == null || ship.owner == 0)
        tooltip.hullmodCard(width, theme(), spec?.displayName) {
            para(descKey())
            heading("ui.hullmod.export.section.effect")
            table {
                row(
                    "ui.hullmod.singularity_power.attr.vent_rate",
                    "ui.hullmod.singularity_power.value.vent_rate",
                    labelTone = HullmodTone.DEFAULT,
                    valueTone = HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(values.ventRateMult - 1f)),
                )
                row(
                    "ui.hullmod.singularity_power.attr.overload",
                    "ui.hullmod.singularity_power.value.overload",
                    labelTone = HullmodTone.DEFAULT,
                    valueTone = HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(values.overloadTimeMult - 1f)),
                )
                row(
                    "ui.hullmod.singularity_power.attr.environment",
                    "ui.hullmod.singularity_power.value.environment",
                    labelTone = HullmodTone.DEFAULT,
                    valueTone = HullmodTone.HIGHLIGHT,
                )
                row(
                    "ui.hullmod.singularity_power.attr.weapon_flux",
                    "ui.hullmod.singularity_power.value.weapon_flux",
                    labelTone = HullmodTone.DEFAULT,
                    valueTone = HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(values.weaponFluxCostMult - 1f)),
                )
            }
            spacer(6f)
            para("ui.hullmod.export.difficulty_note", hl("难度系数", HullmodTone.DEFAULT))
        }
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = theme().borderColor

    override fun getNameColor(): Color = theme().nameColor

    private fun theme(): HullmodTheme =
        if (spec?.id == ID_LENS) THEME_LENS else THEME_ARC

    private fun descKey(): String =
        if (spec?.id == ID_LENS) "ui.hullmod.lens_singularity_power.desc" else "ui.hullmod.arc_singularity_power.desc"

    /** 难度结算值（三锚点映射后）。 */
    internal data class Values(
        val ventRateMult: Float,
        val overloadTimeMult: Float,
        val weaponFluxCostMult: Float,
    ) {
        companion object {
            fun resolve(isPlayer: Boolean): Values = Values(
                ventRateMult = DifficultyTuningImpl.valueFor(VENT_RATE, isPlayer),
                overloadTimeMult = DifficultyTuningImpl.valueFor(OVERLOAD_TIME, isPlayer),
                weaponFluxCostMult = DifficultyTuningImpl.valueFor(WEAPON_FLUX_COST, isPlayer),
            )
        }
    }

    companion object {
        /** 蓝变体 id（菀星设计局-星坠，装配 astd_xc_* 与 astd_lh_001）。 */
        const val ID_ARC: String = "astd_arc_singularity_power"

        /** 紫变体 id（菀星设计局-紫菀，装配 astd_zw_* 与 astd_lh_002）。 */
        const val ID_LENS: String = "astd_lens_singularity_power"

        /** 战斗内 stat 乘区修饰键（逐帧同 id 覆盖，幂等）。 */
        private const val MOD_ID = "astd_singularity_power"

        /** 强制排辐速率乘区（三锚点 +10%/+20%/+50%）。 */
        val VENT_RATE = ScalingEntry(1.10f, 1.20f, 1.50f)

        /** 过载持续时间乘区（三锚点 -10%/-20%/-50%）。 */
        val OVERLOAD_TIME = ScalingEntry(0.90f, 0.80f, 0.50f)

        /** 负面环境影响乘区（固定 -100%，不随难度）。 */
        const val ENVIRONMENT_EFFECT_MULT: Float = 0f

        /** 武器辐能产出乘区（三锚点 -5%/-10%/-25%）。 */
        val WEAPON_FLUX_COST = ScalingEntry(0.95f, 0.90f, 0.75f)

        private val THEME_ARC = HullmodThemes.ARC

        private val THEME_LENS = HullmodThemes.LENS
    }
}
