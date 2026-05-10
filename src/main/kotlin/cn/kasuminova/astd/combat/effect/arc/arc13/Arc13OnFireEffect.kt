package cn.kasuminova.astd.combat.effect.arc.arc13

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.CombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.ViewportAPI
import org.boxutil.manager.CombatRenderingManager
import org.boxutil.units.standard.attribute.NodeData
import org.boxutil.units.standard.entity.SegmentEntity
import org.boxutil.util.CurveUtil
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Matrix3f
import org.lwjgl.util.vector.Matrix4f
import org.lwjgl.util.vector.Vector2f
import org.lwjgl.util.vector.Vector3f
import java.awt.Color
import java.util.EnumSet
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.min

/**
 * ARC-13「三位一体」：
 * - 武器本体发射一个完全不可见的 dummy 弹体，仅用于触发 onFire。
 * - onFire 中：
 *   1. 低层级 CurveUtil.spawnCurveBeam 执行碰撞检测 + 伤害结算（返回对齐后的节点）；
 *   2. 手动修正 curveSplit 遗漏的 start tangentRight 缩放，创建正确截断的 SegmentEntity；
 *   3. 配置材质/时序后提交到 BoxUtil 渲染管线。
 * - 三发 burst 对应三相：动能 / 高爆 / 破片（破片为 200% 伤害）。
 */
class Arc13OnFireEffect : OnFireEffectPlugin {

    companion object {
        // phase 之间的 reset：武器停止开火后超过该时间重新从 phase0 开始
        private const val PHASE_RESET_TIME = 1.2f

        // 曲线形状：控制弯曲程度（越大越"拱"）
        private const val CURVE_OFFSET = 220f
        private const val CURVE_JITTER = 110f

        // 束体参数（等宽）
        private const val BEAM_W = 16f

        private const val BEAM_TEX_SPEED = 200f

        // 让光束“前进”而非瞬间出现：可见长度从 0 → 100% 的推进时间（秒）。
        // 实际推进时长会按曲线长度/速度动态调整，并限制在该范围内。
        private const val BEAM_ADV_MIN = 0.045f
        private const val BEAM_ADV_MAX = 0.110f
        private const val BEAM_ADV_SPEED = 9000f

        // 曲线造型：只在前段“扭转”，后段尽量直线打击。
        private const val TWIST_START_FRACTION = 0.05f

        // AI 瞄准：更偏向正前方（角度差惩罚系数）
        private const val AI_AIM_DIFF_PENALTY = 16f

        // 渲染时序（秒）
        private const val BEAM_IN = 0.03f
        private const val BEAM_FULL = 0.20f
        private const val BEAM_OUT = 0.24f

        // 贴图
        private const val CORE_SPRITE_PATH = "graphics/fx/beamcoreb.png"
        private const val FRINGE_SPRITE_PATH = "graphics/fx/beamfringeb.png"

        private data class State(
            var phase: Int = 0,
            var lastFireAt: Float = -999f,
        )

        private val states: WeakHashMap<WeaponAPI, State> = WeakHashMap()
    }

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        if (engine.isPaused) return

        val now = try {
            engine.getTotalElapsedTime(false)
        } catch (_: Throwable) {
            0f
        }

        val st = states.getOrPut(weapon) { State() }
        if ((now - st.lastFireAt) > PHASE_RESET_TIME) {
            st.phase = 0
        }
        st.lastFireAt = now

        val phase = st.phase
        st.phase = (st.phase + 1) % 3

        // 立即移除 dummy 弹体，避免任何一帧可见/碰撞
        try {
            engine.removeEntity(projectile)
        } catch (_: Throwable) {
        }

        val ship = weapon.ship ?: return

        // 确保 BoxUtil 管线已就绪
        try {
            BoxUtilCombatVfx.ensureReady(engine)
        } catch (_: Throwable) {
        }

        val start = Vector2f(projectile.location)
        val facingRaw = projectile.facing
        val facing = ((facingRaw % 360f) + 360f) % 360f
        val baseRange = weapon.range

        // ===== 瞄准：玩家偏鼠标，AI 偏正前方最近目标 =====
        val baseFacing = try { weapon.currAngle } catch (_: Throwable) { facing }
        val arcHalf = try { (weapon.arc * 0.5f).coerceAtLeast(0f) } catch (_: Throwable) { 0f }
        var aimFacing = baseFacing
        var aimDist = baseRange

        val isPlayerShip = try { engine.playerShip === ship } catch (_: Throwable) { false }
        val autopilotOn = try { engine.isUIAutopilotOn } catch (_: Throwable) { false }

        if (isPlayerShip && !autopilotOn) {
            // 玩家手动：优先打鼠标落点（需在射界内；超出射界则维持武器当前指向）
            val mt = try { ship.mouseTarget } catch (_: Throwable) { null }
            if (mt != null) {
                val dist = MathUtils.getDistance(start, mt)
                val angTo = VectorUtils.getAngle(start, mt)
                val diff = abs(angleDiffDeg(baseFacing, angTo))
                if (arcHalf <= 0.01f || diff <= arcHalf + 0.5f) {
                    aimFacing = angTo
                    aimDist = min(dist, baseRange)
                }
            }
        } else {
            // AI：偏向正前方最近可接触目标（过滤相位）
            val best = pickAiTarget(engine, ship, weapon, start, baseFacing, baseRange)
            if (best != null) {
                val dist = MathUtils.getDistance(start, best.location)
                if (dist > 1f) {
                    val angTo = VectorUtils.getAngle(start, best.location)
                    val diff = abs(angleDiffDeg(baseFacing, angTo))
                    if (arcHalf <= 0.01f || diff <= arcHalf + 0.5f) {
                        aimFacing = angTo
                        aimDist = min(dist, baseRange)
                    }
                }
            }
        }

        val (damageType, damageAmount, coreColor, fringeColor) = when (phase) {
            0 -> Quad(
                DamageType.KINETIC,
                500f,
                Color(220, 250, 255, 235),
                Color(120, 220, 255, 215),
            )
            1 -> Quad(
                DamageType.HIGH_EXPLOSIVE,
                500f,
                Color(255, 245, 210, 235),
                Color(255, 185, 90, 215),
            )
            else -> Quad(
                DamageType.FRAGMENTATION,
                // 最后一发：破片伤害 300%（相对基础单发）
                1500f,
                Color(235, 225, 255, 235),
                Color(170, 140, 255, 215),
            )
        }

        // 发射端脉冲：短寿命扩散环
        Arc13Vfx.spawnMuzzlePulse(engine, start, aimFacing, coreColor)

        // 曲线节点（本地空间，CurveUtil 使用 startOffset 旋转/平移到世界空间）
        // 需求：弧线角度分布更随机，而不是不同相位固定左右/范围。
        val r0 = (Math.random().toFloat() * 2f - 1f)
        val r1 = (Math.random().toFloat() * 2f - 1f)
        val curveSign = if (r0 >= 0f) 1f else -1f
        val curveMag = (0.30f + 0.70f * abs(r0))
        val jitter = r1 * CURVE_JITTER
        val curveY = (CURVE_OFFSET * curveSign * curveMag) + jitter

        // 造型：在射程前 5% 内完成“扭转”，后段收束为直线（end.tangentLeft 的 y=0）。
        val aimRange = aimDist.coerceIn(80f, baseRange)
        val twistX = (aimRange * TWIST_START_FRACTION).coerceAtLeast(30f)
        val tanX = twistX * 0.9f
        val startNode = NodeData(
            Vector2f(0f, 0f),
            Vector2f(0f, 0f),
            Vector2f(tanX, curveY),
        )
        val endNode = NodeData(
            Vector2f(aimRange, 0f),
            // 后段尽量直线：收束到 y=0
            Vector2f(-(aimRange * 0.35f), 0f),
            Vector2f(0f, 0f),
        )

        val startOffset = Vector3f(start.x, start.y, aimFacing)

        try {
            // ===== 第 1 步：碰撞检测（不立即结算伤害） =====
            // 需求：光束到达终点前不要造成伤害。
            // 这里用“延迟 DealtController”仅记录命中点/目标，伤害在推进结束时统一 apply。
            val dealtController = Arc13DelayedDealtController(ship)

            val maxCheckRange = aimRange * 1.2f + 400f

            val transformResult = Matrix3f()
            val scanResult = CurveUtil.spawnCurveBeam(
                engine, startOffset, startNode, endNode,
                maxCheckRange, dealtController,
                null, true, transformResult,
            )

            val hitVec: Vector3f? = scanResult.two   // (hitX, hitY, curveT) or null
            val alignedNodes = scanResult.one         // {start, end} in aligned (local) space

            // ===== 修正 curveSplit 遗漏 =====
            // CurveUtil 内部 curveSplit 更新了 end 节点（nodeData[1]），
            // 但 start 节点的 tangentRight 未被缩放——导致渲染曲线超过命中点。
            // De Casteljau 分割公式：left-subcurve start.tangentRight = t * original.tangentRight
            if (hitVec != null) {
                val hitT = hitVec.z
                val tr = alignedNodes[0].tangentRight
                alignedNodes[0].setTangentRight(tr.x * hitT, tr.y * hitT)
            }

            // 保存“完整曲线”的基准节点（后续推进/收缩动画会反复 curveSplit）
            // 注意：NodeData 的颜色在 BoxUtil 内部可能是 Vector4f；这里不复制颜色，后续统一 set。
            val baseStart = cloneNode(alignedNodes[0])
            val baseEnd = cloneNode(alignedNodes[1])
            baseStart.setColor(coreColor)
            baseStart.setEmissiveColor(fringeColor)
            baseStart.setWidth(BEAM_W)
            baseEnd.setColor(coreColor)
            baseEnd.setEmissiveColor(fringeColor)
            baseEnd.setWidth(BEAM_W)

            // 初始可见段：用 curveSplit 取一个很短的前缀，避免 0 长度导致渲染异常
            val curveLenForAdv = CurveUtil.getCurveLength(baseStart, baseEnd, 8)
            val advTime = if (curveLenForAdv > 0.001f) {
                (curveLenForAdv / BEAM_ADV_SPEED).coerceIn(BEAM_ADV_MIN, BEAM_ADV_MAX)
            } else {
                BEAM_ADV_MIN
            }
            val initT = (0.02f).coerceAtMost(0.2f)
            val initSplit = CurveUtil.curveSplit(baseStart, baseEnd, initT)
            initSplit[0].setColor(coreColor)
            initSplit[0].setEmissiveColor(fringeColor)
            initSplit[0].setWidth(BEAM_W)
            initSplit[1].setColor(coreColor)
            initSplit[1].setEmissiveColor(fringeColor)
            initSplit[1].setWidth(BEAM_W)

            // ===== 第 2 步：手动创建视觉 SegmentEntity =====
            val seg = SegmentEntity()

            // 模型矩阵：从 aligned 空间到世界空间（旋转 + 平移）
            val entityMatrix = Matrix4f()
            entityMatrix.m30 = transformResult.m20   // tx
            entityMatrix.m31 = transformResult.m21   // ty
            entityMatrix.m00 = transformResult.m00   // cos
            entityMatrix.m10 = transformResult.m10   // -sin
            entityMatrix.m01 = transformResult.m01   // sin
            entityMatrix.m11 = transformResult.m11   // cos

            // 用推进前缀作为初始形态，随后由插件推进到完整曲线
            seg.addNode(initSplit[0])
            seg.addNode(initSplit[1])
            seg.setModelMatrix(entityMatrix)

            // 插值精度（射程越长越平滑）
            val interp = ((aimRange / 14f).toInt()).coerceIn(56, 128)
            seg.setInterpolation(interp.toShort())
            seg.setTexturePixels(512f)
            seg.setTextureSpeed(BEAM_TEX_SPEED)
            seg.materialData.setDiffuse(Global.getSettings().getSprite(CORE_SPRITE_PATH))
            seg.materialData.setEmissive(Global.getSettings().getSprite(FRINGE_SPRITE_PATH))
            seg.setAdditiveBlend()

            seg.setNodeRefreshAllFromCurrentIndex()
            seg.submitNodes()

            // 边缘平滑
            val curveLen = CurveUtil.getCurveLength(baseStart, baseEnd, 8)
            val smoothFactor = if (curveLen > 0.001f) {
                ((curveLen - 4f).coerceAtLeast(0f)) / curveLen
            } else {
                0f
            }
            seg.setFillStartAlpha(0f)
            seg.setFillStartFactor(smoothFactor)
            seg.setFillEndAlpha(0f)
            // fillEndFactor 仅在无命中时设置（命中时曲线已截断到命中点，无需再裁剪）
            if (hitVec == null) seg.setFillEndFactor(smoothFactor)

            seg.setLayer(CombatEngineLayers.ABOVE_PARTICLES)
            seg.setGlobalTimer(BEAM_IN, BEAM_FULL, BEAM_OUT)
            CombatRenderingManager.addEntity(seg)

            // 推进动画：让光束前端从发射点“跑”到命中点/最大射程
            try {
                engine.addLayeredRenderingPlugin(
                    Arc13BeamAdvancePlugin(
                        engine = engine,
                        seg = seg,
                        baseStart = baseStart,
                        baseEnd = baseEnd,
                        advTime = advTime,
                        fadeIn = BEAM_IN,
                        full = BEAM_FULL,
                        fadeOut = BEAM_OUT,
                        coreColor = coreColor,
                        fringeColor = fringeColor,
                        width = BEAM_W,
                        facing = facing,
                        damageType = damageType,
                        damageAmount = damageAmount,
                        source = ship,
                        dealt = dealtController,
                    )
                )
            } catch (_: Throwable) {
                // 若引擎不支持该插件接口（或被其他原因阻止），至少保留静态光束。
                // 这里不做额外处理。
            }

            // 命中端 VFX 与伤害在推进结束时触发（见 Arc13BeamAdvancePlugin）
        } catch (_: Throwable) {
        }
    }

    private fun pickAiTarget(
        engine: CombatEngineAPI,
        source: ShipAPI,
        weapon: WeaponAPI,
        from: Vector2f,
        forwardFacing: Float,
        range: Float,
    ): ShipAPI? {
        val ships = try { engine.ships } catch (_: Throwable) { null } ?: return null
        val arcHalf = try { (weapon.arc * 0.5f).coerceAtLeast(0f) } catch (_: Throwable) { 0f }

        var best: ShipAPI? = null
        var bestScore = Float.MAX_VALUE
        for (s in ships) {
            val t = s as? ShipAPI ?: continue
            if (t === source) continue
            if (t.owner == source.owner) continue
            if (!engine.isEntityInPlay(t)) continue
            if (t.isHulk) continue
            if (t.isPhased) continue

            val d = MathUtils.getDistance(from, t.location)
            if (d > range) continue

            val angTo = VectorUtils.getAngle(from, t.location)
            val diff = abs(angleDiffDeg(forwardFacing, angTo))
            if (arcHalf > 0.01f && diff > arcHalf + 0.5f) continue

            // 分数：距离优先，其次偏正前方
            val score = d + diff * AI_AIM_DIFF_PENALTY
            if (score < bestScore) {
                bestScore = score
                best = t
            }
        }
        return best
    }

    private fun angleDiffDeg(a: Float, b: Float): Float {
        var d = (b - a) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    private class Arc13DelayedDealtController(
        private val source: CombatEntityAPI,
    ) : CurveUtil.DealtController {
        data class Hit(
            val target: CombatEntityAPI,
            val point: Vector2f,
            val curveT: Float,
            val shieldHit: Boolean,
        )

        var hit: Hit? = null
            private set

        override fun applyEffect(target: CombatEntityAPI, point: Vector2f, beamT: Float, isShieldHit: Boolean) {
            if (hit == null) {
                hit = Hit(target, Vector2f(point), beamT, isShieldHit)
            }
        }

        override fun isIgnore(target: CombatEntityAPI): Boolean {
            var isFighter = false
            var parentIsSource = false
            var inPhased = false
            val shipTarget = target as? ShipAPI
            if (shipTarget != null) {
                isFighter = try { shipTarget.isFighter } catch (_: Throwable) { false }
                parentIsSource = try { shipTarget.parentStation === source } catch (_: Throwable) { false }
                inPhased = try { shipTarget.isPhased } catch (_: Throwable) { false }
            }

            val collisionNone = try { target.collisionClass == CollisionClass.NONE } catch (_: Throwable) { false }
            val sameOwner = try { target.owner == source.owner } catch (_: Throwable) { false }
            val friendSmall = (target is MissileAPI || isFighter || parentIsSource) && sameOwner

            return target === source || collisionNone || inPhased || friendSmall
        }

        override fun isPierceShield(target: ShipAPI): Boolean = false

        override fun isPierce(target: CombatEntityAPI, point: Vector2f, beamT: Float, isShieldHit: Boolean): Boolean = false
    }

    private fun findShieldHitAt(engine: CombatEngineAPI, source: ShipAPI, point: Vector2f): Boolean {
        val ships = try {
            engine.ships
        } catch (_: Throwable) {
            null
        } ?: return false

        var best: ShipAPI? = null
        var bestD2 = Float.MAX_VALUE
        for (s in ships) {
            if (s === source) continue
            if (!engine.isEntityInPlay(s)) continue
            val dx = point.x - s.location.x
            val dy = point.y - s.location.y
            val d2 = dx * dx + dy * dy
            if (d2 < bestD2) {
                bestD2 = d2
                best = s
            }
        }
        val target = best ?: return false

        val shield = try { target.shield } catch (_: Throwable) { null } ?: return false
        if (!shield.isOn) return false

        val dx = point.x - shield.location.x
        val dy = point.y - shield.location.y
        val r2 = dx * dx + dy * dy
        val rr = shield.radius
        if (r2 > (rr + 18f) * (rr + 18f)) return false
        return try { shield.isWithinArc(point) } catch (_: Throwable) { true }
    }

    private fun cloneNode(src: NodeData): NodeData {
        val loc = Vector2f(src.location)
        val tl = Vector2f(src.tangentLeft)
        val tr = Vector2f(src.tangentRight)
        val n = NodeData(loc, tl, tr)
        // mixFactor/颜色/宽度在外部统一设定
        try { n.setMixFactor(src.mixFactor) } catch (_: Throwable) {}
        return n
    }

    /**
     * 仅做逻辑推进，不负责渲染：通过每帧对两节点做 curveSplit(0..t) 更新，实现“光束前进”。
     */
    private class Arc13BeamAdvancePlugin(
        private val engine: CombatEngineAPI,
        private val seg: SegmentEntity,
        private val baseStart: NodeData,
        private val baseEnd: NodeData,
        private val advTime: Float,
        private val fadeIn: Float,
        private val full: Float,
        private val fadeOut: Float,
        private val coreColor: Color,
        private val fringeColor: Color,
        private val width: Float,
        private val facing: Float,
        private val damageType: DamageType,
        private val damageAmount: Float,
        private val source: ShipAPI,
        private val dealt: Arc13DelayedDealtController,
    ) : CombatLayeredRenderingPlugin {

        private var elapsed = 0f
        private var expired = false
        private var damageApplied = false

        override fun init(p0: CombatEntityAPI) {
        }

        override fun cleanup() {
            expired = true
        }

        override fun isExpired(): Boolean = expired

        override fun getActiveLayers(): EnumSet<CombatEngineLayers> = EnumSet.noneOf(CombatEngineLayers::class.java)

        override fun getRenderRadius(): Float = 0f

        override fun advance(amount: Float) {
            if (expired) return
            if (engine.isPaused) return

            elapsed += amount

            val nodes = seg.nodes
            if (nodes == null || nodes.size < 2) {
                expired = true
                return
            }

            val lifeTotal = (fadeIn + full + fadeOut).coerceAtLeast(0.001f)
            val fadeOutStart = (fadeIn + full).coerceAtLeast(0f)

            // 1) 推进：显示前缀曲线（0..t）
            if (advTime > 0.001f && elapsed < advTime) {
                val t = (elapsed / advTime).coerceIn(0f, 1f)
                val split = CurveUtil.curveSplit(baseStart, baseEnd, t)
                nodes[0] = split[0]
                nodes[1] = split[1]
            } else {
                // 2) 到达终点后：
                //    - 在 full 结束前保持全长
                //    - 进入 fadeOut 后“收缩到终点”（显示后缀 t..1）
                val inFadeOut = elapsed >= fadeOutStart && fadeOut > 0.001f
                if (!inFadeOut) {
                    nodes[0] = baseStart
                    nodes[1] = baseEnd
                } else {
                    val rt = ((elapsed - fadeOutStart) / fadeOut).coerceIn(0f, 1f)
                    val split = CurveUtil.curveSplit(baseStart, baseEnd, rt)
                    // 右子曲线（rt..1）：{mid, modifiedEnd}
                    nodes[0] = split[1]
                    nodes[1] = split[2]
                }
            }

            // 颜色/宽度：确保被替换出的 NodeData 也保持一致
            for (node in nodes) {
                node.setColor(coreColor)
                node.setEmissiveColor(fringeColor)
                node.setWidth(width)
            }

            try {
                seg.setNodeRefreshAllFromCurrentIndex()
                seg.submitNodes()
            } catch (_: Throwable) {
            }

            // 伤害 + 命中 VFX：推进到终点时触发一次
            if (!damageApplied && advTime > 0.001f && elapsed >= advTime) {
                damageApplied = true
                val hit = dealt.hit
                if (hit != null) {
                    try {
                        engine.applyDamage(
                            hit.target,
                            hit.point,
                            damageAmount,
                            damageType,
                            0f,
                            false,
                            false,
                            source,
                            true,
                        )
                    } catch (_: Throwable) {
                    }

                    try {
                        Arc13Vfx.spawnImpact(engine, Vector2f(hit.point), facing, damageType, hit.shieldHit)
                    } catch (_: Throwable) {
                    }
                }
            }

            if (elapsed >= lifeTotal + 0.05f) expired = true
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            // no-op: SegmentEntity 由 BoxUtil 自己渲染
        }
    }

    /** Kotlin 没有标准 tuple，手写一个。 */
    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
