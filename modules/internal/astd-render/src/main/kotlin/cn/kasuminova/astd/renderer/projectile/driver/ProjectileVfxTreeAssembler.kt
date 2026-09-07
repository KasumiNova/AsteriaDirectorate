package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.impl.render.AnchorArcComponent
import cn.kasuminova.astd.impl.render.BoltRenderComponent
import cn.kasuminova.astd.impl.render.BoxFlareComponent
import cn.kasuminova.astd.impl.render.StaticTrailComponent
import cn.kasuminova.astd.impl.render.renderEntity

/**
 * 把 [ProjectileVfxTreeSpec] 蓝图组装为 RenderEntity 场景树（渲染实现侧的唯一组装点）。
 */
object ProjectileVfxTreeAssembler {

    fun assemble(tree: ProjectileVfxTreeSpec): RenderEntity = renderEntity(tree.id) {
        tree.bolt?.let { spec -> addChild(BoltRenderComponent("${tree.id}_bolt", spec)) }
        tree.staticTrails.forEach { (name, spec) ->
            addChild(StaticTrailComponent("${tree.id}_trail_$name", tree.id, name, spec))
        }
        tree.boxFlares.forEach { (name, spec) -> addChild(BoxFlareComponent("${tree.id}_boxflare_$name", spec)) }
        tree.anchorArcs.forEach { (name, spec) -> addChild(AnchorArcComponent("${tree.id}_anchorarc_$name", spec)) }
    }
}
