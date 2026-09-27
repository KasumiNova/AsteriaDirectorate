package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderPhase
import org.lwjgl.util.vector.Vector2f
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 三角碎片航迹发射器组件测试（持续发射器的树内化语义）：
 * - 节拍：满 [ShardWakeSpec.interval] 才喷、喷后累积器清零（reset-to-0，对齐旧 .wpn EveryFrame 语义）；
 * - 相位闸：仅 Active 期间累积与发射（弹体淡出/移除即停喷，无需消亡移交）；
 * - 发射参数域：散布半径 / 初速域 / 飞行方向张角逐颗生效（engine=null 时子节点不灌批，批内实例可断言）。
 */
class ShardWakeComponentTest {

    private val spec = ShardWakeSpec(
        interval = 0.05f,
        perTick = 3,
        scatterRadius = 8f,
        speedMin = 100f,
        speedMax = 150f,
        spreadDeg = 8f,
        shardLength = 34f,
        coreColor = ASTDColor(1f, 1f, 1f, 1f),
        fringeColor = ASTDColor(0.5f, 0.7f, 1f, 1f),
    )

    private fun ctx(host: PointHost, phase: RenderPhase, amount: Float) = RenderContextImpl(
        engine = null,
        host = host,
        frame = FrameStateImpl(
            elapsed = 0f,
            logicElapsed = 0f,
            amountThisFrame = amount,
            origin = Vector2f(host.origin),
            facing = host.facingDeg,
            length = 0f,
            endpoint = null,
            worldUnitsPerPixel = 1f,
            active = phase == RenderPhase.Active,
            intensity = 1f,
            phase = phase,
            flightProgress = 0f,
            dissolve = 0f,
            fadeReason = null,
        ),
    )

    @Test
    fun `cadence emits only on full tick and resets accumulator`() {
        val host = PointHost("h", Vector2f(100f, 200f), 90f)
        val comp = ShardWakeComponent("w", spec)

        // 0.02 + 0.02 = 0.04 < 0.05：两帧不喷。
        comp.advance(ctx(host, RenderPhase.Active, 0.02f), 0.02f)
        comp.advance(ctx(host, RenderPhase.Active, 0.02f), 0.02f)
        assertTrue(comp.shards.batches[0].instances.isEmpty(), "未满节拍不得发射")
        assertEquals(0.04f, comp.accumulator, 1e-4f)

        // 第三帧 0.04+0.02=0.06 >= 0.05：喷一撮 3 颗，累积器清零。
        comp.advance(ctx(host, RenderPhase.Active, 0.02f), 0.02f)
        assertEquals(3, comp.shards.batches[0].instances.size, "满节拍喷 perTick 颗")
        assertEquals(0f, comp.accumulator, 1e-4f, "喷后累积器清零（reset-to-0）")

        // 再满一拍再喷一撮（累积 3+3）。
        repeat(3) { comp.advance(ctx(host, RenderPhase.Active, 0.02f), 0.02f) }
        assertEquals(6, comp.shards.batches[0].instances.size, "下一拍再喷 3 颗（engine=null 不灌批，实例留存）")
    }

    @Test
    fun `only active phase accumulates and emits`() {
        val host = PointHost("h", Vector2f(0f, 0f), 0f)
        val comp = ShardWakeComponent("w", spec)

        repeat(10) { comp.advance(ctx(host, RenderPhase.FadingOut, 0.02f), 0.02f) }
        assertTrue(comp.shards.batches[0].instances.isEmpty(), "淡出中不得发射")
        assertEquals(0f, comp.accumulator, 1e-4f, "淡出中不累积节拍")

        repeat(10) { comp.advance(ctx(host, RenderPhase.Removed, 0.02f), 0.02f) }
        assertTrue(comp.shards.batches[0].instances.isEmpty(), "移除后不得发射")
        assertEquals(0f, comp.accumulator, 1e-4f)
    }

    @Test
    fun `emission params stay in spec domains`() {
        val origin = Vector2f(100f, 200f)
        val facing = 30f
        val host = PointHost("h", origin, facing)
        val comp = ShardWakeComponent("w", spec)

        // 喂满 10 拍共 30 颗做域断言。
        repeat(10) { comp.advance(ctx(host, RenderPhase.Active, 0.05f), 0.05f) }
        val instances = comp.shards.batches[0].instances
        assertEquals(30, instances.size)

        val facingRad = Math.toRadians(facing.toDouble())
        for (inst in instances) {
            val dx = inst.pos.x - origin.x
            val dy = inst.pos.y - origin.y
            assertTrue(sqrt(dx * dx + dy * dy) <= 8f + 1e-3f, "散布半径域: ($dx, $dy)")

            val speed = sqrt(inst.vel.x * inst.vel.x + inst.vel.y * inst.vel.y)
            assertTrue(speed in 100f - 1e-3f..150f + 1e-3f, "初速域: $speed")

            // 速度方向与飞行向夹角 ≤ 8°（点积口径，免角度归一化）。
            val dot = (inst.vel.x * cos(facingRad) + inst.vel.y * sin(facingRad)) / speed
            assertTrue(dot >= cos(Math.toRadians(8.0)).toFloat() - 1e-3f, "张角域: dot=$dot")
        }
    }
}
