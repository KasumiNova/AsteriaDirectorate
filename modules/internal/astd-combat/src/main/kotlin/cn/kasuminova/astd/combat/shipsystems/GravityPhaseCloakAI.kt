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
import org.apache.logging.log4j.LogManager
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
 *   来袭口径为敌方与友军火力合并（被友军高伤火力命中同样是生存威胁，相位可以规避友伤），
 *   友军火力只走这条防御链路，不参与绕后/耗软辐等战术下潜；
 * - 辐能压力下潜：辐能水平 ≥ [DIVE_FLUX_LEVEL] 且硬辐能 < [DIVE_HARD_FLUX_MAX] 且有交战对象
 *   （硬辐能过高时下潜只会加速憋死，故设上限）；
 * - 耗软辐下潜：软辐能占比 ≥ [SOFT_FLUX_DIVE_MIN]、总辐能 < [SOFT_FLUX_DIVE_MAX_FLUX_LEVEL]
 *   且环境安全（near 来袭低于威胁阈值 × [SOFT_FLUX_DIVE_SAFE_NEAR_FRAC]）时下潜耗散软辐
 *   （耗散优先级：紧急避险 > 相位耗软辐 > 强制耗散——闸门收敛在 [isSoftFluxDumpDive]，
 *   advance 只在 decide 返回 DIVE 且命中该闸门时压 DO_NOT_VENT 拦 VentModule）；
 * - 绕后下潜：主威胁目标为低机动舰（[isLowMobilityTarget] 代理指标）且本舰尚未进入其侧后
 *   薄弱区、武器过半可输出、硬辐能 < [FLANK_DIVE_HARD_FLUX_MAX] 时，借相位穿透占位
 *   （闸门收敛在 [isFlankDive]，decide 与 advance 的意图布防共用）；下潜后开启
 *   绕后意图窗口（[flankIntentWindowSec] 按布防距离缩放基准值 [FLANK_INTENT_SEC]），
 *   相位中挂 PHASE_ATTACK_RUN 旗标驱动原版走位
 *   把舰船带往目标背后（原版相位 AI 被替换后该旗标无人管理，正面硬点装配的舰船
 *   下潜后会原地罚站），进入侧后改挂 PHASE_ATTACK_RUN_IN_GOOD_SPOT 就地保持；
 * - 威胁下潜：[NEAR_WINDOW_SEC] 内预计命中伤害达 [diveNearThreshold]；
 * - 装填下潜：可输出武器占比 ≤ [RECHARGE_DIVE_WEAPONS_FRAC] 且硬辐能 < [RECHARGE_DIVE_HARD_FLUX_MAX]；
 * - 撤退下潜：撤退中且硬辐能 < [RETREAT_DIVE_HARD_FLUX]（相位赶路）；
 * - 友军接盘闸：若此刻入相位，穿透本舰的直射弹药在 [FRIENDLY_CATCH_WINDOW_SEC] 内将误伤
 *   友军的估计伤害 ≥ [FRIENDLY_CATCH_DAMAGE_MIN] 时，压制一切下潜（自身濒危——致命来袭，
 *   含友军火力烧身——除外：与接盘博弈时自身生存优先）；
 * - 错峰闸：除紧急下潜外，距上次上浮不足 [MIN_UNPHASE_TIME_SEC] 不下潜；
 *   攻击系统激活中（isOn）不主动下潜（防止自断磁暴充能/复制窗口）。
 *
 * 上浮（相位中时）：
 * - 辐能强制上浮：硬辐能 ≥ [SURFACE_HARD_FLUX]（继续潜只会涨辐能减速被围死）；
 * - 时长强制上浮：连续相位 ≥ [MAX_PHASE_TIME_SEC]（错峰节奏，强制回到战场）；
 *   绕后意图生效期间上限放宽到武装窗口 + [FLANK_PHASE_CAP_MARGIN_SEC]（穿透机动
 *   需要位移时间，辐能闸不受放宽）；
 * - 致命豁免：上述强制上浮触发时，若 [SOON_WINDOW_SEC] 内有致命来袭（≥ 舰体 20%，
 *   含友军火力烧身）且硬辐能 < [HOLD_MAX_HARD_FLUX]，等这一下过去再上浮；
 * - 即将受击闸：全部主动上浮路径统一要求 [SOON_WINDOW_SEC] 窗口来袭低于 [diveSoonThreshold]
 *   （先于一刀切上浮规则判定——交战圈外发射的高速弹不在无威胁上浮的 near 窗口口径内，
 *   但同样会在 soon 窗口落地，不能漏拦；友军火力并入同一口径，持续照射本舰时按住不上浮）；
 * - 上浮安全闸：主动上浮统一要求落点相对安全（[isSurfaceSafe]，强制上浮不受约束）——
 *   与最近敌舰间距 < 双方碰撞半径和 × [SURFACE_PROXIMITY_FRAC]（贴脸上浮必吃点射与撞击）、
 *   [SURFACE_WINDOW_SEC] 弹幕窗口来袭合计 ≥ [surfaceDangerThreshold]（上浮僵直期承伤窗口
 *   比 soon 更长，已在途的鱼雷/齐射在命中前即按住）、或位于主威胁正脸火力轴
 *   （±[SURFACE_FRONT_AXIS_HALF_ARC]° 且 [SURFACE_FRONT_AXIS_MAX_DIST]su 内，优先侧后上浮）
 *   时按住不上浮，保持相位机动等时机；
 * - 无威胁上浮：威胁圈无交战对象且来袭轻微（撤退赶路且硬辐能有余量时保持相位）；
 * - 死角上浮：已机动到主威胁目标侧后射界薄弱区（[REAR_ARC_MIN_DIFF] 口径）且能瞄准本舰的
 *   武器 ≤ [REAR_SURFACE_MAX_COVERAGE] 时立即上浮输出；
 * - 绕后意图拦截（先于无威胁/死角上浮判定）：意图途中（未入侧后）一切主动上浮按住，
 *   保持相位机动穿透——就位点在目标背后远点，途中短暂掉出交战圈不等于脱战，
 *   掉圈提前上浮即布防早夭；意图达成（已入侧后）时覆盖闸放宽到 [SURFACE_COVERAGE_MAX]
 *   即上浮输出；
 * - 覆盖闸：主威胁目标能瞄准本舰的武器数 > [SURFACE_COVERAGE_MAX] 时，战术/输出窗口上浮
 *   继续相位机动等死角（强制上浮不受此闸约束）；
 * - 战术上浮：攻击系统就绪且有交战对象——上浮施放磁暴/复制器；
 * - 输出窗口上浮：可输出武器占比 ≥ [WEAPONS_READY_SURFACE_FRAC] 且来袭可控；
 * - 反闪烁：相位不足 [MIN_PHASE_TIME_SEC] 不主动上浮（强制上浮不受限）；
 * - 落点安全闸：本舰与任一非战机舰船深度重叠（间距 < 双方碰撞半径和 ×
 *   [UNPHASE_UNSAFE_OVERLAP_FRAC]，与原版斗篷 canBeDeactivated 的 locationSafe 口径一致）时，
 *   一切上浮指令按住（含强制上浮）——此时 toggle 会被原版拒绝，白耗开关守卫；
 *   按住续潜无过载风险（canNotCauseOverload），漂出重叠后上浮自然放行。
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
        private val log = LogManager.getLogger(GravityPhaseCloakAI::class.java)

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

        /** 上浮弹幕窗口（s）：窗口内预计命中伤害计入上浮安全评估（上浮僵直期承伤窗口比 soon 更长，鱼雷/齐射在途中即按住）。 */
        internal const val SURFACE_WINDOW_SEC = 2.0f

        /** 上浮弹幕伤害阈值：max(本值, 舰体上限 × 比例)。 */
        internal fun surfaceDangerThreshold(maxHull: Float): Float =
            maxOf(600f, maxHull * 0.10f)

        /** 上浮贴脸判定系数：与最近敌舰间距 < 双方碰撞半径和 × 本值时不上浮（大于原版拒退重叠口径 0.35，留出漂离余量）。 */
        internal const val SURFACE_PROXIMITY_FRAC = 0.9f

        /** 正脸火力轴判定半角（°）：主威胁朝向与本舰相对方位差 ≤ 本值且距离够近时不上浮。 */
        internal const val SURFACE_FRONT_AXIS_HALF_ARC = 60f

        /** 正脸火力轴判定距离（su）：超出本距离敌舰正脸火力不构成贴脸上浮风险。 */
        internal const val SURFACE_FRONT_AXIS_MAX_DIST = 1000f

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

        /** 绕后意图基准时长（s）：绕后下潜后保持相位机动穿透的时间窗（按布防距离缩放，见 [flankIntentWindowSec]）。 */
        internal const val FLANK_INTENT_SEC = 12f

        /** 绕后窗口参考距离（su）：布防距离不超过本值时窗口保持基准时长。 */
        internal const val FLANK_INTENT_REF_DIST = 1200f

        /** 绕后意图窗口上限（s）：慢速舰远处布防需要更长的穿透位移时间。 */
        internal const val FLANK_INTENT_MAX_SEC = 24f

        /** 绕后意图期间相位时长上限相对意图窗口的富余（s）。 */
        internal const val FLANK_PHASE_CAP_MARGIN_SEC = 2f

        /** 上浮落点重叠判定系数：与他舰间距 < （双方碰撞半径和）× 本值时退相位被原版拒绝（对齐原版 locationSafe 口径）。 */
        internal const val UNPHASE_UNSAFE_OVERLAP_FRAC = 0.35f

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
            /** 绕后意图是否生效中（绕后下潜后保持相位机动穿透的窗口期）。 */
            val flankIntentActive: Boolean,
            /** 布防时武装的绕后意图窗口长度（s）；意图未生效时取值不影响判定。 */
            val flankIntentWindowSec: Float,
            /** 主威胁目标距离（su；无威胁时为 [Float.MAX_VALUE]）。 */
            val threatDistance: Float,
            /** 上浮落点是否不安全：本舰碰撞圈与他舰深度重叠（原版斗篷此时拒绝退相位）。 */
            val unphaseUnsafe: Boolean,
            /** 上浮贴脸：最近敌舰间距进入双方碰撞半径和的 [SURFACE_PROXIMITY_FRAC] 倍内。 */
            val surfaceTooClose: Boolean,
            /** 上浮弹幕窗口（[SURFACE_WINDOW_SEC]）内预计命中本舰的伤害合计（敌方三窗口 + 友方 soon）。 */
            val incomingSurfaceDamage: Float,
            /** 正脸火力轴：位于主威胁舰艏 ±[SURFACE_FRONT_AXIS_HALF_ARC]° 锥内且距离在 [SURFACE_FRONT_AXIS_MAX_DIST] 内。 */
            val threatFrontAxisClose: Boolean,
            /** 友军火力在 soon 窗口内命中本舰的估计伤害（友伤规避只触发防御性下潜）。 */
            val incomingFriendlySoonDamage: Float,
        )

        /** 相位指令：NONE 保持 / DIVE 下潜 / SURFACE 上浮。 */
        internal enum class PhaseOrder { NONE, DIVE, SURFACE }

        /**
         * 上浮安全评估（纯函数）：贴脸、弹幕窗口来袭达闸、正脸火力轴任一不安全即按住。
         * 只约束主动上浮——强制上浮（辐能/时长闸）在 decideOrder 中先行返回，不经本闸。
         */
        internal fun isSurfaceSafe(s: PhaseSituation): Boolean =
            !s.surfaceTooClose && !s.threatFrontAxisClose &&
                    s.incomingSurfaceDamage < surfaceDangerThreshold(s.maxHull)

        /** 相位决策核心（纯函数）：口径见类 KDoc 规则清单。 */
        internal fun decide(s: PhaseSituation): PhaseOrder {
            val order = decideOrder(s)
            // 上浮落点安全闸：与他舰深度重叠时原版斗篷拒绝退相位（canBeDeactivated=false，
            // toggle 空发），按住一切上浮指令直到漂出重叠——否则实机会出现「指令三连发、
            // 舰船卡相位」的拒退循环（本系统 canNotCauseOverload，续潜无过载风险）
            if (order == PhaseOrder.SURFACE && s.phased && s.unphaseUnsafe) return PhaseOrder.NONE
            return order
        }

        private fun decideOrder(s: PhaseSituation): PhaseOrder {
            // 防御性来袭口径：敌方与友军火力合并——被友军高伤火力命中同样是生存威胁，
            // 相位可以规避友伤；友军火力只走这条防御链路，不参与绕后/耗软辐等战术下潜
            val defensiveSoon = s.incomingSoonDamage + s.incomingFriendlySoonDamage
            if (s.phased) {
                // 致命豁免：即将吃到致命伤害且辐能有余量时，强制上浮推迟到这一下过去之后
                val holdForLethal =
                    defensiveSoon >= s.maxHull * LETHAL_SOON_HULL_FRACTION &&
                            s.hardFluxLevel < HOLD_MAX_HARD_FLUX

                if (s.hardFluxLevel >= SURFACE_HARD_FLUX && !holdForLethal) return PhaseOrder.SURFACE
                // 绕后意图期间放宽相位时长上限：穿透机动需要位移时间（辐能闸不受放宽），
                // 上限跟随布防时按距离武装的窗口长度
                val maxPhaseTime =
                    if (s.flankIntentActive) s.flankIntentWindowSec + FLANK_PHASE_CAP_MARGIN_SEC
                    else MAX_PHASE_TIME_SEC
                if (s.phaseActiveTime >= maxPhaseTime && !holdForLethal) return PhaseOrder.SURFACE
                if (s.phaseActiveTime < MIN_PHASE_TIME_SEC) return PhaseOrder.NONE

                // 上浮安全闸：贴脸/弹幕窗口/正脸火力轴任一不安全时一切主动上浮按住，
                // 保持相位机动等时机（强制上浮已先行返回，不受此闸约束）
                if (!isSurfaceSafe(s)) return PhaseOrder.NONE

                // 即将受击不主动上浮（先于一刀切主动上浮规则判定：交战圈外发射的高速弹
                // 不在无威胁上浮的 near 窗口口径内，但同样会在 soon 窗口落地；
                // 友军火力持续照射本舰时同样按住不上浮——上浮即被烧）
                if (defensiveSoon >= diveSoonThreshold(s.maxHull)) return PhaseOrder.NONE
                // 绕后意图拦截（先于无威胁/死角上浮）：意图途中一切主动上浮按住——
                // 穿透机动的就位点在目标背后远点，途中短暂掉出交战圈不等于脱战，
                // 掉圈提前上浮即布防早夭（实测 zw101 多次 intent=true engaged=false
                // 途中上浮，绕后扫描幅度被压掉）
                if (s.flankIntentActive) {
                    // 绕后意图达成：已进入侧后薄弱区，覆盖闸放宽到常规上限即上浮输出
                    if (s.inTargetRearArc && s.weaponCoverage <= SURFACE_COVERAGE_MAX) {
                        return PhaseOrder.SURFACE
                    }
                    return PhaseOrder.NONE
                }
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
            // 误伤量达闸后只允许自身濒危（致命来袭，含友军火力烧身）时下潜——自身生存优先
            val selfCritical = defensiveSoon >= s.maxHull * LETHAL_SOON_HULL_FRACTION
            val friendlyRisk = s.friendlyCatchDamage >= FRIENDLY_CATCH_DAMAGE_MIN
            // 紧急下潜绕过错峰与系统激活闸：保命优先（但受友军接盘闸约束，濒危除外）
            if (defensiveSoon >= diveSoonThreshold(s.maxHull) &&
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
            // 绕后下潜：闸门收敛到 [isFlankDive] 单一口径（decide 与 advance 的意图布防共用）
            if (isFlankDive(s)) {
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

        /**
         * 绕后下潜完整闸门（纯函数）：目标低机动、本舰未在其侧后薄弱区、武器过半可输出、
         * 硬辐能有余量、near 来袭未达威胁阈值。decide 的下潜规则与 advance 的意图布防共用本口径。
         */
        internal fun isFlankDive(s: PhaseSituation): Boolean =
            !s.phased && s.cloakReady && s.targetLowMobility && !s.inTargetRearArc &&
                    s.engagedEnemyNear &&
                    s.weaponsReadyFrac >= FLANK_DIVE_WEAPONS_FRAC &&
                    s.hardFluxLevel < FLANK_DIVE_HARD_FLUX_MAX &&
                    s.incomingNearDamage < diveNearThreshold(s.maxHull)

        /**
         * 绕后意图窗口（纯函数）：按布防时距主威胁目标的距离相对 [FLANK_INTENT_REF_DIST]
         * 线性缩放基准窗口 [FLANK_INTENT_SEC]——穿透机动的时间预算就是位移预算，慢速舰
         * 远处下潜时固定窗口会在抵达目标背后前过期，导致意图作废、正面上浮白潜一趟；
         * 下限基准值（近距离布防行为不变），上限 [FLANK_INTENT_MAX_SEC]。
         */
        internal fun flankIntentWindowSec(threatDistance: Float): Float =
            (FLANK_INTENT_SEC * threatDistance / FLANK_INTENT_REF_DIST)
                .coerceIn(FLANK_INTENT_SEC, FLANK_INTENT_MAX_SEC)

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

    /** 绕后意图剩余时长（s）：绕后下潜布防后递减，上浮即清零。 */
    private var flankIntentRemaining = 0f

    /** 布防时武装的绕后意图窗口长度（s）：随 [flankIntentRemaining] 一同布防，供相位时长上限推导。 */
    private var flankIntentWindowArmed = FLANK_INTENT_SEC

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
            // 上浮即结束绕后意图（达成或中止都回到常规节奏）
            flankIntentRemaining = 0f
        }
        toggleGuardElapsed += amount
        flankIntentRemaining = (flankIntentRemaining - amount).coerceAtLeast(0f)

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

        // 绕后走位驱动：原版相位 AI 被替换后 PHASE_ATTACK_RUN 无人管理——
        // 意图途中挂 PHASE_ATTACK_RUN（StrafeTargetManeuverV2 会把相位舰带往目标背后 5000su 点），
        // 进入侧后薄弱区改挂 PHASE_ATTACK_RUN_IN_GOOD_SPOT（走位模块就地保持攻击距离）；
        // 两旗标短时效滚动刷新，意图结束/上浮后自然过期，无需手动 unset。
        // 同挂 DO_NOT_BACK_OFF：走位模块的穿透分支要求非后撤/非规避状态（var52/var58），
        // 相位累积辐能推高 fluxLevel 后会命中「辐能高于目标」规避判定把穿透驱动掐掉，
        // 意图期间由本 AI 的辐能闸（SURFACE_HARD_FLUX）兜底生存，走位层不再自行后撤；
        // 落点重叠时原版斗篷拒绝退相位，不就地保持，继续穿透驱动直到漂出重叠
        if (situation.flankIntentActive && phased) {
            ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, 0.5f)
            if (situation.inTargetRearArc && !situation.unphaseUnsafe) {
                ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.PHASE_ATTACK_RUN_IN_GOOD_SPOT, 0.5f)
            } else {
                ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.PHASE_ATTACK_RUN, 0.5f)
            }
        }

        if (toggleGuardElapsed < TOGGLE_GUARD_SEC) return
        when (order) {
            PhaseOrder.DIVE -> {
                toggleCloak(ship)
                // 绕后意图布防：绕后下潜命中完整闸门且开关指令真正发出时才开启意图窗口
                // （闸门与 decide 共用 [isFlankDive]；开关被守卫拦住时不空布防）
                if (isFlankDive(situation)) {
                    // 窗口按布防距离缩放：穿透机动的时间预算就是位移预算（见 flankIntentWindowSec）
                    flankIntentRemaining = flankIntentWindowSec(situation.threatDistance)
                    flankIntentWindowArmed = flankIntentRemaining
                    log.info(
                        "[GravityPhaseAI] ${ship.name} 绕后意图布防：目标低机动，" +
                                "PHASE_ATTACK_RUN 驱动穿透窗口 ${"%.1f".format(flankIntentRemaining)}s" +
                                "（距离 ${"%.0f".format(situation.threatDistance)}su）",
                    )
                }
            }

            PhaseOrder.SURFACE -> {
                // 上浮决策留痕：定位相位期过短/意外上浮的判定入口（低频事件，一场战斗十余次）
                log.info(
                    "[GravityPhaseAI] ${ship.name} 上浮：phaseTime=${"%.1f".format(situation.phaseActiveTime)}s " +
                            "hardFlux=${"%.2f".format(situation.hardFluxLevel)} engaged=${situation.engagedEnemyNear} " +
                            "near=${"%.0f".format(situation.incomingNearDamage)} soon=${"%.0f".format(situation.incomingSoonDamage)} " +
                            "coverage=${situation.weaponCoverage} rearArc=${situation.inTargetRearArc} " +
                            "intent=${situation.flankIntentActive} systemReady=${situation.systemReady} " +
                            "weaponsReady=${"%.2f".format(situation.weaponsReadyFrac)}",
                )
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
        val incoming = FloatArray(3)
        estimateIncoming(engine.projectiles, ship, incoming, collectFriendly = false)
        estimateIncoming(engine.missiles, ship, incoming, collectFriendly = false)
        estimateBeamThreat(engine, ship, incoming, collectFriendly = false)

        // 友军火力只计 soon 窗口（防御性下潜输入）：同口径采样取 owner == 本舰一侧
        val friendlyIncoming = FloatArray(3)
        estimateIncoming(engine.projectiles, ship, friendlyIncoming, collectFriendly = true)
        estimateIncoming(engine.missiles, ship, friendlyIncoming, collectFriendly = true)
        estimateBeamThreat(engine, ship, friendlyIncoming, collectFriendly = true)

        val threat = pickThreat(engine, ship, target)
        // 机动性取基础值：modifiedValue 会被零辐能加速/燃驱等瞬时增益污染——敌舰停火
        // （相位中的本舰不可被瞄准）后零辐能加速生效，modified 口径会把低机动目标误判成高机动
        val lowMobility = threat != null &&
                isLowMobilityTarget(
                    threat.mutableStats.maxSpeed.baseValue,
                    threat.mutableStats.acceleration.baseValue,
                    threat.mutableStats.maxTurnRate.baseValue,
                )
        val threatDist = if (threat == null) Float.MAX_VALUE
        else kotlin.math.sqrt(distanceSq(ship.location, threat.location))
        // 主威胁看向本舰的绝对方位：侧后薄弱区与正脸火力轴共用同一 bearing
        val threatToUsBearing = threat?.let {
            Math.toDegrees(
                kotlin.math.atan2(
                    (ship.location.y - it.location.y).toDouble(),
                    (ship.location.x - it.location.x).toDouble(),
                )
            ).toFloat()
        }
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
            targetLowMobility = lowMobility,
            inTargetRearArc = threat != null &&
                    angleDiffAbs(threat.facing, threatToUsBearing!!) >= REAR_ARC_MIN_DIFF,
            weaponCoverage = if (threat == null) 0 else weaponCoverageOn(ship, threat),
            friendlyCatchDamage = estimateFriendlyCatch(engine, ship),
            // 绕后意图生效口径：窗口未过期、相位中、且主威胁未明确换成高机动舰。
            // 威胁短暂掉出交战圈（threat == null）不取消意图——布防闸在 isFlankDive
            // 一次性把关，途中抖动取消会让布防早夭（实测 zw101 四次布防三次以
            // 上浮时 engaged=false 夭折，相位白潜）
            flankIntentActive = flankIntentRemaining > 0f && phased && (threat == null || lowMobility),
            flankIntentWindowSec = flankIntentWindowArmed,
            threatDistance = threatDist,
            unphaseUnsafe = isUnphaseUnsafe(engine, ship),
            surfaceTooClose = isSurfaceTooClose(engine, ship),
            // 上浮弹幕口径：敌方 soon+near+弹幕窗口三段合并，叠加友方 soon——
            // 上浮僵直期撞上任何一侧的已在途火力都是承伤
            incomingSurfaceDamage = incoming[0] + incoming[1] + incoming[2] + friendlyIncoming[0],
            threatFrontAxisClose = threat != null && threatDist <= SURFACE_FRONT_AXIS_MAX_DIST &&
                    angleDiffAbs(threat.facing, threatToUsBearing!!) <= SURFACE_FRONT_AXIS_HALF_ARC,
            incomingFriendlySoonDamage = friendlyIncoming[0],
        )
    }

    /** 上浮贴脸采样：最近敌舰间距进入双方碰撞半径和的 [SURFACE_PROXIMITY_FRAC] 倍内（只计有效敌舰，残骸不构成火力威胁）。 */
    private fun isSurfaceTooClose(engine: CombatEngineAPI, ship: ShipAPI): Boolean {
        for (other in engine.ships) {
            if (!isValidEnemyShip(ship, other)) continue
            val limit = (other.collisionRadius + ship.collisionRadius) * SURFACE_PROXIMITY_FRAC
            if (distanceSq(ship.location, other.location) < limit * limit) return true
        }
        return false
    }

    /**
     * 上浮落点重叠判定：本舰碰撞圈与任一非战机舰船深度重叠时，原版斗篷拒绝退相位
     * （canBeDeactivated 的 locationSafe 口径：间距 < 双方碰撞半径和 × [UNPHASE_UNSAFE_OVERLAP_FRAC]）。
     * 小行星不查：原版对环带小行星豁免而公开 API 无法区分环带归属，实测唯一触发源是舰船重叠；
     * hulk 不排除——残骸物理上同样占据落点。
     */
    private fun isUnphaseUnsafe(engine: CombatEngineAPI, ship: ShipAPI): Boolean {
        for (other in engine.ships) {
            if (other === ship || other.isFighter) continue
            if (other.parentStation === ship) continue
            val limit = (other.collisionRadius + ship.collisionRadius) * UNPHASE_UNSAFE_OVERLAP_FRAC
            if (distanceSq(ship.location, other.location) < limit * limit) return true
        }
        return false
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
     *
     * collectFriendly=true 时改采友军舰船（不含自舰）光束：光束伤害即时落地，
     * 持续/爆发光束统一折算进 soon 窗口，仅供防御性下潜使用，不参与输出窗口判断。
     */
    private fun estimateBeamThreat(engine: CombatEngineAPI, ship: ShipAPI, out: FloatArray, collectFriendly: Boolean) {
        for (source in engine.ships) {
            val isThreatSource = if (collectFriendly) {
                source !== ship && source.owner == ship.owner &&
                        source.isAlive && !source.isHulk && !source.isFighter
            } else {
                isValidEnemyShip(ship, source)
            }
            if (!isThreatSource) continue
            for (weapon in source.allWeapons) {
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
                } else if (collectFriendly) {
                    out[0] += beamThreatNear(weapon.derivedStats.dps)
                } else {
                    out[1] += beamThreatNear(weapon.derivedStats.dps)
                }
            }
        }
    }

    /**
     * 来袭伤害估计（三窗口累加进 [out]：[0]=紧急窗口，[1]=威胁窗口，[2]=上浮弹幕窗口）：
     * 弹体朝本舰的分速度超阈值、按当前弹道在窗口内到达且横向偏差落在碰撞圈余量内才计入；
     * 忽略本舰机动，窗口短（≤2.0s）口径足够。
     * 制导导弹（含龙炎 DEM 等跟踪武器）放宽口径：会自行修正弹道，跳过横向偏差检查，
     * 接近速度取其极速的一半兜底（当前速度不代表命中时刻速度）。
     *
     * collectFriendly=true 时改采友方弹药（owner 与本舰相同）：友军火力只计 soon 窗口
     * （防御性下潜输入），eta 超出 soon 窗口的友方弹药不计入。
     */
    private fun estimateIncoming(
        projectiles: List<DamagingProjectileAPI>,
        ship: ShipAPI,
        out: FloatArray,
        collectFriendly: Boolean,
    ) {
        val shipLoc = ship.location
        val radius = ship.collisionRadius
        for (proj in projectiles) {
            if ((proj.owner == ship.owner) != collectFriendly || proj.isFading || proj.isExpired) continue
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
            if (eta < 0f || eta > SURFACE_WINDOW_SEC) continue

            if (!guided) {
                // 命中时刻弹体位置与本舰中心的横向偏差：落在碰撞圈余量内才算会命中
                val hitX = proj.location.x + vel.x * eta - shipLoc.x
                val hitY = proj.location.y + vel.y * eta - shipLoc.y
                if (hitX * hitX + hitY * hitY > (radius + MISS_MARGIN) * (radius + MISS_MARGIN)) continue
            }

            if (eta <= SOON_WINDOW_SEC) {
                out[0] += proj.damageAmount
            } else if (!collectFriendly) {
                if (eta <= NEAR_WINDOW_SEC) {
                    out[1] += proj.damageAmount
                } else {
                    out[2] += proj.damageAmount
                }
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
