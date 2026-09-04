package cn.kasuminova.astd.impl.buff

import cn.kasuminova.astd.api.buff.Buff
import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.api.buff.StackDecayMode
import cn.kasuminova.astd.api.buff.StackableBuff
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.WeaponSlotAPI
import com.fs.starfarer.api.loading.WeaponSpecAPI
import org.apache.log4j.AppenderSkeleton
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.apache.log4j.spi.LoggingEvent
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Buff 单测公共桩：log4j WARN 捕获器、ShipAPI/WeaponAPI mock、基础 Buff 桩与三模式衰减参考实现。
 */

/** log4j 1.x 事件捕获器：附着到目标 logger 上断言 WARN 输出（用罢 [detach] 还原）。 */
class WarnCapture(loggerClass: Class<*>) {
    private val logger: Logger = Logger.getLogger(loggerClass)
    private val previousLevel: Level? = logger.level
    val events = mutableListOf<LoggingEvent>()

    private val appender = object : AppenderSkeleton() {
        override fun append(event: LoggingEvent) {
            events += event
        }

        override fun close() {}
        override fun requiresLayout(): Boolean = false
    }

    init {
        logger.level = Level.WARN
        logger.addAppender(appender)
    }

    /** 捕获到的 WARN 及以上级别的渲染后消息。 */
    fun messages(): List<String> = events.map { it.renderedMessage }

    fun detach() {
        logger.removeAppender(appender)
        logger.level = previousLevel
    }
}

/** 造一艘 customData 真实可用的 ShipAPI mock（[data] 即其 customData 本体）。 */
fun stubShip(data: HashMap<String, Any?> = HashMap(), hulk: Boolean = false, weapons: List<WeaponAPI> = emptyList()): ShipAPI {
    val ship = mock(ShipAPI::class.java)
    `when`(ship.customData).thenReturn(data)
    doAnswer { inv ->
        data[inv.getArgument(0)] = inv.getArgument(1)
        null
    }.`when`(ship).setCustomData(anyString(), nullable(Any::class.java))
    doAnswer { inv ->
        data.remove(inv.getArgument(0))
        null
    }.`when`(ship).removeCustomData(anyString())
    `when`(ship.isHulk).thenReturn(hulk)
    `when`(ship.allWeapons).thenReturn(weapons)
    return ship
}

/** 造一件带槽位与 spec 的 WeaponAPI mock。 */
fun stubWeapon(slotId: String, weaponId: String): WeaponAPI {
    val slot = mock(WeaponSlotAPI::class.java)
    `when`(slot.id).thenReturn(slotId)
    val spec = mock(WeaponSpecAPI::class.java)
    `when`(spec.weaponId).thenReturn(weaponId)
    val weapon = mock(WeaponAPI::class.java)
    `when`(weapon.slot).thenReturn(slot)
    `when`(weapon.spec).thenReturn(spec)
    return weapon
}

/** 基础 Buff 桩：记录 advance/onRemove 调用，宿主有效性可开关。 */
open class StubBuff(
    override val id: String = "astd_test_buff",
    override val lifetime: BuffLifetime = BuffLifetime.HOST_BOUND,
) : Buff {
    var hostValid = true
    var advanceCalls = 0
        private set
    var removeCalls = 0
        private set
    var lastAmount = 0f
        private set

    override fun isHostValid(): Boolean = hostValid

    override fun advance(amount: Float) {
        advanceCalls++
        lastAmount = amount
    }

    override fun onRemove() {
        removeCalls++
    }
}

/**
 * 三模式衰减参考实现（规格 §1.4-5/7 的测试桩，同时是后续武器实现的对照语义）：
 * - 层数增减统一走 [addStacks] 的 clamp 路径；
 * - CONTINUOUS：按 stacksPerSecond 连续扣减（小数累积，整层结算）；
 * - WINDOWED：距最近刷新静默 [windowSeconds] 后按 stacksPerSecond 扣减，恰在窗口边界不衰减，
 *   越过边界的帧只计超出部分；
 * - EXPIRE_ALL：距最近刷新满 [durationSeconds] 后整 Buff 经 [BuffHost.remove] 移除；
 * - 0 值防线：maxStacks <= 0 记 WARN 并 clamp 到 1；stacksPerSecond <= 0（衰减模式需要时）记 WARN 并关停衰减。
 */
class ReferenceStackableBuff(
    override val decayMode: StackDecayMode,
    maxStacks: Int,
    stacksPerSecond: Float = 0f,
    private val windowSeconds: Float = 0f,
    private val durationSeconds: Float = 0f,
    private val host: BuffHost? = null,
) : StackableBuff {
    override val id: String = "astd_test_stack"
    override val lifetime: BuffLifetime = BuffLifetime.HOST_BOUND

    override val maxStacks: Int
    private val rate: Float

    override var stacks: Int = 0
        private set

    private var decayAccumulator = 0f
    private var elapsedSinceRefresh = 0f

    /** EXPIRE_ALL 触发后置位（host 为空时供测试断言；有 host 时已随之移除）。 */
    var expired = false
        private set
    var removeCalls = 0
        private set

    init {
        if (maxStacks <= 0) {
            log.warn("maxStacks 非法（$maxStacks），属配置错误，clamp 到 1: id=$id")
            this.maxStacks = 1
        } else {
            this.maxStacks = maxStacks
        }
        if (decayMode != StackDecayMode.EXPIRE_ALL && stacksPerSecond <= 0f) {
            log.warn("stacksPerSecond 非法（$stacksPerSecond），属配置错误，衰减关停（不静默恒零）: id=$id, mode=$decayMode")
            this.rate = 0f
        } else {
            this.rate = stacksPerSecond
        }
    }

    override fun isHostValid(): Boolean = true

    override fun addStacks(n: Int): Int {
        val before = stacks
        stacks = (stacks + n).coerceIn(0, maxStacks)
        // 正向叠加刷新衰减窗口/到期计时（命中刷新语义）。
        if (n > 0) elapsedSinceRefresh = 0f
        return stacks - before
    }

    override fun advance(amount: Float) {
        elapsedSinceRefresh += amount
        when (decayMode) {
            StackDecayMode.CONTINUOUS -> applyDecay(rate * amount)
            StackDecayMode.WINDOWED -> {
                val overshoot = elapsedSinceRefresh - windowSeconds
                if (overshoot > 0f) {
                    // 恰在窗口边界（overshoot == 0）不衰减；越过边界的帧只计超出部分。
                    applyDecay(rate * minOf(amount, overshoot))
                }
            }
            StackDecayMode.EXPIRE_ALL -> {
                if (!expired && elapsedSinceRefresh >= durationSeconds) {
                    expired = true
                    host?.remove(this)
                }
            }
        }
    }

    override fun onRemove() {
        removeCalls++
    }

    /** 小数累积、整层结算；扣层统一走 [addStacks] 的 clamp 路径。 */
    private fun applyDecay(amount: Float) {
        decayAccumulator += amount
        // 浮点误差容忍：速率*时间在边界上常差 1e-7 量级（如 10/s * 0.1s = 0.99999905），
        // 加 EPS 后取整，避免恰满一层时被截断吞掉。
        val whole = (decayAccumulator + STACK_EPS).toInt()
        if (whole > 0) {
            decayAccumulator -= whole
            addStacks(-whole)
        }
    }

    private companion object {
        const val STACK_EPS = 1e-4f
        val log = Global.getLogger(ReferenceStackableBuff::class.java)
    }
}
