package cn.kasuminova.astd.renderer.boxutil.pool

/**
 * 池化粒子的三段式寿命包络（fadeIn → full → fadeOut，线性）纯函数。
 *
 * 背景：BoxUtil `renderEntityMap` 战斗内只增不删（实体 timer 到期/delete 仅停止渲染，
 * 引用滞留至战斗切换清理），因此高频粒子不得再「新建实体 + 定时器自删」，
 * 统一改由池化常驻实体 + CPU 侧包络驱动 alpha（见 PooledCombatVfx）。
 */

/** 包络 alpha 乘数：fadeIn 线性升、full 恒 1、fadeOut 线性降；越界 clamp。 */
internal fun poolEnvelopeAlpha(age: Float, fadeIn: Float, full: Float, fadeOut: Float): Float {
    if (age <= 0f) return if (fadeIn > 0f) 0f else 1f
    if (age < fadeIn) return (age / fadeIn).coerceIn(0f, 1f)
    val afterFull = age - fadeIn - full
    if (afterFull < 0f) return 1f
    if (fadeOut <= 0f) return 0f
    return (1f - afterFull / fadeOut).coerceIn(0f, 1f)
}

/** 包络是否走完（粒子可回收）。 */
internal fun poolEnvelopeExpired(age: Float, fadeIn: Float, full: Float, fadeOut: Float): Boolean =
    age >= fadeIn + full + fadeOut
