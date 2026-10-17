package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.base.PhaseDecisionContext
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRule
import cn.kasuminova.astd.combat.shipsystems.base.PhaseSituation
import cn.kasuminova.astd.combat.shipsystems.base.PriorityPhaseTree

/** 绕后走位指令：NONE 不干预 / BACKOFF 强制上浮落点暂缓后撤 / DRIVE 穿透驱动 / HOLD_SPOT 就地保持。 */
internal enum class FlankOrder { NONE, BACKOFF, DRIVE, HOLD_SPOT }

/**
 * Mutex B：绕后攻击走位决策树（指令 [FlankOrder]）。
 *
 * 原版相位 AI 被替换后 PHASE_ATTACK_RUN 系旗标无人管理，正面硬点装配的舰船下潜后会
 * 原地罚站；本树接管绕后意图期间的走位旗标组（规则结论由协调器翻译为 setFlag 组）：
 * - [ForcedHoldBackoffRule]：强制上浮落点暂缓中 → BACKOFF（最高优先）——暂缓意味着本次
 *   相位必须收尾，保命后撤拉开优先于穿透就位，防止 DO_NOT_BACK_OFF 与后撤旗标并挂互相打架；
 * - [FlankDriveRule]：绕后走位驱动生效（[flankDriveActive]）且不就地保持 → DRIVE，
 *   挂 DO_NOT_BACK_OFF + PHASE_ATTACK_RUN（StrafeTargetManeuverV2 把相位舰带往目标背后
 *   5000su 点）；
 * - [FlankHoldSpotRule]：驱动生效且满足就地保持口径（[flankDriveHoldSpot]）→ HOLD_SPOT，
 *   改挂 PHASE_ATTACK_RUN_IN_GOOD_SPOT（走位模块就地保持攻击距离）。
 *
 * 绕后意图布防口径 [isFlankDive] 与窗口缩放 [flankIntentWindowSec] 归入本树：
 * A 树执行 DIVE 时由协调器回调 [shouldArmIntent] 武装意图窗口。
 */
internal class FlankAttackTree : PriorityPhaseTree<FlankOrder>(
    id = "FlankAttack",
    none = FlankOrder.NONE,
    rules = listOf(
        ForcedHoldBackoffRule,
        FlankDriveRule,
        FlankHoldSpotRule,
    ),
) {

    /**
     * 绕后意图布防口径：A 树（[PhaseToggleTree]）执行 DIVE 时由协调器回调，
     * 命中完整闸门才武装意图窗口（闸门与 [FlankDiveRule] 共用 [isFlankDive] 单一口径；
     * 开关被守卫拦住时不空布防）。
     */
    fun shouldArmIntent(s: PhaseSituation): Boolean = isFlankDive(s)

    companion object {
        /** 绕后下潜闸：可输出武器占比下限。 */
        internal const val FLANK_DIVE_WEAPONS_FRAC = 0.5f

        /** 绕后下潜的硬辐能上限。 */
        internal const val FLANK_DIVE_HARD_FLUX_MAX = 0.4f

        /** 绕后意图基准时长（s）：绕后下潜后保持相位机动穿透的时间窗（按布防距离缩放，见 [flankIntentWindowSec]）。 */
        internal const val FLANK_INTENT_SEC = 12f

        /** 绕后窗口参考距离（su）：布防距离不超过本值时窗口保持基准时长。 */
        internal const val FLANK_INTENT_REF_DIST = 1200f

        /** 绕后意图窗口上限（s）：慢速舰远处布防需要更长的穿透位移时间。 */
        internal const val FLANK_INTENT_MAX_SEC = 24f

        /**
         * 绕后下潜完整闸门（纯函数）：目标低机动、本舰未在其侧后薄弱区、武器过半可输出、
         * 硬辐能有余量、near 来袭未达威胁阈值。[FlankDiveRule] 与协调器的意图布防共用本口径。
         */
        internal fun isFlankDive(s: PhaseSituation): Boolean =
            !s.phased && s.cloakReady && s.targetLowMobility && !s.inTargetRearArc &&
                    s.engagedEnemyNear &&
                    s.weaponsReadyFrac >= FLANK_DIVE_WEAPONS_FRAC &&
                    s.hardFluxLevel < FLANK_DIVE_HARD_FLUX_MAX &&
                    s.incomingNearDamage < PhaseToggleTree.diveNearThreshold(s.maxHull)

        /**
         * 绕后意图窗口（纯函数）：按布防时距主威胁目标的距离相对 [FLANK_INTENT_REF_DIST]
         * 线性缩放基准窗口 [FLANK_INTENT_SEC]——穿透机动的时间预算就是位移预算，慢速舰
         * 远处下潜时固定窗口会在抵达目标背后前过期，导致意图作废、正面上浮白潜一趟；
         * 下限基准值（近距离布防行为不变），上限 [FLANK_INTENT_MAX_SEC]。
         */
        internal fun flankIntentWindowSec(threatDistance: Float): Float =
            (FLANK_INTENT_SEC * threatDistance / FLANK_INTENT_REF_DIST)
                .coerceIn(FLANK_INTENT_SEC, FLANK_INTENT_MAX_SEC)

        /**
         * 绕后走位驱动是否生效（纯函数）：意图途中且未进入强制上浮落点暂缓——
         * 暂缓意味着本次相位必须收尾，保命后撤拉开优先于穿透就位，
         * 防止 DO_NOT_BACK_OFF 与后撤旗标并挂互相打架。[FlankDriveRule]/[FlankHoldSpotRule]
         * 共用本口径。
         */
        internal fun flankDriveActive(s: PhaseSituation): Boolean =
            s.phased && s.flankIntentActive && s.forcedSurfaceHoldSec <= 0f

        /**
         * 绕后走位是否就地保持（纯函数）：进入侧后薄弱区、落点可退相位且脱间距满足时
         * 挂 PHASE_ATTACK_RUN_IN_GOOD_SPOT 就地保持，否则继续 PHASE_ATTACK_RUN 穿透——
         * 脱间距不足时就地保持没有任何一层在创造间距（对静止/慢速目标几何可能永不打开，
         * 最终憋到强制上浮在同一个贴脸点收尾），必须继续穿透把间距拉开。
         * 与 [FlankIntentRule] 的脱间距闸共用口径，避免走位层保持、决策层等拉开的两层打架。
         */
        internal fun flankDriveHoldSpot(s: PhaseSituation): Boolean =
            s.inTargetRearArc && !s.unphaseUnsafe && s.rearSurfaceDistanceSafe
    }
}

/**
 * 强制上浮落点暂缓期间主动后撤：相位高机动拉开与主威胁间距，自己创造安全落点，
 * 不干等兜底放行撞上贴脸（实机 brilliant 场景暂缓 6s 兜底放行 146su 贴盾上浮）；
 * 绕后穿透驱动此时让位（[FlankAttackTree.flankDriveActive] 内含暂缓闸），DO_NOT_BACK_OFF 不再续挂。
 */
internal object ForcedHoldBackoffRule : PhaseRule<FlankOrder> {
    override val id = "ForcedHoldBackoff"

    override fun evaluate(ctx: PhaseDecisionContext): FlankOrder? {
        val s = ctx.situation
        if (s.phased && s.forcedSurfaceHoldSec > 0f) return FlankOrder.BACKOFF
        return null
    }
}

/**
 * 绕后穿透驱动：意图途中挂 PHASE_ATTACK_RUN 穿透走位（同挂 DO_NOT_BACK_OFF——走位模块的
 * 穿透分支要求非后撤/非规避状态，相位累积辐能推高 fluxLevel 后会命中「辐能高于目标」
 * 规避判定把穿透驱动掐掉，意图期间由 A 树的辐能闸兜底生存，走位层不再自行后撤）。
 */
internal object FlankDriveRule : PhaseRule<FlankOrder> {
    override val id = "FlankDrive"

    override fun evaluate(ctx: PhaseDecisionContext): FlankOrder? {
        val s = ctx.situation
        if (FlankAttackTree.flankDriveActive(s) && !FlankAttackTree.flankDriveHoldSpot(s)) {
            return FlankOrder.DRIVE
        }
        return null
    }
}

/**
 * 绕后就地保持：进入侧后薄弱区、落点可退相位且脱间距满足时改挂
 * PHASE_ATTACK_RUN_IN_GOOD_SPOT 就地保持；落点重叠时原版斗篷拒绝退相位，不就地保持，
 * 继续穿透驱动直到漂出重叠。
 */
internal object FlankHoldSpotRule : PhaseRule<FlankOrder> {
    override val id = "FlankHoldSpot"

    override fun evaluate(ctx: PhaseDecisionContext): FlankOrder? {
        val s = ctx.situation
        if (FlankAttackTree.flankDriveActive(s) && FlankAttackTree.flankDriveHoldSpot(s)) {
            return FlankOrder.HOLD_SPOT
        }
        return null
    }
}
