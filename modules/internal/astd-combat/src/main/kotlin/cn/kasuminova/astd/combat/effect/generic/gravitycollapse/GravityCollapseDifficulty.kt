package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

/**
 * 引力坍缩炮的难度取值唯一入口（对照 `BurstFlowTuning` 既有口径）：
 * 玩家来源（owner == 0）固定 v2 设计基准；敌方来源按轨一 k_s 三锚点映射。
 *
 * 数值锚点按武器规格区分（大/中/小/PD），由各 [GravityCollapseOnHitConfig] 持有；
 * 本对象只负责「玩家/敌方/无来源」三分支的取值决策与无来源告警（WARN-once）。
 */
internal object GravityCollapseDifficulty {

    private val log = AstdLog.logger

    /** weapon.ship 为 null 的 WARN 只记一次（装配界面预览等场景会反复触发）。 */
    @Volatile
    private var nullSourceWarned = false

    /** 一次开火周期所需的全部难度解析结果。 */
    class ResolvedValues(
        val aoeDamageRatio: Float,
        val mobilityReduction: Float,
        val mobilityDuration: Float,
    )

    /**
     * @param sourceOwner 开火来源舰船的 owner（`weapon.ship?.owner`）；null 表示无来源
     * （装配预览等场景），保守取 v2 并 WARN-once。
     */
    fun resolve(
        tuning: DifficultyTuning,
        sourceOwner: Int?,
        config: GravityCollapseOnHitConfig,
        weaponId: String,
    ): ResolvedValues {
        if (sourceOwner == null && !nullSourceWarned) {
            nullSourceWarned = true
            log.warn("[ASTD] 引力坍缩炮：weapon.ship 为 null（weapon=$weaponId），难度数值保守取 v2 设计基准")
        }
        val isPlayer = sourceOwner == null || sourceOwner == 0
        return ResolvedValues(
            aoeDamageRatio = pick(tuning, isPlayer, config.aoeDamageRatio),
            mobilityReduction = pick(tuning, isPlayer, config.mobilityReduction),
            mobilityDuration = pick(tuning, isPlayer, config.mobilityDuration),
        )
    }

    /** 测试复位 WARN-once 状态；游戏运行路径不应调用。 */
    fun resetWarnStateForTests() {
        nullSourceWarned = false
    }

    private fun pick(tuning: DifficultyTuning, isPlayer: Boolean, entry: ScalingEntry): Float =
        if (isPlayer) entry.v2 else tuning.value(entry)
}
