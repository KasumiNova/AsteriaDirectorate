package cn.kasuminova.astd.renderer.effect.lens

import cn.kasuminova.astd.renderer.shader.base.ShaderBlendMode
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectKey
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectLayer
import cn.kasuminova.astd.renderer.shader.base.ShaderEffectSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderGeometrySpec
import cn.kasuminova.astd.renderer.shader.base.ShaderHandle
import cn.kasuminova.astd.renderer.shader.base.ShaderMaterialSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderProgramSpec
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformDefinition
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformSchema
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformSet
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformType
import cn.kasuminova.astd.renderer.shader.base.ShaderUniformValue
import cn.kasuminova.astd.renderer.shader.runtime.ShaderSink
import org.lwjgl.util.vector.Vector2f

/**
 * 误差（drift）标记高光 shader 效果（LENS 主色紫罗兰）。
 *
 * 动机（Task 8 / spec §1.1 + 颜色指令）：被误差标记的敌舰需在周身显示一道发光高光环，
 * 层数越高环越亮越宽，向玩家提示「这艘敌舰正被误差标记增伤」。误差用 LENS 主色紫罗兰，
 * 与深水标记（红色辅色，[DeepWaterMarkVisualEffect]）形成清晰对比。
 *
 * 结构镜像 [EchoFixationFieldVisualEffect]：本对象只持有效果参数与「标记层数 → shader 提交」
 * 的转换；GL 程序、layer 插件、生命周期、状态管理全部委托共享 shader runtime。fragment/vertex
 * GLSL 与 [DeepWaterMarkVisualEffect] 共享（见 [MarkHighlightShaderSource]），仅默认 hue/sat 不同。
 *
 * per-ship keyed upsert：调用方（[cn.kasuminova.astd.combat.hullmods.lens.ASTDLensArrayCoreHullMod]）
 * 用敌舰的稳定 identityHashCode 拼出 instanceId（"drift-{hash}"），每帧 upsert 刷新；停止刷新后
 * 超过 [STALE_AFTER_SECONDS] 自然过期。前缀 "drift-" 与深水 "deepwater-" 区分，避免两类串扰。
 */
internal object DriftMarkVisualEffect {

    /**
     * 提交后超过此秒数无更新即判定过期（核心 hullmod 每帧 upsert，远快于此）。
     * 值由 [MarkHighlightShaderSource] 统一持有，此处仅为公开 API 转引（测试/spec 读取）。
     */
    const val STALE_AFTER_SECONDS = MarkHighlightShaderSource.STALE_AFTER_SECONDS

    /** 紫罗兰主色 hue（约 275°，色彩指令：LENS 主色 hue ≈ 0.76）。 */
    const val PRIMARY_HUE = 0.76f

    /** 主色饱和度（色彩指令：saturation ≈ 0.65~0.72，取偏高的 0.70）。 */
    const val PRIMARY_SATURATION = 0.70f

    // 碰撞半径下限 / 羽化倍率由 [MarkHighlightShaderSource] 统一持有，Drift/DeepWater 共享，
    // 改一处即两 effect 同步（GLSL ringRadius 仍需对齐，见该常量注释）。
    private const val MIN_COLLISION_RADIUS = MarkHighlightShaderSource.MIN_COLLISION_RADIUS
    private const val FEATHER_MARGIN_MULT = MarkHighlightShaderSource.FEATHER_MARGIN_MULT

    private const val EFFECT_ID = "astd_drift_mark_highlight"
    private const val PROGRAM_ID = "astd_drift_mark_highlight_program"

    private val SHADER_UNIFORMS = ShaderUniformSchema(
        listOf(
            ShaderUniformDefinition(
                key = "resolution",
                type = ShaderUniformType.Vec2,
                required = false,
                defaultValue = ShaderUniformValue.Vec2(1f, 1f),
            ),
            // 标记层数归一 0~1（= stacks / maxStacks）：驱动环宽/亮度/内辅环强度。
            ShaderUniformDefinition("markLevel", ShaderUniformType.Float),
            ShaderUniformDefinition("hue", ShaderUniformType.Float),
            ShaderUniformDefinition("saturation", ShaderUniformType.Float),
            ShaderUniformDefinition("alphaMult", ShaderUniformType.Float),
            // 归一域半径：v_uv∈[0,1] → 中心化坐标半径范围（环落在 domain 内）。
            ShaderUniformDefinition("domainRadius", ShaderUniformType.Float),
        ),
    )

    val effectSpec: ShaderEffectSpec = ShaderEffectSpec(
        id = ShaderEffectKey(EFFECT_ID),
        program = ShaderProgramSpec(
            id = PROGRAM_ID,
            vertexSource = MarkHighlightShaderSource.VERTEX_SHADER_SOURCE,
            fragmentSource = MarkHighlightShaderSource.FRAGMENT_SHADER_SOURCE,
        ),
        geometry = ShaderGeometrySpec.WorldQuad(quadHalfExtentFor(MIN_COLLISION_RADIUS)),
        material = ShaderMaterialSpec(ShaderBlendMode.Additive),
        uniformSchema = SHADER_UNIFORMS,
        // AboveShips：高光环绕敌舰边缘，置于舰船之上更醒目，不被舰体精灵遮挡。
        layer = ShaderEffectLayer.AboveShips,
        lifetimeSeconds = 1f,
        staleAfterSeconds = STALE_AFTER_SECONDS,
        renderRadius = quadHalfExtentFor(MIN_COLLISION_RADIUS),
    )

    /**
     * 单帧高光环渲染参数（纯几何/颜色，便于单测）。
     *
     * @property outerRadiusWorld 含羽化的外半径（= quadHalfExtentWorld，用作 renderRadius/culling）。
     * @property quadHalfExtentWorld 渲染 quad 半边长（覆盖环 + 羽化）。
     * @property shaderDomainRadius 归一域半径（FRAGMENT centeredAspect 缩放）。
     * @property markLevel 标记层数归一 0~1（= stacks / maxStacks，clamp）。
     * @property hue 主色色相（紫罗兰）。
     * @property saturation 主色饱和度（紫罗兰）。
     * @property alphaMult 整体不透明度（随层数增强）。
     */
    data class Frame(
        val outerRadiusWorld: Float,
        val quadHalfExtentWorld: Float,
        val shaderDomainRadius: Float,
        val markLevel: Float,
        val hue: Float,
        val saturation: Float,
        val alphaMult: Float,
    )

    /** 含羽化余量的 quad 半边长 / renderRadius。 */
    fun quadHalfExtentFor(collisionRadius: Float): Float =
        collisionRadius.coerceAtLeast(MIN_COLLISION_RADIUS) * FEATHER_MARGIN_MULT

    /**
     * 计算单帧高光环参数。
     *
     * markLevel = stacks / maxStacks（clamp 0~1）。alphaMult 随层数线性增强
     * （0.30 基础 + 0.70 × markLevel，1 层即可见、满层最亮）。
     *
     * @param collisionRadius 敌舰碰撞半径（世界单位）。
     * @param markStacks 当前标记层数（>0 才会被调用方提交）。
     * @param maxStacks 标记上限（[cn.kasuminova.astd.combat.lens.marks.LensMarkMath.MAX_STACKS]）。
     */
    fun frame(collisionRadius: Float, markStacks: Int, maxStacks: Int): Frame {
        require(maxStacks > 0) { "maxStacks must be positive: $maxStacks" }
        val level = (markStacks.toFloat() / maxStacks.toFloat()).coerceIn(0f, 1f)
        val outer = quadHalfExtentFor(collisionRadius)
        val alphaMult = 0.30f + 0.70f * level
        return Frame(
            outerRadiusWorld = outer,
            quadHalfExtentWorld = outer,
            shaderDomainRadius = 1.15f,
            markLevel = level,
            hue = PRIMARY_HUE,
            saturation = PRIMARY_SATURATION,
            alphaMult = alphaMult,
        )
    }

    fun shouldRetire(ageSinceLastSubmit: Float): Boolean = ageSinceLastSubmit > STALE_AFTER_SECONDS

    /**
     * 提交/更新一艘被误差标记敌舰的高光环（keyed upsert）。
     *
     * @param instanceId 敌舰稳定标识（调用方拼 "drift-{identityHashCode(ship)}"），per-ship。
     * @param center 敌舰心（世界坐标）。
     */
    fun submitFrame(
        sink: ShaderSink,
        instanceId: String,
        center: Vector2f,
        frame: Frame,
    ): ShaderHandle? {
        return sink.upsert(
            spec = effectSpec.copy(
                geometry = ShaderGeometrySpec.WorldQuad(frame.quadHalfExtentWorld),
                renderRadius = frame.outerRadiusWorld,
            ),
            instanceId = instanceId,
            center = center,
            facing = 0f,
            uniforms = uniforms(frame),
        )
    }

    private fun uniforms(frame: Frame): ShaderUniformSet = ShaderUniformSet(
        SHADER_UNIFORMS,
        mapOf(
            "markLevel" to ShaderUniformValue.FloatValue(frame.markLevel),
            "hue" to ShaderUniformValue.FloatValue(frame.hue),
            "saturation" to ShaderUniformValue.FloatValue(frame.saturation),
            "alphaMult" to ShaderUniformValue.FloatValue(frame.alphaMult),
            "domainRadius" to ShaderUniformValue.FloatValue(frame.shaderDomainRadius),
        ),
    )
}
