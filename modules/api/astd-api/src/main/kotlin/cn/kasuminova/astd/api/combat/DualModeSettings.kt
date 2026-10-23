package cn.kasuminova.astd.api.combat

/**
 * 提供双模式切换器的当前设置。
 *
 * 属性由实现随设置回调更新，调用方不得缓存。
 */
interface DualModeSettings {

    /**
     * 自动模式的舰船是否使用 `no_auto_penalty` 标签。
     *
     * 该标签同时免除自动化点数与 Automated 船插的最大 CR 惩罚。
     */
    val automatedModeExemptFromAutoPoints: Boolean
}
