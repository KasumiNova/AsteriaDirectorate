package cn.kasuminova.astd.sscsv.entries.catalog.shipsystems

import cn.kasuminova.astd.sscsv.i18n.SsI18n

/** ShipSystem 名称 key 约定：`system.<id>.name`。 */
internal fun systemName(id: String): String = SsI18n.t("system.$id.name")
