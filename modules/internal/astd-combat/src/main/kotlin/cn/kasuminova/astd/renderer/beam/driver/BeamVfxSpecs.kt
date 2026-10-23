package cn.kasuminova.astd.renderer.beam.driver

import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.impl.render.AnnihilationVortexVortexComponent
import cn.kasuminova.astd.impl.render.AvBeam
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

object BeamVfxSpecs {

    private val builders: Map<String, () -> RenderEntity> = mapOf(
        "astd_psi_omega" to { psiOmega() },
        "astd_annihilation_vortex" to { annihilationVortex() },
    )

    fun has(id: String): Boolean = builders.containsKey(id)

    fun build(id: String): RenderEntity? = builders[id]?.invoke()

    private fun psiOmega(): RenderEntity = renderEntity("astd_psi_omega") {
        addChild(BeamCoreComponent("astd_psi_omega_core"))
        addChild(PsiHelixComponent("astd_psi_omega_helix"))
        addChild(PsiSiphonComponent("astd_psi_omega_siphon"))
    }

    private fun annihilationVortex(): RenderEntity = renderEntity("astd_annihilation_vortex") {
        addChild(BeamCoreComponent("astd_annihilation_vortex_core", AvBeam.coreSpec()))
        addChild(AnnihilationVortexVortexComponent("astd_annihilation_vortex_vortex"))
    }

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
