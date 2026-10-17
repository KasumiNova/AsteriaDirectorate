package cn.kasuminova.astd.combat.shipsystems.base

/**
 * 相位决策快照（纯数据）：一拍决策的完整输入，由
 * [cn.kasuminova.astd.combat.shipsystems.GravityPhaseSituationSampler] 采样填充，
 * 决策树（[PriorityPhaseTree]）只读消费，单测直接构造。
 *
 * 字段语义：既有字段与原 GravityPhaseCloakAI 内联实现一致，逐字段平移未改口径；
 * 迫近意图字段（targetDistance/effectiveWeaponRange/pressIntentActive）为后续新增，
 * 口径见各字段注释。
 */
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
    /** 相位斗篷剩余冷却（s；就绪时为 0，供 vent 压制冷却宽限口径使用）。 */
    val cloakCooldownRemaining: Float,
    /** 软辐能占比（fluxLevel - hardFluxLevel）。 */
    val softFluxLevel: Float,
    /** 下线武器占比（0~1；非装饰非系统槽武器中被禁用的比例，无武器时为 0）。 */
    val weaponsDisabledFrac: Float,
    /** 下线引擎占比（0~1；整舰熄火视为全部下线，无引擎时为 0）。 */
    val enginesDisabledFrac: Float,
    /** 主威胁目标是否为低机动舰（代理指标判定，见 GravityPhaseSituationSampler.isLowMobilityTarget）。 */
    val targetLowMobility: Boolean,
    /** 本舰是否已位于主威胁目标的侧后射界薄弱区。 */
    val inTargetRearArc: Boolean,
    /** 主威胁目标当前能瞄准本舰的武器数量。 */
    val weaponCoverage: Int,
    /** 若此刻入相位，穿透本舰的直射火力在友军接盘评估窗口内将误伤友军的估计伤害。 */
    val friendlyCatchDamage: Float,
    /** 绕后意图是否生效中（绕后下潜后保持相位机动穿透的窗口期）。 */
    val flankIntentActive: Boolean,
    /** 布防时武装的机动意图窗口长度（s；绕后/迫近意图共用，供强制上浮时长闸放宽推导）；意图未生效时取值不影响判定。 */
    val flankIntentWindowSec: Float,
    /** 当前攻击目标（ship.shipTarget 经有效性校验）的距离（su；无有效目标为 [Float.MAX_VALUE]。与限 1600su 交战圈的 threatDistance 不同，不限距离）。 */
    val targetDistance: Float,
    /** 有效武器射程（su）：非导弹、非装饰、非系统槽、未禁用且有弹药武器的最大射程（无此类武器为 0——纯导弹舰无迫近收益）。 */
    val effectiveWeaponRange: Float,
    /** 迫近意图是否生效中（迫近下潜后保持相位机动逼近目标有效射程的窗口期；到位/目标丢失/武器打光即由采样端收口失效，口径见 FlankAttackTree.pressIntentActive）。 */
    val pressIntentActive: Boolean,
    /** 主威胁目标距离（su；无威胁时为 [Float.MAX_VALUE]）。 */
    val threatDistance: Float,
    /** 上浮落点是否不安全：本舰碰撞圈与他舰深度重叠（原版斗篷此时拒绝退相位）。 */
    val unphaseUnsafe: Boolean,
    /** 上浮贴脸：最近敌舰间距进入双方碰撞半径和的贴脸判定系数倍内。 */
    val surfaceTooClose: Boolean,
    /** 上浮弹幕窗口内预计命中本舰的伤害合计（敌方三窗口 + 友方 soon）。 */
    val incomingSurfaceDamage: Float,
    /** 正脸火力轴：位于主威胁舰艏火力轴锥内且距离够近。 */
    val threatFrontAxisClose: Boolean,
    /** 绕后达成上浮的脱间距是否满足：与主威胁间距（含弹幕窗口相对速度外推最近接近）≥ 最小脱间距。 */
    val rearSurfaceDistanceSafe: Boolean,
    /** 强制上浮被落点安全闸按住的累计时长（s；达上限兜底放行）。 */
    val forcedSurfaceHoldSec: Float,
    /** 友军火力在 soon 窗口内命中本舰的估计伤害（友伤规避只触发防御性下潜）。 */
    val incomingFriendlySoonDamage: Float,
    /** 预计强制耗散时长（s；无法估计时为 [Float.MAX_VALUE]），战术强制耗散采样。 */
    val ventTimeSec: Float,
    /** 强制耗散速率倍率（stats 修正值），战术强制耗散阈值随其提升。 */
    val ventRateMult: Float,
    /** 是否正在强制耗散中。 */
    val venting: Boolean,
    /** 主威胁方向前方是否有友军承火（承火筛查距离内、方向锥内、比本舰更靠近敌舰）。 */
    val friendlyScreenAhead: Boolean,
    /** 赶路目标点（MOVEMENT_DEST 旗标）距离（su；无目标点时为 [Float.MAX_VALUE]）。 */
    val travelDestDistance: Float,
)

/** 相位指令：NONE 保持 / DIVE 下潜 / SURFACE 上浮。 */
internal enum class PhaseOrder { NONE, DIVE, SURFACE }
