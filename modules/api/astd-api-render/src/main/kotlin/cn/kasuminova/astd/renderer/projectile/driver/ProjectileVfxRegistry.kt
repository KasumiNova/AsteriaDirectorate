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

    private val builders = ConcurrentHashMap<String, () -> ProjectileVfx>()

    /** 注册一个 projectileSpecId 的特效构建函数；同 id 后注册覆盖先注册。 */
    fun register(projectileSpecId: String, builder: () -> ProjectileVfx) {
        builders[projectileSpecId] = builder
    }

    fun has(projectileSpecId: String): Boolean = builders.containsKey(projectileSpecId)

    /** 现构建一份新蓝图 + 策略；未注册的 spec 返回 null（调用方回落旧管线）。 */
    fun build(projectileSpecId: String): ProjectileVfx? = builders[projectileSpecId]?.invoke()
}
