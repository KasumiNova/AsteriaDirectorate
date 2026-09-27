package cn.kasuminova.astd.combat.hullmods.arc

import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI

/**
 * xc_001（星坠）的 id 集合与 hull 判定。
 *
 * 渲染键（KEY_*）由各 effect/manager 共用，避免各自持有字面量；
 * [isASTDXc001Variant] / [isASTDXc001Ship] 仅判定 xc_001 这一具体 hull。
 */
internal object ASTDXc001HullModIds {
    const val HULL_ID: String = "astd_xc_001"

    /** 舰体渲染插件的 combat engine key，由各 effect/manager 共用，避免各自持有字面量。 */
    const val KEY_AFTERIMAGE_RENDERER: String = "astd_xc_001_afterimage_renderer"
    const val KEY_EMISSIVE_OVERLAY_MANAGER: String = "astd_xc_001_emissive_overlay_manager"
    const val KEY_ENGINE_FLARE_MANAGER: String = "astd_xc_001_engine_flare_manager"

    /** 静态装饰灯 bloom 描边武器 id（[cn.kasuminova.astd.renderer.effect.system.ASTDShipGlowEffect] 识别冷态蓝基底用）。 */
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
