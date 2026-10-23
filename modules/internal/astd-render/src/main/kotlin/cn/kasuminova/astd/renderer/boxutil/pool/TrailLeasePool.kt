package cn.kasuminova.astd.renderer.boxutil.pool

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.graphics.SpriteAPI
import org.boxutil.units.standard.entity.TrailEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

data class TrailLeaseKey(
    val layer: CombatEngineLayers,
    val coreSpritePath: String,
    val fringeSpritePath: String,
    val mixPower: Float,
    val capacity: Int,
    val maxCapacity: Int = capacity * DEFAULT_MAX_CAPACITY_MUL,
) {
    companion object {
        const val DEFAULT_MAX_CAPACITY_MUL = 256
    }
}

data class TrailLeaseEnvelope(val fadeIn: Float, val full: Float, val fadeOut: Float)

class TrailLeaseSpec(
    val location: Vector2f,
    val facingDeg: Float,
    val nodes: List<Vector2f>,
    val startWidth: Float,
    val endWidth: Float,
    val coreColor: Color,
    val fringeColor: Color,
    val startAlpha: Float,
    val endAlpha: Float,
    val startEmissiveAlpha: Float,
    val endEmissiveAlpha: Float,
    val fillStartAlpha: Float = 1f,
    val fillStartFactor: Float = 1f,
    val fillEndAlpha: Float = 1f,
    val fillEndFactor: Float = 1f,
    val texturePixels: Float = 1f,
    val textureSpeed: Float = 4f,
    val uvOffset: Float = 0f,
    val jitterPower: Float = 0f,
    val scale: Vector2f? = null,
    val envelope: TrailLeaseEnvelope? = null,
    val watchdogHeartbeat: Float = 0f,
    val watchdogFadeOut: Float = 0f,
)

class TrailLease internal constructor(
    private val binding: TrailLeaseBinding,
    private val slotIndex: Int,
    private val generation: Int,
    val entity: TrailEntity,
) {
    val active: Boolean get() = binding.isHeldBy(slotIndex, generation)

    fun touch() = binding.touch(slotIndex, generation)

    fun release(fadeOutSeconds: Float = 0f) = binding.release(slotIndex, generation, fadeOutSeconds)
}

internal class TrailLeaseSlots(
    initialCapacity: Int,
    val maxCapacity: Int = initialCapacity * TrailLeaseKey.DEFAULT_MAX_CAPACITY_MUL,
) {

    init {
        require(initialCapacity >= 1) { "初始容量必须 >= 1" }
        require(maxCapacity >= initialCapacity) { "maxCapacity 必须 >= 初始容量" }
    }

    internal enum class Mode { FREE, ENVELOPE, MANUAL, RELEASING }

    internal class Slot {
        var mode = Mode.FREE
        var generation = 0
        var age = 0f
        var sinceTouch = 0f
        var fadeIn = 0f
        var full = 0f
        var fadeOut = 0f
        var watchdogHeartbeat = 0f
        var watchdogFadeOut = 0f

        var startAlpha = 0f
        var endAlpha = 0f
        var startEmissiveAlpha = 0f
        var endEmissiveAlpha = 0f
    }

    internal val slots = ArrayList<Slot>(initialCapacity).also { list -> repeat(initialCapacity) { list += Slot() } }

    var capacity: Int = initialCapacity
        private set

    var leasedCount = 0
        private set

    var overflowCount = 0
        private set

    fun claim(
        envelope: TrailLeaseEnvelope?,
        watchdogHeartbeat: Float,
        watchdogFadeOut: Float,
        startAlpha: Float, endAlpha: Float,
        startEmissiveAlpha: Float, endEmissiveAlpha: Float,
    ): Int {
        var index = slots.indexOfFirst { it.mode == Mode.FREE }
        if (index < 0) index = tryGrow()
        if (index < 0) {
            overflowCount++
            return -1
        }
        val s = slots[index]
        s.generation++
        s.mode = if (envelope != null) Mode.ENVELOPE else Mode.MANUAL
        s.age = 0f
        s.sinceTouch = 0f
        s.fadeIn = envelope?.fadeIn ?: 0f
        s.full = envelope?.full ?: 0f
        s.fadeOut = envelope?.fadeOut ?: 0f
        s.watchdogHeartbeat = watchdogHeartbeat
        s.watchdogFadeOut = watchdogFadeOut
        s.startAlpha = startAlpha
        s.endAlpha = endAlpha
        s.startEmissiveAlpha = startEmissiveAlpha
        s.endEmissiveAlpha = endEmissiveAlpha
        leasedCount++
        return index
    }

    private fun tryGrow(): Int {
        if (capacity >= maxCapacity) return -1
        val step = (capacity / 2).coerceAtLeast(GROW_STEP_MIN)
        val newCapacity = (capacity + step).coerceAtMost(maxCapacity)
        repeat(newCapacity - capacity) { slots += Slot() }
        val firstNew = capacity
        capacity = newCapacity
        return firstNew
    }

    private companion object {
        const val GROW_STEP_MIN = 8
    }

    fun isHeld(index: Int, generation: Int): Boolean {
        val s = slots[index]
        return s.generation == generation && s.mode != Mode.FREE
    }

    fun touch(index: Int, generation: Int) {
        val s = slots[index]
        if (s.generation != generation) return
        if (s.mode == Mode.MANUAL) s.sinceTouch = 0f
        if (s.mode == Mode.RELEASING) {
            s.mode = Mode.MANUAL
            s.age = 0f
            s.sinceTouch = 0f
        }
    }

    fun refreshEnvelope(index: Int, generation: Int) {
        val s = slots[index]
        if (s.generation != generation || s.mode != Mode.ENVELOPE) return
        s.age = 0f
    }

    /** @return true = 槽位进入 RELEASING，调用方必须读实体当前 alpha 回写 [snapshotReleaseAlphas]。 */
    fun release(index: Int, generation: Int, fadeOut: Float): Boolean {
        val s = slots[index]
        if (s.generation != generation || s.mode == Mode.FREE) return false
        if (fadeOut > 0f) {
            s.mode = Mode.RELEASING
            s.age = 0f
            s.fadeOut = fadeOut
            return true
        }
        free(s)
        return false
    }

    fun snapshotReleaseAlphas(
        index: Int,
        startAlpha: Float, endAlpha: Float,
        startEmissiveAlpha: Float, endEmissiveAlpha: Float,
    ) {
        val s = slots[index]
        s.startAlpha = startAlpha
        s.endAlpha = endAlpha
        s.startEmissiveAlpha = startEmissiveAlpha
        s.endEmissiveAlpha = endEmissiveAlpha
    }

    fun forceFree(index: Int) {
        free(slots[index])
    }

    fun advance(amount: Float): List<TrailLeaseEvent> {
        var events: MutableList<TrailLeaseEvent>? = null
        for (i in slots.indices) {
            val s = slots[i]
            when (s.mode) {
                Mode.FREE -> {}
                Mode.ENVELOPE -> {
                    s.age += amount
                    if (poolEnvelopeExpired(s.age, s.fadeIn, s.full, s.fadeOut)) {
                        free(s)
                        events = (events ?: ArrayList(2)).also { it += TrailLeaseEvent.Parked(i) }
                    }
                }

                Mode.MANUAL -> {
                    if (s.watchdogHeartbeat > 0f) {
                        s.sinceTouch += amount
                        if (s.sinceTouch >= s.watchdogHeartbeat) {
                            s.mode = Mode.RELEASING
                            s.age = 0f
                            s.fadeOut = s.watchdogFadeOut
                            events = (events ?: ArrayList(2)).also { it += TrailLeaseEvent.BeginReleaseFade(i) }
                        }
                    }
                }

                Mode.RELEASING -> {
                    s.age += amount
                    if (s.age >= s.fadeOut) {
                        free(s)
                        events = (events ?: ArrayList(2)).also { it += TrailLeaseEvent.Parked(i) }
                    }
                }
            }
        }
        return events ?: emptyList()
    }

    fun alphaMulAt(index: Int): Float {
        val s = slots[index]
        return when (s.mode) {
            Mode.ENVELOPE -> poolEnvelopeAlpha(s.age, s.fadeIn, s.full, s.fadeOut)
            Mode.RELEASING -> if (s.fadeOut <= 0f) 0f else (1f - s.age / s.fadeOut).coerceIn(0f, 1f)
            Mode.MANUAL -> 1f
            Mode.FREE -> 0f
        }
    }

    private fun free(s: Slot) {
        s.mode = Mode.FREE
        s.age = 0f
        s.sinceTouch = 0f
        leasedCount--
    }
}

internal sealed interface TrailLeaseEvent {
    val index: Int

    data class BeginReleaseFade(override val index: Int) : TrailLeaseEvent
    data class Parked(override val index: Int) : TrailLeaseEvent
}

internal class TrailLeaseBinding(
    private val engine: CombatEngineAPI,
    private val key: TrailLeaseKey,
) {
    private val log = Global.getLogger(TrailLeaseBinding::class.java)

    val slots = TrailLeaseSlots(key.capacity, key.maxCapacity)

    private val entities = ArrayList<TrailEntity?>(key.capacity)
    private var coreSprite: SpriteAPI? = null
    private var fringeSprite: SpriteAPI? = null
    private var spritesAttempted = false
    private var broken = false

    fun checkout(spec: TrailLeaseSpec): TrailLease? {
        if (broken) return null
        if (!ensureSprites()) return null
        val capacityBefore = slots.capacity
        val index = slots.claim(
            spec.envelope, spec.watchdogHeartbeat, spec.watchdogFadeOut,
            spec.startAlpha, spec.endAlpha, spec.startEmissiveAlpha, spec.endEmissiveAlpha,
        )
        if (index < 0) {
            val count = slots.overflowCount
            if (count == 1 || count % OVERFLOW_WARN_STRIDE == 0) {
                log.warn("池化光束租约触及硬上限拒发（maxCapacity=${key.maxCapacity}，layer=${key.layer}，core=${key.coreSpritePath}，累计拒发 $count 次），本次视觉缺席——需排查租约泄漏或重估峰值")
            }
            return null
        }
        if (slots.capacity != capacityBefore) {
            log.warn("池化光束租约池按需扩容（layer=${key.layer}，core=${key.coreSpritePath}，容量 $capacityBefore → ${slots.capacity}）——初始容量低于真实峰值，遥测提示")
        }
        val entity = getOrCreateEntity(index)
        if (entity == null) {
            slots.forceFree(index)
            return null
        }
        try {
            resetEntity(entity, spec)
        } catch (t: Throwable) {
            slots.forceFree(index)
            parkEntity(entity)
            log.warn("池化光束租约配置失败（layer=${key.layer}），本次视觉缺席", t)
            return null
        }
        return TrailLease(this, index, slots.slots[index].generation, entity)
    }

    fun isHeldBy(index: Int, generation: Int): Boolean = slots.isHeld(index, generation)

    fun touch(index: Int, generation: Int) = slots.touch(index, generation)

    fun release(index: Int, generation: Int, fadeOut: Float) {
        // slots.release 立即归还时不产生 Parked 事件，必须在此泊车，否则实体残留渲染；过期句柄不得泊车新租约的实体
        val wasHeld = slots.isHeld(index, generation)
        val needSnapshot = slots.release(index, generation, fadeOut)
        if (needSnapshot) {
            snapshotCurrentAlphas(index)
            return
        }
        if (wasHeld) entities.getOrNull(index)?.let { parkEntity(it) }
    }

    fun advance(amount: Float) {
        for (event in slots.advance(amount)) {
            when (event) {
                is TrailLeaseEvent.BeginReleaseFade -> snapshotCurrentAlphas(event.index)
                is TrailLeaseEvent.Parked -> entities.getOrNull(event.index)?.let { parkEntity(it) }
            }
        }
        for (i in entities.indices) {
            val e = entities[i] ?: continue
            val s = slots.slots[i]
            if (s.mode == TrailLeaseSlots.Mode.ENVELOPE || s.mode == TrailLeaseSlots.Mode.RELEASING) {
                applyAlpha(i, slots.alphaMulAt(i))
            }
        }
    }

    private fun applyAlpha(index: Int, mul: Float) {
        val e = entities[index] ?: return
        val s = slots.slots[index]
        e.setStartColor(1f, 1f, 1f, s.startAlpha * mul)
        e.setEndColor(1f, 1f, 1f, s.endAlpha * mul)
        e.setStartEmissive(1f, 1f, 1f, s.startEmissiveAlpha * mul)
        e.setEndEmissive(1f, 1f, 1f, s.endEmissiveAlpha * mul)
    }

    private fun snapshotCurrentAlphas(index: Int) {
        val e = entities[index] ?: return
        slots.snapshotReleaseAlphas(index, e.startColorAlpha, e.endColorAlpha, e.startEmissiveAlpha, e.endEmissiveAlpha)
    }

    private fun ensureSprites(): Boolean {
        if (spritesAttempted) return coreSprite != null && fringeSprite != null
        spritesAttempted = true
        return try {
            BoxUtilCombatVfx.ensureReady(engine)
            val settings = Global.getSettings()
            // 必须先 loadTexture 再 getSprite，裸 getSprite 返回 textureID=0 的空壳
            settings.loadTexture(key.coreSpritePath)
            settings.loadTexture(key.fringeSpritePath)
            coreSprite = settings.getSprite(key.coreSpritePath)
            fringeSprite = settings.getSprite(key.fringeSpritePath)
            true
        } catch (t: Throwable) {
            broken = true
            log.warn("池化光束租约贴图加载失败（core=${key.coreSpritePath}），该池视觉缺席", t)
            false
        }
    }

    private fun getOrCreateEntity(index: Int): TrailEntity? {
        while (entities.size <= index) entities += null
        entities[index]?.let {
            if (!it.hasDelete()) return it
            log.warn("池化光束租约实体被外部回收（layer=${key.layer}，槽位=$index），重建实体并重新登记")
            entities[index] = null
        }
        return try {
            val e = TrailEntity()
            e.setAdditiveBlend()
            e.setLayer(key.layer)
            // BoxUtil 全局计时器缺省值会被首个逻辑帧判 TIMER_INVALID 直接 delete，必须钉常驻值
            e.setGlobalTimer(0f, 1e7f, 0f)
            e.mixFactor = key.mixPower
            val mat = e.materialData
            mat.alphaToEmissive = 0f
            mat.isColorToEmissive = 0f
            mat.glowPower = 1f
            mat.setDiffuse(coreSprite)
            mat.setEmissive(fringeSprite)
            parkEntity(e)

            val state = BoxUtilCombatVfx.addEntity(engine, e)
            if (state != 0) {
                e.delete()
                broken = true
                log.warn("池化光束租约实体注册失败（addEntity 返回 $state，layer=${key.layer}），该池视觉缺席")
                return null
            }
            entities[index] = e
            e
        } catch (t: Throwable) {
            broken = true
            log.warn("池化光束租约实体创建失败（layer=${key.layer}），该池视觉缺席", t)
            null
        }
    }

    private fun resetEntity(e: TrailEntity, spec: TrailLeaseSpec) {
        e.resetNodes()
        for (node in spec.nodes) e.addNode(Vector2f(node))
        e.setNodeRefreshIndex(0)
        e.setNodeRefreshAllFromCurrentIndex()
        e.submitNodes()

        e.startWidth = spec.startWidth
        e.endWidth = spec.endWidth
        e.mixFactor = key.mixPower
        e.setStartColor(1f, 1f, 1f, spec.startAlpha)
        e.setEndColor(1f, 1f, 1f, spec.endAlpha)
        e.setStartEmissive(1f, 1f, 1f, spec.startEmissiveAlpha)
        e.setEndEmissive(1f, 1f, 1f, spec.endEmissiveAlpha)

        e.fillStartAlpha = spec.fillStartAlpha
        e.fillStartFactor = spec.fillStartFactor
        e.fillEndAlpha = spec.fillEndAlpha
        e.fillEndFactor = spec.fillEndFactor
        e.texturePixels = spec.texturePixels
        e.textureSpeed = spec.textureSpeed
        e.uvOffset = spec.uvOffset
        e.jitterPower = spec.jitterPower
        e.isFlowWhenPaused = false
        e.isFlick = false
        e.isSyncFlick = false

        val mat = e.materialData
        mat.alphaToEmissive = 0f
        mat.isColorToEmissive = 0f
        mat.glowPower = 1f
        mat.setColor(spec.coreColor)
        mat.setEmissiveColor(spec.fringeColor)

        e.setGlobalTimer(0f, 1e7f, 0f)

        val facing = BoxUtilCombatVfx.normalizeFacingDeg(spec.facingDeg)
        val scale = spec.scale
        if (scale != null) {
            e.setStateVanilla(Vector2f(spec.location), facing, scale)
        } else {
            e.setStateVanilla(Vector2f(spec.location), facing)
        }
    }

    private fun parkEntity(e: TrailEntity) {
        e.setStartColor(1f, 1f, 1f, 0f)
        e.setEndColor(1f, 1f, 1f, 0f)
        e.setStartEmissive(1f, 1f, 1f, 0f)
        e.setEndEmissive(1f, 1f, 1f, 0f)
        // BoxUtil 渲染循环对零节点实体直接 continue（BUtil_EntityImpl.processTrailEntity），泊车必须清空节点
        e.resetNodes()
    }

    companion object {
        private const val OVERFLOW_WARN_STRIDE = 64
    }
}
