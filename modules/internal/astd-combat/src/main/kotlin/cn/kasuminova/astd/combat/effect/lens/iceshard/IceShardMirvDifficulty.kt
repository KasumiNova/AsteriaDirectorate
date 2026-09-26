package cn.kasuminova.astd.combat.effect.lens.iceshard

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingMap
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl

/**
 * 源生冰晶 MIRV 的机制数值锚点与难度取值入口（purple/30-superlative.md）。
 *
 * 动机：冻结持续伤害 / 冻结增伤两条三锚点集中登记，玩家固定 v2 的取值口径与轨一 k_s 映射
 * 只有一处实现（与辉星/摧锋同型，结算时取值，LunaLib 热变更即时生效）。
 * 分裂面板常量（15 枚 / 总伤 6000 / 锥角 20° 等）为固定值，不随难度缩放。
 *
 * 数值缩放口径（90 计划全局约定）：敌方按轨一 k_s 三锚点线性映射；玩家来源（owner == 0）固定 v2。
 */
object IceShardMirvDifficulty {

    /** 冻结持续伤害占子射弹伤害的比例：迟暮 25% / 砺刃 50% / 破晓 100%。 */
    val DOT_RATIO = ScalingEntry(0.25f, 0.50f, 1.00f, ScalingMap.LINEAR)

    /** 冻结增伤（命中点周围承伤提高）：迟暮 15% / 砺刃 25% / 破晓 50%。 */
    val AMP = ScalingEntry(0.15f, 0.25f, 0.50f, ScalingMap.LINEAR)

    /** 分裂距离（su）：母弹距目标此距离内自爆分裂。 */
    const val SPLIT_RANGE = 600f

    /** 分裂豁免时间（秒）：发射后此时间内母弹不分裂（贴脸甩射的载舰安全窗口）。 */
    const val SPLIT_IMMUNITY_SECONDS = 1f

    /** 冰晶射弹数量。 */
    const val SHARD_COUNT = 15

    /** 分裂锥全角（度）。 */
    const val SPLIT_CONE_DEG = 20f

    /** 冰晶总伤（[SHARD_COUNT] 枚合计恒定）。 */
    const val TOTAL_DAMAGE = 6000f

    /** 单枚冰晶伤害区间。 */
    const val SHARD_DAMAGE_MIN = 200f
    const val SHARD_DAMAGE_MAX = 800f

    /** 冰晶基准弹速（su/s，±25% 浮动随伤害相关，见 [IceShardMirvMath.shardSpeed]）。 */
    const val SHARD_BASE_SPEED = 1000f

    /** 冰晶射程（su）。 */
    const val SHARD_RANGE = 1000f

    /** 冻结附着时长（秒）。 */
    const val ATTACH_DURATION = 5f

    /** 冻结伤害周期（秒）。 */
    const val TICK_INTERVAL = 1f

    /** 冻结增伤半径（su）。 */
    const val AMP_RADIUS = 15f

    /** 难度统一取值：玩家来源（[sourceOwner] == 0）固定 v2，否则按轨一 k_s 映射。 */
    fun resolve(entry: ScalingEntry, sourceOwner: Int): Float =
        resolve(DifficultyTuningImpl, entry, sourceOwner)

    /** 可注入 [DifficultyTuning] 的取值入口（单元测试与运行共用同一路径）。 */
    fun resolve(tuning: DifficultyTuning, entry: ScalingEntry, sourceOwner: Int): Float =
        if (sourceOwner == 0) entry.v2 else tuning.value(entry)
}
