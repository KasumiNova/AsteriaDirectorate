package cn.kasuminova.astd.sscsv.entries.catalog.hullmods

import cn.kasuminova.astd.sscsv.i18n.SsI18n

/**
 * HullMod catalog 的通用常量与 I18n 辅助。
 *
 * 注意：这些内容仅影响 ss-csv 生成阶段（即生成出来的 CSV），不会进入游戏运行时逻辑。
 */
internal const val TAGS_BUILTIN: String = "astd_builtin"

/** 占位 HullMod 脚本类名（调试用）。 */
internal const val PLACEHOLDER_SCRIPT: String = "cn.kasuminova.astd.combat.hullmods.PlaceholderHullMod"

/** 占位 HullMod 描述（从 i18n 获取，避免在 entries 中硬编码中文）。 */
internal val PLACEHOLDER_DESC: String get() = SsI18n.t("hullmod._placeholder.desc")

/** 占位 HullMod 简短描述（从 i18n 获取）。 */
internal val PLACEHOLDER_SHORT: String get() = SsI18n.t("hullmod._placeholder.short")

/** HullMod 名称 key 约定：`hullmod.<id>.name`。 */
internal fun hullmodName(id: String): String = SsI18n.t("hullmod.$id.name")
