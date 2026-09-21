package cn.kasuminova.astd.sscsv.entries.catalog.hullmods.joint

import cn.kasuminova.astd.sscsv.entries.HullModEntry
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.TAGS_BUILTIN
import cn.kasuminova.astd.sscsv.entries.catalog.hullmods.hullmodName
import cn.kasuminova.astd.sscsv.i18n.SsI18n

/** 联制线（LH）HullMod。 */

/**
 * 奇点稳定器（联制线两舰内置）：峰值时间不随时流变化加速消耗、免疫时流减益、
 * 禁止安装安全协议超驰（机制见 `docs/design/ships/20-joint.md`）。
 */
object HullMod_astd_singularity_stabilizer : HullModEntry() {
    override val id: String = "astd_singularity_stabilizer"
    override val name: String = hullmodName(id)
    override val tier: Int = 1
    override val rarity: Int = 1
    override val tech: String = "联制"
    override val tags: String = TAGS_BUILTIN
    override val script: String = "cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityStabilizerHullMod"
    override val desc: String = SsI18n.t("hullmod.$id.desc")
    override val short: String = SsI18n.t("hullmod.$id.short")

    // 素材占位：复用现有稳定器图标，正式美术后续替换。
    override val sprite: String = "graphics/hullmods/astd_arch_stabilizer.png"
}
