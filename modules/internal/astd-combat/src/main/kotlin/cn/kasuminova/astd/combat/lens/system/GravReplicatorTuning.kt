package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

/**
 * 引力空间复制器（舜华级 ZW-101 舰船系统）的机制数值声明与纯函数。
 *
 * 动机：复制体伤害比例、复制辐能比例的三锚点查值，以及复制调度时序（发射后 0.5s
 * 复制第一发、再过 0.5s 复制第二发）、激活辐能（基础最大辐能容量 10%）的结算口径
 * 集中在此声明，供系统脚本每帧实时解析（LunaLib 设置变更即时生效），
 * 并由单元测试直接驱动。
 *
 * 玩家来源（owner == 0）固定 v2（砺刃档），对照 GravityRiftTuning 既有口径。
 */
object GravReplicatorTuning {

    /** 激活时产生的软辐能占舰船基础最大辐能容量的比例（固定 10%，设计案无锚点）。 */
    const val ACTIVATION_FLUX_FRACTION = 0.10f

    /** 每发实弹的复制体数量（共两发复制体）。 */
    const val COPY_COUNT = 2

    /** 首发复制延迟（秒）：弹体发射后 0.5s 于原发射点复制第一发。 */
    const val FIRST_COPY_DELAY = 0.5f

    /** 复制间隔（秒）：第一发复制后再过 0.5s 复制第二发。 */
    const val COPY_INTERVAL = 0.5f

    /** 复制体伤害相对原弹体的比例（v1 35% / v2 50% / v5 100%，乘在弹体 damageAmount 上）。 */
    val REPLICA_DAMAGE_RATIO = ScalingEntry(0.35f, 0.50f, 1.00f)

    /** 每生成一发复制体给舰船附加的软辐能占武器单发辐能的比例（v1 60% / v2 50% / v5 20%）。 */
    val REPLICA_FLUX_RATIO = ScalingEntry(0.60f, 0.50f, 0.20f)

    /** 一次复制结算所需的全部机制数值（难度解析结果；最终比例口径，直接可用）。 */
    data class Values(
        /** 复制体伤害比例（乘在原弹体 damageAmount 上）。 */
        val damageRatio: Float,
        /** 单发复制附加软辐能占武器单发辐能的比例。 */
        val fluxRatio: Float,
    )

    /** 难度取值唯一入口：玩家固定 v2，否则按轨一 k_s 映射。 */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        damageRatio = pick(tuning, isPlayer, REPLICA_DAMAGE_RATIO),
        fluxRatio = pick(tuning, isPlayer, REPLICA_FLUX_RATIO),
    )

    /** 第 [copyIndex] 发（0 起）复制体的到期时刻（纯函数，相对弹体被扫描记录的秒数）。 */
    fun copyDueTime(copyIndex: Int): Float = FIRST_COPY_DELAY + copyIndex * COPY_INTERVAL

    /** 复制体伤害（纯函数）：原弹体伤害 × 复制伤害比例。 */
    fun replicaDamage(baseDamage: Float, ratio: Float): Float = baseDamage * ratio

    /** 单发复制附加软辐能（纯函数）：武器单发辐能 × 复制辐能比例。 */
    fun replicaFlux(fluxPerShot: Float, ratio: Float): Float = fluxPerShot * ratio

    /** 激活软辐能（纯函数）：舰船基础最大辐能容量 × [ACTIVATION_FLUX_FRACTION]。 */
    fun activationFlux(maxFluxBase: Float): Float = maxFluxBase * ACTIVATION_FLUX_FRACTION

    private fun pick(tuning: DifficultyTuning, isPlayer: Boolean, entry: ScalingEntry): Float =
        if (isPlayer) entry.v2 else tuning.value(entry)
}
