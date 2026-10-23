package cn.kasuminova.astd.combat.hullmods.arc

import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI

internal object ASTDXc001HullModIds {
    const val HULL_ID: String = "astd_xc_001"

    const val KEY_AFTERIMAGE_RENDERER: String = "astd_xc_001_afterimage_renderer"

    const val WEAPON_LIGHTS_BLOOM: String = "astd_xc_001_lights_bloom"
}

internal fun ShipVariantAPI?.isASTDXc001Variant(): Boolean {
    val variant = this ?: return false
    val hullId = try {
        variant.hullSpec?.hullId
    } catch (_: Throwable) {
        null
    }
    val baseHullId = try {
        variant.hullSpec?.baseHullId
    } catch (_: Throwable) {
        null
    }
    return hullId == ASTDXc001HullModIds.HULL_ID || baseHullId == ASTDXc001HullModIds.HULL_ID
}

internal fun ShipAPI?.isASTDXc001Ship(): Boolean {
    val ship = this ?: return false
    val hullId = try {
        ship.hullSpec?.hullId
    } catch (_: Throwable) {
        null
    }
    val baseHullId = try {
        ship.hullSpec?.baseHullId
    } catch (_: Throwable) {
        null
    }
    return hullId == ASTDXc001HullModIds.HULL_ID || baseHullId == ASTDXc001HullModIds.HULL_ID
}
