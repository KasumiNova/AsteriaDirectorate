package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.math.roundToInt

/**
 * 引力磁暴发生器（密蒙级 ZW-002 舰船系统，系统 id：astd_grav_storm）的机制数值声明与纯函数
 * （原版「量子干扰」acausaldisruptor / AcausalDisruptorStats 的增强基线）。
 *
 * 动机：充能窗口（2s 下限 / 4s 上限）、充能前段相位锁定（[PHASE_LOCKOUT_SECONDS]）、
 * 锥状锁定（60° 锥 × 基础射程，含相位单位与战机——战机按护卫舰档 50% 打击折算、
 * 过载对齐护卫舰档）、按舰级的电弧数量区间、多目标电弧衰减、强制过载时长插值、
 * 过载触发的硬辐能→软辐能转化（[hardToSoftPerSecond]：按被过载目标舰级累加比例，
 * 对释放舰船自身硬辐能一次性结算，总辐能不变）、
 * 充能期装饰电弧渐变（[chargeArcCount]/[chargeArcInterval]）、
 * 单发电弧伤害与系统期间伤害减免的难度三锚点集中在此声明，
 * 供系统脚本每帧实时解析（LunaLib 设置变更即时生效），并由单元测试直接驱动。
 *
 * 玩家来源（owner == 0）按我方档位取值（默认砺刃 v2，见 DifficultyTuning.valueFor）。
 */
object GravStormTuning {

    /** 系统注册 id（csv/.system 接线由装配侧完成）。 */
    const val SYSTEM_ID = "astd_grav_storm"

    /** 锥状锁定基础射程（su），受舰船 mutableStats.systemRangeBonus 加成折算。 */
    const val BASE_RANGE = 1200f

    /** 锥状锁定半角（度）：舰船前方 ±30°，即 60° 锥。 */
    const val CONE_HALF_ANGLE_DEG = 30f

    /** 充能时长上下限（秒）：充能 ≤ [MIN_CHARGE_SECONDS] 提前结束不释放电弧；充能时长占比驱动过载插值。 */
    const val MAX_CHARGE_SECONDS = 4f
    const val MIN_CHARGE_SECONDS = 2f

    /**
     * 相位锁定时长（秒，口径等同 [MIN_CHARGE_SECONDS]）：系统 IN 状态且充能不足该时长期间，
     * 本舰相位系统不可激活（脚本每帧把相位 cloak 压入 COOLDOWN 并钉住小余量，见
     * [PHASE_LOCKOUT_COOLDOWN_REMAINING]；原版 ChargeTracker 实证 COOLDOWN 态按键不激活，
     * 且相位脚本 unapply 即撤 phased——forceState(IDLE) 口径下 Ship.advance 同帧
     * system→cloak 顺序会让「IDLE+按键→IN」在同帧复活，压不住，已弃用）；
     * 达到该时长后恢复「充能期间进相位 → 中止充能进冷却」的既有行为。
     */
    const val PHASE_LOCKOUT_SECONDS = MIN_CHARGE_SECONDS

    /**
     * 相位锁定压制的残留冷却（秒）：锁定期间每帧把相位 cloak 剩余冷却钉为本值
     * （ShipSystemAPI.setCooldownRemaining → ChargeTracker.startCooldown 一步置 COOLDOWN）；
     * 停止压制后余量自行消退归 IDLE（≤ 本值），不污染正常相位循环；cloak 处于
     * 更长的自然冷却时不改写（锁定效果本就成立，不缩短既有冷却）。
     */
    const val PHASE_LOCKOUT_COOLDOWN_REMAINING = 0.1f

    /** 释放窗口（秒，对应 .system 的 chargedown/out 段）：电弧在释放瞬间一次性全部打出，窗口存续期即系统期间伤害减免的存续期。 */
    const val RELEASE_WINDOW_SECONDS = 1.5f

    /** 系统冷却（秒，文档口径；实际生效值以 ship_systems.csv 为准）。 */
    const val COOLDOWN_SECONDS = 24f

    /** 激活代价比例（镜像 CSV entry `f/u (base cap)` = 0.2，实际结算由原版 ChargeTracker
     *  在 IDLE→IN 瞬间以软辐能一次性计入）；本常量仅供 [GravStormSystemAI] 辐能余量估算。 */
    const val ACTIVATION_FLUX_FRACTION = 0.2f

    /** 每多锁定一个目标的总电弧数衰减与上限（-10%/个，最多 -50%）。 */
    const val TARGET_COUNT_PENALTY_PER_EXTRA = 0.10f
    const val TARGET_COUNT_PENALTY_CAP = 0.50f

    /** 单发电弧能量伤害（v1 150 / v2 300 / v5 600，三锚点 LINEAR）。 */
    val ARC_ENERGY_DAMAGE = ScalingEntry(150f, 300f, 600f)

    /** 单发电弧 EMP 伤害（v1 300 / v2 600 / v5 1200）。 */
    val ARC_EMP_DAMAGE = ScalingEntry(300f, 600f, 1200f)

    /** 系统释放期间（充能 + 释放窗口）舰船全类型伤害减免（v1 25% / v2 50% / v5 90%）。 */
    val DAMAGE_TAKEN_REDUCTION = ScalingEntry(0.25f, 0.50f, 0.90f)

    /** 强制过载满充能（4s）时长锚点（秒），按舰级分档；半充能（2s）取半值（见 [overloadDuration]）。 */
    val OVERLOAD_FRIGATE = ScalingEntry(0.5f, 1f, 1.5f)
    val OVERLOAD_DESTROYER = ScalingEntry(1f, 2f, 3f)
    val OVERLOAD_CRUISER = ScalingEntry(1f, 2f, 3f)
    val OVERLOAD_CAPITAL = ScalingEntry(2f, 3f, 6f)

    /** 一次释放所需的全部机制数值（难度解析结果；最终口径，直接可用）。 */
    data class Values(
        /** 单发电弧能量伤害。 */
        val arcEnergyDamage: Float,
        /** 单发电弧 EMP 伤害。 */
        val arcEmpDamage: Float,
        /** 系统期间全类型伤害减免比例（写 stat 时乘 1 - 该值）。 */
        val damageTakenReduction: Float,
        /** 各级舰满充能强制过载时长（秒）。 */
        val overloadFrigate: Float,
        val overloadDestroyer: Float,
        val overloadCruiser: Float,
        val overloadCapital: Float,
    )

    /** 难度取值唯一入口：玩家阵营按我方档位（默认砺刃 v2）映射，其余阵营按轨一 k_s 映射。 */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        arcEnergyDamage = tuning.valueFor(ARC_ENERGY_DAMAGE, isPlayer),
        arcEmpDamage = tuning.valueFor(ARC_EMP_DAMAGE, isPlayer),
        damageTakenReduction = tuning.valueFor(DAMAGE_TAKEN_REDUCTION, isPlayer),
        overloadFrigate = tuning.valueFor(OVERLOAD_FRIGATE, isPlayer),
        overloadDestroyer = tuning.valueFor(OVERLOAD_DESTROYER, isPlayer),
        overloadCruiser = tuning.valueFor(OVERLOAD_CRUISER, isPlayer),
        overloadCapital = tuning.valueFor(OVERLOAD_CAPITAL, isPlayer),
    )

    /**
     * 目标舰级的基础电弧数量区间（护卫舰 2~4 / 驱逐舰 4~8 / 巡洋舰 8~16 / 主力舰 12~24）；
     * 战机按护卫舰档（2~4，单发伤害另经 [FIGHTER_DAMAGE_MULT] 折算）；
     * 其余非舰船级返回 null（调用侧过滤）。
     */
    fun arcBaseCountRange(hullSize: ShipAPI.HullSize?): IntRange? = when (hullSize) {
        ShipAPI.HullSize.FIGHTER -> 2..4
        ShipAPI.HullSize.FRIGATE -> 2..4
        ShipAPI.HullSize.DESTROYER -> 4..8
        ShipAPI.HullSize.CRUISER -> 8..16
        ShipAPI.HullSize.CAPITAL_SHIP -> 12..24
        else -> null
    }

    /** 满充能过载时长锚点按舰级查值（战机对齐护卫舰档）；其余非舰船级返回 null（调用侧过滤）。 */
    fun overloadAnchor(values: Values, hullSize: ShipAPI.HullSize?): Float? = when (hullSize) {
        ShipAPI.HullSize.FIGHTER -> values.overloadFrigate
        ShipAPI.HullSize.FRIGATE -> values.overloadFrigate
        ShipAPI.HullSize.DESTROYER -> values.overloadDestroyer
        ShipAPI.HullSize.CRUISER -> values.overloadCruiser
        ShipAPI.HullSize.CAPITAL_SHIP -> values.overloadCapital
        else -> null
    }

    /** 战机目标的单发电弧伤害系数（护卫舰档打击效果的 50%；电弧数量与过载时长对齐护卫舰档）。 */
    const val FIGHTER_DAMAGE_MULT = 0.5f

    /**
     * 硬辐能→软辐能转化的单目标比例贡献（纯函数）：被转化硬辐能的是释放舰船自身——
     * 每令一个敌对目标承受 1 秒强制过载，按其舰级档位向总转化比例累加一份
     * （本函数 = [hardToSoftPerSecond] × 过载时长），所有目标的比例贡献累加为总转化比例，
     * 乘以释放舰船当前硬辐能对释放舰船一次性结算（总辐能不变，
     * 设计意图：磁暴以自身辐能形态劣化为代价换取群体过载）。
     * 按舰级分档：战机 0.25% / 护卫舰 1% / 驱逐舰 2% / 巡洋舰 3% / 主力舰 4%；
     * 未列舰级返回 0（调用侧只对可锁定舰级结算）。
     */
    fun hardToSoftPerSecond(hullSize: ShipAPI.HullSize?): Float = when (hullSize) {
        ShipAPI.HullSize.FIGHTER -> 0.0025f
        ShipAPI.HullSize.FRIGATE -> 0.01f
        ShipAPI.HullSize.DESTROYER -> 0.02f
        ShipAPI.HullSize.CRUISER -> 0.03f
        ShipAPI.HullSize.CAPITAL_SHIP -> 0.04f
        else -> 0f
    }

    /** 单目标对总转化比例的贡献（纯函数）：目标舰级每秒比例（[hardToSoftPerSecond]）× 过载时长。 */
    fun hardToSoftRatio(hullSize: ShipAPI.HullSize?, overloadSeconds: Float): Float =
        hardToSoftPerSecond(hullSize) * overloadSeconds

    /** 充能期装饰电弧：每波数量随充能进度线性渐变（0% → [CHARGE_ARC_COUNT_MIN]，100% → [CHARGE_ARC_COUNT_MAX]）。 */
    const val CHARGE_ARC_COUNT_MIN = 2
    const val CHARGE_ARC_COUNT_MAX = 8

    /** 充能期装饰电弧：生成间隔随充能进度线性缩短（0% → 0.5s，100% → 0.1s）。 */
    const val CHARGE_ARC_INTERVAL_MAX_SECONDS = 0.5f
    const val CHARGE_ARC_INTERVAL_MIN_SECONDS = 0.1f

    /** 充能期装饰电弧每波数量（纯函数）：随充能进度（0-1，clamp）线性渐变后四舍五入。 */
    fun chargeArcCount(progress: Float): Int =
        (CHARGE_ARC_COUNT_MIN + (CHARGE_ARC_COUNT_MAX - CHARGE_ARC_COUNT_MIN) * progress.coerceIn(0f, 1f)).roundToInt()

    /** 充能期装饰电弧生成间隔秒（纯函数）：随充能进度（0-1，clamp）线性缩短。 */
    fun chargeArcInterval(progress: Float): Float =
        CHARGE_ARC_INTERVAL_MAX_SECONDS + (CHARGE_ARC_INTERVAL_MIN_SECONDS - CHARGE_ARC_INTERVAL_MAX_SECONDS) * progress.coerceIn(0f, 1f)

    /** 多目标电弧衰减（纯函数）：每多锁定一个目标总电弧数 -10%，最多 -50%（1 个目标不衰减）。 */
    fun targetCountMultiplier(targetCount: Int): Float =
        (1f - TARGET_COUNT_PENALTY_PER_EXTRA * (targetCount - 1).coerceAtLeast(0))
            .coerceAtLeast(1f - TARGET_COUNT_PENALTY_CAP)

    /** 单目标最终电弧数（纯函数）：基础数 × 多目标衰减，四舍五入，下限 1。 */
    fun finalArcCount(baseCount: Int, targetCount: Int): Int =
        (baseCount * targetCountMultiplier(targetCount)).roundToInt().coerceAtLeast(1)

    /** 充能时长占比（纯函数）：2s→0、4s→1 线性，越界钳制。 */
    fun chargeNorm(chargeSeconds: Float): Float =
        ((chargeSeconds - MIN_CHARGE_SECONDS) / (MAX_CHARGE_SECONDS - MIN_CHARGE_SECONDS)).coerceIn(0f, 1f)

    /**
     * 强制过载时长（纯函数）：按舰级锚点与充能占比插值——
     * 充能 2s（norm 0）取锚点半值下限，充能 4s（norm 1）取锚点上限。
     */
    fun overloadDuration(anchor: Float, chargeNorm: Float): Float =
        anchor * (0.5f + 0.5f * chargeNorm)

    /** 相位锁定判定（纯函数）：充能时长不足 [PHASE_LOCKOUT_SECONDS] 期间相位系统被抑制。 */
    fun phaseLockoutActive(chargeSeconds: Float): Boolean = chargeSeconds < PHASE_LOCKOUT_SECONDS

    /** 锥状锁定判定（纯函数）：目标相对方位角与舰船朝向的角差 ≤ [CONE_HALF_ANGLE_DEG]。 */
    fun isInCone(angleDiffDeg: Float): Boolean = angleDiffDeg <= CONE_HALF_ANGLE_DEG
}
