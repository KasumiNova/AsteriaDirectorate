package cn.kasuminova.astd.sscsv.i18n

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Properties

/**
 * ss-csv 生成阶段使用的轻量 I18n。
 *
 * 目标：
 * - 让 entries/catalog 中尽可能不再硬编码中文文本；
 * - 通过替换 properties 文件即可生成不同语言版本的 CSV/JSON。
 *
 * 约定：
 * - 语言由系统属性 `sscsv.locale` 控制（例如 zh-cn / en-us）；默认 zh-cn。
 * - 翻译文件位于 classpath：`/i18n/<locale>.properties`。
 */
object SsI18n {
    private const val DEFAULT_LOCALE = "zh-cn"

    private val locale: String by lazy {
        (System.getProperty("sscsv.locale") ?: DEFAULT_LOCALE)
            .trim()
            .lowercase()
            .ifBlank { DEFAULT_LOCALE }
    }

    private val props: Properties by lazy {
        val p = Properties()
        // 先尝试指定 locale；找不到就回退到默认。
        val path = "/i18n/$locale.properties"
        val fallback = "/i18n/$DEFAULT_LOCALE.properties"

        val stream = SsI18n::class.java.getResourceAsStream(path)
            ?: SsI18n::class.java.getResourceAsStream(fallback)

        if (stream != null) {
            stream.use {
                InputStreamReader(it, StandardCharsets.UTF_8).use { r ->
                    p.load(r)
                }
            }
        }
        p
    }

    /**
     * 获取翻译文本。
     *
     * - 若 key 不存在：返回 [fallback]（若提供），否则返回 key 本身。
     * - 支持简单占位符替换：`{name}`。
     */
    fun t(key: String, fallback: String? = null, replacements: Map<String, Any?> = emptyMap()): String {
        val raw = props.getProperty(key) ?: fallback ?: key
        if (replacements.isEmpty()) return raw

        var s = raw
        for ((k, v) in replacements) {
            s = s.replace("{$k}", v?.toString() ?: "")
        }
        return s
    }

    /**
     * 获取翻译文本，并使用 printf 风格占位符进行格式化。
     *
     * 注意：Properties 会把 `\n` 解析为换行；若你希望输出字面量 `\n`，请在 properties 中写 `\\n`。
     */
    fun f(key: String, fallback: String? = null, vararg args: Any?): String {
        val raw = props.getProperty(key) ?: fallback ?: key
        return if (args.isEmpty()) raw else String.format(Locale.ROOT, raw, *args)
    }
}
