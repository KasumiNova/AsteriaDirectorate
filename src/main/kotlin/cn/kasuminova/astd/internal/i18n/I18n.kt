package cn.kasuminova.astd.internal.i18n

import com.fs.starfarer.api.Global
import org.json.JSONObject
import java.awt.Color

object I18n {

    /**
     * 额外字符串表：用于把“非 strings.json 的翻译/文本”独立放在单独文件中维护。
     *
     * 说明：Starsector 默认仅加载 `data/strings/strings.json` 进 StringManager。
     * 这里提供一个“兜底读取”：当 [settings.getString] 找不到时，从这些额外 JSON 里取。
     */
    private val EXTRA_STRING_TABLE_PATHS: List<String> = listOf(
        "data/strings/bounty_strings.json",
        "data/strings/companions_strings.json",
        "data/strings/systems_strings.json",
        "data/strings/settings_strings.json",
    )

    private val log by lazy { Global.getLogger(I18n::class.java) }

    /**
     * category -> (key -> value)
     *
     * 说明：
     * - Starsector 默认仅加载 `data/strings/strings.json` 进 StringManager。
     * - 这里提供一个“兜底读取”：当 [settings.getString] 找不到时，从额外 JSON 里取。
     */
    private val extraStringTablesByCategory: Map<String, JSONObject> by lazy {
        loadExtraStringTables()
    }

    /**
     * 命名占位符格式：
     * - `%key%` （支持 `%%` 转义为字面 `%`）
     *
     * 通用“可高亮变量”标记（由翻译文本决定是否高亮 + 颜色）：
     * - `<param:#RRGGBB:key>`
     *
     * 说明：
     * - 在不支持高亮文本显示的地方，`<param:...>` 会被当作普通变量替换（即忽略高亮语义）。
     * - 颜色只在 UI 层被使用（[I18nUi]），纯字符串场景不会注入任何富文本。
     *
     * - 未提供的 key：默认保留原样（便于发现漏传参）
     */
    private const val PARAM_TAG_PREFIX: String = "<param:"

    data class Highlight(val text: String, val color: Color)

    /** 渲染结果：用于把“替换后的全文”与“需要高亮的片段”一起返回给 UI 层。 */
    data class Rendered(
        val text: String,
        /** 仅在支持高亮的 UI 场景使用（见 [I18nUi]）。 */
        val highlights: List<Highlight> = emptyList()
    )

    enum class Categories {

        /** 推荐：用 modId 作为 category，便于翻译补丁覆盖与避免 key 冲突。 */
        MOD("asteria_directorate"),

        /** 如果你希望按系统/领域分组，也可以继续使用类似 hull_mods 的 category。 */
        HULL_MODS("hull_mods"),
        ;

        internal val id: String

        constructor(key: String) {
            this.id = key
        }

    }

    /** 读取字符串（不做任何替换）。 */
    operator fun get(key: String): String = get(category = null, key = key)

    /** 读取字符串（不做任何替换）。 */
    operator fun get(category: Categories, key: String): String = get(category.id, key)

    /** 读取字符串（不做任何替换）。 */
    operator fun get(category: String?, key: String): String = tryGet(category, key)

    /**
     * 读取并做替换（不含高亮信息）。
     *
     * - `%key%` 与 `<param:...>` 都会被替换为值
     * - `<param:...>` 的“高亮语义”会被忽略（仅输出纯文本）
     */
    fun t(key: String, vararg vars: Pair<String, Any?>): String =
        tInternal(category = null, key = key, vars = vars)

    fun t(category: Categories, key: String, vararg vars: Pair<String, Any?>): String =
        tInternal(category = category.id, key = key, vars = vars)

    fun t(category: String?, key: String, vararg vars: Pair<String, Any?>): String =
        tInternal(category = category, key = key, vars = vars)

    private fun tInternal(category: String?, key: String, vars: Array<out Pair<String, Any?>>): String =
        renderPlain(tryGet(category, key), vars)

    /**
     * 读取并做替换，同时返回“需要高亮的片段（包含颜色）”。
     *
     * 用法示例：
     * - `val r = I18n.tr(Categories.MOD, "ui.cost", "cost" to cost)`
     * - `I18nUi.addPara(tooltip, Categories.MOD, "ui.cost", pad, baseColor, "cost" to cost)`
     */
    fun tr(key: String, vararg vars: Pair<String, Any?>): Rendered = tr(category = null, key = key, vars = vars)

    fun tr(category: Categories, key: String, vararg vars: Pair<String, Any?>): Rendered = tr(category = category.id, key = key, vars = vars)

    fun tr(category: String?, key: String, vararg vars: Pair<String, Any?>): Rendered =
        renderRich(tryGet(category, key), vars)

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
        } catch (_: Throwable) {
            // ignore & fallback
        }

        return category?.let { if (it.isNotEmpty()) "$it:" else "" } + key
    }

    // --- Java-friendly wrappers -------------------------------------------------

    /** Java 侧便捷读取（不做替换）。 */
    @JvmStatic
    fun j(category: String?, key: String): String = tryGet(category, key)

    /** Java 侧便捷读取（替换 1 个变量）。 */
    @JvmStatic
    fun j1(category: String?, key: String, varName: String, value: Any?): String =
        t(category, key, varName to value)

    /** Java 侧便捷读取（替换 2 个变量）。 */
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

                    // 合并同 category：后加载的文件会覆盖同 key。
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
                // 可选文件：缺失/损坏均不致命
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

    /**
     * 解析 `<param:#RRGGBB:key>` 的内部部分：`#RRGGBB:key`
     * 返回 key + Color。
     */
    private fun parseParamTag(inner: String): Pair<String, Color>? {
        // 期望：#FFFFFF:some.key
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
        // 仅处理 `%%` -> `%`，其余保持原样（未替换的占位符可被肉眼发现）。
        if (!template.contains("%%")) return template
        return template.replace("%%", "%")
    }

}
