package cn.kasuminova.astd.sscsv.i18n

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Properties

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

    fun t(key: String, fallback: String? = null, replacements: Map<String, Any?> = emptyMap()): String {
        val raw = props.getProperty(key) ?: fallback ?: key
        if (replacements.isEmpty()) return raw

        var s = raw
        for ((k, v) in replacements) {
            s = s.replace("{$k}", v?.toString() ?: "")
        }
        return s
    }

    fun f(key: String, fallback: String? = null, vararg args: Any?): String {
        val raw = props.getProperty(key) ?: fallback ?: key
        return if (args.isEmpty()) raw else String.format(Locale.ROOT, raw, *args)
    }
}
