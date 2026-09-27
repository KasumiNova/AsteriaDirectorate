package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAIScript
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.util.IntervalUtil
import org.lwjgl.util.vector.Vector2f

/**
 * 引力相位（astd_gravity_phase）防御系统 AI（密蒙级 ZW-002 / 舜华级 ZW-101 / 茑萝级 ZW-103 共用）。
 *
 * 替换原版 PHASE_CLOAK AI（PhaseCloakShieldAI）的动机：原版决策面向纯相位舰，
 * 本系舰船相位之外还携带需要非相位状态施放的攻击系统（引力磁暴/引力空间复制器/引力裂隙），
 * 原版 AI 的「武器装填下潜 + STAY_PHASED 续潜」链路会让舰船长时间相位潜水——
 * 相位维持辐能持续累积硬辐能（noHardDissipation + hardFlux），硬辐能越高相位内航速越低，
 * 最终被压制类舰船堵在相位里憋死，攻击系统全程空转。
 *
 * 决策核心为纯函数 [decide]（单测直接驱动），本类只负责态势采样与指令执行。
 * 决策口径（每 [SCAN_INTERVAL_SEC] 评估一次）：
 *
 * 下潜（未相位、斗篷就绪时）：
 * - 紧急下潜：[SOON_WINDOW_SEC] 内预计命中伤害达 [diveSoonThreshold]——绕过错峰闸直接下潜；
 * - 辐能压力下潜：辐能水平 ≥ [DIVE_FLUX_LEVEL] 且硬辐能 < [DIVE_HARD_FLUX_MAX] 且有交战对象
 *   （硬辐能过高时下潜只会加速憋死，故设上限）；
 * - 耗软辐下潜：软辐能占比 ≥ [SOFT_FLUX_DIVE_MIN]、总辐能 < [SOFT_FLUX_DIVE_MAX_FLUX_LEVEL]
 *   且环境安全（near 来袭低于威胁阈值 × [SOFT_FLUX_DIVE_SAFE_NEAR_FRAC]）时下潜耗散软辐
 *   （耗散优先级：紧急避险 > 相位耗软辐 > 强制耗散——闸门收敛在 [isSoftFluxDumpDive]，
 *   advance 只在 decide 返回 DIVE 且命中该闸门时压 DO_NOT_VENT 拦 VentModule）；
 * - 绕后下潜：主威胁目标为低机动舰（[isLowMobilityTarget] 代理指标）且本舰尚未进入其侧后
 *   薄弱区、武器过半可输出、硬辐能 < [FLANK_DIVE_HARD_FLUX_MAX] 时，借相位穿透占位；
 * - 威胁下潜：[NEAR_WINDOW_SEC] 内预计命中伤害达 [diveNearThreshold]；
 * - 装填下潜：可输出武器占比 ≤ [RECHARGE_DIVE_WEAPONS_FRAC] 且硬辐能 < [RECHARGE_DIVE_HARD_FLUX_MAX]；
 * - 撤退下潜：撤退中且硬辐能 < [RETREAT_DIVE_HARD_FLUX]（相位赶路）；
 * - 友军接盘闸：若此刻入相位，穿透本舰的直射弹药在 [FRIENDLY_CATCH_WINDOW_SEC] 内将误伤
 *   友军的估计伤害 ≥ [FRIENDLY_CATCH_DAMAGE_MIN] 时，压制一切下潜（自身濒危——致命来袭——除外）；
 * - 错峰闸：除紧急下潜外，距上次上浮不足 [MIN_UNPHASE_TIME_SEC] 不下潜；
 *   攻击系统激活中（isOn）不主动下潜（防止自断磁暴充能/复制窗口）。
 *
 * 上浮（相位中时）：
 * - 辐能强制上浮：硬辐能 ≥ [SURFACE_HARD_FLUX]（继续潜只会涨辐能减速被围死）；
 * - 时长强制上浮：连续相位 ≥ [MAX_PHASE_TIME_SEC]（错峰节奏，强制回到战场）；
 * - 致命豁免：上述强制上浮触发时，若 [SOON_WINDOW_SEC] 内有致命来袭（≥ 舰体 20%）
 *   且硬辐能 < [HOLD_MAX_HARD_FLUX]，等这一下过去再上浮；
 * - 即将受击闸：全部主动上浮路径统一要求 [SOON_WINDOW_SEC] 窗口来袭低于 [diveSoonThreshold]
 *   （先于一刀切上浮规则判定——交战圈外发射的高速弹不在无威胁上浮的 near 窗口口径内，
 *   但同样会在 soon 窗口落地，不能漏拦）；
 * - 无威胁上浮：威胁圈无交战对象且来袭轻微（撤退赶路且硬辐能有余量时保持相位）；
 * - 死角上浮：已机动到主威胁目标侧后射界薄弱区（[REAR_ARC_MIN_DIFF] 口径）且能瞄准本舰的
 *   武器 ≤ [REAR_SURFACE_MAX_COVERAGE] 时立即上浮输出；
 * - 覆盖闸：主威胁目标能瞄准本舰的武器数 > [SURFACE_COVERAGE_MAX] 时，战术/输出窗口上浮
 *   继续相位机动等死角（强制上浮不受此闸约束）；
 * - 战术上浮：攻击系统就绪且有交战对象——上浮施放磁暴/复制器；
 * - 输出窗口上浮：可输出武器占比 ≥ [WEAPONS_READY_SURFACE_FRAC] 且来袭可控；
 * - 反闪烁：相位不足 [MIN_PHASE_TIME_SEC] 不主动上浮（强制上浮不受限）。
 *
 * 威胁评估口径：直射弹体按弹道外推 + 横向偏差判定；制导导弹（含龙炎 DEM 等跟踪武器）
 * 跳过横向偏差检查并以极速一半兜底接近速度；光束瞬时命中无弹体，扫描正在开火且射界/射程
 * 覆盖本舰的敌方光束武器折算威胁（持续光束 DPS × [CONT_BEAM_THREAT_WINDOW_SEC] 进 near，
 * 爆发光束爆发总伤 × [BURST_BEAM_THREAT_WEIGHT] 进 soon）。友军接盘只评估非制导弹药
 * （制导导弹脱靶后自行改瞄，穿透误伤口径不覆盖），穿透线段从预计命中本舰点起算。
 *
 * 上浮僵直保护：强制上浮后挂 [ShipwideAIFlags.AIFlags.BACK_OFF]（2s）让舰船后撤度过
 * 斗篷冷却窗口；斗篷冷却中且硬辐能过半/舰体残损时持续刷新 BACK_OFF。
 */
class GravityPhaseCloakAI : ShipSystemAIScript {

    companion object {
        /** 评估间隔（s）。 */
        private const val SCAN_INTERVAL_SEC = 0.2f

        /** 紧急窗口（s）：窗口内预计命中伤害计入「即将受击」。 */
        internal const val SOON_WINDOW_SEC = 0.6f

        /** 威胁窗口（s）：窗口内预计命中伤害计入「来袭威胁」。 */
        internal const val NEAR_WINDOW_SEC = 1.2f

        /** 弹体扫描半径（su），覆盖 NEAR_WINDOW 内高速弹体。 */
        private const val PROJECTILE_SCAN_RANGE = 1500f

        /** 弹体命中判定的横向余量（su，加在碰撞半径上）。 */
        private const val MISS_MARGIN = 50f

        /** 弹体朝向本舰的分速度下限（su/s），低于此值视为非来袭。 */
        private const val APPROACH_SPEED_MIN = 50f

        /** 交战判定半径（su）：圈内存在有效敌舰即视为有交战对象。 */
        internal const val ENGAGE_SCAN_RANGE = 1600f

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

        /** 相位时长上限（s）：连续相位超过本值强制上浮。 */
        internal const val MAX_PHASE_TIME_SEC = 8f

        /** 反闪烁最短相位时长（s），主动上浮的下限。 */
        internal const val MIN_PHASE_TIME_SEC = 1.0f

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

        /** 连续两次斗篷开关指令的最小间隔（s），对齐原版 0.5s 口径。 */
        private const val TOGGLE_GUARD_SEC = 0.5f

        /** 强制上浮后的后撤保护时长（s）。 */
        private const val SURFACE_BACKOFF_SEC = 2.0f

        /** 斗篷冷却期后撤保护的硬辐能闸。 */
        private const val BACKOFF_HARD_FLUX = 0.5f

        /** 斗篷冷却期后撤保护的舰体闸。 */
        private const val BACKOFF_HULL_LEVEL = 0.3f

        /** 相位耗软辐能闸：软辐能占比（fluxLevel - hardFluxLevel）达此值值得下潜耗散。 */
        internal const val SOFT_FLUX_DIVE_MIN = 0.35f

        /** 相位耗软辐能的总辐能上限：超过此值离过载太近，下潜耗散反而可能上浮即过载。 */
        internal const val SOFT_FLUX_DIVE_MAX_FLUX_LEVEL = 0.75f

        /** 耗软辐下潜的环境安全口径：near 窗口来袭低于威胁阈值 × 本值。 */
        internal const val SOFT_FLUX_DIVE_SAFE_NEAR_FRAC = 0.25f

        /** 低机动代理指标：极速不高于本值（su/s）直接判低机动。 */
        internal const val LOW_MOBILITY_MAX_SPEED = 70f

        /** 低机动代理指标：转向速率（°/s）与加速度（su/s²）双双低于本值判低机动。 */
        internal const val LOW_MOBILITY_TURN_RATE = 25f
        internal const val LOW_MOBILITY_ACCEL = 50f

        /** 侧后射界薄弱区口径：目标朝向与「目标→本舰」方位角差达到本值（目标背后 ±60° 锥）。 */
        internal const val REAR_ARC_MIN_DIFF = 120f

        /** 绕后下潜闸：可输出武器占比下限。 */
        internal const val FLANK_DIVE_WEAPONS_FRAC = 0.5f

        /** 绕后下潜的硬辐能上限。 */
        internal const val FLANK_DIVE_HARD_FLUX_MAX = 0.4f

        /** 死角上浮允许的最大武器覆盖数（目标侧后射界内能瞄准本舰的武器 ≤ 本值立即上浮）。 */
        internal const val REAR_SURFACE_MAX_COVERAGE = 1

        /** 主动上浮覆盖闸：主威胁目标能瞄准本舰的武器数超过本值时，继续相位机动等死角。 */
        internal const val SURFACE_COVERAGE_MAX = 3

        /** 武器射界判定余量（°，加在半射界角上）。 */
        private const val COVERAGE_ARC_MARGIN = 10f

        /** 友军接盘评估窗口（s）：入相位后穿透弹药沿原弹道继续飞的评估时长。 */
        internal const val FRIENDLY_CATCH_WINDOW_SEC = 1.5f

        /** 友军接盘误伤闸（伤害量）：达此值压制一切非濒危下潜。 */
        internal const val FRIENDLY_CATCH_DAMAGE_MIN = 300f

        /** 压制强制耗散的旗帜时长（s），按评估节奏刷新。 */
        private const val VENT_SUPPRESS_FLAG_SEC = 0.5f

        /** 持续光束威胁折算窗口（s）：照射中的持续光束按 DPS × 本值折算 near 窗口伤害。 */
        internal const val CONT_BEAM_THREAT_WINDOW_SEC = 1.5f

        /** 爆发光束威胁折算权重：照射中的爆发光束按爆发总伤 × 本值折算 soon 窗口伤害。 */
        internal const val BURST_BEAM_THREAT_WEIGHT = 0.5f

        /** 纯角度差（0~180°），不依赖 Misc 便于单测。 */
        internal fun angleDiffAbs(a: Float, b: Float): Float {
            var d = (a - b) % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return kotlin.math.abs(d)
        }

        /** 低机动目标判定（简化代理指标：极速 或 转向+加速度 双低）。 */
        internal fun isLowMobilityTarget(maxSpeed: Float, acceleration: Float, maxTurnRate: Float): Boolean =
            maxSpeed <= LOW_MOBILITY_MAX_SPEED ||
                    (maxTurnRate <= LOW_MOBILITY_TURN_RATE && acceleration <= LOW_MOBILITY_ACCEL)

        /**
         * 射线段-圆相交（纯函数）：弹体从 (px,py) 出发、窗口内位移 (dx,dy)，
         * 判定线段是否穿过以 (cx,cy) 为心、radius 为半径的圆。
         */
        internal fun segmentHitsCircle(
            px: Float, py: Float, dx: Float, dy: Float,
            cx: Float, cy: Float, radius: Float,
        ): Boolean {
            val lenSq = dx * dx + dy * dy
            if (lenSq < 1e-6f) return false
            // 圆心在线段方向上的投影参数 t（截断到线段内）
            var t = ((cx - px) * dx + (cy - py) * dy) / lenSq
            if (t < 0f) t = 0f
            if (t > 1f) t = 1f
            val nearX = px + dx * t - cx
            val nearY = py + dy * t - cy
            return nearX * nearX + nearY * nearY <= radius * radius
        }

        /** 相位决策快照（纯数据，[decide] 的输入，单测直接构造）。 */
        internal data class PhaseSituation(
            /** 是否处于相位（含 charge up；charge down 视为上浮中，不算相位）。 */
            val phased: Boolean,
            /** 已连续相位时长（s）。 */
            val phaseActiveTime: Float,
            /** 距上次完全上浮的时长（s）。 */
            val timeSinceUnphase: Float,
            /** 硬辐能水平（0~1）。 */
            val hardFluxLevel: Float,
            /** 总辐能水平（0~1）。 */
            val fluxLevel: Float,
            /** 紧急窗口内预计命中伤害。 */
            val incomingSoonDamage: Float,
            /** 威胁窗口内预计命中伤害（不含紧急窗口部分）。 */
            val incomingNearDamage: Float,
            /** 舰体上限。 */
            val maxHull: Float,
            /** 威胁圈内是否存在有效敌舰。 */
            val engagedEnemyNear: Boolean,
            /** 攻击系统是否就绪（空闲且冷却完毕）。 */
            val systemReady: Boolean,
            /** 攻击系统是否激活中（isOn；相位系统本身不算）。 */
            val systemActive: Boolean,
            /** 可输出武器占比（0~1；无武器时为 1）。 */
            val weaponsReadyFrac: Float,
            /** 是否撤退中。 */
            val retreating: Boolean,
            /** 相位斗篷是否可激活（空闲、冷却完毕、未被禁用）。 */
            val cloakReady: Boolean,
            /** 软辐能占比（fluxLevel - hardFluxLevel）。 */
            val softFluxLevel: Float,
            /** 主威胁目标是否为低机动舰（代理指标判定，见 [isLowMobilityTarget]）。 */
            val targetLowMobility: Boolean,
            /** 本舰是否已位于主威胁目标的侧后射界薄弱区。 */
            val inTargetRearArc: Boolean,
            /** 主威胁目标当前能瞄准本舰的武器数量。 */
            val weaponCoverage: Int,
            /** 若此刻入相位，穿透本舰的直射火力在 [FRIENDLY_CATCH_WINDOW_SEC] 内将误伤友军的估计伤害。 */
            val friendlyCatchDamage: Float,
        )

        /** 相位指令：NONE 保持 / DIVE 下潜 / SURFACE 上浮。 */
        internal enum class PhaseOrder { NONE, DIVE, SURFACE }

        /** 相位决策核心（纯函数）：口径见类 KDoc 规则清单。 */
        internal fun decide(s: PhaseSituation): PhaseOrder {
            if (s.phased) {
                // 致命豁免：即将吃到致命伤害且辐能有余量时，强制上浮推迟到这一下过去之后
                val holdForLethal =
                    s.incomingSoonDamage >= s.maxHull * LETHAL_SOON_HULL_FRACTION &&
                            s.hardFluxLevel < HOLD_MAX_HARD_FLUX

                if (s.hardFluxLevel >= SURFACE_HARD_FLUX && !holdForLethal) return PhaseOrder.SURFACE
                if (s.phaseActiveTime >= MAX_PHASE_TIME_SEC && !holdForLethal) return PhaseOrder.SURFACE
                if (s.phaseActiveTime < MIN_PHASE_TIME_SEC) return PhaseOrder.NONE

                // 即将受击不主动上浮（先于一刀切主动上浮规则判定：交战圈外发射的高速弹
                // 不在无威胁上浮的 near 窗口口径内，但同样会在 soon 窗口落地）
                if (s.incomingSoonDamage >= diveSoonThreshold(s.maxHull)) return PhaseOrder.NONE
                if (!s.engagedEnemyNear && s.incomingNearDamage < diveNearThreshold(s.maxHull) * 0.5f) {
                    // 撤退赶路且硬辐能有余量时保持相位
                    if (!(s.retreating && s.hardFluxLevel < RETREAT_STAY_HARD_FLUX)) {
                        return PhaseOrder.SURFACE
                    }
                }
                // 死角上浮：已机动到目标侧后射界薄弱区且覆盖武器稀少，立即上浮输出
                if (s.engagedEnemyNear && s.inTargetRearArc &&
                    s.weaponCoverage <= REAR_SURFACE_MAX_COVERAGE
                ) {
                    return PhaseOrder.SURFACE
                }
                // 覆盖闸：主威胁能瞄准本舰的武器过多时，继续相位机动等死角（强制上浮不受此闸约束）
                if (s.weaponCoverage > SURFACE_COVERAGE_MAX) return PhaseOrder.NONE
                if (s.systemReady && s.engagedEnemyNear) return PhaseOrder.SURFACE
                if (s.weaponsReadyFrac >= WEAPONS_READY_SURFACE_FRAC &&
                    s.incomingNearDamage < diveNearThreshold(s.maxHull)
                ) {
                    return PhaseOrder.SURFACE
                }
                return PhaseOrder.NONE
            }

            if (!s.cloakReady) return PhaseOrder.NONE
            // 友军接盘评估：入相位后原本命中本舰的直射弹药会穿透继续飞，弹道上友军会接盘；
            // 误伤量达闸后只允许自身濒危（致命来袭）时下潜
            val selfCritical = s.incomingSoonDamage >= s.maxHull * LETHAL_SOON_HULL_FRACTION
            val friendlyRisk = s.friendlyCatchDamage >= FRIENDLY_CATCH_DAMAGE_MIN
            // 紧急下潜绕过错峰与系统激活闸：保命优先（但受友军接盘闸约束，濒危除外）
            if (s.incomingSoonDamage >= diveSoonThreshold(s.maxHull) &&
                (!friendlyRisk || selfCritical)
            ) {
                return PhaseOrder.DIVE
            }
            // 攻击系统激活中不主动下潜（防自断磁暴充能/复制窗口）
            if (s.systemActive) return PhaseOrder.NONE
            if (s.timeSinceUnphase < MIN_UNPHASE_TIME_SEC) return PhaseOrder.NONE
            // 友军接盘闸：非紧急下潜一律压制
            if (friendlyRisk) return PhaseOrder.NONE

            if (s.retreating && s.hardFluxLevel < RETREAT_DIVE_HARD_FLUX) return PhaseOrder.DIVE
            if (s.fluxLevel >= DIVE_FLUX_LEVEL && s.hardFluxLevel < DIVE_HARD_FLUX_MAX &&
                s.engagedEnemyNear
            ) {
                return PhaseOrder.DIVE
            }
            // 相位耗软辐能：闸门收敛到 [isSoftFluxDumpDive] 单一口径（decide 与
            // advance 的 DO_NOT_VENT 压制共用，防止「压了 vent 却不下潜」的卡死窗口）
            if (isSoftFluxDumpDive(s)) {
                return PhaseOrder.DIVE
            }
            // 绕后下潜：目标低机动且本舰尚未进入其侧后薄弱区，借相位穿透占位再上浮输出
            if (s.targetLowMobility && !s.inTargetRearArc && s.engagedEnemyNear &&
                s.weaponsReadyFrac >= FLANK_DIVE_WEAPONS_FRAC &&
                s.hardFluxLevel < FLANK_DIVE_HARD_FLUX_MAX &&
                s.incomingNearDamage < diveNearThreshold(s.maxHull)
            ) {
                return PhaseOrder.DIVE
            }
            if (s.incomingNearDamage >= diveNearThreshold(s.maxHull)) return PhaseOrder.DIVE
            if (s.weaponsReadyFrac <= RECHARGE_DIVE_WEAPONS_FRAC && s.engagedEnemyNear &&
                s.hardFluxLevel < RECHARGE_DIVE_HARD_FLUX_MAX
            ) {
                return PhaseOrder.DIVE
            }
            return PhaseOrder.NONE
        }

        /** 是否为强制上浮（辐能闸/时长闸触发；用于上浮僵直保护挂后撤）。 */
        internal fun isForcedSurface(s: PhaseSituation): Boolean =
            s.phased && (s.hardFluxLevel >= SURFACE_HARD_FLUX || s.phaseActiveTime >= MAX_PHASE_TIME_SEC)

        /**
         * 耗软辐下潜完整闸门（纯函数）：软辐可观、总辐能离过载有距离、环境安全、有交战对象。
         * decide 的下潜规则与 advance 的 DO_NOT_VENT 压制共用本口径——压 vent 只在
         * 相位耗散确定会接手时发生，避免「既不能 vent 也不下潜」的高软辐卡死。
         */
        internal fun isSoftFluxDumpDive(s: PhaseSituation): Boolean =
            !s.phased && s.cloakReady &&
                    s.softFluxLevel >= SOFT_FLUX_DIVE_MIN &&
                    s.fluxLevel < SOFT_FLUX_DIVE_MAX_FLUX_LEVEL &&
                    s.incomingNearDamage < diveNearThreshold(s.maxHull) * SOFT_FLUX_DIVE_SAFE_NEAR_FRAC &&
                    s.engagedEnemyNear

        /** 光束威胁折算（纯函数）：持续光束按 DPS × [CONT_BEAM_THREAT_WINDOW_SEC] 计入 near 窗口。 */
        internal fun beamThreatNear(dps: Float): Float = dps * CONT_BEAM_THREAT_WINDOW_SEC

        /** 光束威胁折算（纯函数）：爆发光束按爆发总伤 × [BURST_BEAM_THREAT_WEIGHT] 计入 soon 窗口（爆发窗口即伤害落地窗口）。 */
        internal fun beamThreatSoon(burstDamage: Float): Float = burstDamage * BURST_BEAM_THREAT_WEIGHT
    }

    private var ship: ShipAPI? = null
    private var engine: CombatEngineAPI? = null

    private val scanInterval = IntervalUtil(SCAN_INTERVAL_SEC, SCAN_INTERVAL_SEC)

    /** 已连续相位时长（s），相位外清零。 */
    private var phaseActiveTime = 0f

    /** 距上次完全上浮的时长（s）。 */
    private var timeSinceUnphase = MIN_UNPHASE_TIME_SEC

    /** 距上次斗篷开关指令的时长（s）。 */
    private var toggleGuardElapsed = TOGGLE_GUARD_SEC

    override fun init(ship: ShipAPI, system: ShipSystemAPI, flags: ShipwideAIFlags, engine: CombatEngineAPI) {
        this.ship = ship
        this.engine = engine
        scanInterval.forceIntervalElapsed()
    }

    override fun advance(
        amount: Float,
        missileDangerDir: Vector2f?,
        collisionDangerDir: Vector2f?,
        target: ShipAPI?,
    ) {
        val ship = this.ship ?: return
        val engine = this.engine ?: return
        if (engine.isPaused || ship.isHulk || !ship.isAlive) return
        // 原版接线（ShipSystemSpec.createSystemAI CUSTOM 分支）固定以 ship.getSystem()
        // （舰船主系统，本系舰船即攻击系统）调 init，与 aiScript 挂在哪个系统条目无关；
        // 相位斗篷挂防御槽（ship.phaseCloak），必须显式取用，不得用 init 传入的形参
        val cloak = ship.phaseCloak ?: ship.system ?: return
        if (ship.isDefenseDisabled) return

        val phased = cloak.state == ShipSystemAPI.SystemState.IN ||
                cloak.state == ShipSystemAPI.SystemState.ACTIVE
        if (phased) {
            phaseActiveTime += amount
            timeSinceUnphase = 0f
            // 对齐原版口径：相位中不排气
            ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_VENT, 0.3f)
        } else {
            timeSinceUnphase += amount
            if (cloak.state == ShipSystemAPI.SystemState.IDLE ||
                cloak.state == ShipSystemAPI.SystemState.COOLDOWN
            ) {
                phaseActiveTime = 0f
            }
        }
        toggleGuardElapsed += amount

        scanInterval.advance(amount)
        if (!scanInterval.intervalElapsed()) return

        val situation = sampleSituation(engine, ship, cloak, phased, target)

        // 上浮僵直保护：斗篷冷却窗口内硬辐能过半或舰体残损且有交战对象时持续后撤
        if (!phased && cloak.state == ShipSystemAPI.SystemState.COOLDOWN &&
            (ship.hardFluxLevel >= BACKOFF_HARD_FLUX || ship.hullLevel < BACKOFF_HULL_LEVEL) &&
            situation.engagedEnemyNear
        ) {
            ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, 0.5f)
        }

        val order = decide(situation)

        // 相位耗散优先于强制耗散：只在耗软辐下潜确定接手（decide 返回 DIVE 且命中
        // 耗软辐完整闸门 [isSoftFluxDumpDive]）时压 DO_NOT_VENT 拦 VentModule，
        // 与下潜闸门单一口径对齐——脱战/来袭偏高时闸门不成立，vent 不被压制
        // （决策优先级：紧急避险 > 相位耗软辐 > 强制耗散）
        if (order == PhaseOrder.DIVE && isSoftFluxDumpDive(situation)) {
            ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_VENT, VENT_SUPPRESS_FLAG_SEC)
        }

        if (toggleGuardElapsed < TOGGLE_GUARD_SEC) return
        when (order) {
            PhaseOrder.DIVE -> toggleCloak(ship)
            PhaseOrder.SURFACE -> {
                toggleCloak(ship)
                if (isForcedSurface(situation)) {
                    ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, SURFACE_BACKOFF_SEC)
                }
            }

            PhaseOrder.NONE -> Unit
        }
    }

    /** 相位斗篷开关：原版对防御槽相位斗篷走 TOGGLE_SHIELD_OR_PHASE_CLOAK 指令（toggle 系统再次触发即反向充能）。 */
    private fun toggleCloak(ship: ShipAPI) {
        toggleGuardElapsed = 0f
        ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0)
    }

    /** 态势采样：来袭伤害两窗口、交战圈、攻击系统状态、武器可输出占比、目标机动/射界、友军接盘。 */
    private fun sampleSituation(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        cloak: ShipSystemAPI,
        phased: Boolean,
        target: ShipAPI?,
    ): PhaseSituation {
        val incoming = FloatArray(2)
        estimateIncoming(engine.projectiles, ship, incoming)
        estimateIncoming(engine.missiles, ship, incoming)
        estimateBeamThreat(engine, ship, incoming)

        val threat = pickThreat(engine, ship, target)
        val attackSystem = ship.system?.takeIf { it !== cloak }
        return PhaseSituation(
            phased = phased,
            phaseActiveTime = phaseActiveTime,
            timeSinceUnphase = timeSinceUnphase,
            hardFluxLevel = ship.hardFluxLevel,
            fluxLevel = ship.fluxLevel,
            incomingSoonDamage = incoming[0],
            incomingNearDamage = incoming[1],
            maxHull = ship.maxHitpoints,
            engagedEnemyNear = hasEngagedEnemy(engine, ship),
            systemReady = attackSystem != null &&
                    attackSystem.state == ShipSystemAPI.SystemState.IDLE &&
                    attackSystem.cooldownRemaining <= 0f,
            systemActive = attackSystem?.isOn == true,
            weaponsReadyFrac = weaponsReadyFrac(ship),
            retreating = ship.isRetreating,
            cloakReady = cloak.state == ShipSystemAPI.SystemState.IDLE &&
                    cloak.cooldownRemaining <= 0f && cloak.canBeActivated(),
            softFluxLevel = ship.fluxLevel - ship.hardFluxLevel,
            targetLowMobility = threat != null &&
                    isLowMobilityTarget(threat.maxSpeed, threat.acceleration, threat.maxTurnRate),
            inTargetRearArc = threat != null && angleDiffAbs(
                threat.facing,
                Math.toDegrees(
                    kotlin.math.atan2(
                        (ship.location.y - threat.location.y).toDouble(),
                        (ship.location.x - threat.location.x).toDouble(),
                    )
                ).toFloat(),
            ) >= REAR_ARC_MIN_DIFF,
            weaponCoverage = if (threat == null) 0 else weaponCoverageOn(ship, threat),
            friendlyCatchDamage = estimateFriendlyCatch(engine, ship),
        )
    }

    /** 主威胁目标：当前目标有效则用之，否则取交战圈内最近敌舰（无则 null；回退同样限交战圈）。 */
    private fun pickThreat(engine: CombatEngineAPI, ship: ShipAPI, target: ShipAPI?): ShipAPI? {
        if (target != null && isValidEnemyShip(ship, target) &&
            distanceSq(ship.location, target.location) <= ENGAGE_SCAN_RANGE * ENGAGE_SCAN_RANGE
        ) {
            return target
        }
        var best: ShipAPI? = null
        var bestDist = ENGAGE_SCAN_RANGE * ENGAGE_SCAN_RANGE
        for (other in engine.ships) {
            if (!isValidEnemyShip(ship, other)) continue
            val dist = distanceSq(ship.location, other.location)
            if (dist < bestDist) {
                bestDist = dist
                best = other
            }
        }
        return best
    }

    /** 武器覆盖数：主威胁目标上未禁用、有余弹、射程够得着、且射界当前覆盖本舰的非装饰武器数量。 */
    private fun weaponCoverageOn(ship: ShipAPI, threat: ShipAPI): Int {
        var count = 0
        for (weapon in threat.allWeapons) {
            if (weapon.isDecorative || weapon.isDisabled) continue
            // 无弹药武器不构成覆盖（弹尽后无法开火）
            if (weapon.usesAmmo() && weapon.ammo <= 0) continue
            val dx = ship.location.x - weapon.location.x
            val dy = ship.location.y - weapon.location.y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > weapon.range + ship.collisionRadius) continue
            val angleToUs = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
            if (angleDiffAbs(weapon.currAngle, angleToUs) <= weapon.arc / 2f + COVERAGE_ARC_MARGIN) {
                count++
            }
        }
        return count
    }

    /**
     * 友军接盘评估：当前朝本舰飞来的直射弹药（制导导弹会自行改瞄，不计），
     * 若本舰此刻入相位，弹药沿原弹道在 [FRIENDLY_CATCH_WINDOW_SEC] 窗口内
     * 穿过友军碰撞圈的累计伤害。压制非濒危下潜使用。
     */
    private fun estimateFriendlyCatch(engine: CombatEngineAPI, ship: ShipAPI): Float {
        var catch = 0f
        catch += friendlyCatchFrom(engine.projectiles, engine, ship)
        catch += friendlyCatchFrom(engine.missiles, engine, ship)
        return catch
    }

    private fun friendlyCatchFrom(
        projectiles: List<DamagingProjectileAPI>,
        engine: CombatEngineAPI,
        ship: ShipAPI,
    ): Float {
        val shipLoc = ship.location
        var catch = 0f
        for (proj in projectiles) {
            if (proj.owner == ship.owner || proj.isFading || proj.isExpired) continue
            // 制导导弹脱靶后自行改瞄，穿透误伤口径不覆盖
            if (proj is MissileAPI && proj.isGuided) continue
            val dx = shipLoc.x - proj.location.x
            val dy = shipLoc.y - proj.location.y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > PROJECTILE_SCAN_RANGE || dist < 1e-3f) continue

            val vel = proj.velocity
            val approach = (vel.x * dx + vel.y * dy) / dist
            if (approach <= APPROACH_SPEED_MIN) continue
            // 只有本来会命中本舰的弹药才有「穿透」一说
            val eta = (dist - ship.collisionRadius) / approach
            if (eta < 0f || eta > NEAR_WINDOW_SEC) continue
            val hitX = proj.location.x + vel.x * eta - shipLoc.x
            val hitY = proj.location.y + vel.y * eta - shipLoc.y
            val hitR = ship.collisionRadius + MISS_MARGIN
            if (hitX * hitX + hitY * hitY > hitR * hitR) continue

            // 穿透后的飞行窗口：从预计命中本舰点起算（抵达本舰之前的航段不评估——
            // 弹体与本舰之间隔着友军时会先撞友军，不构成穿透误伤），窗口扣除已飞的 eta
            val startX = proj.location.x + vel.x * eta
            val startY = proj.location.y + vel.y * eta
            val remaining = FRIENDLY_CATCH_WINDOW_SEC - eta
            if (remaining <= 0f) continue
            val flyX = vel.x * remaining
            val flyY = vel.y * remaining
            for (friendly in engine.ships) {
                if (friendly === ship || friendly.owner != ship.owner ||
                    !friendly.isAlive || friendly.isHulk || friendly.isFighter
                ) {
                    continue
                }
                if (segmentHitsCircle(
                        startX, startY, flyX, flyY,
                        friendly.location.x, friendly.location.y, friendly.collisionRadius,
                    )
                ) {
                    catch += proj.damageAmount
                    break
                }
            }
        }
        return catch
    }

    /** 有效敌舰口径（非战机、非残骸、非中立 owner 100），交战圈与威胁目标共用。 */
    private fun isValidEnemyShip(ship: ShipAPI, other: ShipAPI): Boolean =
        other !== ship && other.isAlive && !other.isHulk && !other.isFighter &&
                other.owner != ship.owner && other.owner != 100

    /**
     * 光束威胁采样（折算累加进 [out]：[0]=soon，[1]=near）：
     * 光束瞬时命中、无弹体实体可被 [estimateIncoming] 采样，改为扫描敌舰武器——
     * 正在开火（isFiring，爆发光束含充能窗口）、射界与射程覆盖本舰的光束计入威胁：
     * 持续光束按 [beamThreatNear] 折算（DPS × 窗口秒）进 near；
     * 爆发光束（速子长矛级）按 [beamThreatSoon] 折算（爆发总伤 × 权重）进 soon——
     * 爆发窗口即伤害落地窗口，计入 soon 使紧急下潜直接响应高伤光束照射
     * （相位断照射是相位舰对高伤光束的标准生存手段）。
     */
    private fun estimateBeamThreat(engine: CombatEngineAPI, ship: ShipAPI, out: FloatArray) {
        for (enemy in engine.ships) {
            if (!isValidEnemyShip(ship, enemy)) continue
            for (weapon in enemy.allWeapons) {
                if (weapon.isDecorative || weapon.isDisabled || !weapon.isFiring) continue
                // isBurstBeam 是 isBeam 的子集，先过滤非光束
                if (!weapon.isBeam) continue
                if (weapon.usesAmmo() && weapon.ammo <= 0) continue
                val dx = ship.location.x - weapon.location.x
                val dy = ship.location.y - weapon.location.y
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist > weapon.range + ship.collisionRadius) continue
                val angleToUs = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (angleDiffAbs(weapon.currAngle, angleToUs) > weapon.arc / 2f + COVERAGE_ARC_MARGIN) {
                    continue
                }
                if (weapon.isBurstBeam) {
                    out[0] += beamThreatSoon(weapon.derivedStats.burstDamage)
                } else {
                    out[1] += beamThreatNear(weapon.derivedStats.dps)
                }
            }
        }
    }

    /**
     * 来袭伤害估计（两窗口累加进 [out]：[0]=紧急窗口，[1]=威胁窗口）：
     * 弹体朝本舰的分速度超阈值、按当前弹道在窗口内到达且横向偏差落在碰撞圈余量内才计入；
     * 忽略本舰机动，窗口短（≤1.2s）口径足够。
     * 制导导弹（含龙炎 DEM 等跟踪武器）放宽口径：会自行修正弹道，跳过横向偏差检查，
     * 接近速度取其极速的一半兜底（当前速度不代表命中时刻速度）。
     */
    private fun estimateIncoming(
        projectiles: List<DamagingProjectileAPI>,
        ship: ShipAPI,
        out: FloatArray,
    ) {
        val shipLoc = ship.location
        val radius = ship.collisionRadius
        for (proj in projectiles) {
            if (proj.owner == ship.owner || proj.isFading || proj.isExpired) continue
            val guided = proj is MissileAPI && proj.isGuided
            val dx = shipLoc.x - proj.location.x
            val dy = shipLoc.y - proj.location.y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > PROJECTILE_SCAN_RANGE || dist < 1e-3f) continue

            val vel = proj.velocity
            var approach = (vel.x * dx + vel.y * dy) / dist
            if (guided) {
                val missileSpeed = (proj as MissileAPI).maxSpeed * 0.5f
                if (approach < missileSpeed) approach = missileSpeed
            }
            if (approach <= APPROACH_SPEED_MIN) continue

            val eta = (dist - radius) / approach
            if (eta < 0f || eta > NEAR_WINDOW_SEC) continue

            if (!guided) {
                // 命中时刻弹体位置与本舰中心的横向偏差：落在碰撞圈余量内才算会命中
                val hitX = proj.location.x + vel.x * eta - shipLoc.x
                val hitY = proj.location.y + vel.y * eta - shipLoc.y
                if (hitX * hitX + hitY * hitY > (radius + MISS_MARGIN) * (radius + MISS_MARGIN)) continue
            }

            if (eta <= SOON_WINDOW_SEC) {
                out[0] += proj.damageAmount
            } else {
                out[1] += proj.damageAmount
            }
        }
    }

    /** 交战判定：威胁圈内存在有效敌舰。 */
    private fun hasEngagedEnemy(engine: CombatEngineAPI, ship: ShipAPI): Boolean =
        engine.ships.any { other ->
            isValidEnemyShip(ship, other) &&
                    distanceSq(ship.location, other.location) <= ENGAGE_SCAN_RANGE * ENGAGE_SCAN_RANGE
        }

    /** 可输出武器占比：非装饰、非系统槽武器中未禁用、装填完毕、有余弹的比例（无武器时为 1）。 */
    private fun weaponsReadyFrac(ship: ShipAPI): Float {
        var total = 0
        var ready = 0
        for (weapon in ship.allWeapons) {
            if (weapon.isDecorative || weapon.slot?.isSystemSlot != false) continue
            total++
            if (!weapon.isDisabled && weapon.cooldownRemaining < 0.3f &&
                (!weapon.usesAmmo() || weapon.ammo > 0)
            ) {
                ready++
            }
        }
        return if (total == 0) 1f else ready.toFloat() / total
    }

    private fun distanceSq(a: Vector2f, b: Vector2f): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}
