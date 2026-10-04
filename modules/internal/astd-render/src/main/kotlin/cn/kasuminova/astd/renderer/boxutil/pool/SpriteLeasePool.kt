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

/**
 * 池化 SpriteEntity 租约键：同池共享渲染层/贴图身份/混合模式/实例化口径/容量口径。
 * 其余逐租约属性（纹理绑定/UV/尺寸/颜色/材质参数/变换/实例表/寿命口径）均为检出参数。
 *
 * [textureKey] 是贴图身份而非绑定本身：调用方给路径贴图填路径、给运行期生成/直载纹理
 * 起一个唯一名（如 "outline:&lt;hullId&gt;"），仅用于分池与 WARN 定位，真实纹理由
 * [SpriteLeaseSpec] 三态绑定给出。
 */
data class SpriteLeaseKey(
    val layer: CombatEngineLayers,
    val textureKey: String,
    /** true = additive 混合，false = normal alpha 混合（逐 key 固定，实体创建时定死）。 */
    val additive: Boolean,
    /** true = 实例化渲染（Instance2Data 表由池按检出 spec 建/重置，经 [SpriteLease.instances] 暴露）。 */
    val instanced: Boolean,
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

/**
 * sprite 租约检出规格：一次检出对池化实体做的**全量状态重置**。
 *
 * 纹理绑定三选一（[spritePath] 先 loadTexture 再 getSprite / [sprite] 直挂 SpriteAPI /
 * [texId] 直挂原始 GL 纹理 id），[bindEmissive]=true 时 emissive 绑同一纹理，
 * 否则重置回出厂的 BUtil_NONE（防上一租约残留）。UV 默认整幅 0,0 → 1,1。
 *
 * 寿命口径三选一（与 trail 租约一致）：
 * - [envelope] 非空：一次性包络，池逐帧驱动 color/emissive 双 alpha（基准 = [color]/
 *   [emissiveColor] 的 alpha），到期自动泊车归还（fire-and-forget）；
 * - [watchdogHeartbeat] > 0：手动 alpha + 心跳看门狗——调用方每帧 [SpriteLease.touch]，
 *   超过 heartbeat 秒未触即快照当前双 alpha 线性淡出 [watchdogFadeOut] 秒后自动泊车；
 * - 两者皆空：纯手动，调用方自行驱动 alpha 并显式 [SpriteLease.release]。
 */
class SpriteLeaseSpec(
    val location: Vector2f,
    val facingDeg: Float,
    /** 纹理绑定三选一：路径（池内 loadTexture + getSprite 缓存）。 */
    val spritePath: String? = null,
    /** 纹理绑定三选一：直挂 SpriteAPI（含 BoxDatabase 内建贴图）。 */
    val sprite: SpriteAPI? = null,
    /** 纹理绑定三选一：直挂原始 GL 纹理 id（BoxUtil TextureManager 直载/运行期生成纹理）。 */
    val texId: Int? = null,
    val uvStartX: Float = 0f,
    val uvStartY: Float = 0f,
    val uvEndX: Float = 1f,
    val uvEndY: Float = 1f,
    /** true = emissive 绑与 diffuse 同一纹理；false = 重置回 BUtil_NONE。 */
    val bindEmissive: Boolean = false,
    /** 世界半宽/半高（setBaseSizePerTiles 语义）。 */
    val baseSizeHalfWidth: Float,
    val baseSizeHalfHeight: Float,
    /** 材质 diffuse / emissive 色；alpha 即包络/释放淡出的基准值。 */
    val color: Color = Color.WHITE,
    val emissiveColor: Color = Color.WHITE,
    val glowPower: Float = 1f,
    val alphaToEmissive: Float = 0f,
    val isColorToEmissive: Float = 0f,
    /** 非空时以三参（alphaMix/colorMix/glow）调 setEmissiveState，覆盖上面三个单参；null = 不调。 */
    val emissiveState: Vector3f? = null,
    val isAdditionEmissive: Boolean = false,
    val isIgnoreIllumination: Boolean = false,
    /** 实例化池（[SpriteLeaseKey.instanced]=true）必填 >0：Instance2Data 表容量。 */
    val maxInstances: Int = 0,
    /** 非默认缩放；null = 二参 setStateVanilla。 */
    val scale: Vector2f? = null,
    val envelope: TrailLeaseEnvelope? = null,
    val watchdogHeartbeat: Float = 0f,
    val watchdogFadeOut: Float = 0f,
)

/**
 * 池化 SpriteEntity 租约句柄。租约期间调用方可逐帧驱动 [entity] 的变换/（手动模式）alpha/
 * （实例化池）[instances]；泊车（包络到期/看门狗淡出完/显式释放）后 [active] 转 false，
 * 实体 alpha 归零 + 渲染循环级跳过，留在池内复用。
 * 句柄带槽位代数校验：槽位被再认领后旧句柄的 touch/release 全部静默无效（防串租约）。
 */
class SpriteLease internal constructor(
    private val binding: SpriteLeaseBinding,
    private val slotIndex: Int,
    private val generation: Int,
    val entity: SpriteEntity,
    /** 实例化池的 Instance2Data 表（池已在检出时建/重置并提交）；非实例化池为 null。 */
    val instances: MutableList<Instance2Data>?,
) {
    /** 槽位仍归本租约（含看门狗/释放淡出途中）。 */
    val active: Boolean get() = binding.isHeldBy(slotIndex, generation)

    /** 心跳触活：看门狗模式每帧调用；淡出途中触活即复活回手动模式。 */
    fun touch() = binding.touch(slotIndex, generation)

    /**
     * 显式归还：[fadeOutSeconds] > 0 时先快照当前双 alpha 线性淡出再泊车，0 = 立即泊车。
     * 归还后本句柄 [active] 转 false。
     */
    fun release(fadeOutSeconds: Float = 0f) = binding.release(slotIndex, generation, fadeOutSeconds)
}

/**
 * 池化 SpriteEntity 租约槽位表（纯逻辑，单测可完整验证）：初始容量 + 按需扩容，租约-槽位一一绑定。
 * 扩容/硬上限/状态机/代数语义与 [TrailLeaseSlots] 完全一致，差异仅在 alpha 模型：
 * sprite 是单 alpha（color + emissive 两个基准值），trail 是 start/end 四值。
 */
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

        // ENVELOPE = 检出基准 alpha；RELEASING = 释放时刻快照 alpha（绑定层读写）
        var baseColorAlpha = 0f
        var baseEmissiveAlpha = 0f
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
            // 复活回手动：alpha 由调用方本帧重写（重钉 timer 打断淡出语义）
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
    fun snapshotReleaseAlphas(index: Int, colorAlpha: Float, emissiveAlpha: Float) {
        val s = slots[index]
        s.baseColorAlpha = colorAlpha
        s.baseEmissiveAlpha = emissiveAlpha
    }

    /** 实体建失败时的强制回收（认领后实体不可用：槽位不得滞留）。 */
    fun forceFree(index: Int) {
        free(slots[index])
    }

    /** 推进全部在租槽位：返回本帧需要绑定层处理的事件（看门狗触发快照 / 到期泊车）。 */
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
internal sealed interface SpriteLeaseEvent {
    val index: Int

    data class BeginReleaseFade(override val index: Int) : SpriteLeaseEvent
    data class Parked(override val index: Int) : SpriteLeaseEvent
}

/**
 * 池化 SpriteEntity 租约绑定：初始容量槽位表 + 按需扩容 + 槽位状态机（实体惰性创建，
 * 检出到该槽才建，禁止预热）。
 *
 * 检出即全量重置（纹理三态绑定/UV/尺寸/颜色/材质参数/变换/实例表/定时器重钉），归还/到期
 * 泊车而非 delete——实体常驻战斗域，随 BoxUtil 战斗切换清簿 delete。
 *
 * 泊车必须做到渲染循环级跳过而非仅 alpha=0（BoxUtil BUtil_EntityImpl.processSpriteEntity：
 * 非实例化直绘实体无 alpha 早退，仅 controlCanRenderNow=false 才 continue）——每槽位实体挂
 * [SlotRenderControl]，槽位 FREE 即整实体跳过渲染；实例化实体 additionally renderingCount=0。
 *
 * 扩容只扩槽位结构，实体保持惰性创建（检出到该槽才建，禁止预热）；每次扩容记一条遥测 WARN。
 * 触及 [SpriteLeaseKey.maxCapacity] 才拒发 + 节流 WARN。
 */
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
            // 触及硬上限才拒发：不抢占在租槽位；首次与每 64 次拒发记 WARN（节流可诊断，禁止静默）
            val count = slots.overflowCount
            if (count == 1 || count % OVERFLOW_WARN_STRIDE == 0) {
                log.warn("池化 sprite 租约触及硬上限拒发（maxCapacity=${key.maxCapacity}，layer=${key.layer}，texture=${key.textureKey}，累计拒发 $count 次），本次视觉缺席——需排查租约泄漏或重估峰值")
            }
            return null
        }
        if (slots.capacity != capacityBefore) {
            // 按需扩容遥测：初始容量预估偏低是有价值的信号，非故障
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
        // 立即归还（fadeOut=0）同步泊车（与 TrailLeaseBinding.release 同口径）：槽位 FREE 后
        // 渲染循环虽已被 SlotRenderControl 跳过，实体残留 alpha/实例计数仍应归零作双保险；
        // 先记持有状态再释放——过期句柄（槽位已被新租约认领）不得泊车新租约的实体。
        val wasHeld = slots.isHeld(index, generation)
        val needSnapshot = slots.release(index, generation, fadeOut)
        if (needSnapshot) {
            snapshotCurrentAlphas(index)
            return
        }
        if (wasHeld) entities.getOrNull(index)?.let { parkEntity(it) }
    }

    /** 池推进：包络/看门狗/释放淡出的 alpha 写盘与到期泊车。 */
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

    /** 快照实体当前双 alpha 进槽位（RELEASING 线性淡出的基准）。 */
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

    /** 路径贴图解析：先 loadTexture 进缓存，裸 getSprite 会拿到 textureID=0 的壳（TriShard 实锤教训）。 */
    private fun resolveSprite(path: String): SpriteAPI = spriteCache.getOrPut(path) {
        Global.getSettings().apply { loadTexture(path) }.getSprite(path)
    }

    private fun getOrCreateEntity(index: Int): SpriteEntity? {
        while (entities.size <= index) entities += null
        while (instanceTables.size <= index) instanceTables += null
        entities[index]?.let {
            if (!it.hasDelete()) return it
            // 池实体被外部路径 delete（战斗切换 purge 等）：槽位不得持有死实体，重建并重新登记
            log.warn("池化 sprite 租约实体被外部回收（layer=${key.layer}，texture=${key.textureKey}，槽位=$index），重建实体并重新登记")
            entities[index] = null
            instanceTables[index] = null
        }
        return try {
            val e = SpriteEntity()
            if (key.additive) e.setAdditiveBlend() else e.setNormalBlend()
            e.setLayer(key.layer)
            // 常驻：消亡由槽位状态机泊车，不走 delete（引用本就会滞留）
            e.setGlobalTimer(0f, 1e7f, 0f)
            // 渲染循环级泊车跳过：槽位 FREE 即 controlCanRenderNow=false，整实体不进渲染
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

    /**
     * 检出全量重置：纹理三态绑定/UV/尺寸/颜色/材质参数/变换/实例表/定时器重钉。
     * 池实体被上一租约改过的任何可变状态都在这里回到本租约口径。
     */
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

        // 重钉常驻定时器：上一租约期间无人应改它，重钉兜底保证不被外部路径判死
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

    /**
     * 实例表建/重置：timer 钉 0..99999、白底 255（颜色由 materialData emissiveColor 控制，
     * 实例 alpha 非 0 即可），setInstanceData + submit + renderingCount=0 + alwaysRefresh，
     * instanceTimerOverride 钉 FULL——实例寿命/alpha 全部由调用方逐帧驱动，不走 BoxUtil 自管理。
     */
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

    /**
     * 泊车：双 alpha 归零 + 实例化实体 renderingCount=0。渲染循环级跳过由
     * [SlotRenderControl] 保证（槽位 FREE → controlCanRenderNow=false），这里只是双保险
     * （顶点着色器 max(color.a, emissive.a)<=0 也有离屏早退）。
     */
    private fun parkEntity(e: SpriteEntity) {
        e.materialData.colorAlpha = 0f
        e.materialData.emissiveColorAlpha = 0f
        if (key.instanced) e.setRenderingCount(0)
    }

    /** 槽位渲染开关：FREE 即整实体跳过渲染（非实例化直绘实体无 alpha 早退，必须走 ControlData）。 */
    private inner class SlotRenderControl(private val index: Int) : ControlDataAPI {
        override fun controlCanRenderNow(renderEntity: RenderDataAPI): Boolean =
            slots.slots[index].mode != SpriteLeaseSlots.Mode.FREE
    }

    companion object {
        /** 触及硬上限拒发 WARN 节流步长（首次必记）。 */
        private const val OVERFLOW_WARN_STRIDE = 64
    }
}
