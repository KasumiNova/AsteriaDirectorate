package cn.kasuminova.astd.renderer.boxutil.pool

internal fun poolEnvelopeAlpha(age: Float, fadeIn: Float, full: Float, fadeOut: Float): Float {
    if (age <= 0f) return if (fadeIn > 0f) 0f else 1f
    if (age < fadeIn) return (age / fadeIn).coerceIn(0f, 1f)
    val afterFull = age - fadeIn - full
    if (afterFull < 0f) return 1f
    if (fadeOut <= 0f) return 0f
    return (1f - afterFull / fadeOut).coerceIn(0f, 1f)
}

internal fun poolEnvelopeExpired(age: Float, fadeIn: Float, full: Float, fadeOut: Float): Boolean =
    age >= fadeIn + full + fadeOut
