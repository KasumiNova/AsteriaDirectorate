package cn.kasuminova.astd.combat.hullmods.lens

import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI

/**
 * 透镜阵列核心相关 hullmod / 双模式 marker 的稳定 ID（镜像 ARC 范式）。
 */
internal object LensArrayCoreHullModIds {
    const val HULL_ID: String = "astd_gravitational_lens"

    const val CORE: String = "astd_lens_array_core"
    const val SWITCHER: String = "astd_lens_mode_switcher"
    const val MODE_CREWED: String = "astd_lens_mode_crewed"
    const val MODE_AUTOMATED: String = "astd_lens_mode_automated"
    const val NEXT_CREWED: String = "astd_lens_mode_next_crewed"
    const val NEXT_AUTOMATED: String = "astd_lens_mode_next_automated"

    // 阶段二「回声定影」系统 ID（无人/载人分版，与 .system 文件一致）。
    const val SYSTEM_CREWED: String = "astd_echo_fixation_crewed"
    const val SYSTEM_AUTOMATED: String = "astd_echo_fixation_automated"
}

internal fun ShipVariantAPI?.isGravitationalLensVariant(): Boolean {
    val v = this ?: return false
    val hullId = try { v.hullSpec?.hullId } catch (_: Throwable) { null }
    val baseHullId = try { v.hullSpec?.baseHullId } catch (_: Throwable) { null }
    return hullId == LensArrayCoreHullModIds.HULL_ID || baseHullId == LensArrayCoreHullModIds.HULL_ID
}

internal fun ShipAPI?.isGravitationalLensShip(): Boolean {
    val s = this ?: return false
    val hullId = try { s.hullSpec?.hullId } catch (_: Throwable) { null }
    val baseHullId = try { s.hullSpec?.baseHullId } catch (_: Throwable) { null }
    return hullId == LensArrayCoreHullModIds.HULL_ID || baseHullId == LensArrayCoreHullModIds.HULL_ID
}
