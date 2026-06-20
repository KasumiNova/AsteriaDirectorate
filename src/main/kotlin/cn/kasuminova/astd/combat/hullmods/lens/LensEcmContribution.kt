package cn.kasuminova.astd.combat.hullmods.lens

/**
 * 透镜阵列核心·载人模式「情报中枢」ECM 累加（spec §3.1）。
 *
 * 动机：按友军吨位等级差异化提供 ECM（吨位越小贡献越大），鼓励集群作战。
 * 纯函数便于单测与平衡。返回值为 ECM 等级"分数"（0.05 = 5%）。
 */
object LensEcmContribution {

    const val FRIGATE_ECM = 0.02f
    const val DESTROYER_ECM = 0.015f
    const val CRUISER_ECM = 0.01f
    const val CAPITAL_ECM = 0.005f

    fun totalEcmFraction(frigates: Int, destroyers: Int, cruisers: Int, capitals: Int): Float =
        frigates.coerceAtLeast(0) * FRIGATE_ECM +
            destroyers.coerceAtLeast(0) * DESTROYER_ECM +
            cruisers.coerceAtLeast(0) * CRUISER_ECM +
            capitals.coerceAtLeast(0) * CAPITAL_ECM
}
