package cn.kasuminova.astd.combat.hullmods

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionShipIds
import cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityStabilizerHullMod
import com.fs.starfarer.api.impl.campaign.ids.HullMods

/**
 * 船插互斥注册表：控制方船插 id → 其禁装的候选船插集合（唯一真相来源）。
 *
 * 控制方船插的 IncompatibleHullmodStripper 延迟清理列表与赏金装配管线
 * （BountyFleetTunerImpl 的 SMod/填充候选过滤）共用本表，避免散落字符串与双份维护漂移。
 * 方向语义：控制方在场（内置或已装）时，禁装集合内的候选不得装入，已装的由 stripper 清理。
 */
object HullmodIncompatibility {

    /** 控制方船插 id → 禁装候选集合。 */
    val FORBIDDEN_BY_CONTROLLER: Map<String, Set<String>> = mapOf(
        // 高级舰装集成（列星 xc_103 内置）：一切其他目标定位系统
        ASTDArcProductionShipIds.HULLMOD_ARC_ADVANCED_TARGETING_SYSTEM to setOf(
            "targetingunit",
            "integratedtargetingunit",
            "dedicated_targeting_core",
            "dedicatedtargetingcore",
            "advancedcore",
            "advancedoptics",
            "supercomputer",
            HullMods.DISTRIBUTED_FIRE_CONTROL,
        ),
        // 等离子装甲护盾（熔壁 xc_101 内置）：护盾分流器与强化护盾
        ASTDArcProductionShipIds.HULLMOD_PLASMA_ARMOR_SHIELD to setOf(
            HullMods.SHIELD_SHUNT,
            HullMods.HARDENED_SHIELDS,
        ),
        // 奇点稳定器（联制线 lh_001/lh_002 内置）：安全协议超驰
        ASTDSingularityStabilizerHullMod.MOD_ID to setOf(HullMods.SAFETYOVERRIDES),
    )

    /** 单个控制方船插的禁装集合（未登记返回空集）。 */
    fun forbiddenByController(controllerHullmodId: String): Set<String> =
        FORBIDDEN_BY_CONTROLLER[controllerHullmodId].orEmpty()

    /**
     * 变体当前控制方船插所禁装的候选并集（纯函数）：
     * 入参为变体已装船插（hull builtInMods ∪ variant.hullMods），装配管线据此过滤候选。
     */
    fun forbiddenCandidates(presentHullmodIds: Collection<String>): Set<String> =
        presentHullmodIds.flatMapTo(mutableSetOf()) { forbiddenByController(it) }
}
