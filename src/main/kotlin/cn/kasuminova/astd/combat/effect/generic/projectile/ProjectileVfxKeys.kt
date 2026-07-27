package cn.kasuminova.astd.combat.effect.generic.projectile

/**
 * VFX 去重/engine customData 使用的 key 集中管理。
 *
 * 目的：同一枚弹体可能同时触发 weaponSpec 的 onFireEffect 与 projectileSpec 的 onFireEffect，
 * 需要统一 key 以避免重复绑定。
 */
internal object ProjectileVfxKeys {

    /** 标记：onFire dispatcher 已处理过该弹体（用于去重：.wpn/.proj 双挂 onFireEffect 时只处理一次）。 */
    const val PROJECTILE_VFX_ONFIRE_MARK: String = "astd_projectile_vfx_onfire_mark"

    /** 标记：onFire dispatcher 的短期锁，避免同一帧/同一弹体重复进入 onFire 分发。 */
    const val PROJECTILE_VFX_ONFIRE_LOCK: String = "astd_projectile_vfx_onfire_lock"

    /** 标记：导弹 AI 是否已按 projectileSpecId 注入（避免被重复覆盖/重置状态）。 */

    /** 标记：该弹体已自带完整通用增强层，跳过全局 common fx 叠加。 */
    const val PROJECTILE_VFX_COMMON_FX_SKIP: String = "astd_projectile_vfx_common_fx_skip"

    /** 标记：无论 devMode 与否，仅记录一次日志（用于排查脚本是否触发）。 */
    const val ENGINE_LOG_ONFIRE_ONCE: String = "astd_vfx_log_onfire_once"

    /** 标记：everyFrame bootstrap 是否触发过（用于确认 weapon everyFrameEffect 是否正常运行）。 */
    const val ENGINE_LOG_BOOTSTRAP_ONCE: String = "astd_vfx_log_bootstrap_once"
}
