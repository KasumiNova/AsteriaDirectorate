package cn.kasuminova.astd.combat.effect.arc.geminidem

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.graphics.SpriteAPI
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import org.boxutil.units.standard.entity.FlareEntity
import org.boxutil.units.standard.entity.TrailEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.IdentityHashMap

/**
 * 双子星 DEM payload 光束的 BoxUtil 自绘渲染（两件 payload .wpn 的 `everyFrameEffect`）。
 *
 * 原版束体渲染由 [GeminiDemPayloadBeamEffect] 隐藏（保留伤害结算），本效果按 beam 几何每帧驱动一条
 * tapered beam trail：动能用 astd_trails_zappy（冷蓝白）、高爆用 astd_trails_flow（共振红）。
 *
 * 生命周期：
 * - 出现：RAMP_IN 秒内宽度/透明度 0 → 全额；发射瞬间在发射点（beam.from）炸开 10 个同色星云粒子；
 * - 存续：每帧跟随 beam.from → beam.to（长度/朝向同步），发射点常驻双光斑（SMOOTH 圆斑 +
 *   SHARP_DISC 垂直光柱），束体周围节律性冒出同色星云；动能光束头尾附加同色装饰电弧；
 * - 消散：beam 停火后 FADE_OUT 秒内透明度 → 0、宽度 → 30%，结束 delete；
 * - 兜底：firing 期间逐帧重钉实体 globalTimer 的 FULL 段（KEEPALIVE 秒）——advance 停更
 *   （导弹命中/被击毁）时定时器自然走完 KEEPALIVE + FADE_OUT 自动淡出回收，
 *   不得恢复创建期长 full 兜底（full=10 曾致弹头命中后光束滞留 10s+）。
 *
 * 同步共振视觉：[GeminiDemSyncHandler] 触发时按导弹实体 id 写入视觉状态表，
 * 本层读表驱动束体/光斑颜色在 SYNC_BLEND_SECONDS 内渐变转紫（仅视觉，增伤结算在 SyncHandler）。
 *
 * 状态按 WeaponAPI 实例存于 engine.customData（战斗域自动销毁）；插件实例为 spec 级共享，禁止持有单武器状态字段。
 */
class GeminiDemPayloadBeamVfx : EveryFrameWeaponEffectPlugin {

    private enum class Kind(val texturePath: String, val core: Color, val fringe: Color) {
        KINETIC(TEX_ZAPPY, Color(220, 240, 255), Color(140, 190, 255)),
        HE(TEX_FLOW, Color(255, 150, 150), Color(255, 40, 60)),
    }

    /** 单条 payload 光束的视觉状态（一武器一条；beam 实例轮换时旧实体立即退休淡出）。 */
    private class BeamVisualState {
        var beam: BeamAPI? = null
        var entity: TrailEntity? = null

        /** 发射点常驻光斑：SMOOTH 圆斑 + SHARP_DISC 垂直光柱。 */
        var glowFlare: FlareEntity? = null
        var pillarFlare: FlareEntity? = null

        var activeElapsed = 0f
        var fading = false
        var fadeElapsed = 0f

        /** 同步共振紫色渐变系数（0=原色，1=全紫）。 */
        var syncBlend = 0f

        // 停火后冻结的末帧几何（消散动画不再跟随目标）
        var lastFrom: Vector2f? = null
        var lastFacing = 0f
        var lastLength = 0f

        // 节律特效计时器
        val ambientNebulaInterval = IntervalUtil(AMBIENT_NEBULA_INTERVAL, AMBIENT_NEBULA_INTERVAL)
        val decorArcInterval = IntervalUtil(DECOR_ARC_INTERVAL, DECOR_ARC_INTERVAL)

        // 可复用节点表：TrailEntity.setNodes 持有引用且 _deleteExc/resetNodes 会 clear()，
        // 必须传可变的 java.util.ArrayList（Kotlin listOf 产出的定长 list 会在 delete 时抛
        // UnsupportedOperationException）；逐帧原地改写元素避免每帧分配
        val nodes = arrayListOf(Vector2f(0f, 0f), Vector2f(0f, 0f))
    }

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        val kind = when (weapon.spec?.weaponId) {
            GeminiDemDifficulty.KINETIC_PAYLOAD_ID -> Kind.KINETIC
            GeminiDemDifficulty.HE_PAYLOAD_ID -> Kind.HE
            else -> return
        }

        val beam = weapon.beams?.firstOrNull()
        val firing = beam != null && weapon.isFiring && beam.brightness > 0.05f

        val states = statesOf(engine)
        var state = states[weapon]

        if (firing && beam != null) {
            var newBurst = false
            if (state == null) {
                state = BeamVisualState()
                states[weapon] = state
                newBurst = true
            } else if (state.beam !== beam) {
                // beam 实例轮换（新一轮打击）：旧实体退休为定时器淡出，新 beam 重建新实体
                retireEntity(state.entity)
                retireFlares(state)
                state.entity = null
                state.beam = beam
                state.activeElapsed = 0f
                state.fading = false
                state.fadeElapsed = 0f
                newBurst = true
            }

            state.beam = beam
            state.activeElapsed += amount

            val from = Vector2f(beam.from)
            val facing = Misc.getAngleInDegrees(from, beam.to)
            val length = MathUtils.getDistance(from, beam.to).coerceAtLeast(1f)
            state.lastFrom = from
            state.lastFacing = facing
            state.lastLength = length

            // 同步共振紫色渐变：读 SyncHandler 写在弹头 demDrone（weapon.ship）实体 customData 的到期时刻
            val now = engine.getTotalElapsedTime(false)
            val syncUntil = weapon.ship?.customData?.get(GeminiDemDifficulty.SYNC_VISUAL_KEY) as? Float ?: 0f
            val syncActive = syncUntil > now
            state.syncBlend = (state.syncBlend +
                    (if (syncActive) amount else -amount) / SYNC_BLEND_SECONDS).coerceIn(0f, 1f)
            val core = lerpColor(kind.core, SYNC_CORE, state.syncBlend)
            val fringe = lerpColor(kind.fringe, SYNC_FRINGE, state.syncBlend)

            val ramp = (state.activeElapsed / RAMP_IN).coerceIn(0f, 1f)
            val entity = state.entity ?: createEntity(engine, kind, from, facing, length).also { state.entity = it }
            if (entity != null) {
                updateEntity(entity, state.nodes, from, facing, length, core, fringe, alphaMul = ramp, widthMul = ramp)
                // 存活保活钉：setGlobalTimer 每次调用重置计时，firing 期间逐帧钉住 FULL 段；
                // advance 停更（导弹命中销毁等）时定时器自然走完 KEEPALIVE + FADE_OUT 自动淡出回收，
                // 不再依赖创建期长兜底（full=10 曾导致弹头命中后光束滞留 10s+）
                entity.setGlobalTimer(0f, KEEPALIVE, FADE_OUT)
            }
            if (newBurst) {
                spawnLaunchBurst(engine, from, fringe)
            }
            syncFlares(engine, state, from, facing, core, fringe, alphaMul = ramp)
            advanceAmbientNebula(state, engine, amount, from, facing, length, fringe)
            if (kind == Kind.KINETIC) {
                advanceDecorArcs(state, engine, amount, from, facing, length)
            }
            return
        }

        // 停火/无 beam：进入或推进消散
        if (state == null || state.entity == null) {
            if (state != null) retireFlares(state)
            states.remove(weapon)
            return
        }
        if (!state.fading) {
            state.fading = true
            state.fadeElapsed = 0f
            // 兜底定时器：advance 停更（导弹被击毁等）时由 BoxUtil 自动淡出回收
            state.entity?.setGlobalTimer(0f, FADE_OUT + 0.1f, 0.1f)
            retireFlares(state)
        }
        state.fadeElapsed += amount
        val t = (state.fadeElapsed / FADE_OUT).coerceIn(0f, 1f)
        val entity = state.entity
        val from = state.lastFrom
        if (entity != null && from != null) {
            updateEntity(
                entity, state.nodes, from, state.lastFacing, state.lastLength,
                core = null, fringe = null,
                alphaMul = 1f - t, widthMul = lerp(1f, FADE_WIDTH_END_MUL, t),
            )
        }
        if (t >= 1f) {
            entity?.delete()
            states.remove(weapon)
        }
    }

    private fun createEntity(
        engine: CombatEngineAPI,
        kind: Kind,
        from: Vector2f,
        facing: Float,
        length: Float,
    ): TrailEntity? {
        BoxUtilCombatVfx.ensureReady(engine)
        return BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
            engine = engine,
            location = from,
            facing = facing,
            length = length,
            baseWidth = BASE_WIDTH,
            tipWidth = TIP_WIDTH,
            coreColor = kind.core,
            fringeColor = kind.fringe,
            coreSprite = spriteOf(kind),
            fringeSprite = spriteOf(kind),
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
            // 初始定时器即保活口径：firing 分支逐帧重钉（见 advance），停帧后 KEEPALIVE + FADE_OUT 内自动淡出
            full = KEEPALIVE,
            baseAlphaMul = BASE_ALPHA,
            tipAlphaMul = TIP_ALPHA,
            baseEmissiveAlphaMul = BASE_EMISSIVE_ALPHA,
            tipEmissiveAlphaMul = TIP_EMISSIVE_ALPHA,
            mixPower = 0.5f,
        )?.also { entity ->
            entity.texturePixels = TEX_PIXELS
            entity.textureSpeed = TEX_SPEED
            entity.isFlowWhenPaused = false
        }
    }

    /**
     * 每帧同步几何与生命阶段参数（长度跟随 beam；alpha/宽度乘数由 ramp-in 与消散淡出驱动）。
     *
     * 节点刷新坑（BoxUtil TrailEntity 语义）：submitNodes 只上传 [TrailEntity.setNodeRefreshAllFromCurrentIndex]
     * 圈定的区间，同尺寸再提交默认刷新计数为 0（静默跳过，曾导致光束长度冻结在创建帧）——每帧必须显式圈全区。
     */
    private fun updateEntity(
        entity: TrailEntity,
        nodes: ArrayList<Vector2f>,
        from: Vector2f,
        facing: Float,
        length: Float,
        core: Color?,
        fringe: Color?,
        alphaMul: Float,
        widthMul: Float,
    ) {
        nodes[1].x = length
        entity.setNodes(nodes)
        entity.setNodeRefreshAllFromCurrentIndex()
        entity.submitNodes()
        entity.setStateVanilla(from, BoxUtilCombatVfx.normalizeFacingDeg(facing))

        entity.startWidth = BASE_WIDTH * widthMul
        entity.endWidth = TIP_WIDTH * widthMul

        entity.startColorAlpha = BASE_ALPHA * alphaMul
        entity.endColorAlpha = TIP_ALPHA * alphaMul
        entity.startEmissiveAlpha = BASE_EMISSIVE_ALPHA * alphaMul
        entity.endEmissiveAlpha = TIP_EMISSIVE_ALPHA * alphaMul

        if (core != null && fringe != null) {
            val mat = entity.materialData
            mat.setColor(core)
            mat.setEmissiveColor(fringe)
        }
    }

    /** 退休实体：不再跟踪，改由全局定时器做一次短淡出后自动回收。 */
    private fun retireEntity(entity: TrailEntity?) {
        entity?.setGlobalTimer(0f, 0.01f, FADE_OUT)
    }

    /** 发射点双光斑：firing 期间每帧同步位置/颜色并重钉保活定时器；首次调用惰性创建。 */
    private fun syncFlares(
        engine: CombatEngineAPI,
        state: BeamVisualState,
        from: Vector2f,
        beamFacing: Float,
        core: Color,
        fringe: Color,
        alphaMul: Float,
    ) {
        val glow = state.glowFlare ?: createFlare(engine, smooth = true, core, fringe)?.also { state.glowFlare = it }
        val pillar = state.pillarFlare ?: createFlare(engine, smooth = false, core, fringe)?.also { state.pillarFlare = it }
        glow?.let {
            it.setStateVanilla(from, 0f)
            it.setCoreColor(core)
            it.setFringeColor(fringe)
            it.globalAlpha = alphaMul
            it.setGlobalTimer(0f, KEEPALIVE, FADE_OUT)
        }
        pillar?.let {
            // 光柱固定垂直于光束朝向（草图口径：发射点横杠）
            it.setStateVanilla(from, BoxUtilCombatVfx.normalizeFacingDeg(beamFacing + 90f))
            it.setCoreColor(core)
            it.setFringeColor(fringe)
            it.globalAlpha = alphaMul
            it.setGlobalTimer(0f, KEEPALIVE, FADE_OUT)
        }
    }

    private fun createFlare(engine: CombatEngineAPI, smooth: Boolean, core: Color, fringe: Color): FlareEntity? {
        val entity = FlareEntity()
        entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
        entity.setAdditiveBlend()
        if (smooth) {
            entity.setSmooth()
            entity.setSize(FLARE_GLOW_SIZE, FLARE_GLOW_SIZE)
        } else {
            entity.setSharpDisc()
            entity.setSize(FLARE_PILLAR_LENGTH, FLARE_PILLAR_WIDTH)
        }
        entity.autoAspect()
        // 用 Color 重载：BoxUtil 的 setCoreColor(float,float,float,float) 有源码 bug（误写 fringe 槽位）
        entity.setCoreColor(core)
        entity.setFringeColor(fringe)
        entity.setGlobalTimer(0f, KEEPALIVE, FADE_OUT)
        BoxUtilCombatVfx.ensureReady(engine)
        val addState = BoxUtilCombatVfx.addEntity(engine, entity)
        if (addState != 0) {
            log.warn("双子星 DEM payload 发射点光斑注册失败（addEntity 返回 $addState，smooth=$smooth），本层缺失其余特效照常")
            entity.delete()
            return null
        }
        return entity
    }

    /** 退休光斑：改由全局定时器短淡出后自动回收。 */
    private fun retireFlares(state: BeamVisualState) {
        state.glowFlare?.setGlobalTimer(0f, 0.01f, FADE_OUT)
        state.pillarFlare?.setGlobalTimer(0f, 0.01f, FADE_OUT)
        state.glowFlare = null
        state.pillarFlare = null
    }

    /** 光束出现瞬间：发射点炸开 10 个同色星云粒子（大小 100~200su，偏移 20~40su，零速度，约 1s 消散）。 */
    private fun spawnLaunchBurst(engine: CombatEngineAPI, from: Vector2f, fringe: Color) {
        repeat(LAUNCH_BURST_COUNT) {
            val dir = Misc.getUnitVectorAtDegreeAngle(MathUtils.getRandomNumberInRange(0f, 360f))
            dir.scale(MathUtils.getRandomNumberInRange(LAUNCH_BURST_OFFSET_MIN, LAUNCH_BURST_OFFSET_MAX))
            val pos = Vector2f(from.x + dir.x, from.y + dir.y)
            engine.addNebulaParticle(
                pos, ZERO, MathUtils.getRandomNumberInRange(LAUNCH_NEBULA_SIZE_MIN, LAUNCH_NEBULA_SIZE_MAX),
                1.5f, 0.05f, 0.1f, 0.6f,
                Color(fringe.red, fringe.green, fringe.blue, LAUNCH_NEBULA_ALPHA), true,
            )
        }
    }

    /** 束体周围节律星云：每 0.2s 两个（大小 50~100su，沿束随机落点 ±40su，零速度，约 0.5s 消散）。 */
    private fun advanceAmbientNebula(
        state: BeamVisualState,
        engine: CombatEngineAPI,
        amount: Float,
        from: Vector2f,
        facing: Float,
        length: Float,
        fringe: Color,
    ) {
        state.ambientNebulaInterval.advance(amount)
        if (!state.ambientNebulaInterval.intervalElapsed()) return
        val dir = Misc.getUnitVectorAtDegreeAngle(facing)
        repeat(AMBIENT_NEBULA_COUNT) {
            val along = MathUtils.getRandomNumberInRange(0f, length)
            val jitter = Misc.getUnitVectorAtDegreeAngle(MathUtils.getRandomNumberInRange(0f, 360f))
            jitter.scale(MathUtils.getRandomNumberInRange(0f, AMBIENT_NEBULA_JITTER))
            val pos = Vector2f(from.x + dir.x * along + jitter.x, from.y + dir.y * along + jitter.y)
            engine.addNebulaParticle(
                pos, ZERO, MathUtils.getRandomNumberInRange(AMBIENT_NEBULA_SIZE_MIN, AMBIENT_NEBULA_SIZE_MAX),
                1.3f, 0.05f, 0.1f, 0.3f,
                Color(fringe.red, fringe.green, fringe.blue, AMBIENT_NEBULA_ALPHA), true,
            )
        }
    }

    /** 动能光束装饰电弧：头尾各一道，端点相对束线抖动 ≤40su（合计误差 ≤80su），纯视觉。 */
    private fun advanceDecorArcs(
        state: BeamVisualState,
        engine: CombatEngineAPI,
        amount: Float,
        from: Vector2f,
        facing: Float,
        length: Float,
    ) {
        state.decorArcInterval.advance(amount)
        if (!state.decorArcInterval.intervalElapsed()) return
        val dir = Misc.getUnitVectorAtDegreeAngle(facing)
        val seg = (length * DECOR_ARC_SEGMENT_FRACTION).coerceAtMost(DECOR_ARC_SEGMENT_MAX)
        // 尾段（发射点侧）
        spawnDecorArc(engine, jitter(from), jitter(offset(from, dir, seg)))
        // 头段（命中点侧）
        spawnDecorArc(engine, jitter(offset(from, dir, length - seg)), jitter(offset(from, dir, length)))
    }

    private fun spawnDecorArc(engine: CombatEngineAPI, a: Vector2f, b: Vector2f) {
        engine.spawnEmpArcVisual(a, null, b, null, DECOR_ARC_THICKNESS, Kind.KINETIC.fringe, Kind.KINETIC.core)
    }

    private fun offset(origin: Vector2f, dir: Vector2f, dist: Float): Vector2f =
        Vector2f(origin.x + dir.x * dist, origin.y + dir.y * dist)

    private fun jitter(pos: Vector2f): Vector2f {
        val j = Misc.getUnitVectorAtDegreeAngle(MathUtils.getRandomNumberInRange(0f, 360f))
        j.scale(MathUtils.getRandomNumberInRange(0f, DECOR_ARC_JITTER))
        return Vector2f(pos.x + j.x, pos.y + j.y)
    }

    private fun spriteOf(kind: Kind): SpriteAPI = when (kind) {
        Kind.KINETIC -> kineticSprite
        Kind.HE -> heSprite
    }

    companion object {
        private val log = Global.getLogger(GeminiDemPayloadBeamVfx::class.java)

        private const val TEX_ZAPPY = "graphics/fx/astd_trails_zappy.png"
        private const val TEX_FLOW = "graphics/fx/astd_trails_flow.png"

        /** 出现 ramp-in 时长（秒）。 */
        private const val RAMP_IN = 0.1f

        /** 停火消散时长（秒）。 */
        private const val FADE_OUT = 0.45f

        /** firing 期间逐帧重钉的定时器 FULL 段（秒）：advance 停更后实体最多再存活本值 + FADE_OUT。 */
        private const val KEEPALIVE = 0.15f

        /** 消散末端宽度比例（宽度从全额过渡到该倍率）。 */
        private const val FADE_WIDTH_END_MUL = 0.3f

        // 束宽：基部（导弹侧）→ 尖端（命中侧），对齐原版 payload 束宽 30 的观感
        private const val BASE_WIDTH = 20f
        private const val TIP_WIDTH = 20f

        // 节点透明度基准（乘以生命阶段系数后逐帧写入）
        private const val BASE_ALPHA = 0.8f
        private const val TIP_ALPHA = 1.0f
        private const val BASE_EMISSIVE_ALPHA = 0.2f
        private const val TIP_EMISSIVE_ALPHA = 0.4f

        // 贴图流动：256px 贴图沿束长滚动（方向/速率烟测目检面）
        private const val TEX_PIXELS = 256f
        private const val TEX_SPEED = 360f

        // 同步共振紫色（渐变目标色，仅视觉）
        private val SYNC_CORE = Color(235, 190, 255)
        private val SYNC_FRINGE = Color(180, 90, 255)

        /** 紫色渐变全量时长（秒）。 */
        private const val SYNC_BLEND_SECONDS = 0.25f

        // 发射瞬间星云爆发
        private const val LAUNCH_BURST_COUNT = 10
        private const val LAUNCH_BURST_OFFSET_MIN = 20f
        private const val LAUNCH_BURST_OFFSET_MAX = 40f
        private const val LAUNCH_NEBULA_SIZE_MIN = 100f
        private const val LAUNCH_NEBULA_SIZE_MAX = 200f
        private const val LAUNCH_NEBULA_ALPHA = 110

        // 束体周围节律星云
        private const val AMBIENT_NEBULA_INTERVAL = 0.2f
        private const val AMBIENT_NEBULA_COUNT = 2
        private const val AMBIENT_NEBULA_SIZE_MIN = 50f
        private const val AMBIENT_NEBULA_SIZE_MAX = 100f
        private const val AMBIENT_NEBULA_JITTER = 40f
        private const val AMBIENT_NEBULA_ALPHA = 90

        // 动能装饰电弧
        private const val DECOR_ARC_INTERVAL = 0.1f
        private const val DECOR_ARC_JITTER = 40f
        private const val DECOR_ARC_THICKNESS = 10f
        private const val DECOR_ARC_SEGMENT_FRACTION = 0.3f
        private const val DECOR_ARC_SEGMENT_MAX = 250f

        // 发射点常驻光斑尺寸
        private const val FLARE_GLOW_SIZE = 46f
        private const val FLARE_PILLAR_LENGTH = 160f
        private const val FLARE_PILLAR_WIDTH = 30f

        /** engine.customData 键：WeaponAPI → 视觉状态表（战斗域生命周期）。 */
        private const val STATES_KEY = "astd_gemini_dem_payload_vfx_states"

        private val ZERO = Vector2f(0f, 0f)

        private val kineticSprite: SpriteAPI by lazy { Global.getSettings().getSprite(TEX_ZAPPY) }
        private val heSprite: SpriteAPI by lazy { Global.getSettings().getSprite(TEX_FLOW) }

        @Suppress("UNCHECKED_CAST")
        private fun statesOf(engine: CombatEngineAPI): IdentityHashMap<WeaponAPI, BeamVisualState> =
            engine.customData.getOrPut(STATES_KEY) { IdentityHashMap<WeaponAPI, BeamVisualState>() }
                    as IdentityHashMap<WeaponAPI, BeamVisualState>

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
            lerp(a.red.toFloat(), b.red.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(a.green.toFloat(), b.green.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(a.blue.toFloat(), b.blue.toFloat(), t).toInt().coerceIn(0, 255),
        )
    }
}
