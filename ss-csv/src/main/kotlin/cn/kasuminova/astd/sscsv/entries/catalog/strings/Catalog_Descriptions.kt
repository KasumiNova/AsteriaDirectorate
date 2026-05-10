package cn.kasuminova.astd.sscsv.entries.catalog.strings

import cn.kasuminova.astd.sscsv.entries.DescriptionEntry
import cn.kasuminova.astd.sscsv.i18n.SsI18n

/**
 * `data/strings/descriptions.csv`：武器/系统等“顶部描述文本”。
 *
 * 约定：
 * - 这些文本会直接进入 UI，尽量避免 ASCII `%`（推荐用全角 `％`）。
 */

private fun desc(id: String, key: String, fallback: String = ""): String =
    SsI18n.t("desc.$id.$key", fallback)

object Desc_astd_stellar_jet : DescriptionEntry() {
    override val id: String = "astd_stellar_jet"
    override val type: String = "SHIP_SYSTEM"

    override val text1: String = desc(id, "text1")
    override val text2: String = desc(id, "text2")
    override val text3: String = desc(id, "text3")
    override val text4: String = desc(id, "text4")
}

object Desc_astd_arc_flare_overdrive_crewed : DescriptionEntry() {
    override val id: String = "astd_arc_flare_overdrive_crewed"
    override val type: String = "SHIP_SYSTEM"

    override val text1: String = desc(id, "text1")
    override val text2: String = desc(id, "text2")
    override val text3: String = desc(id, "text3")
    override val text4: String = desc(id, "text4")
}

object Desc_astd_arc_flare_overdrive_automated : DescriptionEntry() {
    override val id: String = "astd_arc_flare_overdrive_automated"
    override val type: String = "SHIP_SYSTEM"

    override val text1: String = desc(id, "text1")
    override val text2: String = desc(id, "text2")
    override val text3: String = desc(id, "text3")
    override val text4: String = desc(id, "text4")
}

object Desc_astd_stellar_jet_emitter : DescriptionEntry() {
    override val id: String = "astd_stellar_jet_emitter"
    override val type: String = "WEAPON"

    override val text1: String = desc(id, "text1")
    override val text2: String = desc(id, "text2")
    override val text3: String = desc(id, "text3")
    override val text4: String = desc(id, "text4")
}

object Desc_astd_gcp12 : DescriptionEntry() {
    override val id: String = "astd_gcp12"
    override val type: String = "WEAPON"

    override val text1: String = desc(id, "text1")
    override val notes: String = desc(id, "notes")
}

object Desc_astd_gcp8 : DescriptionEntry() {
    override val id: String = "astd_gcp8"
    override val type: String = "WEAPON"

    override val text1: String = desc(id, "text1")
    override val notes: String = desc(Desc_astd_gcp12.id, "notes")
}

object Desc_astd_gcp4 : DescriptionEntry() {
    override val id: String = "astd_gcp4"
    override val type: String = "WEAPON"

    override val text1: String = desc(id, "text1")
    override val notes: String = desc(Desc_astd_gcp12.id, "notes")
}

object Desc_astd_gcp2 : DescriptionEntry() {
    override val id: String = "astd_gcp2"
    override val type: String = "WEAPON"

    override val text1: String = desc(id, "text1")
    override val notes: String = desc(Desc_astd_gcp12.id, "notes")
}
