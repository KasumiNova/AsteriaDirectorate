package cn.kasuminova.astd.internal.i18n

import com.fs.starfarer.api.Global
import org.json.JSONObject
import java.awt.Color

object I18n {

    private val EXTRA_STRING_TABLE_PATHS: List<String> = listOf(
        "data/strings/bounty_strings.json",
        "data/strings/systems_strings.json",
        "data/strings/settings_strings.json",
    )

    private val log by lazy { Global.getLogger(I18n::class.java) }

    private val extraStringTablesByCategory: Map<String, JSONObject> by lazy {
        loadExtraStringTables()
    }

    private const val PARAM_TAG_PREFIX: String = "<param:"

    data class Highlight(val text: String, val color: Color)

    data class Rendered(
        val text: String,
        val highlights: List<Highlight> = emptyList()
    )

    enum class Categories {

        MOD("asteria_directorate"),

        HULL_MODS("hull_mods"),
        ;

        val id: String

        constructor(key: String) {
            this.id = key
        }

    }

    operator fun get(key: String): String = get(category = null, key = key)

    operator fun get(category: Categories, key: String): String = get(category.id, key)

    operator fun get(category: String?, key: String): String = tryGet(category, key)

    fun t(key: String, vararg vars: Pair<String, Any?>): String =
        tInternal(category = null, key = key, vars = vars)

    fun t(category: Categories, key: String, vararg vars: Pair<String, Any?>): String =
        tInternal(category = category.id, key = key, vars = vars)

    fun t(category: String?, key: String, vararg vars: Pair<String, Any?>): String =
        tInternal(category = category, key = key, vars = vars)

    private fun tInternal(category: String?, key: String, vars: Array<out Pair<String, Any?>>): String =
        renderPlain(tryGet(category, key), vars)

    fun tr(key: String, vararg vars: Pair<String, Any?>): Rendered = tr(category = null, key = key, vars = vars)

    fun tr(category: Categories, key: String, vararg vars: Pair<String, Any?>): Rendered = tr(category = category.id, key = key, vars = vars)

    fun tr(category: String?, key: String, vararg vars: Pair<String, Any?>): Rendered =
        renderRich(tryGet(category, key), vars)

    fun format(template: String, vararg vars: Pair<String, Any?>): Rendered = renderRich(template, vars)

    private fun tryGet(category: String?, key: String): String {
        if (!category.isNullOrBlank()) {
            val table = extraStringTablesByCategory[category]
            if (table != null) {
                val v = table.optString(key, null)
                if (!v.isNullOrEmpty()) return v
            }
        }

        try {
            return Global.getSettings().getString(category, key)
        } catch (t: Throwable) {
            log.info("I18n: string lookup failed: category=$category key=$key (${t.message})")
        }

        return category?.let { if (it.isNotEmpty()) "$it:" else "" } + key
    }

    @JvmStatic
    fun j(category: String?, key: String): String = tryGet(category, key)

    @JvmStatic
    fun j1(category: String?, key: String, varName: String, value: Any?): String =
        t(category, key, varName to value)

    @JvmStatic
    fun j2(category: String?, key: String, varName1: String, value1: Any?, varName2: String, value2: Any?): String =
        t(category, key, varName1 to value1, varName2 to value2)

    private fun loadExtraStringTables(): Map<String, JSONObject> {
        val out = HashMap<String, JSONObject>(4)

        for (path in EXTRA_STRING_TABLE_PATHS) {
            try {
                val root = Global.getSettings().loadJSON(path, Categories.MOD.id)
                val it = root.keys()
                while (it.hasNext()) {
                    val cat = it.next() as? String ?: continue
                    val obj = root.optJSONObject(cat) ?: continue

                    val existing = out[cat]
                    if (existing == null) {
                        out[cat] = obj
                    } else {
                        val it2 = obj.keys()
                        while (it2.hasNext()) {
                            val k = it2.next() as? String ?: continue
                            existing.put(k, obj.get(k))
                        }
                    }
                }
            } catch (_: Throwable) {
                log.info("I18n: no/invalid extra string tables at $path")
            }
        }

        if (out.isNotEmpty()) {
            log.info("I18n: loaded extra string tables (categories=${out.size}) from ${EXTRA_STRING_TABLE_PATHS.size} file(s)")
        }

        return out
    }

    private fun renderPlain(template: String, vars: Array<out Pair<String, Any?>>): String {
        val map = buildVarMap(vars)

        val sb = StringBuilder(template.length + 16)
        val n = template.length
        var i = 0
        while (i < n) {
            val c = template[i]

            if (c == '%') {
                if (i + 1 < n && template[i + 1] == '%') {
                    sb.append('%')
                    i += 2
                    continue
                }

                val end = template.indexOf('%', i + 1)
                if (end > i + 1) {
                    val key = template.substring(i + 1, end)
                    val value = map[key]
                    if (value != null) {
                        sb.append(value.toString())
                        i = end + 1
                        continue
                    }
                }
            } else if (c == '<' && template.startsWith(PARAM_TAG_PREFIX, i)) {
                val end = template.indexOf('>', i + PARAM_TAG_PREFIX.length)
                if (end != -1) {
                    val inner = template.substring(i + PARAM_TAG_PREFIX.length, end)
                    val parsed = parseParamTag(inner)
                    if (parsed != null) {
                        val (key, _) = parsed
                        val value = map[key]
                        if (value != null) {
                            sb.append(value.toString())
                        } else {
                            sb.append(key.replace("%%", "%"))
                        }
                        i = end + 1
                        continue
                    }
                }
            }

            sb.append(c)
            i++
        }

        return sb.toString()
    }

    private fun renderRich(template: String, vars: Array<out Pair<String, Any?>>): Rendered {
        val map = buildVarMap(vars)

        val sb = StringBuilder(template.length + 16)
        val highlightList = mutableListOf<Highlight>()
        val n = template.length
        var i = 0
        while (i < n) {
            val c = template[i]

            if (c == '%') {
                if (i + 1 < n && template[i + 1] == '%') {
                    sb.append('%')
                    i += 2
                    continue
                }

                val end = template.indexOf('%', i + 1)
                if (end > i + 1) {
                    val key = template.substring(i + 1, end)
                    val value = map[key]
                    if (value != null) {
                        sb.append(value.toString())
                        i = end + 1
                        continue
                    }
                }
            } else if (c == '<' && template.startsWith(PARAM_TAG_PREFIX, i)) {
                val end = template.indexOf('>', i + PARAM_TAG_PREFIX.length)
                if (end != -1) {
                    val inner = template.substring(i + PARAM_TAG_PREFIX.length, end)
                    val parsed = parseParamTag(inner)
                    if (parsed != null) {
                        val (key, color) = parsed
                        val value = map[key]
                        val text = (value?.toString()) ?: key.replace("%%", "%")
                        sb.append(text)
                        if (text.isNotEmpty()) {
                            highlightList.add(Highlight(text, color))
                        }
                        i = end + 1
                        continue
                    }
                }
            }

            sb.append(c)
            i++
        }

        return Rendered(text = sb.toString(), highlights = highlightList)
    }

    private fun buildVarMap(vars: Array<out Pair<String, Any?>>): HashMap<String, Any?> {
        val map = HashMap<String, Any?>(vars.size * 2)
        for (p in vars) {
            map[p.first] = p.second
        }
        return map
    }

    private fun parseParamTag(inner: String): Pair<String, Color>? {
        if (inner.length < 9) return null
        if (inner[0] != '#') return null
        if (inner[7] != ':') return null

        val hex = inner.substring(1, 7)
        if (!hex.all { it.isDigit() || (it.lowercaseChar() in 'a'..'f') }) return null

        val key = inner.substring(8)
        if (key.isEmpty()) return null

        val rgb = hex.toInt(16)
        val color = Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
        return key to color
    }

    private fun unescapePercents(template: String): String {
        if (!template.contains("%%")) return template
        return template.replace("%%", "%")
    }

}
