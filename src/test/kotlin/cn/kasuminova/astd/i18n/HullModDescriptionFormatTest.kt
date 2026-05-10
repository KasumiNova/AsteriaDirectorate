package cn.kasuminova.astd.i18n

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class HullModDescriptionFormatTest {

    @Test
    fun `formatter facing ss csv i18n texts are String format safe`() {
        val propertiesPath = Path.of("ss-csv", "src", "main", "resources", "i18n", "zh-cn.properties")
        assertTrue(Files.exists(propertiesPath), "缺少测试输入文件: $propertiesPath")

        val properties = Properties()
        Files.newBufferedReader(propertiesPath).use { reader ->
            properties.load(reader)
        }

        val keys = properties.stringPropertyNames()
            .filter {
                it.startsWith("hullmod.") ||
                    it.startsWith("desc.") ||
                    it.startsWith("system.")
            }
            .sorted()

        assertTrue(keys.isNotEmpty(), "未找到任何 ss-csv 本地化文本")

        val dummyArgs = arrayOf<Any>("A", "B", 1, 2.5f)
        val failures = mutableListOf<String>()

        for (key in keys) {
            val value = properties.getProperty(key) ?: continue
            try {
                String.format(Locale.ROOT, value, *dummyArgs)
            } catch (t: Throwable) {
                failures += "$key => ${t::class.simpleName}: ${t.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail("以下 ss-csv 文本不符合 String.format 规范:\n${failures.joinToString("\n")}")
        }
    }

    @Test
    fun `tactical overdrive runtime status strings exist`() {
        val jsonPath = Path.of("contents", "data", "strings", "strings.json")
        assertTrue(Files.exists(jsonPath), "缺少运行时字符串文件: $jsonPath")

        val text = Files.readString(jsonPath)
        assertTrue(text.contains("\"asteria_directorate\""), "缺少 asteria_directorate 分类")
        assertTrue(text.contains("\"system.arc_flare_overdrive.status.crewed.active\""), "缺少 system.arc_flare_overdrive.status.crewed.active")
        assertTrue(text.contains("\"system.arc_flare_overdrive.status.automated.active\""), "缺少 system.arc_flare_overdrive.status.automated.active")
    }

    @Test
    fun `systems strings json values are String format safe`() {
        val jsonPath = Path.of("contents", "data", "strings", "systems_strings.json")
        assertTrue(Files.exists(jsonPath), "缺少系统字符串文件: $jsonPath")

        val text = Files.readString(jsonPath)
        val values = Regex("\"((?:\\\\.|[^\\\"])*)\"\\s*:\\s*\"((?:\\\\.|[^\\\"])*)\"")
            .findAll(text)
            .map { it.groupValues[1] to it.groupValues[2].replace("\\\"", "\"").replace("\\n", "\n") }
            .filterNot { (_, value) -> Regex("%[A-Za-z][A-Za-z0-9_]*%") .containsMatchIn(value) }
            .toList()

        assertTrue(values.isNotEmpty(), "未找到任何适合原生格式化检查的系统字符串值")

        val dummyArgs = arrayOf<Any>("A", "B", 12, 25)
        val failures = mutableListOf<String>()
        for ((key, value) in values) {
            try {
                String.format(Locale.ROOT, value, *dummyArgs)
            } catch (t: Throwable) {
                failures += "$key => ${t::class.simpleName}: ${t.message} => $value"
            }
        }

        if (failures.isNotEmpty()) {
            fail("以下 systems_strings.json 文本不符合 String.format 规范:\n${failures.joinToString("\n")}")
        }
    }
}