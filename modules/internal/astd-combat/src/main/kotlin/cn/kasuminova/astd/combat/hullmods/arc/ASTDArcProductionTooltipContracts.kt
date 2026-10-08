package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.HullmodTooltipSpec
import cn.kasuminova.astd.ui.dsl.hullmodTooltip

/**
 * ARC 量产内置船插的 tooltip 契约（DSL 声明式数据）。
 *
 * 双重职责：既是 tooltip 渲染的内容源（hullmod 类经 `tooltip.hullmodCard(..., contract.card)` 渲染），
 * 又向自动化场景暴露 [Contract.textKeys] 作文案解析证据（AutomationEvidence 消费，勿破坏枚举口径）。
 */
object ASTDArcProductionTooltipContracts {

    /**
     * 单个内置船插的 tooltip 契约：hullmod id 与卡片声明的绑定。
     *
     * @property hullmodId 契约对应的 hullmod id（用于核对舰体是否已挂载该船插）。
     * @property card 卡片内容声明（渲染源）。
     */
    class Contract(
        val hullmodId: String,
        val card: HullmodTooltipSpec,
    ) {
        /** 卡片引用的全部 i18n 文本键（保持声明序去重）。 */
        val textKeys: Set<String> get() = card.textKeys
    }

    val arcAdvancedFireControl = Contract(
        hullmodId = ASTDArcProductionShipIds.HULLMOD_ARC_ADVANCED_FIRE_CONTROL,
        card = hullmodTooltip {
            para("ui.hullmod.arc_advanced_fire_control.summary")
            heading("ui.hullmod.export.section.effect")
            table {
                row(
                    "ui.hullmod.arc_advanced_fire_control.attr.weapon_flux",
                    "ui.hullmod.arc_advanced_fire_control.value.weapon_flux",
                )
                row(
                    "ui.hullmod.arc_advanced_fire_control.attr.weapon_rate",
                    "ui.hullmod.arc_advanced_fire_control.value.weapon_rate",
                )
                row(
                    "ui.hullmod.arc_advanced_fire_control.attr.ramp",
                    "ui.hullmod.arc_advanced_fire_control.value.ramp",
                )
            }
            para("ui.hullmod.arc_advanced_fire_control.note")
        },
    )

    val arcSharedTacticalNetwork = Contract(
        hullmodId = ASTDArcProductionShipIds.HULLMOD_ARC_SHARED_TACTICAL_NETWORK,
        card = hullmodTooltip {
            para("ui.hullmod.arc_shared_tactical_network.summary")
            heading("ui.hullmod.export.section.effect")
            table {
                row(
                    "ui.hullmod.arc_shared_tactical_network.attr.network",
                    "ui.hullmod.arc_shared_tactical_network.value.network",
                )
                row(
                    "ui.hullmod.arc_shared_tactical_network.attr.command",
                    "ui.hullmod.arc_shared_tactical_network.value.command",
                )
                row(
                    "ui.hullmod.arc_shared_tactical_network.attr.frigate",
                    "ui.hullmod.arc_shared_tactical_network.value.frigate",
                )
                row(
                    "ui.hullmod.arc_shared_tactical_network.attr.destroyer",
                    "ui.hullmod.arc_shared_tactical_network.value.destroyer",
                )
                row(
                    "ui.hullmod.arc_shared_tactical_network.attr.cruiser",
                    "ui.hullmod.arc_shared_tactical_network.value.cruiser",
                )
            }
            para("ui.hullmod.arc_shared_tactical_network.note")
        },
    )

    /** 等离子装甲护盾：标题栏由原版描述承接（showTitle=false）。 */
    val plasmaArmorShield = Contract(
        hullmodId = ASTDArcProductionShipIds.HULLMOD_PLASMA_ARMOR_SHIELD,
        card = hullmodTooltip(showTitle = false) {
            heading("ui.hullmod.plasma_armor_shield.section.directional_armor")
            para("ui.hullmod.plasma_armor_shield.line.directional_armor")
            table(
                headerAKey = "ui.hullmod.plasma_armor_shield.table.direction.header_a",
                headerBKey = "ui.hullmod.plasma_armor_shield.table.direction.header_b",
            ) {
                row(
                    "ui.hullmod.plasma_armor_shield.table.direction.row_0.label",
                    "ui.hullmod.plasma_armor_shield.table.direction.row_0.value",
                    tone = HullmodTone.WARNING,
                )
                row(
                    "ui.hullmod.plasma_armor_shield.table.direction.row_1.label",
                    "ui.hullmod.plasma_armor_shield.table.direction.row_1.value",
                    tone = HullmodTone.WARNING,
                )
                row(
                    "ui.hullmod.plasma_armor_shield.table.direction.row_2.label",
                    "ui.hullmod.plasma_armor_shield.table.direction.row_2.value",
                    tone = HullmodTone.WARNING,
                )
            }
            heading("ui.hullmod.plasma_armor_shield.section.effect")
            para("ui.hullmod.plasma_armor_shield.line.shield_damage_type")
            table(
                headerAKey = "ui.hullmod.plasma_armor_shield.table.shield_damage.header_a",
                headerBKey = "ui.hullmod.plasma_armor_shield.table.shield_damage.header_b",
            ) {
                row(
                    "ui.hullmod.plasma_armor_shield.table.shield_damage.row_0.label",
                    "ui.hullmod.plasma_armor_shield.table.shield_damage.row_0.value",
                    tone = HullmodTone.WARNING,
                )
                row(
                    "ui.hullmod.plasma_armor_shield.table.shield_damage.row_1.label",
                    "ui.hullmod.plasma_armor_shield.table.shield_damage.row_1.value",
                    tone = HullmodTone.WARNING,
                )
                row(
                    "ui.hullmod.plasma_armor_shield.table.shield_damage.row_2.label",
                    "ui.hullmod.plasma_armor_shield.table.shield_damage.row_2.value",
                    tone = HullmodTone.WARNING,
                )
                row(
                    "ui.hullmod.plasma_armor_shield.table.shield_damage.row_3.label",
                    "ui.hullmod.plasma_armor_shield.table.shield_damage.row_3.value",
                    tone = HullmodTone.WARNING,
                )
            }
            heading("ui.hullmod.plasma_armor_shield.section.limits")
            para("ui.hullmod.plasma_armor_shield.line.limits")
            para("ui.hullmod.plasma_armor_shield.line.limit_hardened_shields")
            para("ui.hullmod.plasma_armor_shield.line.limit_shield_shunt")
            para(
                "ui.hullmod.plasma_armor_shield.line.max_armor_penalty",
                hl("50%", HullmodTone.WARNING),
            )
        },
    )

    /** 离子化反冲蓄能器：标题栏由原版描述承接（showTitle=false）。 */
    val ionizedRecoilAccumulator = Contract(
        hullmodId = ASTDArcProductionShipIds.HULLMOD_IONIZED_RECOIL_ACCUMULATOR,
        card = hullmodTooltip(showTitle = false) {
            heading("ui.hullmod.ionized_recoil_accumulator.section.effect")
            para("ui.hullmod.ionized_recoil_accumulator.line.proc_intro")
            table(
                headerAKey = "ui.hullmod.ionized_recoil_accumulator.table.flux.header_a",
                headerBKey = "ui.hullmod.ionized_recoil_accumulator.table.flux.header_b",
            ) {
                row(
                    "ui.hullmod.ionized_recoil_accumulator.table.flux.row_0.label",
                    "ui.hullmod.ionized_recoil_accumulator.table.flux.row_0.value",
                    tone = HullmodTone.WARNING,
                )
                row(
                    "ui.hullmod.ionized_recoil_accumulator.table.flux.row_1.label",
                    "ui.hullmod.ionized_recoil_accumulator.table.flux.row_1.value",
                    tone = HullmodTone.WARNING,
                )
                row(
                    "ui.hullmod.ionized_recoil_accumulator.table.flux.row_2.label",
                    "ui.hullmod.ionized_recoil_accumulator.table.flux.row_2.value",
                    tone = HullmodTone.WARNING,
                )
            }
            para(
                "ui.hullmod.ionized_recoil_accumulator.line.beam_proc",
                hl("90%", HullmodTone.WARNING),
            )
            para("ui.hullmod.ionized_recoil_accumulator.line.damage_proc")
            heading("ui.hullmod.ionized_recoil_accumulator.section.flux_damage")
            para(
                "ui.hullmod.ionized_recoil_accumulator.line.flux_conversion",
                // 高亮声明序必须与文本序一致（I18nUi 前向 indexOf 匹配），否则丢色
                hl("3%", HullmodTone.WARNING),
                hl("等额", HullmodTone.WARNING),
                hl("800su", HullmodTone.WARNING),
                hl("能量武器射程", HullmodTone.WARNING),
                hl("能量伤害", HullmodTone.WARNING),
            )
            para(
                "ui.hullmod.ionized_recoil_accumulator.line.damage",
                hl("100%", HullmodTone.WARNING),
                hl("200%", HullmodTone.WARNING),
            )
            para("ui.hullmod.ionized_recoil_accumulator.line.targeting")
            para(
                "ui.hullmod.ionized_recoil_accumulator.line.cooldown",
                hl("1s", HullmodTone.WARNING),
            )
        },
    )

    val arcAdvancedTargetingSystem = Contract(
        hullmodId = ASTDArcProductionShipIds.HULLMOD_ARC_ADVANCED_TARGETING_SYSTEM,
        card = hullmodTooltip {
            para("ui.hullmod.arc_advanced_targeting_system.summary")
            heading("ui.hullmod.export.section.effect")
            table {
                row(
                    "ui.hullmod.arc_advanced_targeting_system.attr.range",
                    "ui.hullmod.arc_advanced_targeting_system.value.range",
                )
                row(
                    "ui.hullmod.arc_advanced_targeting_system.attr.projectile_speed",
                    "ui.hullmod.arc_advanced_targeting_system.value.projectile_speed",
                )
                row(
                    "ui.hullmod.arc_advanced_targeting_system.attr.weapon_flux",
                    "ui.hullmod.arc_advanced_targeting_system.value.weapon_flux",
                )
                row(
                    "ui.hullmod.arc_advanced_targeting_system.attr.short_range",
                    "ui.hullmod.arc_advanced_targeting_system.value.short_range",
                )
                row(
                    "ui.hullmod.arc_advanced_targeting_system.attr.op_small",
                    "ui.hullmod.arc_advanced_targeting_system.value.op_small",
                )
                row(
                    "ui.hullmod.arc_advanced_targeting_system.attr.op_medium",
                    "ui.hullmod.arc_advanced_targeting_system.value.op_medium",
                )
            }
            heading("ui.hullmod.export.section.note")
            para("ui.hullmod.arc_advanced_targeting_system.note")
        },
    )

    val xc102Contracts = listOf(arcAdvancedFireControl, arcSharedTacticalNetwork)
    val xc101Contracts = listOf(plasmaArmorShield, ionizedRecoilAccumulator)
    val xc103Contracts = listOf(arcAdvancedTargetingSystem)
}
