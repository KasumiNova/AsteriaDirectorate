package cn.kasuminova.astd.sscsv.entries

import cn.kasuminova.astd.sscsv.annotations.SsCsvComment
import cn.kasuminova.astd.sscsv.i18n.SsI18n

/**
 * Example hull mod entries migrated from the existing CSV.
 *
 * These are primarily for validating the generator output; feel free to extend or replace them.
 */

private const val PLACEHOLDER_SCRIPT = "cn.kasuminova.astd.combat.hullmods.PlaceholderHullMod"
private val PLACEHOLDER_DESC: String
    get() = SsI18n.t("hullmod._placeholder.desc")
private val PLACEHOLDER_SHORT: String
    get() = SsI18n.t("hullmod._placeholder.short")

@SsCsvComment("ARC 唯一舰 Arc Flare 的内置 Hullmod（占位）。")
object ArcLoopInterface : HullModEntry() {
    override val id: String = "astd_arc_loop_interface"
    override val name: String = SsI18n.t("hullmod.$id.name")

    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "ARC"

    override val tags: String = "astd_builtin"

    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT

    override val sprite: String = "graphics/hullmods/astd_arc_loop_interface.png"
}

@SsCsvComment("ARC 唯一舰 Negentropy Edge 的内置 Hullmod（占位）。")
object VirtualParticleLatticeWeb : HullModEntry() {
    override val id: String = "astd_virtual_particle_lattice_web"
    override val name: String = SsI18n.t("hullmod.$id.name")

    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "ARC"

    override val tags: String = "astd_builtin"

    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT

    override val sprite: String = "graphics/hullmods/astd_arc_loop_interface.png"
}

@SsCsvComment("ARC 唯一舰 Negentropy Edge 的瞬态势能插件（占位）。")
object TransientPotentialManifold : HullModEntry() {
    override val id: String = "astd_transient_potential_manifold"
    override val name: String = SsI18n.t("hullmod.$id.name")

    override val tier: Int = 3
    override val rarity: Int = 1
    override val tech: String = "ARC"

    override val tags: String = "astd_builtin"

    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT

    override val sprite: String = "graphics/hullmods/astd_vectorized_jet_array.png"
}

@SsCsvComment("Nano-Restoration Protocol（占位）：后续会替换为真实机制与脚本。")
object NanoRestorationProtocol : HullModEntry() {
    override val id: String = "astd_nano_restoration_protocol"
    override val name: String = SsI18n.t("hullmod.$id.name")

    override val tier: Int = 2
    override val rarity: Int = 1
    override val tech: String = "ARC"

    override val tags: String = "astd_builtin"

    override val script: String = PLACEHOLDER_SCRIPT
    override val desc: String = PLACEHOLDER_DESC
    override val short: String = PLACEHOLDER_SHORT

    override val sprite: String = "graphics/hullmods/astd_nano_restoration_protocol.png"
}
