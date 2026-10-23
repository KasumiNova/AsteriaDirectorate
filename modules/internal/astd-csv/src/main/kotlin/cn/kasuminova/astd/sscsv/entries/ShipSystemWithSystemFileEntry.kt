package cn.kasuminova.astd.sscsv.entries

import cn.kasuminova.astd.sscsv.GeneratedFile
import cn.kasuminova.astd.sscsv.SsExtraOutputs

abstract class ShipSystemWithSystemFileEntry : ShipSystemEntry(), SsExtraOutputs {

    open val systemType: String = "STAT_MOD"

    open val aiType: String = "WEAPON_BOOST"

    open val aiScript: String? = null

    open val statsScript: String = "cn.kasuminova.astd.combat.shipsystems.PlaceholderShipSystemStats"

    open val useSound: String? = "system_ammo_feeder"

    open val loopSound: String? = null

    open val deactivateSound: String? = null

    open val outOfUsesSound: String? = null

    open val extraSystemRawFields: Map<String, String> = emptyMap()

    override fun extraFiles(): List<GeneratedFile> {
        val fields = linkedMapOf<String, String>(
            "id" to id,
            "type" to systemType,
            "aiType" to aiType,
        )

        aiScript?.trim()?.takeIf { it.isNotEmpty() }?.let {
            fields["aiScript"] = it
        }

        fields["statsScript"] = statsScript
        useSound?.let { fields["useSound"] = it }
        loopSound?.let { fields["loopSound"] = it }
        deactivateSound?.let { fields["deactivateSound"] = it }
        outOfUsesSound?.let { fields["outOfUsesSound"] = it }

        val body = buildString {
            fields.entries.joinTo(this, ",\n") { (k, v) ->
                "    \"$k\": \"$v\""
            }
            if (extraSystemRawFields.isNotEmpty()) {
                append(",\n")
                extraSystemRawFields.entries.joinTo(this, ",\n") { (k, v) ->
                    "    \"$k\": $v"
                }
            }
        }
        val json = "{\n$body\n}\n"

        return listOf(
            GeneratedFile(
                relativePath = "data/shipsystems/$id.system",
                content = json,
            )
        )
    }
}
