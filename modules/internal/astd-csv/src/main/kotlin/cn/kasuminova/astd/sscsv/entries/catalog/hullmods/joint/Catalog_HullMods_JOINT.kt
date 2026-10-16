package cn.kasuminova.astd.sscsv.entries.catalog.hullmods.joint

import cn.kasuminova.astd.sscsv.entries.HullModEntry
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.TAGS_BUILTIN
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.hullmodName
import cn.kasuminova.astd.sscsv.i18n.SsI18n

/** 联制线（LH）HullMod。 */

/**
 * 奇点稳定器·蓝变体（联制 lh_001 内置）：与紫变体共用效果脚本
 * [cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityStabilizerHullMod]，仅贴图不同。
 * 机制：峰值时间不随时流变化加速消耗、免疫时流减益、禁止安装安全协议超驰（见 `docs/design/ships/20-joint.md`）。
 */
object HullMod_astd_arc_singularity_stabilizer : HullModEntry() {
    override val id: String = "astd_arc_singularity_stabilizer"
    override val name: String = hullmodName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val tech: String = "联制"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityStabilizerHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_singularity_stabilizer_arc.png"
}

/** 奇点稳定器·紫变体（联制 lh_002 内置），见 [HullMod_astd_arc_singularity_stabilizer]。 */
object HullMod_astd_lens_singularity_stabilizer : HullModEntry() {
    override val id: String = "astd_lens_singularity_stabilizer"
    override val name: String = hullmodName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val tech: String = "联制"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityStabilizerHullMod"
    override val short: String = SsI18n.t("hullmod.$id.short")
    override val sprite: String = "graphics/hullmods/astd_singularity_stabilizer_lens.png"
}
