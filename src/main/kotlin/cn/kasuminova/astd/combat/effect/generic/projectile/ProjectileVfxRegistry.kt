package cn.kasuminova.astd.combat.effect.generic.projectile

import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxPreset
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxPresetCatalog
import com.fs.starfarer.api.Global
import org.json.JSONObject

/**
 * 以 projectileSpecId 为键的 projectile runtime VFX 注册表。
 *
 * 配置文件声明 projectileSpecId 到 runtime preset id 的映射，preset 由
 * [ASTDProjectileVfxPresetCatalog] 集中维护。
 */
internal object ProjectileVfxRegistry {

    private const val CONFIG_PATH = "data/config/astd_projectile_vfx.json"

    private val log = Global.getLogger(ProjectileVfxRegistry::class.java)

    private var loaded = false
    private val presetIdByProjectileSpecId: MutableMap<String, String> = HashMap()

    fun ensureLoaded() {
        if (loaded) return

        presetIdByProjectileSpecId.clear()

        var ok = false
        try {
            val json = Global.getSettings().loadJSON(CONFIG_PATH)
            applyConfig(json)
            ok = presetIdByProjectileSpecId.isNotEmpty()
            log.info("ProjectileVfxRegistry: loaded runtime config from $CONFIG_PATH (entries=${presetIdByProjectileSpecId.size})")
        } catch (ex: Exception) {
            log.info("ProjectileVfxRegistry: no/invalid runtime config at $CONFIG_PATH", ex)
        }

        if (!ok) {
            loadDefaults()
            log.info("ProjectileVfxRegistry: using built-in runtime defaults (entries=${presetIdByProjectileSpecId.size})")
        }

        loaded = true
    }

    fun presetFor(projectileSpecId: String): ASTDProjectileVfxPreset? {
        ensureLoaded()
        val presetId = presetIdByProjectileSpecId[projectileSpecId] ?: return null
        return ASTDProjectileVfxPresetCatalog.preset(presetId)
    }

    private fun loadDefaults() {
        presetIdByProjectileSpecId.clear()
        DEFAULT_PRESET_IDS_BY_PROJECTILE_SPEC_ID.forEach { (projectileSpecId, presetId) ->
            if (ASTDProjectileVfxPresetCatalog.preset(presetId) != null) {
                presetIdByProjectileSpecId[projectileSpecId] = presetId
            }
        }
    }

    private fun applyConfig(json: JSONObject) {
        val entries = json.optJSONArray("entries") ?: return
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val projectileSpecId = entry.optString("projectileSpecId", "").trim()
            val presetId = entry.optString("preset", "").trim()
            if (projectileSpecId.isBlank() || presetId.isBlank()) continue

            if (ASTDProjectileVfxPresetCatalog.preset(presetId) == null) {
                log.warn("ProjectileVfxRegistry: unknown runtime preset '$presetId' for projectileSpecId=$projectileSpecId")
                continue
            }

            presetIdByProjectileSpecId[projectileSpecId] = presetId
        }
    }

    private val DEFAULT_PRESET_IDS_BY_PROJECTILE_SPEC_ID: Map<String, String> = mapOf(
        "astd_aod7_shot" to "aod7_shot",
        "astd_spc3_shot" to "spc3_shot",
        "astd_drv9_slug" to "drv9_slug",
        "astd_drv11_slug" to "drv11",
        "astd_drv_omega_slug" to "drv_omega_slug",
        "astd_slt3_pulse" to "slt3_pulse",
        "astd_slt4_burst" to "slt4_burst",
        "astd_slt_omega_stream" to "slt_omega_stream",
        "astd_vpd6_pulse" to "vpd6_pulse",
        "astd_vpd_omega_arc" to "vpd_omega_arc",
        "astd_rct6_torp" to "rct6",
        "astd_tsm2_missile" to "singularity_event_horizon_missile",
        "astd_tsm_omega_missile" to "tsm_omega_missile",
        "astd_gsp12_rift" to "gsp12_rift",
        "astd_jmb2_beam" to "jmb2_beam",
        "astd_jmb9_beam" to "jmb9_beam",
        "astd_jmb_omega_beam" to "jmb_omega_beam",
        "astd_sgl8_swarm" to "singularity_nova_missile",
        "astd_stellar_jet_bolt" to "stellar_jet_bolt",
        "astd_fdp4_charge" to "fdp4_charge",
        "astd_ftb_omega_beam" to "ftb_omega_beam",
        "astd_mnl2_mine" to "mnl2_mine",
        "astd_mnl3_mine" to "mnl3_mine",
        "astd_mnl_omega_grid" to "mnl_omega_grid",
    )
}
