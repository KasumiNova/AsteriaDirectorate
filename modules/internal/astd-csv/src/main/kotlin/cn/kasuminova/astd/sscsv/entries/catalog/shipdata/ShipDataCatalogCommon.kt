package cn.kasuminova.astd.sscsv.entries.catalog.shipdata

import cn.kasuminova.astd.sscsv.i18n.SsI18n

/** ShipData catalog 的 I18n 辅助：`ship.<id>.name`。 */
internal fun shipName(id: String): String = SsI18n.t("ship.$id.name")
