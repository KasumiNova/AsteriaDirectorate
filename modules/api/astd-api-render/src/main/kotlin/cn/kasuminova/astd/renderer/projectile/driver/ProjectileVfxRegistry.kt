package cn.kasuminova.astd.renderer.projectile.driver

import java.util.concurrent.ConcurrentHashMap

/**
 * 按 projectileSpecId 注册并构建弹体特效。
 *
 * 注册表支持并发访问。构建函数由调用方在战斗主线程执行，每次调用都会产生新的蓝图和策略。
 */
object ProjectileVfxRegistry {

    private val builders = ConcurrentHashMap<String, (Float?) -> ProjectileVfx>()

    /**
     * 注册特效构建函数，同 id 后注册覆盖先注册。
     * 构建函数接收武器面板射程，没有武器来源时接收 null。
     */
    fun register(projectileSpecId: String, builder: (Float?) -> ProjectileVfx) {
        builders[projectileSpecId] = builder
    }

    /** 返回是否已注册 [projectileSpecId]。 */
    fun has(projectileSpecId: String): Boolean = builders.containsKey(projectileSpecId)

    /**
     * 调用构建函数并返回新的蓝图与策略，未注册时返回 null。
     * @param weaponRangeSu 武器面板射程，没有武器来源时为 null
     */
    fun build(projectileSpecId: String, weaponRangeSu: Float? = null): ProjectileVfx? =
        builders[projectileSpecId]?.invoke(weaponRangeSu)
}
