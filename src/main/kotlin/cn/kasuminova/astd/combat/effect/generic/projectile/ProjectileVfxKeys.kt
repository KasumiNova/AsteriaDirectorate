package cn.kasuminova.astd.combat.effect.generic.projectile

/**
 * VFX 去重/engine customData 使用的 key 集中管理。
 *
 * 目的：
 * - 同一枚弹体可能同时触发 weaponSpec 的 onFireEffect 与 projectileSpec 的 onFireEffect，
 *   或者同时被“扫描式”插件与 onFireEffect 处理；需要统一 key 以避免重复绑定。
 */
internal object ProjectileVfxKeys {

    /**
     * 标记：扫描式 dispatcher 已处理过该弹体（scan 的“只处理一次/回退 backoff”用）。
     *
     * 注意：此标记不应影响 onFire dispatcher（否则可能导致导弹 AI/VFX 链路被 scan 抢先短路）。
     */
    const val PROJECTILE_VFX_SCAN_MARK: String = "astd_projectile_vfx_scan_mark"

    /** 标记：扫描式 dispatcher 的短期锁（避免扫描插件被重复安装/同帧重入造成重复处理）。 */
    const val PROJECTILE_VFX_SCAN_LOCK: String = "astd_projectile_vfx_scan_lock"

    /**
     * 标记：onFire dispatcher 已处理过该弹体（用于去重：.wpn/.proj 双挂 onFireEffect 时只处理一次）。
     *
     * 注意：scan dispatcher 可以尊重它（作为兜底插件的定位），但反向不应该。
     */
    const val PROJECTILE_VFX_ONFIRE_MARK: String = "astd_projectile_vfx_onfire_mark"

    /** 标记：onFire dispatcher 的短期锁，避免同一帧/同一弹体重复进入 onFire 分发。 */
    const val PROJECTILE_VFX_ONFIRE_LOCK: String = "astd_projectile_vfx_onfire_lock"

    /** 标记：导弹 AI 是否已按 projectileSpecId 注入（避免被重复覆盖/重置状态）。 */
    const val PROJECTILE_AI_INSTALLED_MARK: String = "astd_projectile_ai_installed_mark"

    /** 标记：devMode 下仅提示一次（避免刷屏）。 */
    const val ENGINE_DEV_ONFIRE_ONCE: String = "astd_vfx_dev_onfire_once"
    const val ENGINE_DEV_SCAN_ONCE: String = "astd_vfx_dev_scan_once"

    /** 标记：该弹体已自带完整通用增强层，跳过全局 common fx 叠加。 */
    const val PROJECTILE_VFX_COMMON_FX_SKIP: String = "astd_projectile_vfx_common_fx_skip"

    /** 标记：无论 devMode 与否，仅记录一次日志（用于排查脚本是否触发）。 */
    const val ENGINE_LOG_ONFIRE_ONCE: String = "astd_vfx_log_onfire_once"
    const val ENGINE_LOG_SCAN_ONCE: String = "astd_vfx_log_scan_once"

    /** 标记：everyFrame bootstrap 是否触发过（用于确认 weapon everyFrameEffect 是否正常运行）。 */
    const val ENGINE_LOG_BOOTSTRAP_ONCE: String = "astd_vfx_log_bootstrap_once"
}
