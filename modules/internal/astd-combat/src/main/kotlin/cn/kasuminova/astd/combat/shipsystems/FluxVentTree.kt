package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.base.PhaseDecisionContext
import cn.kasuminova.astd.combat.shipsystems.base.PhaseOrder
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRule
import cn.kasuminova.astd.combat.shipsystems.base.PhaseSituation
import cn.kasuminova.astd.combat.shipsystems.base.PriorityPhaseTree

/** 耗散链路指令：NONE 不干预 / SUPPRESS 压制强制耗散 / VENT 主动强制耗散。 */
internal enum class VentOrder { NONE, SUPPRESS, VENT }

/**
 * Mutex C：辐能耗散（vent）决策树（指令 [VentOrder]）。
 *
 * 决策优先级：紧急避险 > 相位耗软辐 > 强制耗散。规则按优先级：
 * - [DiveOrderedSuppressRule]：A 树已决 DIVE → SUPPRESS（树间互斥入规则）——下潜指令已发出时
 *   vent 纯属浪费（vent 启动即不可取消，且相位中本就不排气）；
 * - [SoftFluxDumpVentHoldRule]：耗软辐宽限口径（[isSoftFluxDumpVentHold]）→ SUPPRESS——
 *   斗篷冷却尾声提前压 vent，就绪即由 A 树下潜接手；
 * - [TacticalVentRule]：战术强制耗散口径（[isTacticalVent]）且 A 树本拍无开关指令 → VENT。
 *
 * 「相位中不排气」「冷却期残损后撤」属无条件旗标维护，不是决策，留在协调器。
 *
 * 互斥契约：单拍只出一个结论，SUPPRESS 与 VENT 互斥（规则表优先级截断）。当前辐能口径
 * 下二者不可能同拍成立（耗软辐压制要求软辐占比 ≥ 0.35，战术耗散要求总辐能 ≤ 0.15 ×
 * ventRateMult 且硬辐占比 ≥ 85%，与 softFluxLevel = fluxLevel - hardFluxLevel 联立无解）；
 * 日后调整这两组辐能常量时须重新核对本契约——重构前两条链路是同拍独立 if（可同时挂
 * DO_NOT_VENT 并发 VENT 指令），若口径放开到可同拍成立，行为会从「压制+排气」静默变为
 * 「只压制」。
 */
internal class FluxVentTree : PriorityPhaseTree<VentOrder>(
    id = "FluxVent",
    none = VentOrder.NONE,
    rules = listOf(
        DiveOrderedSuppressRule,
        SoftFluxDumpVentHoldRule,
        TacticalVentRule,
    ),
) {

    companion object {
        /**
         * 耗软辐 vent 压制的斗篷冷却宽限（s）：冷却尾声提前压 DO_NOT_VENT——
         * 否则 VentModule 必在冷却窗口内抢先 vent（vent 启动即不可取消），相位耗散永远抢不到窗口；
         * 宽限有界：斗篷就绪即下潜，环境转差/辐能逼近上限时闸门失效 vent 自然放行兜底。
         */
        internal const val SOFT_FLUX_VENT_HOLD_COOLDOWN_SEC = 4f

        /** 战术强制耗散的总辐能上限（基础值；实际闸值 × ventRateMult，随强制耗散属性提升）。 */
        internal const val TACTICAL_VENT_FLUX_CAP = 0.15f

        /** 战术强制耗散的硬辐能占比下限：辐能几乎全为硬辐才值得 vent（软辐走相位耗散链路）。 */
        internal const val TACTICAL_VENT_HARD_FRAC = 0.85f

        /** 战术强制耗散的预计耗散时长上限（s）：耗散窗口超过本值不值得主动 vent。 */
        internal const val TACTICAL_VENT_MAX_SEC = 1.5f

        /** 战术强制耗散的环境安全口径：near 来袭低于威胁阈值 × 本值。 */
        internal const val TACTICAL_VENT_SAFE_NEAR_FRAC = 0.5f

        /**
         * 耗软辐 vent 压制口径（纯函数）：完整闸门 [PhaseToggleTree.isSoftFluxDumpDive] 成立，
         * 或仅差斗篷就绪且冷却剩余 ≤ [SOFT_FLUX_VENT_HOLD_COOLDOWN_SEC]（冷却尾声提前压
         * vent——否则 VentModule 必在冷却窗口内抢先启动，vent 不可取消，相位耗散永远抢不到
         * 窗口）。宽限有界：斗篷就绪即下潜（A 树与完整闸门单一口径），环境转差/辐能逼近
         * 上限时闸门失效 vent 自然放行兜底。
         * 与 A 树下潜链的封锁闸同口径：攻击系统激活中（systemActive）/友军接盘风险
         * （friendlyCatchDamage 达 [PhaseToggleTree.FRIENDLY_CATCH_DAMAGE_MIN]）时下潜链
         * （除紧急下潜外）不会出手，NONE 路径压制 vent 会构成真实的「压了 vent 却不下潜」
         * 窗口，必须先行放行；A 树决出 DIVE 的情形（含紧急下潜越过封锁闸）由
         * [DiveOrderedSuppressRule] 直接压制，不经本口径。
         */
        internal fun isSoftFluxDumpVentHold(s: PhaseSituation): Boolean {
            if (s.systemActive ||
                s.friendlyCatchDamage >= PhaseToggleTree.FRIENDLY_CATCH_DAMAGE_MIN
            ) {
                return false
            }
            if (PhaseToggleTree.isSoftFluxDumpDive(s)) return true
            if (s.phased || s.cloakReady ||
                s.cloakCooldownRemaining > SOFT_FLUX_VENT_HOLD_COOLDOWN_SEC
            ) {
                return false
            }
            return PhaseToggleTree.isSoftFluxDumpDive(s.copy(cloakReady = true))
        }

        /**
         * 战术强制耗散口径（纯函数）：交战但环境安全（soon/near 来袭均低于下潜阈值）、
         * 总辐能低（≤ [TACTICAL_VENT_FLUX_CAP] × ventRateMult——强制耗散属性提升耗散速度，
         * 阈值随之上浮）、辐能几乎全为硬辐（≥ [TACTICAL_VENT_HARD_FRAC]）且预计耗散窗口短
         * （≤ [TACTICAL_VENT_MAX_SEC]）时主动 vent 清硬辐——硬辐能压低相位内航速与下潜余量，
         * 低辐窗口主动清掉比攒高了再被动后撤耗散更保全输出位。
         * 软辐占比较高时不走本口径：相位耗散（[PhaseToggleTree.isSoftFluxDumpDive]）才是软辐的
         * 去处，两链路辐能口径互斥（本口径要求总辐能低且硬辐占比高，耗软辐要求软辐占比 ≥ 0.35）。
         * 辐能归零时不 vent（fluxLevel > 0.01 闸）：空 vent 纯属自缚僵直。
         * 攻击系统激活中（systemActive）不 vent：vent 停火会作废磁暴充能/复制窗口
         * （与 A 树下潜链的 systemActive 封锁闸同口径）。
         */
        internal fun isTacticalVent(s: PhaseSituation): Boolean =
            !s.phased && s.engagedEnemyNear && !s.retreating && !s.venting && !s.systemActive &&
                    s.fluxLevel > 0.01f &&
                    s.fluxLevel <= TACTICAL_VENT_FLUX_CAP * s.ventRateMult &&
                    s.hardFluxLevel >= s.fluxLevel * TACTICAL_VENT_HARD_FRAC &&
                    s.ventTimeSec <= TACTICAL_VENT_MAX_SEC &&
                    s.incomingSoonDamage + s.incomingFriendlySoonDamage <
                    PhaseToggleTree.diveSoonThreshold(s.maxHull) &&
                    s.incomingNearDamage < PhaseToggleTree.diveNearThreshold(s.maxHull) *
                    TACTICAL_VENT_SAFE_NEAR_FRAC
    }
}

/**
 * 树间互斥：A 树本拍已决 DIVE → 压制 vent。下潜指令已发出时 vent 纯属浪费
 * （vent 启动即不可取消，且相位中本就不排气）。
 */
internal object DiveOrderedSuppressRule : PhaseRule<VentOrder> {
    override val id = "DiveOrderedSuppress"

    override fun evaluate(ctx: PhaseDecisionContext): VentOrder? {
        if (ctx.cloakOrder == PhaseOrder.DIVE) return VentOrder.SUPPRESS
        return null
    }
}

/** 耗软辐 vent 压制：[FluxVentTree.isSoftFluxDumpVentHold] 宽限口径成立即压制。 */
internal object SoftFluxDumpVentHoldRule : PhaseRule<VentOrder> {
    override val id = "SoftFluxDumpVentHold"

    override fun evaluate(ctx: PhaseDecisionContext): VentOrder? {
        if (FluxVentTree.isSoftFluxDumpVentHold(ctx.situation)) return VentOrder.SUPPRESS
        return null
    }
}

/**
 * 战术强制耗散：交战但环境安全、低辐能且几乎全为硬辐、预计耗散窗口短时主动 vent
 * 清硬辐（维持相位航速与下潜余量）；只在 A 树本拍无下潜/上浮指令时出手。
 * 与耗软辐链路互斥（辐能口径相反，见 [FluxVentTree.isTacticalVent]），不会顶掉相位耗散窗口。
 */
internal object TacticalVentRule : PhaseRule<VentOrder> {
    override val id = "TacticalVent"

    override fun evaluate(ctx: PhaseDecisionContext): VentOrder? {
        if (ctx.cloakOrder != PhaseOrder.NONE) return null
        if (FluxVentTree.isTacticalVent(ctx.situation)) return VentOrder.VENT
        return null
    }
}
