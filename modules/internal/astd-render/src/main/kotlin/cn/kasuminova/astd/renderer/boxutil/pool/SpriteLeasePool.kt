package cn.kasuminova.astd.renderer.boxutil.pool

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.graphics.SpriteAPI
import org.boxutil.base.api.ControlDataAPI
import org.boxutil.base.api.InstanceDataAPI
import org.boxutil.base.api.RenderDataAPI
import org.boxutil.define.BoxDatabase
import org.boxutil.define.BoxEnum
import org.boxutil.define.InstanceType
import org.boxutil.units.standard.attribute.Instance2Data
import org.boxutil.units.standard.entity.SpriteEntity
import org.lwjgl.util.vector.Vector2f
import org.lwjgl.util.vector.Vector3f
import java.awt.Color

data class SpriteLeaseKey(
    val layer: CombatEngineLayers,
    val textureKey: String,
    val additive: Boolean,
    val instanced: Boolean,
    val capacity: Int,
    val maxCapacity: Int = capacity * DEFAULT_MAX_CAPACITY_MUL,
) {
    companion object {
        const val DEFAULT_MAX_CAPACITY_MUL = 256
    }
}

class SpriteLeaseSpec(
    val location: Vector2f,
    val facingDeg: Float,
    val spritePath: String? = null,
    val sprite: SpriteAPI? = null,
    val texId: Int? = null,
    val uvStartX: Float = 0f,
    val uvStartY: Float = 0f,
    val uvEndX: Float = 1f,
    val uvEndY: Float = 1f,
    val bindEmissive: Boolean = false,
    val baseSizeHalfWidth: Float,
    val baseSizeHalfHeight: Float,
    val color: Color = Color.WHITE,
    val emissiveColor: Color = Color.WHITE,
    val glowPower: Float = 1f,
    val alphaToEmissive: Float = 0f,
    val isColorToEmissive: Float = 0f,
    val emissiveState: Vector3f? = null,
    val isAdditionEmissive: Boolean = false,
    val isIgnoreIllumination: Boolean = false,
    val maxInstances: Int = 0,
    val scale: Vector2f? = null,
    val envelope: TrailLeaseEnvelope? = null,
    val watchdogHeartbeat: Float = 0f,
    val watchdogFadeOut: Float = 0f,
)

class SpriteLease internal constructor(
    private val binding: SpriteLeaseBinding,
    private val slotIndex: Int,
    private val generation: Int,
    val entity: SpriteEntity,
    val instances: MutableList<Instance2Data>?,
) {
    val active: Boolean get() = binding.isHeldBy(slotIndex, generation)

    fun touch() = binding.touch(slotIndex, generation)

    fun release(fadeOutSeconds: Float = 0f) = binding.release(slotIndex, generation, fadeOutSeconds)
}

internal class SpriteLeaseSlots(
    initialCapacity: Int,
    val maxCapacity: Int = initialCapacity * SpriteLeaseKey.DEFAULT_MAX_CAPACITY_MUL,
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

        var baseColorAlpha = 0f
        var baseEmissiveAlpha = 0f
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
        baseColorAlpha: Float,
        baseEmissiveAlpha: Float,
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
        s.baseColorAlpha = baseColorAlpha
        s.baseEmissiveAlpha = baseEmissiveAlpha
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

    fun snapshotReleaseAlphas(index: Int, colorAlpha: Float, emissiveAlpha: Float) {
        val s = slots[index]
        s.baseColorAlpha = colorAlpha
        s.baseEmissiveAlpha = emissiveAlpha
    }

    fun forceFree(index: Int) {
        free(slots[index])
    }

    fun advance(amount: Float): List<SpriteLeaseEvent> {
        var events: MutableList<SpriteLeaseEvent>? = null
        for (i in slots.indices) {
            val s = slots[i]
            when (s.mode) {
                Mode.FREE -> {}
                Mode.ENVELOPE -> {
                    s.age += amount
                    if (poolEnvelopeExpired(s.age, s.fadeIn, s.full, s.fadeOut)) {
                        free(s)
                        events = (events ?: ArrayList(2)).also { it += SpriteLeaseEvent.Parked(i) }
                    }
                }

                Mode.MANUAL -> {
                    if (s.watchdogHeartbeat > 0f) {
                        s.sinceTouch += amount
                        if (s.sinceTouch >= s.watchdogHeartbeat) {
                            s.mode = Mode.RELEASING
                            s.age = 0f
                            s.fadeOut = s.watchdogFadeOut
                            events = (events ?: ArrayList(2)).also { it += SpriteLeaseEvent.BeginReleaseFade(i) }
                        }
                    }
                }

                Mode.RELEASING -> {
                    s.age += amount
                    if (s.age >= s.fadeOut) {
                        free(s)
                        events = (events ?: ArrayList(2)).also { it += SpriteLeaseEvent.Parked(i) }
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

internal sealed interface SpriteLeaseEvent {
    val index: Int

    data class BeginReleaseFade(override val index: Int) : SpriteLeaseEvent
    data class Parked(override val index: Int) : SpriteLeaseEvent
}

internal class SpriteLeaseBinding(
    private val engine: CombatEngineAPI,
    private val key: SpriteLeaseKey,
) {
    private val log = Global.getLogger(SpriteLeaseBinding::class.java)

    val slots = SpriteLeaseSlots(key.capacity, key.maxCapacity)

    private val entities = ArrayList<SpriteEntity?>(key.capacity)
    private val instanceTables = ArrayList<MutableList<Instance2Data>?>(key.capacity)
    private val spriteCache = HashMap<String, SpriteAPI>()
    private var ready = false
    private var broken = false

    fun checkout(spec: SpriteLeaseSpec): SpriteLease? {
        if (broken) return null
        if (!ensureReady()) return null
        val capacityBefore = slots.capacity
        val index = slots.claim(
            spec.envelope, spec.watchdogHeartbeat, spec.watchdogFadeOut,
            spec.color.alpha / 255f, spec.emissiveColor.alpha / 255f,
        )
        if (index < 0) {
            val count = slots.overflowCount
            if (count == 1 || count % OVERFLOW_WARN_STRIDE == 0) {
                log.warn("池化 sprite 租约触及硬上限拒发（maxCapacity=${key.maxCapacity}，layer=${key.layer}，texture=${key.textureKey}，累计拒发 $count 次），本次视觉缺席——需排查租约泄漏或重估峰值")
            }
            return null
        }
        if (slots.capacity != capacityBefore) {
            log.warn("池化 sprite 租约池按需扩容（layer=${key.layer}，texture=${key.textureKey}，容量 $capacityBefore → ${slots.capacity}）——初始容量低于真实峰值，遥测提示")
        }
        val entity = getOrCreateEntity(index)
        if (entity == null) {
            slots.forceFree(index)
            return null
        }
        try {
            resetEntity(entity, spec, index)
        } catch (t: Throwable) {
            slots.forceFree(index)
            parkEntity(entity)
            log.warn("池化 sprite 租约配置失败（layer=${key.layer}，texture=${key.textureKey}），本次视觉缺席", t)
            return null
        }
        return SpriteLease(this, index, slots.slots[index].generation, entity, instanceTables.getOrNull(index))
    }

    fun isHeldBy(index: Int, generation: Int): Boolean = slots.isHeld(index, generation)

    fun touch(index: Int, generation: Int) = slots.touch(index, generation)

    fun release(index: Int, generation: Int, fadeOut: Float) {
        // 过期句柄（槽位已被新租约认领）不得泊车新租约的实体，必须先记持有状态再释放
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
                is SpriteLeaseEvent.BeginReleaseFade -> snapshotCurrentAlphas(event.index)
                is SpriteLeaseEvent.Parked -> entities.getOrNull(event.index)?.let { parkEntity(it) }
            }
        }
        for (i in entities.indices) {
            val e = entities[i] ?: continue
            val s = slots.slots[i]
            if (s.mode == SpriteLeaseSlots.Mode.ENVELOPE || s.mode == SpriteLeaseSlots.Mode.RELEASING) {
                applyAlpha(i, slots.alphaMulAt(i))
            }
        }
    }

    private fun applyAlpha(index: Int, mul: Float) {
        val e = entities[index] ?: return
        val s = slots.slots[index]
        e.materialData.colorAlpha = s.baseColorAlpha * mul
        e.materialData.emissiveColorAlpha = s.baseEmissiveAlpha * mul
    }

    private fun snapshotCurrentAlphas(index: Int) {
        val e = entities[index] ?: return
        slots.snapshotReleaseAlphas(index, e.materialData.colorAlpha, e.materialData.emissiveColorAlpha)
    }

    private fun ensureReady(): Boolean {
        if (ready) return true
        return try {
            BoxUtilCombatVfx.ensureReady(engine)
            ready = true
            true
        } catch (t: Throwable) {
            broken = true
            log.warn("池化 sprite 租约 BoxUtil 就绪失败（layer=${key.layer}，texture=${key.textureKey}），该池视觉缺席", t)
            false
        }
    }

    // 必须先 loadTexture 再 getSprite，裸 getSprite 返回 textureID=0 的空壳
    private fun resolveSprite(path: String): SpriteAPI = spriteCache.getOrPut(path) {
        Global.getSettings().apply { loadTexture(path) }.getSprite(path)
    }

    private fun getOrCreateEntity(index: Int): SpriteEntity? {
        while (entities.size <= index) entities += null
        while (instanceTables.size <= index) instanceTables += null
        entities[index]?.let {
            if (!it.hasDelete()) return it
            log.warn("池化 sprite 租约实体被外部回收（layer=${key.layer}，texture=${key.textureKey}，槽位=$index），重建实体并重新登记")
            entities[index] = null
            instanceTables[index] = null
        }
        return try {
            val e = SpriteEntity()
            if (key.additive) e.setAdditiveBlend() else e.setNormalBlend()
            e.setLayer(key.layer)
            // BoxUtil 全局计时器缺省值会被首个逻辑帧判 TIMER_INVALID 直接 delete，必须钉常驻值
            e.setGlobalTimer(0f, 1e7f, 0f)
            e.setControlData(SlotRenderControl(index))
            parkEntity(e)

            val state = BoxUtilCombatVfx.addEntity(engine, e)
            if (state != 0) {
                e.delete()
                broken = true
                log.warn("池化 sprite 租约实体注册失败（addEntity 返回 $state，layer=${key.layer}，texture=${key.textureKey}），该池视觉缺席")
                return null
            }
            entities[index] = e
            e
        } catch (t: Throwable) {
            broken = true
            log.warn("池化 sprite 租约实体创建失败（layer=${key.layer}，texture=${key.textureKey}），该池视觉缺席", t)
            null
        }
    }

    private fun resetEntity(e: SpriteEntity, spec: SpriteLeaseSpec, index: Int) {
        val mat = e.materialData
        when {
            spec.spritePath != null -> {
                val s = resolveSprite(spec.spritePath)
                mat.setDiffuse(s)
                mat.setEmissive(if (spec.bindEmissive) s else BoxDatabase.BUtil_NONE)
            }

            spec.sprite != null -> {
                mat.setDiffuse(spec.sprite)
                mat.setEmissive(if (spec.bindEmissive) spec.sprite else BoxDatabase.BUtil_NONE)
            }

            spec.texId != null -> {
                mat.setDiffuse(spec.texId)
                if (spec.bindEmissive) mat.setEmissive(spec.texId) else mat.setEmissive(BoxDatabase.BUtil_NONE)
            }
        }
        e.setUVStart(spec.uvStartX, spec.uvStartY)
        e.setUVEnd(spec.uvEndX, spec.uvEndY)
        e.setBaseSizePerTiles(spec.baseSizeHalfWidth, spec.baseSizeHalfHeight)

        mat.setColor(spec.color)
        mat.setEmissiveColor(spec.emissiveColor)
        mat.glowPower = spec.glowPower
        mat.alphaToEmissive = spec.alphaToEmissive
        mat.isColorToEmissive = spec.isColorToEmissive
        spec.emissiveState?.let { mat.setEmissiveState(it.x, it.y, it.z) }
        mat.isAdditionEmissive = spec.isAdditionEmissive
        mat.isIgnoreIllumination = spec.isIgnoreIllumination

        e.setGlobalTimer(0f, 1e7f, 0f)

        val facing = BoxUtilCombatVfx.normalizeFacingDeg(spec.facingDeg)
        val scale = spec.scale
        if (scale != null) {
            e.setStateVanilla(Vector2f(spec.location), facing, scale)
        } else {
            e.setStateVanilla(Vector2f(spec.location), facing)
        }

        if (key.instanced) setupInstances(e, spec, index)
    }

    private fun setupInstances(e: SpriteEntity, spec: SpriteLeaseSpec, index: Int) {
        var table = instanceTables[index]
        if (table == null || table.size != spec.maxInstances) {
            table = ArrayList<Instance2Data>(spec.maxInstances)
            instanceTables[index] = table
        } else {
            table.clear()
        }
        repeat(spec.maxInstances) {
            val d = Instance2Data()
            d.setTimer(0f, 99999f, 0f)
            d.setColor(255, 255, 255, 255)
            d.setEmissiveColor(255, 255, 255, 255)
            table += d
        }
        @Suppress("UNCHECKED_CAST")
        e.setInstanceData(table as MutableList<InstanceDataAPI>, 0f, 99999f, 0f)
        e.mallocInstance(InstanceType.DYNAMIC_2D, spec.maxInstances)
        e.instanceDataRefreshIndex = 0
        e.instanceDataRefreshOffset = 0
        e.setInstanceDataRefreshAllFromCurrentIndex()
        e.submitInstance()
        e.setRenderingCount(0)
        e.isAlwaysRefreshInstanceData = true
        e.setInstanceTimerOverride(1f, BoxEnum.TIMER_FULL)
    }

    private fun parkEntity(e: SpriteEntity) {
        e.materialData.colorAlpha = 0f
        e.materialData.emissiveColorAlpha = 0f
        if (key.instanced) e.setRenderingCount(0)
    }

    /** BoxUtil 非实例化直绘实体无 alpha 早退，FREE 槽位必须经 ControlData 让整实体跳过渲染。 */
    private inner class SlotRenderControl(private val index: Int) : ControlDataAPI {
        override fun controlCanRenderNow(renderEntity: RenderDataAPI): Boolean =
            slots.slots[index].mode != SpriteLeaseSlots.Mode.FREE
    }

    companion object {
        private const val OVERFLOW_WARN_STRIDE = 64
    }
}
