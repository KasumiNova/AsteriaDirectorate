package cn.kasuminova.astd.renderer.boxutil.pool

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
        var scaleRateX = 0f
        var scaleRateY = 0f
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

    private var cursor = 0

    var activeCount = 0
        private set

    fun spawn(
        posX: Float, posY: Float,
        velX: Float, velY: Float,
        facingDeg: Float, turnRateDeg: Float,
        scaleX: Float, scaleY: Float,
        scaleRateX: Float = 0f, scaleRateY: Float = 0f,
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
        s.scaleRateX = scaleRateX
        s.scaleRateY = scaleRateY
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
            s.scaleX += s.scaleRateX * amount
            s.scaleY += s.scaleRateY * amount
        }
    }

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
