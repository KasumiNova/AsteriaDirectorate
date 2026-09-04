package cn.kasuminova.astd.sscsv.entries.catalog.weapondata

import cn.kasuminova.astd.sscsv.i18n.SsI18n

/** Weapon 名称 key 约定：`weapon.<id>.name`。 */
internal fun weaponName(id: String): String = SsI18n.t("weapon.$id.name")
