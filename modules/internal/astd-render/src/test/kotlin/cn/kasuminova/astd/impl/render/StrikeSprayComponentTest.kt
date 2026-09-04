package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.RenderPhase
import cn.kasuminova.astd.impl.buff.WarnCapture
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.lwjgl.util.vector.Vector2f
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.atMost
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.awt.Color
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 刺束喷散组件与入口测试（§10.9 v4.1：吞并 ImpactStrikeFx + 侧飞修复，参数逐值平移 v2.2）：
 * - 侧飞回归：12~18 针（错峰三段全激活）逐针位移方向 == 自身 facing，侧向分量 < 1e-3；
 * - 错峰三段：v2.2 二次曲线权重配额（1/9 : 3/9 : 5/9，末段最大）、段延迟单调、首段零延迟；
 * - 参数域：锥面 v2.2 档（9~13 针、[220,520] 速度、0.30~0.70L×0.85×1.20 塑形域）与
 *   aod7 轻量档（7~10 针、无错峰零延迟）逐字域；
 * - 过期停驱；入口防线与烟雾：非法入参 WARN + null；spawnSmoke 星云颗粒数在参数域内。
 */
class StrikeSprayComponentTest {
    private val captures = mutableListOf<WarnCapture>()

    @AfterTest
    fun tearDown() {
        captures.forEach { it.detach() }
        captures.clear()
    }

    private val origin = Vector2f(1000f, 2000f)
    private val core = Color(120, 180, 255)
    private val fringe = Color(60, 120, 255)

    private fun stubEngine(): CombatEngineAPI {
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.customData).thenReturn(HashMap())
        return engine
    }

    /** 锥面 v2.2 档（length=600 推导：0.30~0.70L = 180~420；arc 64° = 40° 半角 ×2×0.8）。 */
    private fun coneSpec() = StrikeSprayVfx.StrikeSpraySpec(
        origin = Vector2f(origin),
        facingDeg = 90f,
        arcDeg = 64f,
        coreColor = core,
        fringeColor = fringe,
        style = StrikeSprayVfx.SprayStyle(
            baseRaysMin = 9,
            baseRaysExtra = 4,
            arc = 64f,
            lengthMin = 180f,
            lengthMax = 420f,
            widthMin = 7.5f,
            widthMax = 15f,
            fullMin = 0.05f,
            fullMax = 0.10f,
            fadeOutMin = 0.30f,
            fadeOutMax = 0.52f,
            speedMin = 220f,
            speedMax = 520f,
            impactScale = 0.85f,
            introRampSeconds = 0.05f,
        ),
    )

    private fun aod7Style() = StrikeSprayVfx.SprayStyle(
        baseRaysMin = 7,
        baseRaysExtra = 3,
        arc = 58f,
        lengthMin = 95f,
        lengthMax = 210f,
        widthMin = 7.5f,
        widthMax = 15.0f,
        fullMin = 0.05f,
        fullMax = 0.10f,
        fadeOutMin = 0.30f,
        fadeOutMax = 0.52f,
        speedMin = 220f,
        speedMax = 520f,
        impactScale = 0.85f,
        introRampSeconds = 0.05f,
    )

    /** 错峰触发档：12+6 → 12~18 针，必触发 ramp（≥12）。 */
    private fun rampSpec() = StrikeSprayVfx.StrikeSpraySpec(
        origin = Vector2f(origin),
        facingDeg = 90f,
        arcDeg = 60f,
        coreColor = core,
        fringeColor = fringe,
        style = aod7Style().copy(baseRaysMin = 12, baseRaysExtra = 6, arc = 60f),
    )

    private fun explicitSpec(arcDeg: Float = 58f, style: StrikeSprayVfx.SprayStyle = aod7Style()) = StrikeSprayVfx.StrikeSpraySpec(
        origin = Vector2f(origin),
        facingDeg = 90f,
        arcDeg = arcDeg,
        coreColor = core,
        fringeColor = fringe,
        style = style,
    )

    private fun frameCtx(engine: CombatEngineAPI, host: PointHost, elapsed: Float, amount: Float) = RenderContextImpl(
        engine = engine,
        host = host,
        frame = FrameStateImpl(
            elapsed = elapsed,
            logicElapsed = elapsed,
            amountThisFrame = amount,
            origin = Vector2f(host.origin),
            facing = host.facingDeg,
            length = 0f,
            endpoint = null,
            worldUnitsPerPixel = 1f,
            active = true,
            intensity = 1f,
            phase = RenderPhase.Active,
            flightProgress = 0f,
            dissolve = 0f,
            fadeReason = null,
        ),
    )

    // ---- 侧飞回归与驱动模型 ----

    @Test
    fun `needles fly exactly along their facing without lateral drift`() {
        val engine = stubEngine()
        val host = PointHost("h", origin, 90f)
        val comp = StrikeSprayComponent("t", rampSpec())

        assertTrue(comp.needles.size in 12..18, "12+6 档针数 12~18: ${comp.needles.size}")
        comp.onAttach(frameCtx(engine, host, 0f, 0.02f))
        // 推进 0.2s：覆盖错峰三段（段间隔 ≤ 0.06×2=0.12s）全激活；寿命下限 0.05+0.30=0.35s 内无针过期。
        repeat(10) { comp.advance(frameCtx(engine, host, 0.02f, 0.02f), 0.02f) }
        assertTrue(comp.needles.all { it.activated }, "0.2s 后全部针必须激活（含错峰后段）")
        assertTrue(comp.needles.none { it.expired }, "0.2s 内不得有针过期")

        val before = comp.needles.map { Vector2f(it.pos) }
        comp.advance(frameCtx(engine, host, 0.02f, 0.02f), 0.02f)

        for ((index, needle) in comp.needles.withIndex()) {
            val dx = needle.pos.x - before[index].x
            val dy = needle.pos.y - before[index].y
            val rad = Math.toRadians(needle.facing.toDouble())
            val fx = cos(rad).toFloat()
            val fy = sin(rad).toFloat()
            val lateral = abs(dx * (-fy) + dy * fx)
            assertTrue(lateral < 1e-3f, "针 $index 位移不得有侧向分量（侧飞回归）: $lateral")
            val along = dx * fx + dy * fy
            assertTrue(along > 0f, "针 $index 必须沿自身 facing 前飞: $along")
        }
    }

    @Test
    fun `ramp cohorts follow v2 2 quadratic quotas with monotone delays`() {
        val comp = StrikeSprayComponent("t", rampSpec())
        val interval = 0.05f / StrikeSprayComponent.RAMP_STEP_COUNT.toFloat()

        assertTrue(comp.needles.size >= StrikeSprayComponent.RAMP_MIN_RAYS, "12~18 针必触发错峰")
        // 三段配额：二次曲线权重 1/9 : 3/9 : 5/9（末段最大、每段至少一根）。
        val cohortSizes = comp.needles.groupingBy { it.activationDelay }.eachCount()
        assertEquals(3, cohortSizes.size, "错峰必须恰好三段: $cohortSizes")
        val ordered = cohortSizes.entries.sortedBy { it.key }
        assertEquals(0f, ordered.first().key, "首段必须零延迟（attach 立即激活段）")
        assertTrue(ordered[1].key in interval - 1e-4f..interval + 1e-4f, "第二段延迟 = interval: ${ordered[1].key}")
        assertTrue(ordered[2].key in 2f * interval - 1e-4f..2f * interval + 1e-4f, "末段延迟 = 2×interval: ${ordered[2].key}")
        assertTrue(ordered[2].value > ordered[0].value, "末段配额必须大于首段（1/9 < 5/9）: $cohortSizes")
        // 延迟单调不减（段配额按序分段）。
        val delays = comp.needles.map { it.activationDelay }
        assertEquals(delays, delays.sorted(), "激活延迟必须单调不减")
    }

    @Test
    fun `expired needles stop being driven`() {
        val engine = stubEngine()
        val host = PointHost("h", origin, 90f)
        val comp = StrikeSprayComponent("t", explicitSpec())

        comp.onAttach(frameCtx(engine, host, 0f, 0.02f))
        // 推进 0.8s：超过针最长寿命（full 0.10 + fadeOut 0.52 = 0.62s）。
        repeat(40) { comp.advance(frameCtx(engine, host, 0.02f, 0.02f), 0.02f) }
        assertTrue(comp.needles.all { it.expired }, "0.8s 后全部针必须过期")

        val parked = comp.needles.map { Vector2f(it.pos) }
        comp.advance(frameCtx(engine, host, 0.02f, 0.02f), 0.02f)
        for ((index, needle) in comp.needles.withIndex()) {
            assertEquals(parked[index].x, needle.pos.x, 0f, "过期针不得再位移（x）")
            assertEquals(parked[index].y, needle.pos.y, 0f, "过期针不得再位移（y）")
        }
    }

    @Test
    fun `headless boxutil absence logs warn and keeps param integration`() {
        val capture = WarnCapture(StrikeSprayComponent::class.java).also { captures += it }
        val engine = stubEngine()
        val host = PointHost("h", origin, 90f)
        val comp = StrikeSprayComponent("t", explicitSpec())

        comp.onAttach(frameCtx(engine, host, 0f, 0.02f))
        comp.advance(frameCtx(engine, host, 0.02f, 0.02f), 0.02f)

        assertTrue(
            capture.messages().any { it.contains("刺束针贴图加载失败") },
            "单测环境贴图不可用必须记 WARN: ${capture.messages()}",
        )
        assertTrue(comp.needles.all { it.entity == null }, "实体缺席但针参数必须在册")
        assertTrue(comp.needles.count { it.activated } >= 1, "激活流程照常推进")
    }

    // ---- 参数域（v2.2 逐值平移） ----

    @Test
    fun `cone tier ports v2 2 explicit params`() {
        val comp = StrikeSprayComponent("t", coneSpec())

        assertTrue(comp.needles.size in 9..13, "锥面 v2.2 档针数 9~13: ${comp.needles.size}")
        for (needle in comp.needles) {
            // 视觉长 = 180~420 ×impactScale 0.85 ×错峰段系数(≥0.35) ×内部塑形 ×1.20：
            // 无错峰段 183.6~428.4；错峰首段下限 183.6×0.5185≈95.2，地板 183.6×0.35≈64.3。
            assertTrue(needle.visualLength in 64.3f - 1e-2f..428.4f + 1e-2f, "v2.2 视觉长塑形域: ${needle.visualLength}")
            assertTrue(needle.baseWidth in 2.2f..12f, "基部宽 clamp 域: ${needle.baseWidth}")
            assertTrue(needle.tipWidth in 0.40f..1.9f, "尖端宽 clamp 域: ${needle.tipWidth}")
            val speed = hypot(needle.vel.x.toDouble(), needle.vel.y.toDouble()).toFloat()
            assertTrue(speed in 220f - 1e-3f..520f + 1e-3f, "v2.2 速度域（随机速度×随机寿命=不等比例飞行）: $speed")
            assertTrue(needle.full in 0.05f - 1e-4f..0.10f + 1e-4f, "满亮相域: ${needle.full}")
            assertTrue(needle.fadeOut in 0.30f - 1e-4f..0.52f + 1e-4f, "淡出相域: ${needle.fadeOut}")
            // 针尖补光参数域（v2.2 主路径同款）：尺寸 [4,18]、时长 [0.06,0.18]、漂移速度 = 针速 ×0.25。
            assertTrue(needle.tipGlowSize in 4f..18f, "针尖补光尺寸域: ${needle.tipGlowSize}")
            assertTrue(needle.tipGlowDuration in 0.06f - 1e-4f..0.18f + 1e-4f, "针尖补光时长域: ${needle.tipGlowDuration}")
            val glowSpeed = hypot(needle.tipGlowVel.x.toDouble(), needle.tipGlowVel.y.toDouble()).toFloat()
            assertEquals(speed * 0.25f, glowSpeed, 1e-3f, "补光漂移速度必须 = 针速 ×0.25")
        }
    }

    @Test
    fun `exact rays overrides fixed domain when present`() {
        // v4.4 数量动态化通路：exactRays=15 精确生效（固定域 9+4 只给 9~13，绝不产出 15）。
        val comp15 = StrikeSprayComponent("t15", explicitSpec(style = aod7Style().copy(exactRays = 15)))
        assertEquals(15, comp15.needles.size, "exactRays 必须精确驱动针数")

        // exactRays 下限直通（clamp [1,80] 内）：3 根精确生效（固定域永远 ≥7）。
        val comp3 = StrikeSprayComponent("t3", explicitSpec(style = aod7Style().copy(exactRays = 3)))
        assertEquals(3, comp3.needles.size, "exactRays 小值必须精确驱动")

        // null 走原固定域随机（aod7 显式档零影响）。
        val compNull = StrikeSprayComponent("tn", explicitSpec())
        assertTrue(compNull.needles.size in 7..10, "exactRays=null 必须走固定域 7~10: ${compNull.needles.size}")
    }

    @Test
    fun `aod7 light tier ports explicit params without ramp`() {
        val comp = StrikeSprayComponent("t", explicitSpec())

        assertTrue(comp.needles.size in 7..10, "aod7 轻量档针数 7~10: ${comp.needles.size}")
        for (needle in comp.needles) {
            // 针数 < 12 → 无错峰：全部零延迟 attach 即激活段。
            assertEquals(0f, needle.activationDelay, "显式轻量档不得错峰")
            // 视觉长 = 95~210 × impactScale 0.85 × 内部塑形 ×1.20 = 96.9~214.2。
            assertTrue(needle.visualLength in 96.9f - 1e-2f..214.2f + 1e-2f, "视觉长域: ${needle.visualLength}")
            val speed = hypot(needle.vel.x.toDouble(), needle.vel.y.toDouble()).toFloat()
            assertTrue(speed in 220f - 1e-3f..520f + 1e-3f, "速度域: $speed")
            assertTrue(needle.full in 0.05f - 1e-4f..0.10f + 1e-4f, "满亮相域: ${needle.full}")
            assertTrue(needle.fadeOut in 0.30f - 1e-4f..0.52f + 1e-4f, "淡出相域: ${needle.fadeOut}")
        }
    }

    // ---- BoxUtil 负角防线 ----

    @Test
    fun `needle facing normalized to 0 to 360 for boxutil transforms`() {
        // atan2 直出的 -90°（朝下引爆）经 ±arc/2 随机散布后，修复前可产出负 facing，
        // BoxUtil sinFormCosF 对负角取错 sin 符号 → 针簇整体镜像反转。
        val comp = StrikeSprayComponent("tneg", coneSpec().copy(facingDeg = -90f))
        assertTrue(comp.needles.isNotEmpty())
        for ((index, needle) in comp.needles.withIndex()) {
            assertTrue(needle.facing >= 0f && needle.facing < 360f, "针 $index facing 必须在 [0,360): ${needle.facing}")
            // 方向保持：速度方向必须与归一化 facing 一致（归一化是纯周期映射，不得改变世界方向）。
            val speed = hypot(needle.vel.x.toDouble(), needle.vel.y.toDouble()).toFloat()
            val rad = Math.toRadians(needle.facing.toDouble())
            assertEquals(cos(rad).toFloat() * speed, needle.vel.x, 1e-2f, "针 $index 速度 x 必须沿归一化 facing")
            assertEquals(sin(rad).toFloat() * speed, needle.vel.y, 1e-2f, "针 $index 速度 y 必须沿归一化 facing")
        }
        // 喷散轴心朝下（-90° ≡ 270°）：针朝向合成矢量必须指向 -y。
        var sumX = 0f
        var sumY = 0f
        for (needle in comp.needles) {
            val rad = Math.toRadians(needle.facing.toDouble())
            sumX += cos(rad).toFloat()
            sumY += sin(rad).toFloat()
        }
        assertTrue(sumY < 0f && abs(sumX) < abs(sumY), "针簇合成方向必须朝下（-90° 语义不变）: ($sumX, $sumY)")
    }

    // ---- 入口防线与烟雾 ----

    @Test
    fun `spawn spray rejects invalid spec with warn and no plugin`() {
        val capture = WarnCapture(StrikeSprayVfx::class.java).also { captures += it }
        val engine = mock(CombatEngineAPI::class.java)

        assertNull(StrikeSprayVfx.spawnSpray(engine, explicitSpec(arcDeg = 0f)))
        assertNull(StrikeSprayVfx.spawnSpray(engine, explicitSpec(style = aod7Style().copy(speedMin = 0f))))
        assertTrue(capture.messages().any { it.contains("arcDeg 非正") }, "必须记 WARN: ${capture.messages()}")
        assertTrue(capture.messages().any { it.contains("非正区间端点") }, "必须记 WARN: ${capture.messages()}")
    }

    @Test
    fun `spawn spray registers driver for valid spec`() {
        val engine = stubEngine()

        val plugin = StrikeSprayVfx.spawnSpray(engine, explicitSpec())

        assertNotNull(plugin)
        verify(engine, times(1)).addPlugin(plugin)
    }

    @Test
    fun `spawn smoke emits nebula puffs within style count domain`() {
        val engine = mock(CombatEngineAPI::class.java)

        // aod7 轻量档烟雾参数：puff 2+2 → 2~4 颗。
        StrikeSprayVfx.spawnSmoke(
            engine,
            origin,
            90f,
            Color(130, 195, 255, 85),
            StrikeSprayVfx.SmokeStyle(
                puffCountBase = 2,
                puffCountExtra = 2,
                spreadArc = 24f,
                sizeMin = 42f,
                sizeMax = 86f,
                speedMin = 70f,
                speedMax = 155f,
                durationMin = 0.34f,
                durationMax = 0.62f,
            ),
        )

        verify(engine, atLeast(2)).addNebulaParticle(
            any(), any(), anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat(), any(), anyBoolean(),
        )
        verify(engine, atMost(4)).addNebulaParticle(
            any(), any(), anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat(), any(), anyBoolean(),
        )
    }

    @Test
    fun `spawn impact fx emits smoke and spray together with inward facing`() {
        val engine = stubEngine()

        val plugin = StrikeSprayVfx.spawnImpactFx(
            engine = engine,
            point = origin,
            towardTargetFacing = 90f,
            facingMode = StrikeSprayVfx.FacingMode.INWARD,
            smokeColor = Color(130, 195, 255, 85),
            coreColor = core,
            fringeColor = fringe,
            sprayStyle = aod7Style(),
            smokeStyle = StrikeSprayVfx.SmokeStyle(puffCountBase = 2, puffCountExtra = 2),
        )

        assertNotNull(plugin, "INWARD 组合入口必须注册刺束驱动")
        verify(engine, atLeast(2)).addNebulaParticle(
            any(), any(), anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat(), any(), anyBoolean(),
        )
        verify(engine, times(1)).addPlugin(plugin)
    }
}
