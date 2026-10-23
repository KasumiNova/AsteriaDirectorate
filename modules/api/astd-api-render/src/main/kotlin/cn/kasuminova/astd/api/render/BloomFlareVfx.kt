package cn.kasuminova.astd.api.render

import cn.kasuminova.astd.impl.render.BloomFlareSpec
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 在指定位置生成一组 BoxUtil 光斑，并按给定时长扩散和渐隐。
 *
 * 仅在战斗主线程调用。[spawn] 会创建渲染实体，并在到期后删除；无 GL 环境或任一实体注册失败时，
 * 删除已创建的实体、记录 WARN 并返回 0。
 */
interface BloomFlareVfx {

    /**
     * 光斑逐帧跟随锚点：光束起点等动态锚点的位置与朝向每帧都在变化，
     * 生成时传入的固定坐标/朝向无法表达，故由调用方提供定位回调，
     * 实现侧在存续期内每帧回调并改写各枚光斑实体的位置与朝向。
     */
    fun interface FlareFollow {
        /** 将当前世界坐标写入 [out]（复用向量，不得持有引用），返回当前朝向角度（度）。 */
        fun locate(out: Vector2f): Float
    }

    /**
     * 在 [center] 生成一组绽放辉星。
     *
     * @param durationSeconds 扩散/渐隐动画时长（秒），到期删除全部实体。
     * @param flares 光斑规格列表（同位叠放，各自尺寸/朝向/颜色独立）。
     * @param follow 非 null 时每帧回调改写整组光斑的位置与朝向（动态锚点场景，如光束起点）。
     * @return 实际生成的光斑枚数；任一建立/注册失败整组放弃返回 0。
     */
    fun spawn(
        engine: CombatEngineAPI,
        center: Vector2f,
        durationSeconds: Float,
        flares: List<BloomFlareSpec>,
        timerFadeIn: Float = 0f,
        timerFull: Float = 1e7f,
        timerFadeOut: Float = 0f,
        follow: FlareFollow? = null,
    ): Int
}
