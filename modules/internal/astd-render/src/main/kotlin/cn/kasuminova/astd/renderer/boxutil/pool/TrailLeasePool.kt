package cn.kasuminova.astd.renderer.boxutil.pool

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.graphics.SpriteAPI
import org.boxutil.units.standard.entity.TrailEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 池化 TrailEntity 租约键：同池共享渲染层/核心与 fringe 贴图/mixFactor/容量口径。
 * 其余逐租约属性（节点表/宽度/颜色/alpha/fill/纹理流动/寿命口径）均为检出参数。
 */
data class TrailLeaseKey(
    val layer: CombatEngineLayers,
    val coreSpritePath: String,
    val fringeSpritePath: String,
    val mixPower: Float,
    /** 初始容量：槽位结构起始大小，按真实典型峰值估算（实体仍惰性创建，不预热实体）。 */
    val capacity: Int,
    /**
     * 硬上限（默认 [DEFAULT_MAX_CAPACITY_MUL]× 初始容量）：池满按需扩容的上界。定位是
     * **租约泄漏探测器**而非容量规划——所有租约都有自动归还路径（包络到期/看门狗/release/
     * forceFree），池持续增长只可能来自真实并发峰值或泄漏 bug，触及上限即拒发 + 节流 WARN。
     */
    val maxCapacity: Int = capacity * DEFAULT_MAX_CAPACITY_MUL,
) {
    companion object {
        /** [maxCapacity] 默认倍数。 */
        const val DEFAULT_MAX_CAPACITY_MUL = 256
    }
}

/** 一次性包络寿命口径（fadeIn → full → fadeOut 线性）：池逐帧驱动 alpha，到期自动泊车归还。 */
data class TrailLeaseEnvelope(val fadeIn: Float, val full: Float, val fadeOut: Float)

/**
 * 租约检出规格：一次检出对池化实体做的**全量状态重置**。
 *
 * 默认值对齐新建 TrailEntity 的出厂语义（fill=1/1 无羽化、texturePixels=1、textureSpeed=4、
 * uvOffset=0、jitter=0）——历史生产者走 createBeamVisual/FromCenter 未显式设置的旋钮，
 * 池化后拿到的逐值一致，保证视觉零回归。
 *
 * 寿命口径三选一：
 * - [envelope] 非空：一次性包络，池驱动 alpha，到期自动归还（fire-and-forget）；
 * - [watchdogHeartbeat] > 0：手动 alpha + 心跳看门狗（对齐 BoxUtil 逐帧重钉 globalTimer 语义）——
 *   调用方每帧 [TrailLease.touch]，超过 heartbeat 秒未触即快照当前 alpha 线性淡出
 *   [watchdogFadeOut] 秒后自动泊车（宿主停更时实体不滞留）；
 * - 两者皆空：纯手动，调用方自行驱动 alpha 并显式 [TrailLease.release]。
 */
class TrailLeaseSpec(
    val location: Vector2f,
    val facingDeg: Float,
    /** 局部节点表（拷贝进池实体自有缓冲，节点序即 UV 方向，调用方按原创建路径逐值给出）。 */
    val nodes: List<Vector2f>,
    val startWidth: Float,
    val endWidth: Float,
    /** 材质 diffuse / emissive 色（START/END 节点色固定白，仅 alpha 逐租约）。 */
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
    /** 非默认缩放（锥面弧恒等缩放显式锚定用）；null = 二参 setStateVanilla。 */
    val scale: Vector2f? = null,
    val envelope: TrailLeaseEnvelope? = null,
    val watchdogHeartbeat: Float = 0f,
    val watchdogFadeOut: Float = 0f,
)

/**
 * 池化 TrailEntity 租约句柄。租约期间调用方可逐帧驱动 [entity] 的几何/变换/（手动模式）alpha；
 * 泊车（包络到期/看门狗淡出完/显式释放）后 [active] 转 false，实体 alpha 归零留在池内复用。
 * 句柄带槽位代数校验：槽位被再认领后旧句柄的 touch/release 全部静默无效（防串租约）。
 */
class TrailLease internal constructor(
    private val binding: TrailLeaseBinding,
    private val slotIndex: Int,
    private val generation: Int,
    val entity: TrailEntity,
) {
    /** 槽位仍归本租约（含看门狗/释放淡出途中）。 */
    val active: Boolean get() = binding.isHeldBy(slotIndex, generation)

    /** 心跳触活：看门狗模式每帧调用（对齐 BoxUtil 逐帧重钉 globalTimer）；淡出途中触活即复活回手动模式。 */
    fun touch() = binding.touch(slotIndex, generation)

    /**
     * 显式归还：[fadeOutSeconds] > 0 时先快照当前 alpha 线性淡出再泊车（对齐 BoxUtil 定时器
     * 淡出语义），0 = 立即泊车。归还后本句柄 [active] 转 false。
     */
    fun release(fadeOutSeconds: Float = 0f) = binding.release(slotIndex, generation, fadeOutSeconds)
}

/**
 * 池化 TrailEntity 租约槽位表（纯逻辑，单测可完整验证）：初始容量 + 按需扩容，租约-槽位一一绑定。
 *
 * 扩容模型：[capacity] 为当前槽位数（起始 = 初始容量）；claim 无空闲槽且未达 [maxCapacity] 时
 * 按 max([GROW_STEP_MIN], 当前容量/2) 步长在尾部追加空槽——小池 8 起步避免连扩刷 WARN，大池
 * 1.5× 几何增长摊销并天然限制扩容次数。扩容只扩槽位结构，实体由绑定层惰性创建（禁止预热）。
 *
 * [maxCapacity] 是**租约泄漏探测器**而非容量规划：所有租约都有自动归还路径（包络到期/看门狗/
 * release/forceFree），池持续增长只可能来自真实并发峰值或泄漏 bug——触及上限才拒发 + 累计
 * [overflowCount]（绑定层据此节流 WARN），不抢占在租槽位（抢占会半路杀死存活视觉）。
 *
 * 槽位状态机：FREE → LEASED（envelope / manual+watchdog / 纯手动）→ RELEASING（快照淡出）→ FREE。
 * 每槽带 [Slot.generation] 代数：归还/再认领后旧句柄操作全部失效；扩容只在尾部追加空槽，
 * 不影响在租槽位的代数与归属。
 */
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

        // ENVELOPE = 检出基准 alpha；RELEASING = 释放时刻快照 alpha（绑定层读写）
        var startAlpha = 0f
        var endAlpha = 0f
        var startEmissiveAlpha = 0f
        var endEmissiveAlpha = 0f
    }

    internal val slots = ArrayList<Slot>(initialCapacity).also { list -> repeat(initialCapacity) { list += Slot() } }

    /** 当前槽位数（扩容单调递增，上界 [maxCapacity]）。 */
    var capacity: Int = initialCapacity
        private set

    var leasedCount = 0
        private set

    /** 达到 [maxCapacity] 后的拒发累计（绑定层节流 WARN 的数据源）。 */
    var overflowCount = 0
        private set

    /**
     * 认领空闲槽；无空闲槽先按需扩容，已达 [maxCapacity] 才返回 -1 并累计拒发（不抢占在租槽位）。
     * alpha 基准仅包络模式使用（池逐帧乘包络写实体）；手动模式由调用方全权。
     */
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

    /** 池满按需扩容：尾部追加空槽并返回首个新槽下标；已达 [maxCapacity] 返回 -1。 */
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
        /** 扩容步长下限：小池 8 起步，避免小池逐级连扩刷 WARN。 */
        const val GROW_STEP_MIN = 8
    }

    /** 句柄是否仍持有该槽（LEASED/RELEASING 且代数一致）。 */
    fun isHeld(index: Int, generation: Int): Boolean {
        val s = slots[index]
        return s.generation == generation && s.mode != Mode.FREE
    }

    /** 心跳触活：看门狗计时归零；RELEASING 途中触活复活回 MANUAL（对齐重钉定时器打断淡出）。 */
    fun touch(index: Int, generation: Int) {
        val s = slots[index]
        if (s.generation != generation) return
        if (s.mode == Mode.MANUAL) s.sinceTouch = 0f
        if (s.mode == Mode.RELEASING) {
            // 复活回手动：alpha 由调用方本帧重写（BoxUtil 重钉 timer 语义：淡出被打断回满亮）
            s.mode = Mode.MANUAL
            s.age = 0f
            s.sinceTouch = 0f
        }
    }

    /** 包络年龄归零（ENVELOPE 模式）。 */
    fun refreshEnvelope(index: Int, generation: Int) {
        val s = slots[index]
        if (s.generation != generation || s.mode != Mode.ENVELOPE) return
        s.age = 0f
    }

    /**
     * 显式释放：fadeOut > 0 进入 RELEASING（快照当前 alpha 由调用方给出），否则立即 FREE。
     * @return true = 本调用使槽位进入 RELEASING（调用方需快照实体当前 alpha 回写 [snapshotReleaseAlphas]）
     */
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

    /** RELEASING 槽位的 alpha 快照（绑定层在释放/看门狗触发时读实体当前值写入）。 */
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

    /** 实体建失败时的强制回收（认领后实体不可用：槽位不得滞留）。 */
    fun forceFree(index: Int) {
        free(slots[index])
    }

    /** 推进全部在租槽位：返回本帧需要绑定层处理的事件（看门狗触发快照 / 到期泊车）。 */
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

    /** 槽位当前 alpha 乘数：ENVELOPE=包络值、RELEASING=线性剩余（fadeOut<=0 视为立即完成）、MANUAL=1（调用方全权）、FREE=0。 */
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

/** 槽位推进事件：BeginReleaseFade = 看门狗触发（绑定层快照实体当前 alpha）；Parked = 到期泊车。 */
internal sealed interface TrailLeaseEvent {
    val index: Int

    data class BeginReleaseFade(override val index: Int) : TrailLeaseEvent
    data class Parked(override val index: Int) : TrailLeaseEvent
}

/**
 * 池化 TrailEntity 租约绑定：初始容量常驻实体 + 按需扩容槽位 + 槽位状态机。
 *
 * 检出即全量重置（节点表/宽度/颜色/alpha/fill/纹理流动/变换/定时器重钉），归还/到期泊车
 * （alpha 归零 + 清空节点——BoxUtil 对零节点实体直接跳过渲染）而非 delete——实体常驻战斗域，
 * 随 BoxUtil 战斗切换清簿 delete（实体经
 * [BoxUtilCombatVfx.addEntity] 入登记簿，engine 实例切换 purge 兜底）。
 *
 * 扩容只扩槽位结构，实体保持惰性创建（检出到该槽才建，禁止预热）；每次扩容记一条遥测 WARN
 * （几何增长步长天然限制扩容次数，不另设节流）。触及 [TrailLeaseKey.maxCapacity] 才拒发。
 */
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
            // 触及硬上限才拒发：不抢占在租槽位；首次与每 64 次拒发记 WARN（节流可诊断，禁止静默）。
            // maxCapacity 是租约泄漏探测器——所有租约均有自动归还路径（包络到期/看门狗/release/
            // forceFree），持续增长只可能来自真实并发峰值或租约泄漏 bug，WARN 必须能定位持有方
            val count = slots.overflowCount
            if (count == 1 || count % OVERFLOW_WARN_STRIDE == 0) {
                log.warn("池化光束租约触及硬上限拒发（maxCapacity=${key.maxCapacity}，layer=${key.layer}，core=${key.coreSpritePath}，累计拒发 $count 次），本次视觉缺席——需排查租约泄漏或重估峰值")
            }
            return null
        }
        if (slots.capacity != capacityBefore) {
            // 按需扩容遥测：初始容量预估偏低是有价值的信号，非故障
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
        val needSnapshot = slots.release(index, generation, fadeOut)
        if (needSnapshot) snapshotCurrentAlphas(index)
    }

    /** 池推进：包络/看门狗/释放淡出的 alpha 写盘与到期泊车。 */
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

    /** 快照实体当前 alpha 进槽位（RELEASING 线性淡出的基准）。 */
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
            // 先 loadTexture 进缓存，裸 getSprite 会拿到 textureID=0 的壳（TriShard 实锤教训）
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
            // 池实体被外部路径 delete（战斗切换 purge 等）：槽位不得持有死实体，重建并重新登记，
            // 否则该槽视觉静默缺席（WARN 保留 purge 路径诊断）
            log.warn("池化光束租约实体被外部回收（layer=${key.layer}，槽位=$index），重建实体并重新登记")
            entities[index] = null
        }
        return try {
            val e = TrailEntity()
            e.setAdditiveBlend()
            e.setLayer(key.layer)
            // 常驻：消亡由槽位状态机泊车（alpha 归零），不走 delete（引用本就会滞留）
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

    /**
     * 检出全量重置：节点表（resetNodes 重建，拷贝调用方节点）、宽度/颜色/alpha/fill/纹理流动/
     * 变换/定时器重钉。池实体被上一租约改过的任何可变状态都在这里回到本租约口径。
     */
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

        // 重钉常驻定时器：上一租约期间无人应改它，重钉兜底保证不被外部路径判死
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
        // 清空节点：BoxUtil 渲染循环对 isHaveValidNodeCount()==false 的实体直接 continue
        // （BUtil_EntityImpl.processTrailEntity），泊车实体逐帧渲染开销归零，容量不再构成常驻底噪
        e.resetNodes()
    }

    companion object {
        /** 触及硬上限拒发 WARN 节流步长（首次必记）。 */
        private const val OVERFLOW_WARN_STRIDE = 64
    }
}
