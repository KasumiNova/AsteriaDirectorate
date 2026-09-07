package cn.kasuminova.astd.renderer.projectile.driver

import java.util.concurrent.ConcurrentHashMap

/**
 * 弹体特效构建函数注册表（渲染专用 API 侧的运行时契约）。
 *
 * 武器专属定义（astd-combat 的 `ProjectileVfxSpecs`）在战斗装配时把构建函数注册进来；
 * 渲染驱动（astd-render 的 `ProjectileVfxDriverPlugin`）按 projectileSpecId 现查现构建，
 * 渲染侧由此不反向依赖武器定义模块。
 */
object ProjectileVfxRegistry {

    private val builders = ConcurrentHashMap<String, (Float?) -> ProjectileVfx>()

    /**
     * 注册一个 projectileSpecId 的特效构建函数；同 id 后注册覆盖先注册。
     * 构建函数参数为武器面板射程（世界单位；弹体无武器来源时为 null）——带长按射程比例
     * 派生的 spec 在构建期取该值，未提供时使用 spec 声明的固定带长。
     */
    fun register(projectileSpecId: String, builder: (Float?) -> ProjectileVfx) {
        builders[projectileSpecId] = builder
    }

    fun has(projectileSpecId: String): Boolean = builders.containsKey(projectileSpecId)

    /**
     * 现构建一份新蓝图 + 策略；未注册的 spec 返回 null（调用方回落旧管线）。
     * @param weaponRangeSu 武器面板射程（世界单位），供射程比例带长的 spec 派生；null 时用固定带长。
     */
    fun build(projectileSpecId: String, weaponRangeSu: Float? = null): ProjectileVfx? =
        builders[projectileSpecId]?.invoke(weaponRangeSu)
}
