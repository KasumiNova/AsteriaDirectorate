package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer

internal object ASTDArcProductionTooltipContracts {
    data class Contract(
        val hullmodId: String,
        val blocks: List<ASTDHullModTooltipRenderer.Block>,
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
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.plasma_armor_shield.summary"),
            ASTDHullModTooltipRenderer.heading("ui.hullmod.export.section.effect"),
            ASTDHullModTooltipRenderer.table(
                rows = arrayOf(
                    ASTDHullModTooltipRenderer.row("ui.hullmod.plasma_armor_shield.attr.armor_shield", "ui.hullmod.plasma_armor_shield.value.armor_shield"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.plasma_armor_shield.attr.damage_type", "ui.hullmod.plasma_armor_shield.value.damage_type"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.plasma_armor_shield.attr.grid", "ui.hullmod.plasma_armor_shield.value.grid"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.plasma_armor_shield.attr.boost", "ui.hullmod.plasma_armor_shield.value.boost"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.plasma_armor_shield.attr.recovery", "ui.hullmod.plasma_armor_shield.value.recovery"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.plasma_armor_shield.attr.limits", "ui.hullmod.plasma_armor_shield.value.limits"),
                ),
            ),
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.plasma_armor_shield.note"),
        ),
    )

    val ionizedRecoilAccumulator = Contract(
        hullmodId = ASTDArcProductionShipIds.HULLMOD_IONIZED_RECOIL_ACCUMULATOR,
        blocks = listOf(
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.ionized_recoil_accumulator.summary"),
            ASTDHullModTooltipRenderer.heading("ui.hullmod.export.section.effect"),
            ASTDHullModTooltipRenderer.table(
                rows = arrayOf(
                    ASTDHullModTooltipRenderer.row("ui.hullmod.ionized_recoil_accumulator.attr.recoil", "ui.hullmod.ionized_recoil_accumulator.value.recoil"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.ionized_recoil_accumulator.attr.volley", "ui.hullmod.ionized_recoil_accumulator.value.volley"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.ionized_recoil_accumulator.attr.damage", "ui.hullmod.ionized_recoil_accumulator.value.damage"),
                    ASTDHullModTooltipRenderer.row("ui.hullmod.ionized_recoil_accumulator.attr.pierce", "ui.hullmod.ionized_recoil_accumulator.value.pierce"),
                ),
            ),
            ASTDHullModTooltipRenderer.paragraph("ui.hullmod.ionized_recoil_accumulator.note"),
        ),
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
