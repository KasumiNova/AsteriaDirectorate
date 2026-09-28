package cn.kasuminova.astd.api.render

import cn.kasuminova.astd.impl.render.BloomFlareSpec
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 「绽放辉星」通用特效入口：在指定世界坐标叠放一组 BoxUtil FlareEntity 光斑，
 * 存续期内各枚光斑尺寸线性扩散、透明度线性归零，到期销毁（坠落残响爆心十字辉星、
 * 摧锋鱼雷命中十字辉星的共用抽取）。
 *
 * FlareEntity 无内建尺寸关键帧（只有全局计时器），扩散/渐隐由实现侧的逐帧插件推进；
 * 计时器参数（[timerFadeIn]/[timerFull]/[timerFadeOut]）只负责钉住实体生命周期，
 * 默认钉超长 full，观感动画全部由扩散/渐隐驱动。
 *
 * 降级口径：无 GL 环境（单测/无头）或任一实体注册失败时整组放弃（已建实体删除），
 * 记 WARN 日志并返回 0，调用方其余特效构成不受影响。
 */
interface BloomFlareVfx {

    /**
     * 在 [center] 生成一组绽放辉星。
     *
     * @param durationSeconds 扩散/渐隐动画时长（秒），到期删除全部实体。
     * @param flares 光斑规格列表（同位叠放，各自尺寸/朝向/颜色独立）。
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
    ): Int
}
