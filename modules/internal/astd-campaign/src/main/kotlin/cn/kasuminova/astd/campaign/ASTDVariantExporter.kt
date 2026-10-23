package cn.kasuminova.astd.campaign

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.apache.log4j.Logger
import org.json.JSONArray
import org.json.JSONObject

/** 把 [ShipVariantAPI] 序列化为与原版 `HullVariantSpec.toJSONObject()` 字段对齐的 .variant JSON，写入 [EXPORT_DIR]。 */
object ASTDVariantExporter {

    const val EXPORT_DIR = "saves/astd_variant_export"

    private val log: Logger = Logger.getLogger(ASTDVariantExporter::class.java)

    private val fileNameTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val unsafeFileNameChars = Regex("[^A-Za-z0-9._-]")

    @JvmStatic
    @JvmOverloads
    fun toJson(variant: ShipVariantAPI, quality: Float = 1.0f): JSONObject {
        val hullSpec = requireNotNull(variant.hullSpec) {
            "variant ${variant.hullVariantId} has no hull spec"
        }

        val json = JSONObject()
        json.put("displayName", variant.displayName.orEmpty())
        json.put("hullId", hullSpec.hullId)
        json.put("variantId", variant.hullVariantId)
        json.put("fluxVents", variant.numFluxVents)
        json.put("fluxCapacitors", variant.numFluxCapacitors)
        json.put("quality", quality.toDouble())
        if (variant.isGoalVariant) {
            json.put("goalVariant", true)
        }

        val groups = JSONArray()
        for (group in variant.weaponGroups.orEmpty()) {
            val groupJson = JSONObject()
            groupJson.put("mode", group.type.name)
            groupJson.put("autofire", group.isAutofireOnByDefault)
            val weapons = JSONObject()
            for (slotId in group.slots.orEmpty()) {
                val weaponId = variant.getWeaponId(slotId)
                if (!weaponId.isNullOrBlank()) {
                    weapons.put(slotId, weaponId)
                }
            }
            groupJson.put("weapons", weapons)
            groups.put(groupJson)
        }

        val stationModules = variant.stationModules.orEmpty()
        if (stationModules.isNotEmpty()) {
            val modules = JSONArray()
            for ((slotId, moduleVariantId) in stationModules) {
                modules.put(JSONObject().put(slotId, moduleVariantId))
            }
            json.put("modules", modules)
        }

        json.put("weaponGroups", groups)

        if (variant.fittedWings.orEmpty().isNotEmpty()) {
            val wings = JSONArray()
            val allWings = variant.wings.orEmpty()
            for (index in allWings.indices) {
                if (hullSpec.isBuiltInWing(index)) continue
                wings.put(allWings[index].orEmpty())
            }
            json.put("wings", wings)
        }

        val hullMods = JSONArray()
        for (modId in variant.hullMods.orEmpty()) {
            if (!hullSpec.isBuiltInMod(modId)) {
                hullMods.put(modId)
            }
        }
        json.put("hullMods", hullMods)

        val permaMods = JSONArray()
        for (modId in variant.permaMods.orEmpty()) {
            permaMods.put(modId)
        }
        json.put("permaMods", permaMods)

        val sMods = JSONArray()
        for (modId in variant.sMods.orEmpty()) {
            sMods.put(modId)
        }
        json.put("sMods", sMods)

        val sModdedBuiltInIds = variant.sModdedBuiltIns.orEmpty()
        if (sModdedBuiltInIds.isNotEmpty()) {
            val sModdedBuiltIns = JSONArray()
            for (modId in sModdedBuiltInIds) {
                sModdedBuiltIns.put(modId)
            }
            json.put("sModdedBuiltIns", sModdedBuiltIns)
        }

        val tagList = variant.tags.orEmpty()
        if (tagList.isNotEmpty()) {
            val tags = JSONArray()
            for (tag in tagList) {
                tags.put(tag)
            }
            json.put("tags", tags)
        }

        return json
    }

    @JvmStatic
    @JvmOverloads
    fun exportVariant(
        variant: ShipVariantAPI,
        quality: Float = 1.0f,
        outputDir: File = File(EXPORT_DIR),
    ): File {
        val prepared = if (variant.hasUnassignedWeapons()) {
            variant.clone().apply { assignUnassignedWeapons() }
        } else {
            variant
        }
        val json = toJson(prepared, quality)
        return writeVariantFile(json, prepared.hullVariantId, outputDir)
    }

    @JvmStatic
    @JvmOverloads
    fun exportMember(
        member: FleetMemberAPI,
        quality: Float = 1.0f,
        outputDir: File = File(EXPORT_DIR),
    ): File {
        val variant = requireNotNull(member.variant) {
            "fleet member ${member.id} has no variant"
        }
        return exportVariant(variant, quality, outputDir)
    }

    @JvmStatic
    @JvmOverloads
    fun exportFleet(
        fleet: CampaignFleetAPI,
        quality: Float = 1.0f,
        outputDir: File = File(EXPORT_DIR),
    ): List<File> {
        return fleet.fleetData.membersListCopy
            .filterNot { it.isFighterWing }
            .map { exportMember(it, quality, outputDir) }
    }

    @JvmStatic
    fun exportPlayerFlagship(): File {
        val flagship = requireNotNull(playerFleet().flagship) { "player fleet has no flagship" }
        return exportMember(flagship)
    }

    @JvmStatic
    fun exportPlayerFleet(): List<File> {
        return exportFleet(playerFleet())
    }

    @JvmStatic
    fun exportPlayerFleetByHullId(hullId: String): List<File> {
        return playerFleet().fleetData.membersListCopy
            .filterNot { it.isFighterWing }
            .filter { it.hullId == hullId }
            .map { exportMember(it) }
    }

    private fun playerFleet(): CampaignFleetAPI {
        return requireNotNull(Global.getSector()?.playerFleet) { "no player fleet in current context" }
    }

    private fun writeVariantFile(json: JSONObject, variantId: String?, outputDir: File): File {
        val baseName = (variantId.orEmpty().ifBlank { "variant" })
            .replace(unsafeFileNameChars, "_")
        val file = uniqueFile(outputDir, baseName)
        try {
            outputDir.mkdirs()
            file.writeText(json.toString(4) + "\n", Charsets.UTF_8)
        } catch (e: IOException) {
            log.error("[ASTDVariantExporter] Failed to write variant ${variantId} to ${file.absolutePath}", e)
            throw e
        }
        log.info("[ASTDVariantExporter] Exported variant ${variantId} to ${file.absolutePath}")
        return file
    }

    private fun uniqueFile(outputDir: File, baseName: String): File {
        val stamp = fileNameTime.format(LocalDateTime.now())
        var candidate = File(outputDir, "${baseName}_${stamp}.variant")
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(outputDir, "${baseName}_${stamp}-${suffix++}.variant")
        }
        return candidate
    }
}
