package cn.kasuminova.astd.sscsv.i18n

import cn.kasuminova.astd.sscsv.tooltip.TooltipRegistry
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class MergedStringTable(
    val values: Map<String, String>,
    val duplicates: List<DuplicateStringKey>,
)

internal object StringTableMerge {

    fun merge(sources: List<ParsedPropertiesWithSource>): MergedStringTable {
        val values = LinkedHashMap<String, String>()
        val origins = HashMap<String, String>()
        val duplicates = mutableListOf<DuplicateStringKey>()

        for (source in sources) {
            for ((key, value) in source.parsed.values) {
                val line = source.parsed.lineNumbers.getValue(key)
                val previous = origins.put(key, propertyLocation(source.name, line))
                if (previous != null) {
                    duplicates += DuplicateStringKey(key, previous, propertyLocation(source.name, line))
                }
                values[key] = value
            }
            duplicates += source.parsed.duplicates
        }

        return MergedStringTable(values = values, duplicates = duplicates)
    }
}

internal data class ParsedPropertiesWithSource(
    val name: String,
    val parsed: ParsedProperties,
)

object SsI18n {
    private const val DEFAULT_LOCALE = "zh-cn"

    private val SUPPLEMENT_PATTERNS = listOf(
        "i18n/tooltip-weapons.%s.properties",
        "i18n/tooltip-systems.%s.properties",
    )

    private val locale: String by lazy {
        (System.getProperty("sscsv.locale") ?: DEFAULT_LOCALE)
            .trim()
            .lowercase()
            .ifBlank { DEFAULT_LOCALE }
    }

    private val table: MergedStringTable by lazy {
        StringTableMerge.merge(loadSources())
    }

    private val tooltipRegistry: TooltipRegistry by lazy {
        TooltipRegistry.parse(table.values)
    }

    /**
     * 读取本地化文本。已登记动态字段以该条目自身的默认参数展开，其余字段保持原有取值。
     *
     * 武器正文等原生 `{%s}` 字段原样返回模板，由游戏按高亮参数填充。
     */
    fun t(key: String, fallback: String? = null, replacements: Map<String, Any?> = emptyMap()): String {
        val raw = baseline(key) ?: table.values[key] ?: fallback ?: key
        if (replacements.isEmpty()) return raw

        var s = raw
        for ((k, v) in replacements) {
            s = s.replace("{$k}", v?.toString() ?: "")
        }
        return s
    }

    fun f(key: String, fallback: String? = null, vararg args: Any?): String {
        val raw = table.values[key] ?: fallback ?: key
        return if (args.isEmpty()) raw else String.format(Locale.ROOT, raw, *args)
    }

    /** 已登记动态字段在默认参数下的基准文本；未登记字段返回 null。 */
    fun baseline(key: String): String? = tooltipRegistry.expandField(key)

    internal fun tooltipRegistry(): TooltipRegistry = tooltipRegistry

    internal fun duplicateStringKeys(): List<DuplicateStringKey> = table.duplicates

    private fun loadSources(): List<ParsedPropertiesWithSource> {
        val sources = mutableListOf<ParsedPropertiesWithSource>()
        loadSource("i18n/%s.properties")?.let(sources::add)
        for (pattern in SUPPLEMENT_PATTERNS) {
            loadSource(pattern)?.let(sources::add)
        }
        return sources
    }

    private fun loadSource(pattern: String): ParsedPropertiesWithSource? {
        val names = if (locale == DEFAULT_LOCALE) {
            listOf(pattern.format(locale))
        } else {
            listOf(pattern.format(locale), pattern.format(DEFAULT_LOCALE))
        }

        for (name in names) {
            val stream = SsI18n::class.java.getResourceAsStream("/$name") ?: continue
            stream.use {
                InputStreamReader(it, StandardCharsets.UTF_8).use { reader ->
                    return ParsedPropertiesWithSource(name, PropertiesParser.parse(name, reader))
                }
            }
        }

        return null
    }
}
