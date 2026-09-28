package cn.kasuminova.astd.combat.hullmods.base

import cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityStabilizerHullMod.Companion.UNITY_EPSILON
import cn.kasuminova.astd.combat.hullmods.base.ASTDSingularityStabilizerHullMod.Companion.resolveTimeAnchor
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.campaign.ids.HullMods

/**
 * 奇点稳定器（联制线两舰内置）：时流锚定船插（机制见 `docs/design/ships/20-joint.md` §舰船插件）。
 *
 * 三件机制：
 * 1. 峰值时间不随时流变化加速/减慢消耗——原版 `Ship.advance` 的 `timeDeployed` 推进与 CR 扣减
 *    均使用已被本舰 timeMult 缩放过的帧时长（advanceInCombat 先于该结算块调用），因此：
 *    - 回卷 `timeDeployed`：本帧将被推进 `amount`（含时流缩放），预先减去 `amount × (1 - 1/timeMult)`，
 *      使净推进恒等于真实秒数（时流 200% 时峰值不再以 2 倍速度流逝）；
 *    - 补偿 CR 扣减：`crLossPerSecondPercent` 乘 `1/timeMult`，使单位真实时间的 CR 损失不变。
 *    两项锚定仅在接敌状态（`areSignificantEnemiesInRange`）生效：原版脱战时跳过推进与 CR 结算，
 *    无条件回卷会把峰值时间「退款」。
 * 2. 时间流速下限钳制 100%——每帧先 unmodify 再读 `timeMult.modifiedValue`，
 *    若被外部减益压到 1 以下则乘回 `1/mv`（免疫时流减益，含敌方视界变速等）。
 *    钳制生效的帧不再做锚定补偿：引擎结算所用的时流已被钳回 1，amount 即真实秒数，
 *    再乘 1/mv 会双重补偿（峰值反而以 1/mv 倍加速流逝，审查实测推演确认）。
 * 3. 禁止安装安全协议超驰——互斥走 [IncompatibleHullmodStripper] 延迟清理：
 *    applyEffectsBeforeShipCreation 只检测入队（原版 updateStatsForOpCosts 实时迭代 hullMods
 *    期间回调本方法，结构性 removeMod 必抛 CME），五路清理延后到 AfterCreation/advanceInCombat/
 *    战役每帧 drainer 安全点执行；原版候选不受控无法灰掉，接受「装上即清理」行为
 *    （SKILL: hullmod-incompatibility-guidelines）。
 *
 * 锚定判定数学抽为纯函数 [resolveTimeAnchor]（单元测试直接驱动，见 JointTuningTest 同族测试）。
 */
class ASTDSingularityStabilizerHullMod : BaseHullMod() {

    companion object {
        /** stat 源 id：CR 扣减补偿与时流下限钳制共用。 */
        private const val MOD_ID = "astd_singularity_stabilizer"

        /** 禁装船插列表（集中定义）：安全协议超驰。 */
        private val FORBIDDEN_HULLMOD_IDS = setOf(HullMods.SAFETYOVERRIDES)

        /** 「时流恰为 1」的浮点容差（避免外部叠乘落在 1.0±ε 时补偿/不补偿逐帧抖动）。 */
        private const val UNITY_EPSILON = 0.001f

        /**
         * 一帧锚定判定（纯函数，唯一入口）。
         *
         * @property clampMult 时流下限钳制乘区（null = 无需钳制）；写入 `stats.timeMult`
         * @property anchor 峰值/CR 锚定系数 `1/timeMult`（null = 无需锚定）；
         *   钳制生效时恒 null——引擎结算时流已被钳回 1，再补偿即双重补偿
         */
        internal data class TimeAnchor(val clampMult: Float?, val anchor: Float?)

        /**
         * 判定入口：raw ≤ 0（零时流异常态）不钳不补（1/0 无穷大防线）；
         * raw < 1 钳制优先、锚定关闭；raw ≈ 1（±[UNITY_EPSILON]）不动；raw > 1 仅锚定。
         */
        internal fun resolveTimeAnchor(raw: Float): TimeAnchor = when {
            raw <= 0f -> TimeAnchor(null, null)
            raw < 1f - UNITY_EPSILON -> TimeAnchor(1f / raw, null)
            raw <= 1f + UNITY_EPSILON -> TimeAnchor(null, null)
            else -> TimeAnchor(null, 1f / raw)
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        // 只检测入队：本回调可能在原版 OP 结算的 hullMods 实时迭代内触发，结构性移除会抛 CME
        IncompatibleHullmodStripper.requestStrip(stats.variant, FORBIDDEN_HULLMOD_IDS, MOD_ID)
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        // ShipFactory 迭代 getAllMods() 快照，此处入队并立即清理是安全的
        IncompatibleHullmodStripper.requestStrip(ship.variant, FORBIDDEN_HULLMOD_IDS, MOD_ID)
        IncompatibleHullmodStripper.drainPending()
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        IncompatibleHullmodStripper.drainPending()
        if (amount <= 0f || ship.isHulk || !ship.isAlive) return
        val stats = ship.mutableStats

        // 先清掉本船插上一帧的修正，读外部修正后的净值
        stats.timeMult.unmodifyMult(MOD_ID)
        val decision = resolveTimeAnchor(stats.timeMult.modifiedValue)
        if (decision.clampMult != null) {
            stats.timeMult.modifyMult(MOD_ID, decision.clampMult)
        }

        val anchor = decision.anchor
        // 原版仅在接敌状态（enemyShipInRange）才推进 timeDeployed 并扣 CR；脱战时本帧无推进可回卷，
        // 无条件补偿会把峰值时间「退款」（时流越高退款越快，审查实测推演确认），故锚定仅在接敌时生效
        if (anchor == null || !ship.areSignificantEnemiesInRange()) {
            stats.crLossPerSecondPercent.unmodifyMult(MOD_ID)
            return
        }
        stats.crLossPerSecondPercent.modifyMult(MOD_ID, anchor)
        // timeDeployed 本帧将被推进 amount（含时流缩放），预回卷使净推进 = amount × anchor = 真实秒数。
        // ShipAPI 无 getTimeDeployed()，等值读取口为 getTimeDeployedForCRReduction()（经原版实现核实），
        // 写入侧无对应 getter 故用显式 setter 调用（Kotlin 属性语法要求读写成对）
        ship.setTimeDeployed(ship.timeDeployedForCRReduction - amount * (1f - anchor))
    }
}
