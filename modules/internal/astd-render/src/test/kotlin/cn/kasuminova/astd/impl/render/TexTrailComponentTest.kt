package cn.kasuminova.astd.impl.render

import cn.kasuminova.astd.api.render.ASTDProjectileHistoryNode
import org.lwjgl.util.vector.Vector2f
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

class TexTrailComponentTest {

    private val spec = TexTrailSpec(
        width = 8f,
        texturePath = "graphics/fx/gr_trails_zappy.png",
        headColor = ASTDColor(1f, 1f, 1f, 1f),
        tailColor = ASTDColor(0f, 0f, 1f, 0.2f),
        nodeCount = 5,
    )

    @Test
    fun `empty history falls back to straight beam with head at origin`() {
        val nodes = texTrailNodes(emptyList(), Vector2f(50f, 60f), 30f, 200f, spec, 1f)

        assertEquals(5, nodes.size)
        assertEquals(0f, nodes.first().position.x, 0.0001f)
        assertEquals(0f, nodes.first().position.y, 0.0001f)
        assertEquals(-200f, nodes.last().position.x, 0.0001f)
        assertEquals(0f, nodes.last().position.y, 0.0001f)
        // 直梁走向恒为 -x（局部系 180°）
        for (node in nodes) {
            assertEquals(180f, node.angle, 0.0001f)
            assertEquals(8f, node.width, 0.0001f)
        }
    }

    @Test
    fun `intensity scales alpha of every node`() {
        val nodes = texTrailNodes(emptyList(), Vector2f(), 0f, 200f, spec, 0.5f)

        assertEquals(0.5f, nodes.first().color.alpha, 0.0001f)
        assertEquals(0.1f, nodes.last().color.alpha, 0.0001f)
    }

    @Test
    fun `curved history bends nodes in local frame`() {
        // facing=0（+x 前向），弹体在 (100,0)；历史点显示它从 (0,0) 经 (50,30) 飞来 → 局部系应向 -y 侧弯
        val history = listOf(
            ASTDProjectileHistoryNode(Vector2f(0f, 0f), 0f, 0f),
            ASTDProjectileHistoryNode(Vector2f(50f, 30f), 0f, 0.1f),
            ASTDProjectileHistoryNode(Vector2f(100f, 0f), 0f, 0.2f),
        )

        val nodes = texTrailNodes(history, Vector2f(100f, 0f), 0f, 200f, spec, 1f)

        // 头节点锚在原点
        assertEquals(0f, nodes.first().position.x, 0.0001f)
        assertEquals(0f, nodes.first().position.y, 0.0001f)
        // 中间节点须出现侧向弯曲（采样点未必正中 30 的拐点，但须明显偏离直梁）
        val maxAbsY = nodes.dropLast(1).maxOf { kotlin.math.abs(it.position.y) }
        assert(maxAbsY in 20f..31f) { "expected lateral bend from curved history, maxAbsY=$maxAbsY" }
        // 历史弧长(~116.6)短于请求长度 200：尾端沿末段方向延长，y 继续向负侧伸出
        assert(nodes.last().position.y < -35f) { "tail should extend along final heading: ${nodes.last().position}" }
        // 尾端不超出请求长度
        for (node in nodes) {
            val distance = sqrt(node.position.x * node.position.x + node.position.y * node.position.y)
            assert(distance <= 200f + 0.5f) { "node beyond requested length: $distance" }
        }
    }

    @Test
    fun `facing rotates world history into local frame`() {
        // facing=90°（+y 前向），弹体在 (0,100)；历史沿 +y 飞来 → 局部系应是一条 -x 直梁
        val history = listOf(
            ASTDProjectileHistoryNode(Vector2f(0f, 0f), 90f, 0f),
            ASTDProjectileHistoryNode(Vector2f(0f, 50f), 90f, 0.1f),
            ASTDProjectileHistoryNode(Vector2f(0f, 100f), 90f, 0.2f),
        )

        val nodes = texTrailNodes(history, Vector2f(0f, 100f), 90f, 200f, spec, 1f)

        for (node in nodes) {
            assertEquals(0f, node.position.y, 0.001f)
            assertEquals(180f, node.angle, 0.5f)
        }
        assertEquals(-100f, nodes[2].position.x, 0.5f)
    }

    @Test
    fun `three-stop gradient hits mid color at midT`() {
        val gradientSpec = TexTrailSpec(
            width = 8f,
            texturePath = "graphics/fx/gr_trails_zappy.png",
            headColor = ASTDColor(1f, 1f, 1f, 1f),
            midColor = ASTDColor(0f, 1f, 0f, 0.5f),
            midT = 0.25f,
            tailColor = ASTDColor(0f, 0f, 1f, 0.2f),
            nodeCount = 9,
        )

        val nodes = texTrailNodes(emptyList(), Vector2f(), 0f, 200f, gradientSpec, 1f)

        // t=0.25 → nodes[2]（9 节点步进 0.125）：恰好落在中段色
        assertEquals(0f, nodes[2].color.red, 0.0001f)
        assertEquals(1f, nodes[2].color.green, 0.0001f)
        assertEquals(0.5f, nodes[2].color.alpha, 0.0001f)
        // t=0.125 → head→mid 半程：红 0.5、绿 1
        assertEquals(0.5f, nodes[1].color.red, 0.0001f)
        assertEquals(1f, nodes[1].color.green, 0.0001f)
        // t=1 → 尾色不变
        assertEquals(1f, nodes.last().color.blue, 0.0001f)
        assertEquals(0.2f, nodes.last().color.alpha, 0.0001f)
    }

    @Test
    fun `wobble is deterministic for identical inputs`() {
        val wobbleSpec = spec.copy(wobbleAmplitude = 6f, wobbleWavelength = 60f, wobblePhase = 0.8f)

        val first = texTrailNodes(emptyList(), Vector2f(50f, 60f), 30f, 200f, wobbleSpec, 0.7f, wobbleAdvance = 1.3f)
        val second = texTrailNodes(emptyList(), Vector2f(50f, 60f), 30f, 200f, wobbleSpec, 0.7f, wobbleAdvance = 1.3f)

        assertEquals(first.size, second.size)
        for (i in first.indices) {
            assertEquals(first[i].position.x, second[i].position.x, 0f)
            assertEquals(first[i].position.y, second[i].position.y, 0f)
            assertEquals(first[i].angle, second[i].angle, 0f)
            assertEquals(first[i].color.alpha, second[i].color.alpha, 0f)
        }
    }

    @Test
    fun `wobble offset stays within amplitude and head stays anchored`() {
        val wobbleSpec = spec.copy(
            nodeCount = 25, wobbleAmplitude = 6f, wobbleWavelength = 60f, wobblePhase = 0.8f,
        )

        // 直梁（空历史）走向恒 -x：扰动全部落在局部 y 上，x 与走向角不受扰动影响
        val nodes = texTrailNodes(emptyList(), Vector2f(), 0f, 200f, wobbleSpec, 1f)

        // 头锚定（t=0 处扰动为 0），弹头接缝不被撕开
        assertEquals(0f, nodes.first().position.y, 0.0001f)
        var maxAbsY = 0f
        for (node in nodes) {
            maxAbsY = maxOf(maxAbsY, kotlin.math.abs(node.position.y))
            assert(kotlin.math.abs(node.position.y) <= 6f + 0.001f) { "offset beyond amplitude: ${node.position}" }
            assertEquals(180f, node.angle, 0.0001f)
        }
        // 扰动确实发生（不是恒零），尾部摆幅接近满振幅
        assert(maxAbsY > 3f) { "expected visible wobble, maxAbsY=$maxAbsY" }
    }

    @Test
    fun `zero wobble amplitude degenerates to unperturbed nodes regardless of advance`() {
        // 默认 spec（wobbleAmplitude=0）：旧行为，wobbleAdvance 不得产生任何影响
        val without = texTrailNodes(emptyList(), Vector2f(50f, 60f), 30f, 200f, spec, 1f)
        val withAdvance = texTrailNodes(emptyList(), Vector2f(50f, 60f), 30f, 200f, spec, 1f, wobbleAdvance = 2.5f)

        assertEquals(without.size, withAdvance.size)
        for (i in without.indices) {
            assertEquals(without[i].position.x, withAdvance[i].position.x, 0f)
            assertEquals(without[i].position.y, withAdvance[i].position.y, 0f)
            assertEquals(without[i].angle, withAdvance[i].angle, 0f)
        }
    }

    @Test
    fun `wobble advance translates pattern along the band`() {
        val wobbleSpec = spec.copy(
            nodeCount = 25, wobbleAmplitude = 6f, wobbleWavelength = 60f, wobbleScroll = 30f, wobblePhase = 0.8f,
        )

        val still = texTrailNodes(emptyList(), Vector2f(), 0f, 200f, wobbleSpec, 1f, wobbleAdvance = 0f)
        val advanced = texTrailNodes(emptyList(), Vector2f(), 0f, 200f, wobbleSpec, 1f, wobbleAdvance = 0.37f)

        // 同一带体仅平移相位不同：图案须移动（存在节点横向位置不同），且每个节点仍在振幅上界内
        val moved = still.indices.any { kotlin.math.abs(still[it].position.y - advanced[it].position.y) > 0.001f }
        assert(moved) { "wobble advance should translate the pattern along the band" }
        for (node in advanced) {
            assert(kotlin.math.abs(node.position.y) <= 6f + 0.001f) { "offset beyond amplitude: ${node.position}" }
        }
    }

    @Test
    fun `strip bakes world-space triangle strip with arclen u and unit v`() {
        // 直梁中线（空历史）：头 (0,0) → 尾 (-200,0)，facing=0 时局部即世界
        val nodes = texTrailNodes(emptyList(), Vector2f(), 0f, 200f, spec, 1f)

        val strip = texTrailStrip(nodes, Vector2f(), 0f, 100f, 0.25f)

        assertEquals(nodes.size * 2 * TEX_TRAIL_VERTEX_FLOATS, strip.size)
        // 首节点（头，x=0，走向 180° → 法向 (0,-1)）：上沿 v=+1 在 y=-4，下沿在 y=+4（半宽 4）
        assertEquals(0f, strip[0], 0.001f)
        assertEquals(-4f, strip[1], 0.001f)
        assertEquals(0f - 0.25f, strip[2], 0.001f)  // u = 弧长/100 - scroll
        assertEquals(1f, strip[3], 0.001f)
        assertEquals(0f, strip[8], 0.001f)
        assertEquals(4f, strip[9], 0.001f)
        assertEquals(-1f, strip[11], 0.001f)
        // 尾节点（x=-200）：u = 200/100 - 0.25 = 1.75
        val tail = strip.size - TEX_TRAIL_VERTEX_FLOATS * 2
        assertEquals(-200f, strip[tail], 0.001f)
        assertEquals(1.75f, strip[tail + 2], 0.001f)
        // 颜色随节点插值写入顶点流（头白 alpha 1 → 尾蓝 alpha 0.2）
        assertEquals(1f, strip[4], 0.001f)
        assertEquals(1f, strip[7], 0.001f)
        assertEquals(1f, strip[tail + 6], 0.001f)  // 尾 blue
        assertEquals(0.2f, strip[tail + 7], 0.001f)
    }

    @Test
    fun `strip rotates local nodes into world frame by facing`() {
        val nodes = texTrailNodes(emptyList(), Vector2f(), 0f, 200f, spec, 1f)

        // facing=90°（+y 前向）：局部 -x 直梁应转为世界 -y 直梁，尾端在 origin + (0,-200)。
        // 走向角 180+90=270° → 法向 (1,0)：上沿顶点 x 偏移 +半宽 4
        val strip = texTrailStrip(nodes, Vector2f(100f, 100f), 90f, 100f, 0f)

        assertEquals(104f, strip[0], 0.001f)
        assertEquals(100f, strip[1], 0.001f)
        val tail = strip.size - TEX_TRAIL_VERTEX_FLOATS * 2
        assertEquals(104f, strip[tail], 0.5f)
        assertEquals(-100f, strip[tail + 1], 0.5f)
        // 下沿顶点反向偏移：x = 96
        assertEquals(96f, strip[tail + TEX_TRAIL_VERTEX_FLOATS], 0.5f)
        assertEquals(-100f, strip[tail + TEX_TRAIL_VERTEX_FLOATS + 1], 0.5f)
    }

    @Test
    fun `strip recede shifts whole band backward along facing`() {
        val nodes = texTrailNodes(emptyList(), Vector2f(), 0f, 200f, spec, 1f)

        // facing=90°（+y 前向）：recede 30 应把整条带沿世界 -y（后方）平移 30
        val plain = texTrailStrip(nodes, Vector2f(100f, 100f), 90f, 100f, 0f)
        val receded = texTrailStrip(nodes, Vector2f(100f, 100f), 90f, 100f, 0f, 30f)

        assertEquals(plain.size, receded.size)
        for (i in plain.indices step TEX_TRAIL_VERTEX_FLOATS) {
            assertEquals(plain[i], receded[i], 0.001f)       // x 不变
            assertEquals(plain[i + 1] - 30f, receded[i + 1], 0.001f)  // y -30
        }
    }

    @Test
    fun `mesh triangles bake to world-space vertex stream with zero uv`() {
        val mesh = ASTDProjectileVfxBodyRenderer.Mesh(
            vertices = emptyList(),
            triangles = listOf(
                ASTDProjectileVfxBodyRenderer.Triangle(
                    ASTDProjectileVfxBodyRenderer.Vertex(Vector2f(10f, 0f), ASTDColor(1f, 0.5f, 0.25f, 0.8f)),
                    ASTDProjectileVfxBodyRenderer.Vertex(Vector2f(0f, 10f), ASTDColor(0f, 1f, 0f, 1f)),
                    ASTDProjectileVfxBodyRenderer.Vertex(Vector2f(0f, 0f), ASTDColor(0f, 0f, 1f, 0.5f)),
                ),
            ),
            renderOrder = 300,
        )

        // facing=90°（+y 前向）：局部 (10,0) → 世界 origin + (0,10)；局部 (0,10) → origin + (-10,0)
        val out = texTrailMeshTriangles(mesh, Vector2f(100f, 200f), 90f)

        assertEquals(3 * TEX_TRAIL_VERTEX_FLOATS, out.size)
        // 顶点 a：世界 (100, 210)，uv=(0,0)，颜色原样
        assertEquals(100f, out[0], 0.001f)
        assertEquals(210f, out[1], 0.001f)
        assertEquals(0f, out[2], 0.001f)
        assertEquals(0f, out[3], 0.001f)
        assertEquals(1f, out[4], 0.001f)
        assertEquals(0.5f, out[5], 0.001f)
        assertEquals(0.25f, out[6], 0.001f)
        assertEquals(0.8f, out[7], 0.001f)
        // 顶点 b：世界 (90, 200)
        assertEquals(90f, out[8], 0.001f)
        assertEquals(200f, out[9], 0.001f)
        // 顶点 c：世界即 origin，颜色 alpha 0.5
        assertEquals(100f, out[16], 0.001f)
        assertEquals(200f, out[17], 0.001f)
        assertEquals(0.5f, out[23], 0.001f)
    }

    @Test
    fun `age envelope keeps young nodes and dissolves linearly past dissolve start`() {
        assertEquals(1f, ageAlphaEnvelope(0f, 0.6f))
        assertEquals(1f, ageAlphaEnvelope(0.3f, 0.6f))
        assertEquals(1f, ageAlphaEnvelope(0.6f, 0.6f), "消散起点边界仍满亮")
        assertEquals(0.5f, ageAlphaEnvelope(0.8f, 0.6f), 1e-4f)
        assertEquals(0f, ageAlphaEnvelope(1f, 0.6f), 1e-4f)
        assertEquals(0f, ageAlphaEnvelope(1.5f, 0.6f), "超过寿命彻底消散")
    }

    @Test
    fun `age width envelope keeps full width then shrinks to floor at life end`() {
        assertEquals(1f, ageWidthEnvelope(0f, 0.6f))
        assertEquals(1f, ageWidthEnvelope(0.3f, 0.6f))
        assertEquals(1f, ageWidthEnvelope(0.6f, 0.6f), "消散起点边界仍满宽")
        // lifeProgress=0.8 → alpha=0.5 → width=0.15+0.85×0.5=0.575
        assertEquals(0.575f, ageWidthEnvelope(0.8f, 0.6f), 1e-4f)
        assertEquals(0.15f, ageWidthEnvelope(1f, 0.6f), 1e-4f, "寿命末期收细到下限 0.15")
        assertEquals(0.15f, ageWidthEnvelope(1.5f, 0.6f), 1e-4f, "超过寿命保持下限（alpha 已透明）")
    }

    @Test
    fun `per-node lifetime narrows older tail nodes alongside dissolve`() {
        // 同 per-node lifetime 场景：尾节点 age=0.4 → progress≈0.6667 → 宽=8×(0.15+0.85×0.8333)
        val history = (0..4).map { ASTDProjectileHistoryNode(Vector2f(it * 25f, 0f), 0f, it * 0.1f) }

        val nodes = texTrailNodes(history, Vector2f(100f, 0f), 0f, 100f, spec, 1f, now = 0.4f, lifetimeSeconds = 0.6f)

        assertEquals(8f, nodes.first().width, 1e-4f, "头节点 age=0 满宽")
        assertEquals(8f, nodes[2].width, 1e-4f, "未到消散起点不收细")
        assertEquals(
            8f * (0.15f + 0.85f * 0.8333f),
            nodes.last().width,
            1e-2f,
            "尾段最老应先开始收细（逐渐变细消散）",
        )
    }

    @Test
    fun `per-node lifetime dissolves older tail nodes first`() {
        // 直线飞行 100su：历史点 0.0s（尾）→ 0.4s（头），now=0.4、lifetime=0.6、dissolveStart=0.6
        val history = (0..4).map { ASTDProjectileHistoryNode(Vector2f(it * 25f, 0f), 0f, it * 0.1f) }

        val nodes = texTrailNodes(history, Vector2f(100f, 0f), 0f, 100f, spec, 1f, now = 0.4f, lifetimeSeconds = 0.6f)

        // 头节点 age=0：满亮不衰减
        assertEquals(0f, nodes.first().age, 1e-4f)
        assertEquals(1f, nodes.first().color.alpha, 1e-4f)
        // 中间节点 age=0.2 → progress=0.333 < 0.6：不衰减，保持静态渐变（t=0.5 → lerp(1, 0.2)=0.6）
        assertEquals(0.2f, nodes[2].age, 1e-4f)
        assertEquals(0.6f, nodes[2].color.alpha, 1e-3f)
        // 尾节点 age=0.4 → progress=0.6667 → envelope=1-(0.6667-0.6)/0.4≈0.8333：alpha=0.2×0.8333
        assertEquals(0.4f, nodes.last().age, 1e-4f)
        assertEquals(0.6667f, nodes.last().lifeProgress, 1e-3f)
        assertEquals(0.2f * 0.8333f, nodes.last().color.alpha, 1e-3f, "尾段最老应先开始消散")
    }

    @Test
    fun `zero lifetime disables age-based dissolve`() {
        // 宿主不提供寿命（光束/一次性特效）：节点不随年龄衰减，尾色保持静态渐变值
        val history = (0..4).map { ASTDProjectileHistoryNode(Vector2f(it * 25f, 0f), 0f, it * 0.1f) }

        val nodes = texTrailNodes(history, Vector2f(100f, 0f), 0f, 100f, spec, 1f, now = 99f, lifetimeSeconds = 0f)

        for (node in nodes) {
            assertEquals(0f, node.lifeProgress, 0f)
        }
        assertEquals(0.2f, nodes.last().color.alpha, 1e-4f)
    }

    @Test
    fun `segment twist base is deterministic smooth and within range`() {
        val angle = segmentTwistBase(37f, 50f, 45f)
        assertEquals(angle, segmentTwistBase(37f, 50f, 45f), 0f, "同一弧长跨帧稳定")
        assert(kotlin.math.abs(angle) <= 45f) { "twist base beyond range: $angle" }
        assertEquals(0f, segmentTwistBase(37f, 50f, 0f), "maxAngle=0 即关闭")
        // 平滑衔接：沿弧长逐点推进时取值连续（smoothstep 过渡，无折点跳变），变化率有界
        var prev = segmentTwistBase(0f, 50f, 90f)
        var d = 1f
        while (d <= 200f) {
            val cur = segmentTwistBase(d, 50f, 90f)
            assert(kotlin.math.abs(cur - prev) <= 90f * 4f / 50f) { "弧长 $d 处扭转不连续: $prev → $cur" }
            prev = cur; d += 1f
        }
        // 不同弧长桶给出不同值（噪声非恒定）
        assert(segmentTwistBase(10f, 50f, 90f) != segmentTwistBase(120f, 50f, 90f)) { "不同弧长桶应给出不同扭转角" }
    }

    @Test
    fun `twist angle comes from arc noise and accumulates with age`() {
        // 9 节点直梁：扭转初相 = 距头弧长的平滑噪声（带体系坐标，跨帧稳定），与历史点时间戳无关
        val history = (0..4).map { ASTDProjectileHistoryNode(Vector2f(it * 25f, 0f), 0f, it * 0.1f) }
        val twistSpec = spec.copy(nodeCount = 9, twistMaxAngleDeg = 30f, twistWavelength = 40f, twistTurnDegPerSec = 90f)

        val young = texTrailNodes(history, Vector2f(100f, 0f), 0f, 100f, twistSpec, 1f, now = 0.4f)
        // 节点 1（距头 12.5）：扭转 = 弧长噪声(12.5, λ=40) + 年龄 0.05 × 90°/s
        assertEquals(0.05f, young[1].age, 1e-4f)
        assertEquals(
            segmentTwistBase(12.5f, 40f, 30f) + 0.05f * 90f, young[1].twistDeg, 1e-3f,
            "扭转 = 弧长平滑噪声 + 年龄累积",
        )

        // 年龄 +1s：所有节点扭转角 +90°（累积项与弧长无关，不产生段间跳变）
        val aged = texTrailNodes(history, Vector2f(100f, 0f), 0f, 100f, twistSpec, 1f, now = 1.4f)
        for (i in young.indices) {
            assertEquals(90f, aged[i].twistDeg - young[i].twistDeg, 1e-3f, "节点 $i 扭转角应随年龄线性累积")
        }

        // twistWavelength=0 → 回落 tileLength（扭转图案与贴图平铺周期同频）
        assertEquals(spec.tileLength, spec.copy(twistWavelength = 0f).effectiveTwistWavelength(), 0f)
        assertEquals(45f, spec.copy(twistWavelength = 45f).effectiveTwistWavelength(), 0f)

        // twistMaxAngleDeg=0：扭转完全关闭（含累积项为 0 时恒 0）
        val closed = texTrailNodes(history, Vector2f(100f, 0f), 0f, 100f, spec, 1f, now = 1.4f)
        for (node in closed) {
            assertEquals(0f, node.twistDeg, 0f)
        }
    }

    @Test
    fun `time offset accelerates node aging`() {
        // 消亡后驱动按「寿命/死亡消散窗口」累加偏移：节点年龄 = now + offset − 出生时刻
        val history = (0..4).map { ASTDProjectileHistoryNode(Vector2f(it * 25f, 0f), 0f, it * 0.1f) }
        val base = texTrailNodes(history, Vector2f(100f, 0f), 0f, 100f, spec, 1f, now = 0.4f, lifetimeSeconds = 10f)
        val boosted = texTrailNodes(history, Vector2f(100f, 0f), 0f, 100f, spec, 1f, now = 0.4f, lifetimeSeconds = 10f, timeOffset = 2f)
        for (i in base.indices) {
            assertEquals(base[i].age + 2f, boosted[i].age, 1e-4f, "节点 $i 年龄应加上时间偏移")
        }
    }

    @Test
    fun `dynamic node count subdivides long trails within guardrails`() {
        assertEquals(16, dynamicTexTrailNodeCount(100f, 16))   // 短带取下限（spec 声明值）
        assertEquals(30, dynamicTexTrailNodeCount(650f, 16))   // 650/22≈29.5→30：按带长细分
        assertEquals(72, dynamicTexTrailNodeCount(2400f, 16))  // 上限护栏
        assertEquals(24, dynamicTexTrailNodeCount(0f, 24))     // 零长取下限
    }

    @Test
    fun `strip twist rotates cross offset toward path tangent`() {
        // 直梁 facing=0：走向 180° → 法向 n=(0,-1)、切向 t=(-1,0)；twist=90° 时偏移全落到切向
        val base = texTrailNodes(emptyList(), Vector2f(), 0f, 200f, spec, 1f)
        val twisted = base.map { it.copy(twistDeg = 90f) }

        val strip = texTrailStrip(twisted, Vector2f(), 0f, 100f, 0f)

        // 头节点中心 (0,0)：上沿 = (0,0) + t*4 = (-4,0)，下沿 = (4,0)
        assertEquals(-4f, strip[0], 1e-3f)
        assertEquals(0f, strip[1], 1e-3f)
        assertEquals(4f, strip[8], 1e-3f)
        assertEquals(0f, strip[9], 1e-3f)
    }
}
