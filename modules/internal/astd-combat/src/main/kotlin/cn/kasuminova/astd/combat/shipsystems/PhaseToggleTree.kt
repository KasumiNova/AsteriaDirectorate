package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.base.PhaseDecisionContext
import cn.kasuminova.astd.combat.shipsystems.base.PhaseOrder
import cn.kasuminova.astd.combat.shipsystems.base.PhaseRule
import cn.kasuminova.astd.combat.shipsystems.base.PhaseSituation
import cn.kasuminova.astd.combat.shipsystems.base.PriorityPhaseTree

/**
 * Mutex A：相位斗篷开关决策树（指令 [PhaseOrder]：NONE 保持 / DIVE 下潜 / SURFACE 上浮）。
 *
 * 由原单类 if 链（2026-10 重构前实现）按原优先级逐条拆分为命名规则对象，
 * 逻辑逐字迁移不改口径。规则结论三分：DIVE/SURFACE（触发开关）/ NONE（闸门，终止评估）；
 * null 弃权放行下一规则。相位分支规则先集体弃权于未相位态势（反之亦然），两分支共用
 * 一张规则表、由规则自身的相位闸还原原 if 链的分支语义。
 *
 * 树级收尾闸（postGate）：上浮落点重叠（unphaseUnsafe）时把 SURFACE 压成 NONE——
 * 与他舰深度重叠时原版斗篷拒绝退相位（canBeDeactivated=false，toggle 空发），
 * 按住一切上浮指令直到漂出重叠；本系统 canNotCauseOverload，续潜无过载风险。
 *
 * 下潜规则（未相位分支，按优先级）：
 * - [CloakReadyGateRule]：斗篷未就绪（冷却中/被禁用）一切下潜免谈；
 * - [EmergencyDiveRule]：紧急下潜——紧急窗口内预计命中伤害达 [diveSoonThreshold]，绕过错峰闸
 *   直接下潜；来袭口径为敌方与友军火力合并（被友军高伤火力命中同样是生存威胁，相位可以规避
 *   友伤），友军火力只走这条防御链路；前方友军承火时阈值 ×[SCREENED_DIVE_THRESHOLD_MULT]；
 *   受友军接盘闸约束（自身濒危除外）；
 * - [SystemActiveGateRule]：攻击系统激活中（isOn）不主动下潜（防自断磁暴充能/复制窗口）；
 * - [FriendlyCatchGateRule]：友军接盘闸——若此刻入相位，穿透本舰的直射弹药将误伤友军的
 *   估计伤害达 [FRIENDLY_CATCH_DAMAGE_MIN] 时压制一切非紧急下潜；
 * - [SoftFluxDumpDiveRule]：耗软辐下潜——软辐能占比 ≥ [SOFT_FLUX_DIVE_MIN]、总辐能 <
 *   [SOFT_FLUX_DIVE_MAX_FLUX_LEVEL] 且环境安全时下潜耗散软辐（耗散优先级：紧急避险 >
 *   相位耗软辐 > 强制耗散——闸门收敛在 [isSoftFluxDumpDive]）。本链路不受错峰闸约束：
 *   相位耗散是本系统的核心循环，被 [MIN_UNPHASE_TIME_SEC] 拦住时 VentModule 必在窗口内
 *   抢先 vent（vent 启动即不可取消，绕后输出窗口随之报废）；环境安全口径为 near 来袭低于
 *   威胁阈值 ×[SOFT_FLUX_DIVE_SAFE_NEAR_FRAC]，侧后占位放宽到 ×[SOFT_FLUX_DIVE_SAFE_NEAR_FRAC_REAR]；
 * - [StaggerGateRule]：错峰闸——除紧急下潜与耗软辐下潜外，距上次上浮不足
 *   [MIN_UNPHASE_TIME_SEC] 不下潜；
 * - [TravelDiveRule]：相位赶路——有赶路目标点（MOVEMENT_DEST 旗标）且距离 ≥
 *   [TRAVEL_PHASE_MIN_DIST]、脱战、非撤退、辐能低时下潜赶路（相位 3x 时间倍率随舰时钟加速
 *   位移），闸门收敛在 [isTravelDive]；
 * - [RetreatDiveRule]：撤退下潜——撤退中且硬辐能 < [RETREAT_DIVE_HARD_FLUX]（相位赶路）；
 * - [FluxPressureDiveRule]：辐能压力下潜——辐能水平 ≥ [DIVE_FLUX_LEVEL] 且硬辐能 <
 *   [DIVE_HARD_FLUX_MAX] 且有交战对象（硬辐能过高时下潜只会加速憋死，故设上限）；
 * - [FlankDiveRule]：绕后下潜——主威胁目标为低机动舰且本舰尚未进入其侧后薄弱区、武器过半
 *   可输出、硬辐能 < [FlankAttackTree.FLANK_DIVE_HARD_FLUX_MAX] 时借相位穿透占位
 *   （闸门收敛在 [FlankAttackTree.isFlankDive]，与本树的下潜规则和协调器的意图布防共用）；
 * - [ThreatDiveRule]：威胁下潜——威胁窗口内预计命中伤害达 [diveNearThreshold]
 *   （前方友军承火时阈值同样放宽）；
 * - [RechargeDiveRule]：装填下潜——可输出武器占比 ≤ [RECHARGE_DIVE_WEAPONS_FRAC] 且硬辐能
 *   < [RECHARGE_DIVE_HARD_FLUX_MAX]；
 * - [RepairDiveRule]：修复下潜——武器或引擎下线占比 ≥ [REPAIR_DIVE_DISABLED_FRAC] 且有交战
 *   对象、硬辐能 < [REPAIR_DIVE_HARD_FLUX_MAX] 时下潜——相位 3x 时间倍率随舰时钟加速组件
 *   修复（原版口径：快进舰每帧额外 advance，ComponentHealthTracker 随舰时钟走），
 *   相位无敌同时兜底修复窗口的生存。
 *
 * 上浮规则（相位分支，按优先级）：
 * - [ForcedSurfaceRule]：强制上浮——辐能闸（硬辐能 ≥ [SURFACE_HARD_FLUX]，继续潜只会涨辐能
 *   减速被围死）/时长闸（连续相位 ≥ [MAX_PHASE_TIME_SEC]，错峰节奏强制回到战场；绕后意图
 *   生效期间上限放宽到武装窗口 + [FLANK_PHASE_CAP_MARGIN_SEC]，辐能闸不受放宽）；
 *   落点同样要求相对安全（[isSurfaceSafe]），不安全时有界暂缓
 *   （[FORCED_SURFACE_HOLD_MAX_SEC]，协调器按决策节拍累计，口径 [nextForcedSurfaceHoldSec]，
 *   致命豁免期预算暂停），超时兜底放行防永久相位幽灵船——贴脸强制上浮正是实机
 *   「贴盾上浮/上浮即吃鱼雷」入口；致命豁免：[isHoldForLethal] 成立时等这一下过去再上浮；
 * - [MinPhaseTimeRule]：反闪烁——相位不足 [MIN_PHASE_TIME_SEC] 不主动上浮（强制上浮不受限）；
 * - [SurfaceSafetyGateRule]：上浮安全闸——主动上浮统一要求落点相对安全（[isSurfaceSafe]，
 *   强制上浮不受约束）：与最近敌舰贴脸、上浮弹幕窗口来袭达 [surfaceDangerThreshold]、
 *   或位于主威胁正脸火力轴时按住不上浮；
 * - [SoonHitGateRule]：即将受击闸——全部主动上浮路径统一要求紧急窗口来袭低于
 *   [diveSoonThreshold]（先于一刀切上浮规则判定：交战圈外发射的高速弹不在无威胁上浮的
 *   near 窗口口径内，但同样会在 soon 窗口落地；友军火力并入同一口径，持续照射本舰时
 *   按住不上浮）；
 * - [FlankIntentRule]：绕后意图拦截（先于无威胁/死角上浮判定）——意图途中（未入侧后）一切
 *   主动上浮按住，保持相位机动穿透（就位点在目标背后远点，途中短暂掉出交战圈不等于脱战，
 *   掉圈提前上浮即布防早夭）；意图达成（已入侧后）时覆盖闸放宽到 [SURFACE_COVERAGE_MAX]
 *   即上浮输出，脱间距不足（贴着敌舰护盾/碰撞箱）按住保持相位穿出拉开；
 * - [NoThreatSurfaceRule]：无威胁上浮——威胁圈无交战对象且来袭轻微（撤退赶路且硬辐能有
 *   余量时保持相位；相位赶路途中辐能 < [TRAVEL_HOLD_FLUX_MAX] 时同样拦截保持相位，
 *   口径 [isTravelPhaseHold]，超标放行上浮耗散，维持低辐赶路循环）；
 * - [RearSurfaceRule]：死角上浮——已机动到主威胁目标侧后射界薄弱区、能瞄准本舰的武器
 *   ≤ [REAR_SURFACE_MAX_COVERAGE] 且脱间距满足时立即上浮输出；
 * - [CoverageGateRule]：覆盖闸——主威胁目标能瞄准本舰的武器数 > [SURFACE_COVERAGE_MAX] 时，
 *   战术/输出窗口上浮继续相位机动等死角（强制上浮不受此闸约束）；
 * - [TacticalSurfaceRule]：战术上浮——攻击系统就绪且有交战对象，上浮施放磁暴/复制器；
 * - [WeaponsReadySurfaceRule]：输出窗口上浮——可输出武器占比 ≥ [WEAPONS_READY_SURFACE_FRAC]
 *   且来袭可控。
 */
internal class PhaseToggleTree : PriorityPhaseTree<PhaseOrder>(
    id = "PhaseToggle",
    none = PhaseOrder.NONE,
    rules = listOf(
        // 相位分支
        ForcedSurfaceRule,
        MinPhaseTimeRule,
        SurfaceSafetyGateRule,
        SoonHitGateRule,
        FlankIntentRule,
        NoThreatSurfaceRule,
        RearSurfaceRule,
        CoverageGateRule,
        TacticalSurfaceRule,
        WeaponsReadySurfaceRule,
        // 未相位分支
        CloakReadyGateRule,
        EmergencyDiveRule,
        SystemActiveGateRule,
        FriendlyCatchGateRule,
        SoftFluxDumpDiveRule,
        StaggerGateRule,
        TravelDiveRule,
        RetreatDiveRule,
        FluxPressureDiveRule,
        FlankDiveRule,
        ThreatDiveRule,
        RechargeDiveRule,
        RepairDiveRule,
    ),
) {

    /**
     * 上浮落点安全闸（树级收尾闸，原 decide 的收尾口径）：与他舰深度重叠时原版斗篷拒绝退相位
     * （canBeDeactivated=false，toggle 空发），按住一切上浮指令直到漂出重叠——否则实机会出现
     * 「指令三连发、舰船卡相位」的拒退循环（本系统 canNotCauseOverload，续潜无过载风险）。
     */
    override fun postGate(order: PhaseOrder, ctx: PhaseDecisionContext): PhaseOrder {
        if (order == PhaseOrder.SURFACE && ctx.situation.phased && ctx.situation.unphaseUnsafe) {
            return PhaseOrder.NONE
        }
        return order
    }

    companion object {
        /** 评估间隔（s）：协调器按本节拍采样并评估三棵树。 */
        internal const val SCAN_INTERVAL_SEC = 0.2f

        /** 紧急下潜伤害阈值：max(本值, 舰体上限 × 比例)。 */
        internal fun diveSoonThreshold(maxHull: Float): Float =
            maxOf(300f, maxHull * 0.05f)

        /** 威胁下潜伤害阈值：max(本值, 舰体上限 × 比例)。 */
        internal fun diveNearThreshold(maxHull: Float): Float =
            maxOf(600f, maxHull * 0.10f)

        /** 致命来袭占舰体上限比例（强制上浮豁免闸）。 */
        internal const val LETHAL_SOON_HULL_FRACTION = 0.20f

        /** 致命豁免的硬辐能上限：超过后不再为躲伤害续潜（辐能本身已更危险）。 */
        internal const val HOLD_MAX_HARD_FLUX = 0.75f

        /** 辐能强制上浮闸：硬辐能到达本值必须上浮。 */
        internal const val SURFACE_HARD_FLUX = 0.55f

        /**
         * 强制上浮落点安全暂缓上限（s）：强制上浮（辐能/时长闸）同样要求落点相对安全
         * （[isSurfaceSafe]），不安全时按住有界暂缓——贴脸强制上浮正是实机「贴盾上浮/
         * 上浮即吃鱼雷」入口（zw103 vs 辉煌实测五次上浮全为强制、多次 sepSafe=false、
         * 最低 188su）；超过本值兜底放行，防永久相位幽灵船。
         */
        internal const val FORCED_SURFACE_HOLD_MAX_SEC = 6f

        /** 相位时长上限（s）：连续相位超过本值强制上浮。 */
        internal const val MAX_PHASE_TIME_SEC = 8f

        /** 反闪烁最短相位时长（s），主动上浮的下限。 */
        internal const val MIN_PHASE_TIME_SEC = 1.0f

        /** 上浮弹幕伤害阈值：max(本值, 舰体上限 × 比例)。 */
        internal fun surfaceDangerThreshold(maxHull: Float): Float =
            maxOf(600f, maxHull * 0.10f)

        /** 下潜错峰（s）：上浮后间隔不足本值不做常规下潜。 */
        internal const val MIN_UNPHASE_TIME_SEC = 2.5f

        /** 辐能压力下潜闸：辐能水平达到本值才允许避险下潜。 */
        internal const val DIVE_FLUX_LEVEL = 0.90f

        /** 辐能压力下潜的硬辐能上限：硬辐能已高时下潜无意义。 */
        internal const val DIVE_HARD_FLUX_MAX = 0.50f

        /** 装填下潜闸：可输出武器占比不高于本值才允许装填下潜。 */
        internal const val RECHARGE_DIVE_WEAPONS_FRAC = 0.35f

        /** 装填下潜的硬辐能上限。 */
        internal const val RECHARGE_DIVE_HARD_FLUX_MAX = 0.35f

        /** 输出窗口上浮闸：可输出武器占比达到本值且来袭可控时上浮开打。 */
        internal const val WEAPONS_READY_SURFACE_FRAC = 0.6f

        /** 撤退下潜的硬辐能上限。 */
        internal const val RETREAT_DIVE_HARD_FLUX = 0.25f

        /** 撤退途中保持相位的硬辐能上限。 */
        internal const val RETREAT_STAY_HARD_FLUX = 0.35f

        /** 相位耗软辐能闸：软辐能占比（fluxLevel - hardFluxLevel）达此值值得下潜耗散。 */
        internal const val SOFT_FLUX_DIVE_MIN = 0.35f

        /** 相位耗软辐能的总辐能上限：超过此值离过载太近，下潜耗散反而可能上浮即过载。 */
        internal const val SOFT_FLUX_DIVE_MAX_FLUX_LEVEL = 0.75f

        /** 耗软辐下潜的环境安全口径：near 窗口来袭低于威胁阈值 × 本值。 */
        internal const val SOFT_FLUX_DIVE_SAFE_NEAR_FRAC = 0.25f

        /**
         * 耗软辐下潜的侧后占位安全口径：已在主威胁射界薄弱区且覆盖不超 [SURFACE_COVERAGE_MAX] 时
         * 放宽到威胁阈值 × 本值——主威胁覆盖不了侧后，near 来袭多来自次要方向，
         * 原地相位耗散（上浮仍在输出位）比后撤 vent 更保全绕后输出窗口。
         */
        internal const val SOFT_FLUX_DIVE_SAFE_NEAR_FRAC_REAR = 0.5f

        /** 修复下潜闸：武器或引擎下线占比达本值时优先相位窗口（3x 时间倍率加速组件修复）。 */
        internal const val REPAIR_DIVE_DISABLED_FRAC = 0.5f

        /** 修复下潜的硬辐能上限：硬辐能过高时下潜只会加速憋死。 */
        internal const val REPAIR_DIVE_HARD_FLUX_MAX = 0.5f

        /** 死角上浮允许的最大武器覆盖数（目标侧后射界内能瞄准本舰的武器 ≤ 本值立即上浮）。 */
        internal const val REAR_SURFACE_MAX_COVERAGE = 1

        /** 主动上浮覆盖闸：主威胁目标能瞄准本舰的武器数超过本值时，继续相位机动等死角。 */
        internal const val SURFACE_COVERAGE_MAX = 3

        /** 友军接盘误伤闸（伤害量）：达此值压制一切非濒危下潜。 */
        internal const val FRIENDLY_CATCH_DAMAGE_MIN = 300f

        /** 前方友军承火时下潜阈值的放宽倍数：有人扛线时减少规避无效伤害的反复潜水。 */
        internal const val SCREENED_DIVE_THRESHOLD_MULT = 2f

        /** 相位赶路的最小目标距离（su）：低于本值开关僵直大于赶路收益，不下潜。 */
        internal const val TRAVEL_PHASE_MIN_DIST = 2000f

        /** 相位赶路下潜的总辐能上限：辐能过高时下潜只会加速累积硬辐。 */
        internal const val TRAVEL_DIVE_FLUX_MAX = 0.30f

        /** 相位赶路下潜的硬辐能上限。 */
        internal const val TRAVEL_DIVE_HARD_FLUX_MAX = 0.25f

        /** 相位赶路途中保持相位的总辐能上限：超过本值放行上浮耗散，维持低辐赶路循环。 */
        internal const val TRAVEL_HOLD_FLUX_MAX = 0.35f

        /** 绕后意图期间相位时长上限相对意图窗口的富余（s）。 */
        internal const val FLANK_PHASE_CAP_MARGIN_SEC = 2f

        /**
         * 上浮安全评估（纯函数）：贴脸、弹幕窗口来袭达闸、正脸火力轴任一不安全即按住。
         * 只约束主动上浮——强制上浮（辐能/时长闸）在 [ForcedSurfaceRule] 中先行判定，不经本闸。
         */
        internal fun isSurfaceSafe(s: PhaseSituation): Boolean =
            !s.surfaceTooClose && !s.threatFrontAxisClose &&
                    s.incomingSurfaceDamage < surfaceDangerThreshold(s.maxHull)

        /** 是否为强制上浮（辐能闸/时长闸触发；用于上浮僵直保护挂后撤）。 */
        internal fun isForcedSurface(s: PhaseSituation): Boolean =
            s.phased && (s.hardFluxLevel >= SURFACE_HARD_FLUX || s.phaseActiveTime >= MAX_PHASE_TIME_SEC)

        /**
         * 强制上浮触发口径（纯函数）：辐能闸或时长闸（绕后意图期间时长上限放宽到
         * 武装窗口 + 富余，辐能闸不受放宽）。[ForcedSurfaceRule] 与协调器的
         * 落点安全暂缓时长累计共用本口径。
         */
        internal fun isForcedSurfaceTrigger(s: PhaseSituation): Boolean {
            if (!s.phased) return false
            val maxPhaseTime =
                if (s.flankIntentActive) s.flankIntentWindowSec + FLANK_PHASE_CAP_MARGIN_SEC
                else MAX_PHASE_TIME_SEC
            return s.hardFluxLevel >= SURFACE_HARD_FLUX || s.phaseActiveTime >= maxPhaseTime
        }

        /**
         * 致命豁免口径（纯函数）：紧急窗口内有致命来袭（≥ 舰体
         * [LETHAL_SOON_HULL_FRACTION]，含友军火力烧身）且硬辐能有余量
         * （< [HOLD_MAX_HARD_FLUX]）时，强制上浮推迟到这一下过去之后。
         * [ForcedSurfaceRule] 与协调器的暂缓预算节拍共用本口径。
         */
        internal fun isHoldForLethal(s: PhaseSituation): Boolean =
            s.incomingSoonDamage + s.incomingFriendlySoonDamage >=
                    s.maxHull * LETHAL_SOON_HULL_FRACTION &&
                    s.hardFluxLevel < HOLD_MAX_HARD_FLUX

        /**
         * 强制上浮落点暂缓预算节拍（纯函数）：触发（[isForcedSurfaceTrigger]）且落点不安全时
         * 随决策节拍累计；致命豁免（[isHoldForLethal]）期暂停——豁免是等这一下过去而非等安全
         * 落点，不该烧兜底预算（否则豁免结束预算已被同步耗尽，于不安全落点立即兜底放行）；
         * 未触发/落点安全即清零。协调器的预算时钟专用口径。
         */
        internal fun nextForcedSurfaceHoldSec(s: PhaseSituation, currentSec: Float): Float =
            when {
                !isForcedSurfaceTrigger(s) || isSurfaceSafe(s) -> 0f
                isHoldForLethal(s) -> currentSec
                else -> currentSec + SCAN_INTERVAL_SEC
            }

        /**
         * 耗软辐下潜完整闸门（纯函数）：斗篷就绪、软辐可观、总辐能离过载有距离、环境安全、
         * 有交战对象。环境安全口径：near 来袭低于威胁阈值 × [SOFT_FLUX_DIVE_SAFE_NEAR_FRAC]；
         * 侧后占位（已入主威胁射界薄弱区且覆盖不超 [SURFACE_COVERAGE_MAX]）放宽到
         * ×[SOFT_FLUX_DIVE_SAFE_NEAR_FRAC_REAR]——主威胁覆盖不了侧后，原地相位耗散
         * （上浮仍在输出位）比后撤 vent 更保全绕后输出窗口。
         * [SoftFluxDumpDiveRule] 专用；C 树的 vent 压制走
         * [FluxVentTree.isSoftFluxDumpVentHold] 宽限口径（判据在 C 树规则内，协调器只执行 setFlag）。
         */
        internal fun isSoftFluxDumpDive(s: PhaseSituation): Boolean {
            if (s.phased || !s.cloakReady || !s.engagedEnemyNear) return false
            if (s.softFluxLevel < SOFT_FLUX_DIVE_MIN || s.fluxLevel >= SOFT_FLUX_DIVE_MAX_FLUX_LEVEL) {
                return false
            }
            val safeFrac =
                if (s.inTargetRearArc && s.weaponCoverage <= SURFACE_COVERAGE_MAX) {
                    SOFT_FLUX_DIVE_SAFE_NEAR_FRAC_REAR
                } else {
                    SOFT_FLUX_DIVE_SAFE_NEAR_FRAC
                }
            return s.incomingNearDamage < diveNearThreshold(s.maxHull) * safeFrac
        }

        /**
         * 相位赶路下潜口径（纯函数）：有赶路目标点（MOVEMENT_DEST 旗标，原版
         * OrderResponseModule 设置、燃驱 AI 同款用法）且距离 ≥ [TRAVEL_PHASE_MIN_DIST]、
         * 脱战、非撤退、辐能低时下潜——相位 3x 时间倍率随舰时钟加速赶路。
         * 交战（有交战对象时辐能与生存管理优先于赶路）与空闲（无目标点）状态下无效。
         * 注意哨兵口径：无目标点采样为 [Float.MAX_VALUE]，必须显式排除——
         * 「无目标点」若直接过 ≥ 闸会被误判成「无限远目标点」。
         */
        internal fun isTravelDive(s: PhaseSituation): Boolean =
            !s.phased && s.cloakReady && !s.engagedEnemyNear && !s.retreating &&
                    s.travelDestDistance >= TRAVEL_PHASE_MIN_DIST &&
                    s.travelDestDistance < Float.MAX_VALUE &&
                    s.fluxLevel <= TRAVEL_DIVE_FLUX_MAX &&
                    s.hardFluxLevel <= TRAVEL_DIVE_HARD_FLUX_MAX

        /**
         * 相位赶路途中保持口径（纯函数）：赶路相位中、辐能未积累到 [TRAVEL_HOLD_FLUX_MAX] 时
         * 拦截「无威胁上浮」保持相位赶路；辐能积累超标后放行上浮耗散，维持低辐能赶路循环
         * （相位维持涨辐能减速，低辐相位航速才有赶路收益）。
         * 哨兵口径同 [isTravelDive]：无目标点的 [Float.MAX_VALUE] 必须显式排除。
         */
        internal fun isTravelPhaseHold(s: PhaseSituation): Boolean =
            s.phased && !s.engagedEnemyNear && !s.retreating &&
                    s.travelDestDistance >= TRAVEL_PHASE_MIN_DIST &&
                    s.travelDestDistance < Float.MAX_VALUE &&
                    s.fluxLevel < TRAVEL_HOLD_FLUX_MAX
    }
}

/**
 * 强制上浮（辐能/时长闸，时长闸在绕后意图期间放宽到武装窗口 + 富余）：
 * 落点同样要求相对安全——贴脸强制上浮正是实机「贴盾上浮/上浮即吃鱼雷」入口，
 * 不安全时有界暂缓（[PhaseToggleTree.FORCED_SURFACE_HOLD_MAX_SEC]），超时兜底放行防永久相位。
 */
internal object ForcedSurfaceRule : PhaseRule<PhaseOrder> {
    override val id = "ForcedSurface"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        // 与其余规则对称的显式相位守卫（正确性不依赖 isForcedSurfaceTrigger 的内嵌相位闸）
        if (!s.phased) return null
        // 致命豁免：即将吃到致命伤害且辐能有余量时，强制上浮推迟到这一下过去之后
        val holdForLethal = PhaseToggleTree.isHoldForLethal(s)
        if (PhaseToggleTree.isForcedSurfaceTrigger(s) && !holdForLethal) {
            if (PhaseToggleTree.isSurfaceSafe(s) ||
                s.forcedSurfaceHoldSec >= PhaseToggleTree.FORCED_SURFACE_HOLD_MAX_SEC
            ) {
                return PhaseOrder.SURFACE
            }
            return PhaseOrder.NONE
        }
        return null
    }
}

/** 反闪烁：相位不足 [PhaseToggleTree.MIN_PHASE_TIME_SEC] 不主动上浮（强制上浮不受限）。 */
internal object MinPhaseTimeRule : PhaseRule<PhaseOrder> {
    override val id = "MinPhaseTime"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        if (s.phaseActiveTime < PhaseToggleTree.MIN_PHASE_TIME_SEC) return PhaseOrder.NONE
        return null
    }
}

/**
 * 上浮安全闸：贴脸/弹幕窗口/正脸火力轴任一不安全时一切主动上浮按住，
 * 保持相位机动等时机（强制上浮已先行判定，不受此闸约束）。
 */
internal object SurfaceSafetyGateRule : PhaseRule<PhaseOrder> {
    override val id = "SurfaceSafetyGate"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        if (!PhaseToggleTree.isSurfaceSafe(s)) return PhaseOrder.NONE
        return null
    }
}

/**
 * 即将受击不主动上浮（先于一刀切主动上浮规则判定：交战圈外发射的高速弹
 * 不在无威胁上浮的 near 窗口口径内，但同样会在 soon 窗口落地；
 * 友军火力持续照射本舰时同样按住不上浮——上浮即被烧）。
 */
internal object SoonHitGateRule : PhaseRule<PhaseOrder> {
    override val id = "SoonHitGate"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        // 防御性来袭口径：敌方与友军火力合并——被友军高伤火力命中同样是生存威胁，
        // 相位可以规避友伤；友军火力只走这条防御链路，不参与绕后/耗软辐等战术下潜
        val defensiveSoon = s.incomingSoonDamage + s.incomingFriendlySoonDamage
        if (defensiveSoon >= PhaseToggleTree.diveSoonThreshold(s.maxHull)) return PhaseOrder.NONE
        return null
    }
}

/**
 * 绕后意图拦截（先于无威胁/死角上浮）：意图途中一切主动上浮按住——
 * 穿透机动的就位点在目标背后远点，途中短暂掉出交战圈不等于脱战，
 * 掉圈提前上浮即布防早夭（实测 zw101 多次 intent=true engaged=false
 * 途中上浮，绕后扫描幅度被压掉）。
 */
internal object FlankIntentRule : PhaseRule<PhaseOrder> {
    override val id = "FlankIntent"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        if (s.flankIntentActive) {
            // 绕后意图达成：已进入侧后薄弱区、覆盖闸放宽到常规上限、脱间距满足即上浮输出；
            // 脱间距不足（贴着敌舰护盾/碰撞箱）按住，保持相位穿出拉开——
            // 贴脸上浮必吃撞击与近防（实机 zw101 绕后 132su 贴脸上浮）
            if (s.inTargetRearArc && s.weaponCoverage <= PhaseToggleTree.SURFACE_COVERAGE_MAX &&
                s.rearSurfaceDistanceSafe
            ) {
                return PhaseOrder.SURFACE
            }
            return PhaseOrder.NONE
        }
        return null
    }
}

/** 无威胁上浮：威胁圈无交战对象且来袭轻微（撤退赶路/相位赶路且辐能有余量时保持相位）。 */
internal object NoThreatSurfaceRule : PhaseRule<PhaseOrder> {
    override val id = "NoThreatSurface"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        if (!s.engagedEnemyNear &&
            s.incomingNearDamage < PhaseToggleTree.diveNearThreshold(s.maxHull) * 0.5f
        ) {
            // 撤退赶路且硬辐能有余量时保持相位；相位赶路途中辐能未超标同样保持
            // （[PhaseToggleTree.isTravelPhaseHold]，辐能积累超标后放行上浮耗散，维持低辐赶路循环）
            if (!(s.retreating && s.hardFluxLevel < PhaseToggleTree.RETREAT_STAY_HARD_FLUX) &&
                !PhaseToggleTree.isTravelPhaseHold(s)
            ) {
                return PhaseOrder.SURFACE
            }
        }
        return null
    }
}

/** 死角上浮：已机动到目标侧后射界薄弱区、覆盖武器稀少且脱间距满足，立即上浮输出。 */
internal object RearSurfaceRule : PhaseRule<PhaseOrder> {
    override val id = "RearSurface"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        if (s.engagedEnemyNear && s.inTargetRearArc &&
            s.weaponCoverage <= PhaseToggleTree.REAR_SURFACE_MAX_COVERAGE && s.rearSurfaceDistanceSafe
        ) {
            return PhaseOrder.SURFACE
        }
        return null
    }
}

/** 覆盖闸：主威胁能瞄准本舰的武器过多时，继续相位机动等死角（强制上浮不受此闸约束）。 */
internal object CoverageGateRule : PhaseRule<PhaseOrder> {
    override val id = "CoverageGate"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        if (s.weaponCoverage > PhaseToggleTree.SURFACE_COVERAGE_MAX) return PhaseOrder.NONE
        return null
    }
}

/** 战术上浮：攻击系统就绪且有交战对象——上浮施放磁暴/复制器。 */
internal object TacticalSurfaceRule : PhaseRule<PhaseOrder> {
    override val id = "TacticalSurface"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        if (s.systemReady && s.engagedEnemyNear) return PhaseOrder.SURFACE
        return null
    }
}

/** 输出窗口上浮：可输出武器占比达闸且来袭可控时上浮开打。 */
internal object WeaponsReadySurfaceRule : PhaseRule<PhaseOrder> {
    override val id = "WeaponsReadySurface"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (!s.phased) return null
        if (s.weaponsReadyFrac >= PhaseToggleTree.WEAPONS_READY_SURFACE_FRAC &&
            s.incomingNearDamage < PhaseToggleTree.diveNearThreshold(s.maxHull)
        ) {
            return PhaseOrder.SURFACE
        }
        return null
    }
}

/** 斗篷未就绪（冷却中/被禁用）：一切下潜免谈。 */
internal object CloakReadyGateRule : PhaseRule<PhaseOrder> {
    override val id = "CloakReadyGate"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (!s.cloakReady) return PhaseOrder.NONE
        return null
    }
}

/**
 * 紧急下潜绕过错峰与系统激活闸：保命优先（但受友军接盘闸约束，濒危除外）；
 * 前方友军承火时阈值放宽（screenMult），有人扛线时不为擦伤火力反复潜水。
 */
internal object EmergencyDiveRule : PhaseRule<PhaseOrder> {
    override val id = "EmergencyDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        // 防御性来袭口径同 SoonHitGateRule：敌方与友军火力合并
        val defensiveSoon = s.incomingSoonDamage + s.incomingFriendlySoonDamage
        // 前方友军承火时放宽下潜阈值：有人扛线时本舰受到的直瞄火力显著减少，
        // 减少规避无效伤害的反复潜水（只放宽规避类下潜闸，不动上浮与战术链路）
        val screenMult =
            if (s.friendlyScreenAhead) PhaseToggleTree.SCREENED_DIVE_THRESHOLD_MULT else 1f
        // 友军接盘评估：入相位后原本命中本舰的直射弹药会穿透继续飞，弹道上友军会接盘；
        // 误伤量达闸后只允许自身濒危（致命来袭，含友军火力烧身）时下潜——自身生存优先
        val selfCritical = defensiveSoon >= s.maxHull * PhaseToggleTree.LETHAL_SOON_HULL_FRACTION
        val friendlyRisk = s.friendlyCatchDamage >= PhaseToggleTree.FRIENDLY_CATCH_DAMAGE_MIN
        if (defensiveSoon >= PhaseToggleTree.diveSoonThreshold(s.maxHull) * screenMult &&
            (!friendlyRisk || selfCritical)
        ) {
            return PhaseOrder.DIVE
        }
        return null
    }
}

/** 攻击系统激活中不主动下潜（防自断磁暴充能/复制窗口）。 */
internal object SystemActiveGateRule : PhaseRule<PhaseOrder> {
    override val id = "SystemActiveGate"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (s.systemActive) return PhaseOrder.NONE
        return null
    }
}

/** 友军接盘闸：穿透误伤估计达闸时压制一切非紧急下潜（紧急下潜已在 [EmergencyDiveRule] 先行判定）。 */
internal object FriendlyCatchGateRule : PhaseRule<PhaseOrder> {
    override val id = "FriendlyCatchGate"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (s.friendlyCatchDamage >= PhaseToggleTree.FRIENDLY_CATCH_DAMAGE_MIN) return PhaseOrder.NONE
        return null
    }
}

/**
 * 相位耗软辐能（先于一刀切错峰闸判定）：闸门收敛到 [PhaseToggleTree.isSoftFluxDumpDive]
 * 单一口径。不受错峰闸约束——相位耗散是本系统的核心循环，错峰窗口内不压 vent 时
 * VentModule 必抢先 vent（vent 启动即不可取消），绕后/侧后输出窗口随之报废；
 * 闸门内含 cloakReady，通风口只在相位耗散确定接手时被压制。
 */
internal object SoftFluxDumpDiveRule : PhaseRule<PhaseOrder> {
    override val id = "SoftFluxDumpDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (PhaseToggleTree.isSoftFluxDumpDive(s)) return PhaseOrder.DIVE
        return null
    }
}

/** 错峰闸：除紧急下潜与耗软辐下潜外，距上次上浮不足 [PhaseToggleTree.MIN_UNPHASE_TIME_SEC] 不下潜。 */
internal object StaggerGateRule : PhaseRule<PhaseOrder> {
    override val id = "StaggerGate"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (s.timeSinceUnphase < PhaseToggleTree.MIN_UNPHASE_TIME_SEC) return PhaseOrder.NONE
        return null
    }
}

/**
 * 相位赶路（先于一刀切下潜规则判定）：赶路目标点足够远且脱战低辐时下潜赶路；
 * 与撤退下潜互斥（[PhaseToggleTree.isTravelDive] 排除撤退态），与交战类下潜互斥（要求脱战）。
 */
internal object TravelDiveRule : PhaseRule<PhaseOrder> {
    override val id = "TravelDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (PhaseToggleTree.isTravelDive(s)) return PhaseOrder.DIVE
        return null
    }
}

/** 撤退下潜：撤退中且硬辐能有余量时下潜赶路（相位 3x 时间倍率加速位移）。 */
internal object RetreatDiveRule : PhaseRule<PhaseOrder> {
    override val id = "RetreatDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (s.retreating && s.hardFluxLevel < PhaseToggleTree.RETREAT_DIVE_HARD_FLUX) {
            return PhaseOrder.DIVE
        }
        return null
    }
}

/** 辐能压力下潜：辐能水平达闸、硬辐能有上限余量且有交战对象时下潜避险。 */
internal object FluxPressureDiveRule : PhaseRule<PhaseOrder> {
    override val id = "FluxPressureDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (s.fluxLevel >= PhaseToggleTree.DIVE_FLUX_LEVEL &&
            s.hardFluxLevel < PhaseToggleTree.DIVE_HARD_FLUX_MAX && s.engagedEnemyNear
        ) {
            return PhaseOrder.DIVE
        }
        return null
    }
}

/** 绕后下潜：闸门收敛到 [FlankAttackTree.isFlankDive] 单一口径（本规则与协调器的意图布防共用）。 */
internal object FlankDiveRule : PhaseRule<PhaseOrder> {
    override val id = "FlankDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (FlankAttackTree.isFlankDive(s)) return PhaseOrder.DIVE
        return null
    }
}

/** 威胁下潜：威胁窗口伤害达阈值下潜（前方友军承火时阈值同样放宽）。 */
internal object ThreatDiveRule : PhaseRule<PhaseOrder> {
    override val id = "ThreatDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        val screenMult =
            if (s.friendlyScreenAhead) PhaseToggleTree.SCREENED_DIVE_THRESHOLD_MULT else 1f
        if (s.incomingNearDamage >= PhaseToggleTree.diveNearThreshold(s.maxHull) * screenMult) {
            return PhaseOrder.DIVE
        }
        return null
    }
}

/** 装填下潜：可输出武器占比低迷且硬辐能有余量时，下潜等装填。 */
internal object RechargeDiveRule : PhaseRule<PhaseOrder> {
    override val id = "RechargeDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (s.weaponsReadyFrac <= PhaseToggleTree.RECHARGE_DIVE_WEAPONS_FRAC && s.engagedEnemyNear &&
            s.hardFluxLevel < PhaseToggleTree.RECHARGE_DIVE_HARD_FLUX_MAX
        ) {
            return PhaseOrder.DIVE
        }
        return null
    }
}

/**
 * 修复下潜：武器/引擎下线过半时优先相位窗口——相位 3x 时间倍率随舰时钟加速
 * 组件修复（原版口径：快进舰每帧额外 advance，ComponentHealthTracker 随舰时钟走），
 * 相位无敌兜底修复窗口的生存；硬辐能过高时下潜只会加速憋死，设上限。
 */
internal object RepairDiveRule : PhaseRule<PhaseOrder> {
    override val id = "RepairDive"

    override fun evaluate(ctx: PhaseDecisionContext): PhaseOrder? {
        val s = ctx.situation
        if (s.phased) return null
        if (s.engagedEnemyNear && s.hardFluxLevel < PhaseToggleTree.REPAIR_DIVE_HARD_FLUX_MAX &&
            (s.weaponsDisabledFrac >= PhaseToggleTree.REPAIR_DIVE_DISABLED_FRAC ||
                    s.enginesDisabledFrac >= PhaseToggleTree.REPAIR_DIVE_DISABLED_FRAC)
        ) {
            return PhaseOrder.DIVE
        }
        return null
    }
}
