package cn.kasuminova.astd.combat.effect.generic

import cn.kasuminova.astd.renderer.effect.system.ArcFlareEmissiveOverlayManager
import cn.kasuminova.astd.renderer.effect.system.ArcFlareEngineFlareManager
import cn.kasuminova.astd.renderer.effect.system.ArcFlareAfterimageManager
import cn.kasuminova.astd.renderer.effect.system.ASTDVectorThrustEngineManager
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.projectile.ASTDProjectileVfxRuntimePlugin
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI

/**
 * 战斗内 VFX 启动器：确保扫描式 dispatcher 被安装到 CombatEngine。
 *
 * 背景：
 * - 理论上 projectile weapon 的 onFireEffect 足够驱动 VFX。
 * - 但在某些数据/脚本加载异常、或 onFire 未被调用/被跳过的情况下，
 *   弹体贴图又被改成透明，会出现“弹体 + 曳光完全不可见”。
 *
 * 解决方案：
 * - 用一个极轻量的 EveryFrameWeaponEffect 作为入口，在战斗开始后尽早把扫描式插件装入引擎。
 * - 扫描式插件会遍历 engine.projectiles，为每个弹体按 projectileSpecId 分发并挂载 tracer/visual。
 */
internal object CombatVfxBootstrap {

    private val log = Global.getLogger(CombatVfxBootstrap::class.java)

    fun ensureInstalled(engine: CombatEngineAPI) {
        ASTDProjectileVfxRuntimePlugin.ensureInstalled(engine)
        // 新 RenderEntity 管线的每帧插件（仅已迁移 spec 走这条路，见 ProjectileVfxDriverPlugin / ProjectileVfxSpecs）。
        ProjectileVfxDriverPlugin.ensureInstalled(engine)

        // 顺手尝试让 BoxUtil 进入 ready（即便 BoxUtil 不可用，这里也不会炸）
        try {
            BoxUtilCombatVfx.ensureReady(engine)
        } catch (_: Throwable) {
        }

        // 舰体 emissive 覆盖层：目前用于 Arc Flare 整船发光贴图。
        try {
            ArcFlareEmissiveOverlayManager.ensureInstalled(engine)
        } catch (_: Throwable) {
        }

        try {
            ArcFlareEngineFlareManager.ensureInstalled(engine)
        } catch (_: Throwable) {
        }

        try {
            ArcFlareAfterimageManager.ensureInstalled(engine)
        } catch (_: Throwable) {
        }

        // ASTD 全系类矢量推进：按引擎贡献逐引擎调火焰强度。
        try {
            ASTDVectorThrustEngineManager.ensureInstalled(engine)
        } catch (_: Throwable) {
        }
    }

    private fun isDevModeSafe(): Boolean {
        return try {
            Global.getSettings().isDevMode
        } catch (_: Throwable) {
            false
        }
    }
}
