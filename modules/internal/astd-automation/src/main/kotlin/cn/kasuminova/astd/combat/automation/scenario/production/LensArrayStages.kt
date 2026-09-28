package cn.kasuminova.astd.combat.automation.scenario.production

import cn.kasuminova.astd.combat.hullmods.lens.LensArrayCoreHullModIds
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipVariantAPI

/**
 * 决明级 phase1/phase2 场景共享查询（拆分前枢纽私有函数，两场景诊断/查找共用）。
 */
    internal fun lensDeployedShipIds(engine: CombatEngineAPI): List<String> =
        engine.ships
            .asSequence()
            .filter { !it.isFighter }
            .mapNotNull { it.hullSpec?.hullId }
            .distinct()
            .sorted()
            .toList()

    /** ShipVariantAPI.hasLensAutomatedMode 的安全包装（perma-mod 或普通 hullmod 任一即无人模式）。 */
    internal fun ShipVariantAPI.hasLensAutomatedModeSafe(): Boolean =
        try {
            permaMods.contains(LensArrayCoreHullModIds.MODE_AUTOMATED) ||
                    hasHullMod(LensArrayCoreHullModIds.MODE_AUTOMATED)
        } catch (_: Throwable) {
            false
        }
