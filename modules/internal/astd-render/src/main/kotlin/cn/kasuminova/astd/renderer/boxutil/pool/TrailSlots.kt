package cn.kasuminova.astd.renderer.boxutil.pool

/**
 * 池化光束段（TrailEntity 双节点束）的纯逻辑槽位表：与 [SpriteParticleSlots] 同构的
 * 固定容量环形复用，但槽位与 TrailEntity 一一绑定（渲染侧按索引对齐实体数组），
 * 因此认领需要返回稳定索引，回收仅由包络到期驱动。
 *
 * 每槽额外持有逐次 spawn 的宽度/颜色 alpha 基准（尾/头 alpha 与 emissive 乘数），
 * 渲染侧每帧按包络乘算后写入实体 START/END 色。
 */
internal class TrailSlots(val capacity: Int) {

    init {
        require(capacity >= 1) { "capacity 必须 >= 1" }
    }

    internal class Slot {
        var active = false
        var age = 0f
        var fadeIn = 0f
        var full = 0f
        var fadeOut = 0f
        var tailAlpha = 0f
        var headAlpha = 0f
        var tailEmissiveAlpha = 0f
        var headEmissiveAlpha = 0f
    }

    internal val slots = Array(capacity) { Slot() }

    private var cursor = 0

    var activeCount = 0
        private set

    /** 认领槽位（无空闲时按游标覆盖最旧），返回与实体数组对齐的稳定索引。 */
    fun claim(
        fadeIn: Float, full: Float, fadeOut: Float,
        tailAlpha: Float, headAlpha: Float,
        tailEmissiveAlpha: Float, headEmissiveAlpha: Float,
    ): Int {
        val index = claimIndex()
        val s = slots[index]
        if (!s.active) activeCount++
        s.active = true
        s.age = 0f
        s.fadeIn = fadeIn
        s.full = full
        s.fadeOut = fadeOut
        s.tailAlpha = tailAlpha
        s.headAlpha = headAlpha
        s.tailEmissiveAlpha = tailEmissiveAlpha
        s.headEmissiveAlpha = headEmissiveAlpha
        return index
    }

    /** 推进年龄，包络走完则回收（渲染侧据此把实体 alpha 归零泊车）。 */
    fun advance(amount: Float) {
        for (s in slots) {
            if (!s.active) continue
            s.age += amount
            if (poolEnvelopeExpired(s.age, s.fadeIn, s.full, s.fadeOut)) {
                s.active = false
                activeCount--
            }
        }
    }

    /** 槽位当前包络 alpha 乘数；非活跃返回 0（泊车态）。 */
    fun alphaAt(index: Int): Float {
        val s = slots[index]
        if (!s.active) return 0f
        return poolEnvelopeAlpha(s.age, s.fadeIn, s.full, s.fadeOut)
    }

    private fun claimIndex(): Int {
        for (i in 0 until capacity) {
            val index = (cursor + i) % capacity
            if (!slots[index].active) {
                cursor = index + 1
                return index
            }
        }
        val index = cursor % capacity
        cursor = index + 1
        return index
    }
}
