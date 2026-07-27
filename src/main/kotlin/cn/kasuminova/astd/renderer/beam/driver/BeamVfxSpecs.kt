package cn.kasuminova.astd.renderer.beam.driver

import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.impl.render.BeamAmbientComponent
import cn.kasuminova.astd.impl.render.BeamCoreComponent
import cn.kasuminova.astd.impl.render.BeamHelixParticleComponent
import cn.kasuminova.astd.impl.render.BeamMicroBeamComponent
import cn.kasuminova.astd.impl.render.BeamMuzzleComponent
import cn.kasuminova.astd.impl.render.BeamRingComponent
import cn.kasuminova.astd.impl.render.GcBeam
import cn.kasuminova.astd.impl.render.PsiHelixComponent
import cn.kasuminova.astd.impl.render.PsiSiphonComponent
import cn.kasuminova.astd.impl.render.renderEntity

/**
 * 光束特效的作者面：按光束 id 现构建一棵 [RenderEntity] 场景树（与弹体侧 `ProjectileVfxSpecs` 对称）。
 * 束体 4 件套走公共 [BeamCoreComponent]，其余为各光束的专属节点（detail/impact 等）。
 *
 * 注：光束驱动不需要弹体那套 policy（采样/飞行布局/单向淡出），故 build 直接返回树，不套 `BeamVfx(tree, policy)`
 * 空壳（避免薄包装层）。每次生成光束都重新调用构建函数（不缓存），支持调试期字面量热交换。
 */
object BeamVfxSpecs {

    private val builders: Map<String, () -> RenderEntity> = mapOf(
        "astd_psi_omega" to { psiOmega() },
    )

    /** 该光束 id 是否已迁移到新管线（有构建函数）。 */
    fun has(id: String): Boolean = builders.containsKey(id)

    /** 现构建一棵光束树；无此 id 返回 null。 */
    fun build(id: String): RenderEntity? = builders[id]?.invoke()

    /**
     * PSI-Ω「灵魂虹吸」：厚实核心 + 辉光束体（4 件套，默认即 Psi 数值）+ 双螺旋扰动丝带 + 命中端回流虹吸。
     * 伤害/CR-PPT 抽取仍由 `PsiSunderBeamEffect` 维护，本树只画视觉。
     */
    private fun psiOmega(): RenderEntity = renderEntity("astd_psi_omega") {
        addChild(BeamCoreComponent("astd_psi_omega_core"))
        addChild(PsiHelixComponent("astd_psi_omega_helix"))
        addChild(PsiSiphonComponent("astd_psi_omega_siphon"))
    }

    /**
     * 引力坍缩炮：束体 4 件套（白芯+暗红辉光，fadeMul 收束）+ 沿束光圈环 + 炮口光锥 + 沿束 nebula + 装饰微束 + 螺旋粒子。
     * 由武器/系统两个宿主直接构建（非注册表）——它们各带 scale/beamWidthMul，且起手 burst 仅 weapon 宿主要（[startupBurst]）。
     * AOE/伤害/系统逻辑仍由各宿主插件维护，本树只画视觉。
     */
    fun gravityCollapse(scale: Float, beamWidthMul: Float, startupBurst: Boolean): RenderEntity =
        renderEntity("astd_gravity_collapse") {
            addChild(BeamCoreComponent("astd_gravity_collapse_core", GcBeam.coreSpec()))
            addChild(BeamRingComponent("astd_gravity_collapse_rings", scale))
            addChild(BeamMuzzleComponent("astd_gravity_collapse_muzzle", scale, startupBurst))
            addChild(BeamAmbientComponent("astd_gravity_collapse_ambient", scale, beamWidthMul))
            addChild(BeamMicroBeamComponent("astd_gravity_collapse_micro", scale, beamWidthMul))
            addChild(BeamHelixParticleComponent("astd_gravity_collapse_helix"))
        }
}
