package cn.kasuminova.astd.combat.automation.base

internal class PhaseMotionEvidence(
    private val windowSec: Float = 15f,
    private val minimumPath: Float = 600f,
    private val maximumDisplacement: Float = 150f,
) {
    private data class Sample(val time: Float, val x: Float, val y: Float, val segment: Float)

    private val samples = ArrayDeque<Sample>()
    private var path = 0f
    private var lastSampleAt = 0f

    var circling = false
        private set
    var windowPath = 0f
        private set
    var windowDisplacement = 0f
        private set

    fun sample(time: Float, x: Float, y: Float): Boolean {
        require(time.isFinite() && x.isFinite() && y.isFinite())
        val previous = samples.lastOrNull()
        if (previous != null && (time < previous.time || time - lastSampleAt < 0.5f)) return circling
        val segment = previous?.let { kotlin.math.hypot(x - it.x, y - it.y) } ?: 0f
        samples += Sample(time, x, y, segment)
        path += segment
        lastSampleAt = time
        while (samples.size > 1 && time - samples.first().time > windowSec) {
            samples.removeFirst()
            path -= samples.first().segment
        }
        val first = samples.first()
        windowPath = path
        windowDisplacement = kotlin.math.hypot(x - first.x, y - first.y)
        if (time - first.time >= windowSec && path >= minimumPath && windowDisplacement <= maximumDisplacement) {
            circling = true
        }
        return circling
    }
}
