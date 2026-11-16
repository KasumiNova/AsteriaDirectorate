package cn.kasuminova.astd.combat.description

import cn.kasuminova.astd.internal.i18n.I18n

internal data class WeaponDescriptionText(
    val primary: String?,
    val primaryHighlights: String?,
    val ancillary: String?,
    val ancillaryHighlights: String?,
)

internal data class ShipSystemDescriptionText(
    val text1: String,
    val text3: String,
    val text4: String?,
    val text5: String?,
)

internal object DescriptionTemplate {
    private val name = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val weaponSlot = Regex("\\{%s}")

    fun parameters(template: String): Set<String> {
        val result = linkedSetOf<String>()
        var index = 0
        while (index < template.length) {
            if (template[index] != '%') {
                index++
            } else if (template.startsWith("%%", index)) {
                index += 2
            } else {
                val end = template.indexOf('%', index + 1)
                val candidate = if (end > index) template.substring(index + 1, end) else ""
                if (name.matches(candidate)) {
                    result += candidate
                    index = end + 1
                } else {
                    index++
                }
            }
        }
        return result
    }

    fun render(template: String, values: Map<String, String>, location: String): String {
        val missing = parameters(template) - values.keys
        require(missing.isEmpty()) { "Missing description parameters at $location: $missing" }
        return I18n.formatPlain(template, *values.entries.map { it.key to it.value }.toTypedArray())
    }

    fun highlights(body: String?, values: String?, location: String): List<String> {
        if (body.isNullOrEmpty()) {
            require(values.isNullOrEmpty()) { "Highlights without description at $location" }
            return emptyList()
        }
        require(values == null || ('\n' !in values && '\r' !in values)) { "Multiline highlight parameter at $location" }
        val parts = if (values.isNullOrEmpty()) emptyList() else values.split('|').map(String::trim)
        require(parts.none { it.isEmpty() || '\n' in it || '\r' in it }) { "Invalid highlight parameter at $location" }
        require(weaponSlot.findAll(body).count() == parts.size) { "Description/highlight count mismatch at $location" }
        return parts
    }
}

internal class DescriptionText(private val raw: (String) -> String) {
    private data class Paragraph(val body: String, val highlights: String?)

    fun weaponBase(id: String, values: Map<String, String>, original: WeaponDescriptionText): WeaponDescriptionText {
        val prefix = "weapon.$id.tooltip"
        fun part(field: String, previousBody: String?, previousHighlights: String?): Paragraph? {
            val body = raw("$prefix.$field")
            val highlights = raw("$prefix.${field}HL")
            require(DescriptionTemplate.parameters(body).isEmpty()) { "Named parameter in native weapon body: $id.$field" }
            if (body.isEmpty()) {
                require(highlights.isEmpty()) { "Highlights without template: $id.$field" }
                return previousBody?.takeIf(String::isNotEmpty)?.let {
                    DescriptionTemplate.highlights(it, previousHighlights, "$id.$field.original")
                    Paragraph(it, previousHighlights)
                }
            }
            val rendered = DescriptionTemplate.render(highlights, values, "$prefix.${field}HL")
            DescriptionTemplate.highlights(body, rendered, "$id.$field")
            return Paragraph(body, rendered.takeIf(String::isNotEmpty))
        }
        val primary = part("customPrimary", original.primary, original.primaryHighlights)
        val ancillary = part("customAncillary", original.ancillary, original.ancillaryHighlights)
        return WeaponDescriptionText(
            primary?.body, primary?.highlights,
            ancillary?.body, ancillary?.highlights,
        )
    }

    fun systemBase(id: String, values: Map<String, String>): ShipSystemDescriptionText {
        fun field(name: String): String = DescriptionTemplate.render(raw("desc.$id.$name"), values, "desc.$id.$name")
        val text1 = field("text1")
        val text3 = field("text3")
        require(text1.isNotBlank() && text3.isNotBlank()) { "Empty system introduction: $id" }
        return ShipSystemDescriptionText(text1, text3, field("text4").takeIf(String::isNotEmpty), field("text5").takeIf(String::isNotEmpty))
    }
}
