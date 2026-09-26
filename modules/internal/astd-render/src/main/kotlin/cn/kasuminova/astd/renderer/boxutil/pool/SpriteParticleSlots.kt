package cn.kasuminova.astd.renderer.boxutil.pool

/**
 * 池化 sprite 粒子的纯逻辑槽位表：固定容量环形复用，不触碰任何 BoxUtil/GL 类型，
 * 供单测直接驱动；渲染侧（PooledCombatVfx.SpritePoolBinding）每帧把活跃槽位灌进实例数据。
 *
 * 语义：
 * - [spawn] 认领一个空闲槽位；无空闲时按游标覆盖最旧槽位（视觉等同最旧粒子提前寿终）；
 * - [advance] 推进年龄并按速度/自转角速度做 CPU 侧积分（实例侧 velocity/turnRate 恒 0，
 *   避免与 BoxUtil 实例自管理双重积分）；
 * - alpha 包络由 [poolEnvelopeAlpha] 逐帧派生，定时器不再交给 BoxUtil。
 */
internal class SpriteParticleSlots(val capacity: Int) {

    init {
        require(capacity >= 1) { "capacity 必须 >= 1" }
    }

    internal class Slot {
        var active = false
        var age = 0f
        var fadeIn = 0f
        var full = 0f
        var fadeOut = 0f
        var posX = 0f
        var posY = 0f
        var velX = 0f
        var velY = 0f
        var facingDeg = 0f
        var turnRateDeg = 0f
        var scaleX = 0f
        var scaleY = 0f
        var r = 0
        var g = 0
        var b = 0
        var a = 0
        var er = 0
        var eg = 0
        var eb = 0
        var ea = 0
    }

    internal val slots = Array(capacity) { Slot() }

    /** 复用游标：无空闲槽时从此处覆盖最旧槽位。 */
    private var cursor = 0

    var activeCount = 0
        private set

    /** 认领并初始化一个槽位，返回槽位索引（供测试断言复用行为）。 */
    fun spawn(
        posX: Float, posY: Float,
        velX: Float, velY: Float,
        facingDeg: Float, turnRateDeg: Float,
        scaleX: Float, scaleY: Float,
        r: Int, g: Int, b: Int, a: Int,
        er: Int, eg: Int, eb: Int, ea: Int,
        fadeIn: Float, full: Float, fadeOut: Float,
    ): Int {
        val index = claimIndex()
        val s = slots[index]
        if (!s.active) activeCount++
        s.active = true
        s.age = 0f
        s.fadeIn = fadeIn
        s.full = full
        s.fadeOut = fadeOut
        s.posX = posX
        s.posY = posY
        s.velX = velX
        s.velY = velY
        s.facingDeg = facingDeg
        s.turnRateDeg = turnRateDeg
        s.scaleX = scaleX
        s.scaleY = scaleY
        s.r = r
        s.g = g
        s.b = b
        s.a = a
        s.er = er
        s.eg = eg
        s.eb = eb
        s.ea = ea
        return index
    }

    /** 推进全部活跃槽位：积分位置/自转，包络走完则回收。 */
    fun advance(amount: Float) {
        for (s in slots) {
            if (!s.active) continue
            s.age += amount
            if (poolEnvelopeExpired(s.age, s.fadeIn, s.full, s.fadeOut)) {
                s.active = false
                activeCount--
                continue
            }
            s.posX += s.velX * amount
            s.posY += s.velY * amount
            s.facingDeg += s.turnRateDeg * amount
        }
    }

    /** 遍历活跃槽位（紧凑写实例数据用）；[alphaMul] 为当前包络 alpha 乘数。 */
    fun forEachActive(block: (slot: Slot, alphaMul: Float) -> Unit) {
        for (s in slots) {
            if (!s.active) continue
            block(s, poolEnvelopeAlpha(s.age, s.fadeIn, s.full, s.fadeOut))
        }
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
