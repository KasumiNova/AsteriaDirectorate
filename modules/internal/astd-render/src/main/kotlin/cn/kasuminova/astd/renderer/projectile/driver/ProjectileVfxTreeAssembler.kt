package cn.kasuminova.astd.renderer.projectile.driver

import cn.kasuminova.astd.api.render.RenderEntity
import cn.kasuminova.astd.impl.render.AnchorArcComponent
import cn.kasuminova.astd.impl.render.BoxFlareComponent
import cn.kasuminova.astd.impl.render.TexTrailComponent
import cn.kasuminova.astd.impl.render.headBloomComponent
import cn.kasuminova.astd.impl.render.renderEntity

/**
 * 把 [ProjectileVfxTreeSpec] 蓝图组装为 RenderEntity 场景树（渲染实现侧的唯一组装点）。
 *
 * 组件节点内部复用几何层的 `*ForTests` 纯网格数学（不手抄）。
 */
object ProjectileVfxTreeAssembler {

    fun assemble(tree: ProjectileVfxTreeSpec): RenderEntity = renderEntity(tree.id) {
        val trailLayer = tree.trailLayer
        if (trailLayer != null) {
            tree.head?.let {
                // 弹头恒并入 bloom 管线（同一离屏提取+模糊+合成）：弹头与拖尾能量同源，
                // 接缝处光晕连续——直绘弹头不进 bloom，能量天然低于带体，调色抹不平接缝色差
                addChild(headBloomComponent("${tree.id}_head", trailLayer, listOf(it), tree.headSizeScale))
            }
        }
        tree.texTrails.forEach { (name, spec) -> addChild(TexTrailComponent("${tree.id}_textrail_$name", spec)) }
        tree.boxFlares.forEach { (name, spec) -> addChild(BoxFlareComponent("${tree.id}_boxflare_$name", spec)) }
        tree.anchorArcs.forEach { (name, spec) -> addChild(AnchorArcComponent("${tree.id}_anchorarc_$name", spec)) }
    }
}
