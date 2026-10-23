package cn.kasuminova.astd.sscsv.entries.catalog.hullmods

import cn.kasuminova.astd.sscsv.i18n.SsI18n

internal const val TAGS_BUILTIN: String = "astd_builtin"

internal const val PLACEHOLDER_SCRIPT: String = "cn.kasuminova.astd.combat.hullmods.PlaceholderHullMod"

internal val PLACEHOLDER_SHORT: String get() = SsI18n.t("hullmod._placeholder.short")

internal fun hullmodName(id: String): String = SsI18n.t("hullmod.$id.name")
