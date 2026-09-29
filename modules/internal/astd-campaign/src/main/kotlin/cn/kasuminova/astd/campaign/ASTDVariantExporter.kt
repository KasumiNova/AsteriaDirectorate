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

/**
 * 开发者用舰船装配（.variant）导出器。
 *
 * 把游戏内配好的 [ShipVariantAPI] 序列化为与原版加载器（HullVariantSpec 的 JSONObject 构造器）
 * 兼容的标准 .variant JSON，并写入游戏根目录下 [EXPORT_DIR]。产物可直接移入
 * `contents/data/variants/` 作为 stock variant 被游戏加载。通常经 SSOptimizer runcode 调用，
 * 字段集与原版 `HullVariantSpec.toJSONObject()` 对齐。
 */
object ASTDVariantExporter {

    /** 相对游戏根目录（进程工作目录）的导出目录。 */
    const val EXPORT_DIR = "saves/astd_variant_export"

    private val log: Logger = Logger.getLogger(ASTDVariantExporter::class.java)

    private val fileNameTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val unsafeFileNameChars = Regex("[^A-Za-z0-9._-]")

    /**
     * 序列化为标准 .variant JSON。字段对齐原版 `HullVariantSpec.toJSONObject()`：
     * 必需字段 displayName/hullId/variantId/fluxVents/fluxCapacitors/weaponGroups 始终写出；
     * goalVariant 仅在为 true 时写出；wings/modules/tags/sModdedBuiltIns 仅在非空时写出；
     * quality 为原版加载器忽略但 stock variant 惯例保留的元数据字段。
     */
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

    /**
     * 导出单个装配到 [outputDir]，返回写出的文件。
     * 存在未分组装武器的装配会先克隆并自动分组，不改动传入的装配对象。
     */
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

    /** 导出舰队成员的当前装配。战斗机成员没有可导出的舰船装配，调用方需自行过滤。 */
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

    /** 导出整支舰队的全部舰船成员（不含舰载机联队），返回写出的文件列表。 */
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

    /** runcode 便捷入口：导出玩家舰队旗舰。 */
    @JvmStatic
    fun exportPlayerFlagship(): File {
        val flagship = requireNotNull(playerFleet().flagship) { "player fleet has no flagship" }
        return exportMember(flagship)
    }

    /** runcode 便捷入口：导出玩家舰队全部舰船成员。 */
    @JvmStatic
    fun exportPlayerFleet(): List<File> {
        return exportFleet(playerFleet())
    }

    /** runcode 便捷入口：按舰体 id 过滤导出玩家舰队成员。 */
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
