package cn.kasuminova.astd.sscsv.tooltip

import cn.kasuminova.astd.sscsv.CsvTarget
import cn.kasuminova.astd.sscsv.SsCsvEntry
import cn.kasuminova.astd.sscsv.entries.DescriptionEntry
import java.util.TreeMap

internal class TooltipTemplateException(message: String) : IllegalStateException(message)

internal enum class TooltipKind(
    val declarationKey: String,
    val keyPrefix: String,
    val idsKey: String,
) {
    WEAPON("dynamic.weapons", "weapon", "tooltip.weapon.ids"),
    SYSTEM("dynamic.systems", "system", "tooltip.system.ids"),
}

internal enum class EscapePolicy { COLLAPSE, PRESERVE }

internal class DynamicTooltip(
    val kind: TooltipKind,
    val id: String,
    val fields: Map<String, String>,
    val defaults: Map<String, String>,
) {
    fun escapePolicy(field: String): EscapePolicy = when {
        kind == TooltipKind.WEAPON && field in WEAPON_NATIVE_BODY_FIELDS -> EscapePolicy.PRESERVE
        else -> EscapePolicy.COLLAPSE
    }

    fun fieldKey(field: String): String = when (kind) {
        TooltipKind.WEAPON -> "weapon.$id.tooltip.$field"
        TooltipKind.SYSTEM -> "desc.$id.$field"
    }

    fun defaultKey(name: String): String =
        "tooltip.${kind.keyPrefix}.$id.default.$name"

    private companion object {
        val WEAPON_NATIVE_BODY_FIELDS = setOf("customPrimary", "customAncillary")
    }
}

internal class TooltipRegistry private constructor(
    val entries: List<DynamicTooltip>,
) {

    private class IndexedField(
        val entry: DynamicTooltip,
        val field: String,
        val template: String,
    )

    private val fieldIndex: Map<String, IndexedField> = buildMap {
        for (entry in this@TooltipRegistry.entries) {
            for ((field, template) in entry.fields) {
                put(entry.fieldKey(field), IndexedField(entry, field, template))
            }
        }
    }

    fun expandField(key: String): String? {
        val indexed = fieldIndex[key] ?: return null
        val collapse = indexed.entry.escapePolicy(indexed.field) == EscapePolicy.COLLAPSE
        return NamedTemplate.expand(indexed.template, indexed.entry.defaults, collapse)
    }

    fun validateTargets(csvEntries: List<SsCsvEntry>) {
        val weaponIds = csvEntries
            .filter { it.target == CsvTarget.WEAPON_DATA }
            .mapTo(HashSet()) { it.key }
        val systemIds = csvEntries
            .filter { it.target == CsvTarget.SHIP_SYSTEMS }
            .mapTo(HashSet()) { it.key }
        val descriptions = csvEntries
            .filterIsInstance<DescriptionEntry>()
            .associateBy { it.id }

        for (entry in entries) {
            when (entry.kind) {
                TooltipKind.WEAPON -> {
                    if (entry.id !in weaponIds) {
                        throw TooltipTemplateException(
                            "tooltip target not found: kind=weapon id=${entry.id}: no weapon_data entry"
                        )
                    }
                }

                TooltipKind.SYSTEM -> {
                    if (entry.id !in systemIds) {
                        throw TooltipTemplateException(
                            "tooltip target not found: kind=system id=${entry.id}: no ship_systems entry"
                        )
                    }
                    val description = descriptions[entry.id]
                    if (description?.type != "SHIP_SYSTEM") {
                        throw TooltipTemplateException(
                            "tooltip target not found: kind=system id=${entry.id}: " +
                                "no descriptions entry with type=SHIP_SYSTEM"
                        )
                    }
                }
            }
        }
    }

    fun exportedStrings(): Map<String, String> {
        val out = TreeMap<String, String>()
        for (kind in TooltipKind.entries) {
            out[kind.idsKey] = entries.filter { it.kind == kind }.joinToString(",") { it.id }
        }
        for (entry in entries) {
            for ((field, template) in entry.fields) {
                out[entry.fieldKey(field)] = template
            }
            for ((name, value) in entry.defaults) {
                out[entry.defaultKey(name)] = value
            }
        }
        return out
    }

    companion object {

        private val DEFAULT_KEY =
            Regex("^tooltip\\.(weapon|system)\\.([^.]+)\\.default\\.([A-Za-z][A-Za-z0-9_]*)$")

        private val SYSTEM_FIELDS = listOf("text1", "text2", "text3", "text4", "text5")

        fun parse(source: Map<String, String>): TooltipRegistry {
            val declared = TooltipKind.entries.associateWith { kind ->
                declarationIds(source[kind.declarationKey], kind)
            }
            val defaults = collectDefaults(source, declared)
            val entries = TooltipKind.entries.flatMap { kind ->
                declared.getValue(kind).map { id ->
                    val entryDefaults = defaults[kind to id].orEmpty()
                    when (kind) {
                        TooltipKind.WEAPON -> parseWeapon(id, entryDefaults, source)
                        TooltipKind.SYSTEM -> parseSystem(id, entryDefaults, source)
                    }
                }
            }
            return TooltipRegistry(entries)
        }

        private fun declarationIds(value: String?, kind: TooltipKind): List<String> {
            val ids = value
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: return emptyList()

            val seen = HashSet<String>()
            for (id in ids) {
                if (!seen.add(id)) {
                    throw TooltipTemplateException(
                        "tooltip declaration duplicated: kind=${kind.keyPrefix} id=$id key=${kind.declarationKey}"
                    )
                }
            }
            return ids
        }

        private fun collectDefaults(
            source: Map<String, String>,
            declared: Map<TooltipKind, List<String>>,
        ): Map<Pair<TooltipKind, String>, Map<String, String>> {
            val declaredIds = declared.mapValues { (_, ids) -> ids.toHashSet() }
            val defaults = HashMap<Pair<TooltipKind, String>, HashMap<String, String>>()

            for ((key, value) in source) {
                val match = DEFAULT_KEY.matchEntire(key) ?: continue
                val kind = TooltipKind.entries.first { it.keyPrefix == match.groupValues[1] }
                val id = match.groupValues[2]
                if (id !in declaredIds.getValue(kind)) {
                    throw TooltipTemplateException(
                        "tooltip parameter targets undeclared ${kind.keyPrefix}: id=$id key=$key"
                    )
                }
                val name = match.groupValues[3]
                rejectValueBreakers(kind, id, key, value)
                defaults.getOrPut(kind to id) { HashMap() }[name] = value
            }

            return defaults
        }

        private fun rejectValueBreakers(
            kind: TooltipKind,
            id: String,
            key: String,
            value: String,
        ) {
            val newline = value.contains('\n') || value.contains('\r')
            val pipe = value.contains('|')
            if (!newline && !pipe) return

            val forbidden = when {
                newline && pipe -> "line break or '|'"
                newline -> "line break"
                else -> "'|'"
            }
            throw TooltipTemplateException(
                "tooltip parameter value must not contain $forbidden: " +
                    "kind=${kind.keyPrefix} id=$id key=$key"
            )
        }

        private fun parseWeapon(
            id: String,
            defaults: Map<String, String>,
            source: Map<String, String>,
        ): DynamicTooltip {
            val fields = LinkedHashMap<String, String>()

            for (field in listOf("customPrimary", "customPrimaryHL")) {
                val key = "weapon.$id.tooltip.$field"
                val raw = source[key] ?: fail(
                    TooltipKind.WEAPON, id, field, null, "missing template key $key"
                )
                if (raw.isBlank()) {
                    fail(TooltipKind.WEAPON, id, field, null, "template must not be blank")
                }
                fields[field] = raw
            }

            val ancillary = source["weapon.$id.tooltip.customAncillary"]
            val ancillaryHL = source["weapon.$id.tooltip.customAncillaryHL"]
            if ((ancillary == null) != (ancillaryHL == null)) {
                fail(
                    TooltipKind.WEAPON, id, "customAncillary", null,
                    "customAncillary and customAncillaryHL must be declared together"
                )
            }
            if (ancillary != null && ancillaryHL != null) {
                fields["customAncillary"] = ancillary
                fields["customAncillaryHL"] = ancillaryHL
            }

            validateWeaponFields(id, fields, defaults)
            return DynamicTooltip(TooltipKind.WEAPON, id, fields, defaults)
        }

        private fun validateWeaponFields(
            id: String,
            fields: Map<String, String>,
            defaults: Map<String, String>,
        ) {
            for (bodyField in listOf("customPrimary", "customAncillary")) {
                val body = fields[bodyField] ?: continue
                val varName = NamedTemplate.variables(body).firstOrNull() ?: continue
                fail(
                    TooltipKind.WEAPON, id, bodyField, varName,
                    "weapon body keeps native {%s} slots and may not use named parameters; move dynamic values to the highlight template"
                )
            }

            for (hlField in listOf("customPrimaryHL", "customAncillaryHL")) {
                val highlight = fields[hlField] ?: continue
                val bodyField = if (hlField == "customPrimaryHL") "customPrimary" else "customAncillary"

                if (highlight.contains('\n') || highlight.contains('\r')) {
                    fail(
                        TooltipKind.WEAPON, id, hlField, null,
                        "highlight template must be single line"
                    )
                }

                for (name in NamedTemplate.variables(highlight)) {
                    if (name !in defaults) {
                        fail(
                            TooltipKind.WEAPON, id, hlField, name,
                            "missing tooltip parameter default"
                        )
                    }
                }

                val segments = if (highlight.isBlank()) emptyList() else highlight.split('|')
                if (segments.any { it.isBlank() }) {
                    fail(
                        TooltipKind.WEAPON, id, hlField, null,
                        "highlight template has an empty parameter"
                    )
                }

                val slots = NamedTemplate.slotCount(fields.getValue(bodyField))
                if (slots != segments.size) {
                    fail(
                        TooltipKind.WEAPON, id, hlField, null,
                        "${bodyField} has $slots {%s} slot(s) but highlight has ${segments.size} parameter(s)"
                    )
                }
            }
        }

        private fun parseSystem(
            id: String,
            defaults: Map<String, String>,
            source: Map<String, String>,
        ): DynamicTooltip {
            val fields = LinkedHashMap<String, String>()
            for (field in SYSTEM_FIELDS) {
                val key = "desc.$id.$field"
                fields[field] = source[key] ?: fail(
                    TooltipKind.SYSTEM, id, field, null, "missing template key $key"
                )
            }

            for ((field, template) in fields) {
                for (name in NamedTemplate.variables(template)) {
                    if (name !in defaults) {
                        fail(
                            TooltipKind.SYSTEM, id, field, name,
                            "missing tooltip parameter default"
                        )
                    }
                }
            }

            for (field in listOf("text1", "text3")) {
                if (fields.getValue(field).isBlank()) {
                    fail(
                        TooltipKind.SYSTEM, id, field, null,
                        "must not be blank because the native setter replaces empty text with a default hint"
                    )
                }
            }

            return DynamicTooltip(TooltipKind.SYSTEM, id, fields, defaults)
        }

        private fun fail(
            kind: TooltipKind,
            id: String,
            field: String?,
            varName: String?,
            reason: String,
        ): Nothing {
            val where = buildString {
                append("tooltip template invalid: kind=")
                append(kind.keyPrefix)
                append(" id=")
                append(id)
                if (field != null) {
                    append(" field=")
                    append(field)
                }
                if (varName != null) {
                    append(" var=")
                    append(varName)
                }
            }
            throw TooltipTemplateException("$where: $reason")
        }
    }
}
