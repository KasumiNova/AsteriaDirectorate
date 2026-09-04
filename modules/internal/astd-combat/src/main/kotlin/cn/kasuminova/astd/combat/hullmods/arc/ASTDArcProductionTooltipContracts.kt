package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer

object ASTDArcProductionTooltipContracts {
    data class Contract(
        val hullmodId: String,
        val blocks: List<ASTDHullModTooltipRenderer.Block>,
        val headerInNativeDescription: Boolean = false,
    ) {
        val textKeys: Set<String> = blocks.flatMapTo(LinkedHashSet()) { block ->
            when (block) {
                is ASTDHullModTooltipRenderer.Paragraph -> listOf(block.key)
                is ASTDHullModTooltipRenderer.Heading -> listOf(block.key)
                is ASTDHullModTooltipRenderer.Table -> buildList {
                    add(block.headerAKey)
                    add(block.headerBKey)
                    for (row in block.rows) {
                        add(row.labelKey)
                        add(row.valueKey)
                    }
                }
            }
        }
    }

    val arcAdvancedFireControl = Contract(
        hullmodId = ASTDArcProductionShipIds.HULLMOD_ARC_ADVANCED_FIRE_CONTROL,
        blocks = listOf(
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.arc_advanced_fire_control.summary"),
            ASTDHullModTooltipRenderer.heading("ui.hullmod.export.section.effect"),
            ASTDHullModTooltipRenderer.table(
                rows = arrayOf(
                    ASTDHullModTooltipRenderer.row("ui.hullmod.arc_advanced_fire_control.attr.weapon_flux", "ui.hullmod.arc_advanced_fire_control.value.weapon_flux"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.arc_advanced_fire_control.attr.weapon_rate", "ui.hullmod.arc_advanced_fire_control.value.weapon_rate"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.arc_advanced_fire_control.attr.ramp", "ui.hullmod.arc_advanced_fire_control.value.ramp"),
                ),
            ),
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.arc_advanced_fire_control.note"),
        ),
    )

    val arcSharedTacticalNetwork = Contract(
        hullmodId = ASTDArcProductionShipIds.HULLMOD_ARC_SHARED_TACTICAL_NETWORK,
        blocks = listOf(
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.arc_shared_tactical_network.summary"),
            ASTDHullModTooltipRenderer.heading("ui.hullmod.export.section.effect"),
            ASTDHullModTooltipRenderer.table(
                rows = arrayOf(
                    ASTDHullModTooltipRenderer.row("ui.hullmod.arc_shared_tactical_network.attr.network", "ui.hullmod.arc_shared_tactical_network.value.network"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.arc_shared_tactical_network.attr.command", "ui.hullmod.arc_shared_tactical_network.value.command"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.arc_shared_tactical_network.attr.frigate", "ui.hullmod.arc_shared_tactical_network.value.frigate"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.arc_shared_tactical_network.attr.destroyer", "ui.hullmod.arc_shared_tactical_network.value.destroyer"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.arc_shared_tactical_network.attr.cruiser", "ui.hullmod.arc_shared_tactical_network.value.cruiser"),
                ),
            ),
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.arc_shared_tactical_network.note"),
        ),
    )

    val plasmaArmorShield = Contract(
        hullmodId = ASTDArcProductionShipIds.HULLMOD_PLASMA_ARMOR_SHIELD,
        blocks = listOf(
            ASTDHullModTooltipRenderer.heading("ui.hullmod.plasma_armor_shield.section.directional_armor"),
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.plasma_armor_shield.line.directional_armor"),
            ASTDHullModTooltipRenderer.table(
                headerAKey = "ui.hullmod.plasma_armor_shield.table.direction.header_a",
                headerBKey = "ui.hullmod.plasma_armor_shield.table.direction.header_b",
                rows = arrayOf(
                    ASTDHullModTooltipRenderer.row("ui.hullmod.plasma_armor_shield.table.direction.row_0.label", "ui.hullmod.plasma_armor_shield.table.direction.row_0.value", role = "warning"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.plasma_armor_shield.table.direction.row_1.label", "ui.hullmod.plasma_armor_shield.table.direction.row_1.value", role = "warning"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.plasma_armor_shield.table.direction.row_2.label", "ui.hullmod.plasma_armor_shield.table.direction.row_2.value", role = "warning"),
                ),
            ),
            ASTDHullModTooltipRenderer.heading("ui.hullmod.plasma_armor_shield.section.effect"),
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.plasma_armor_shield.line.shield_damage_type"),
            ASTDHullModTooltipRenderer.table(
                headerAKey = "ui.hullmod.plasma_armor_shield.table.shield_damage.header_a",
                headerBKey = "ui.hullmod.plasma_armor_shield.table.shield_damage.header_b",
                rows = arrayOf(
                    ASTDHullModTooltipRenderer.row("ui.hullmod.plasma_armor_shield.table.shield_damage.row_0.label", "ui.hullmod.plasma_armor_shield.table.shield_damage.row_0.value", role = "warning"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.plasma_armor_shield.table.shield_damage.row_1.label", "ui.hullmod.plasma_armor_shield.table.shield_damage.row_1.value", role = "warning"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.plasma_armor_shield.table.shield_damage.row_2.label", "ui.hullmod.plasma_armor_shield.table.shield_damage.row_2.value", role = "warning"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.plasma_armor_shield.table.shield_damage.row_3.label", "ui.hullmod.plasma_armor_shield.table.shield_damage.row_3.value", role = "warning"),
                ),
            ),
            ASTDHullModTooltipRenderer.heading("ui.hullmod.plasma_armor_shield.section.limits"),
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.plasma_armor_shield.line.limits"),
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.plasma_armor_shield.line.limit_hardened_shields"),
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.plasma_armor_shield.line.limit_shield_shunt"),
            ASTDHullModTooltipRenderer.paragraph(
                "ui.hullmod.plasma_armor_shield.line.max_armor_penalty",
                highlights = arrayOf(ASTDHullModTooltipRenderer.highlight("50%", "#FFE024")),
            ),
        ),
        headerInNativeDescription = true,
    )

    val ionizedRecoilAccumulator = Contract(
        hullmodId = ASTDArcProductionShipIds.HULLMOD_IONIZED_RECOIL_ACCUMULATOR,
        blocks = listOf(
            ASTDHullModTooltipRenderer.heading("ui.hullmod.ionized_recoil_accumulator.section.effect"),
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.ionized_recoil_accumulator.line.proc_intro"),
            ASTDHullModTooltipRenderer.table(
                headerAKey = "ui.hullmod.ionized_recoil_accumulator.table.flux.header_a",
                headerBKey = "ui.hullmod.ionized_recoil_accumulator.table.flux.header_b",
                rows = arrayOf(
                    ASTDHullModTooltipRenderer.row("ui.hullmod.ionized_recoil_accumulator.table.flux.row_0.label", "ui.hullmod.ionized_recoil_accumulator.table.flux.row_0.value", role = "warning"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.ionized_recoil_accumulator.table.flux.row_1.label", "ui.hullmod.ionized_recoil_accumulator.table.flux.row_1.value", role = "warning"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.ionized_recoil_accumulator.table.flux.row_2.label", "ui.hullmod.ionized_recoil_accumulator.table.flux.row_2.value", role = "warning"),
                ),
            ),
            ASTDHullModTooltipRenderer.paragraph(
                "ui.hullmod.ionized_recoil_accumulator.line.beam_proc",
                highlights = arrayOf(ASTDHullModTooltipRenderer.highlight("90%", "#FFE024")),
            ),
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.ionized_recoil_accumulator.line.damage_proc"),
            ASTDHullModTooltipRenderer.heading("ui.hullmod.ionized_recoil_accumulator.section.flux_damage"),
            ASTDHullModTooltipRenderer.paragraph(
                "ui.hullmod.ionized_recoil_accumulator.line.flux_conversion",
                highlights = arrayOf(
                    ASTDHullModTooltipRenderer.highlight("800su", "#FFE024"),
                    ASTDHullModTooltipRenderer.highlight("能量武器射程", "#FFE024"),
                    ASTDHullModTooltipRenderer.highlight("3%", "#FFE024"),
                    ASTDHullModTooltipRenderer.highlight("等额", "#FFE024"),
                    ASTDHullModTooltipRenderer.highlight("能量伤害", "#FFE024"),
                ),
            ),
            ASTDHullModTooltipRenderer.paragraph(
                "ui.hullmod.ionized_recoil_accumulator.line.damage",
                highlights = arrayOf(
                    ASTDHullModTooltipRenderer.highlight("100%", "#FFE024"),
                    ASTDHullModTooltipRenderer.highlight("200%", "#FFE024"),
                ),
            ),
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.ionized_recoil_accumulator.line.targeting"),
            ASTDHullModTooltipRenderer.paragraph(
                "ui.hullmod.ionized_recoil_accumulator.line.cooldown",
                highlights = arrayOf(ASTDHullModTooltipRenderer.highlight("1s", "#FFE024")),
            ),
        ),
        headerInNativeDescription = true,
    )

    val arcAdvancedTargetingSystem = Contract(
        hullmodId = ASTDArcProductionShipIds.HULLMOD_ARC_ADVANCED_TARGETING_SYSTEM,
        blocks = listOf(
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.arc_advanced_targeting_system.summary"),
            ASTDHullModTooltipRenderer.heading("ui.hullmod.export.section.effect"),
            ASTDHullModTooltipRenderer.table(
                rows = arrayOf(
                    ASTDHullModTooltipRenderer.row("ui.hullmod.arc_advanced_targeting_system.attr.range", "ui.hullmod.arc_advanced_targeting_system.value.range"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.arc_advanced_targeting_system.attr.short_range", "ui.hullmod.arc_advanced_targeting_system.value.short_range"),
                ),
            ),
            ASTDHullModTooltipRenderer.heading("ui.hullmod.export.section.note"),
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.arc_advanced_targeting_system.note"),
        ),
    )

    val distributedPursuitNetwork = Contract(
        hullmodId = ASTDArcProductionShipIds.HULLMOD_DISTRIBUTED_PURSUIT_NETWORK,
        blocks = listOf(
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.distributed_pursuit_network.summary"),
            ASTDHullModTooltipRenderer.heading("ui.hullmod.distributed_pursuit_network.section.members"),
            ASTDHullModTooltipRenderer.table(
                rows = arrayOf(
                    ASTDHullModTooltipRenderer.row("ui.hullmod.distributed_pursuit_network.attr.members", "ui.hullmod.distributed_pursuit_network.value.members"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.distributed_pursuit_network.attr.same_network", "ui.hullmod.distributed_pursuit_network.value.same_network"),
                ),
            ),
            ASTDHullModTooltipRenderer.heading("ui.hullmod.distributed_pursuit_network.section.bonus"),
            ASTDHullModTooltipRenderer.table(
                rows = arrayOf(
                    ASTDHullModTooltipRenderer.row("ui.hullmod.distributed_pursuit_network.attr.speed", "ui.hullmod.distributed_pursuit_network.value.speed"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.distributed_pursuit_network.attr.range", "ui.hullmod.distributed_pursuit_network.value.range"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.distributed_pursuit_network.attr.peak", "ui.hullmod.distributed_pursuit_network.value.peak"),
                ),
            ),
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.distributed_pursuit_network.note"),
        ),
    )

    val arcJetContracts = listOf(arcAdvancedFireControl, arcSharedTacticalNetwork)
    val plasmaArchContracts = listOf(plasmaArmorShield, ionizedRecoilAccumulator)
    val radiationBeltContracts = listOf(arcAdvancedTargetingSystem, distributedPursuitNetwork)
}
