package cn.kasuminova.astd.combat.effect.arc.geminidem

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.graphics.SpriteAPI
import com.fs.starfarer.api.util.Misc
import org.boxutil.units.standard.entity.TrailEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.IdentityHashMap

/**
 * 双子星 DEM payload 光束的 BoxUtil 自绘渲染（两件 payload .wpn 的 `everyFrameEffect`）。
 *
 * 原版束体渲染由 [GeminiDemPayloadBeamEffect] 隐藏（保留伤害结算），本效果按 beam 几何每帧驱动一条
 * tapered beam trail：动能用 astd_trails_zappy（冷蓝白）、高爆用 astd_trails_flow（暖橙白）。
 *
 * 生命周期：
 * - 出现：RAMP_IN 秒内宽度/透明度 0 → 全额；
 * - 存续：每帧跟随 beam.from → beam.to（长度/朝向同步）；
 * - 消散：beam 停火后 FADE_OUT 秒内透明度 → 0、宽度 → 30%，结束 delete；
 * - 兜底：firing 期间逐帧重钉实体 globalTimer 的 FULL 段（KEEPALIVE 秒）——advance 停更
 *   （导弹命中/被击毁）时定时器自然走完 KEEPALIVE + FADE_OUT 自动淡出回收，
 *   不得恢复创建期长 full 兜底（full=10 曾致弹头命中后光束滞留 10s+）。
 *
 * 状态按 WeaponAPI 实例存于 engine.customData（战斗域自动销毁）；插件实例为 spec 级共享，禁止持有单武器状态字段。
 */
class GeminiDemPayloadBeamVfx : EveryFrameWeaponEffectPlugin {

    private enum class Kind(val texturePath: String, val core: Color, val fringe: Color) {
        KINETIC(TEX_ZAPPY, Color(220, 240, 255), Color(140, 190, 255)),
        HE(TEX_FLOW, Color(255, 240, 220), Color(255, 170, 100)),
    }

    /** 单条 payload 光束的视觉状态（一武器一条；beam 实例轮换时旧实体立即退休淡出）。 */
    private class BeamVisualState {
        var beam: BeamAPI? = null
        var entity: TrailEntity? = null
        var activeElapsed = 0f
        var fading = false
        var fadeElapsed = 0f

        // 停火后冻结的末帧几何（消散动画不再跟随目标）
        var lastFrom: Vector2f? = null
        var lastFacing = 0f
        var lastLength = 0f

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
            if (state == null) {
                state = BeamVisualState()
                states[weapon] = state
            } else if (state.beam !== beam) {
                // beam 实例轮换（新一轮打击）：旧实体退休为定时器淡出，新 beam 重建新实体
                retireEntity(state.entity)
                state.entity = null
                state.beam = beam
                state.activeElapsed = 0f
                state.fading = false
                state.fadeElapsed = 0f
            }

            state.beam = beam
            state.activeElapsed += amount

            val from = Vector2f(beam.from)
            val facing = Misc.getAngleInDegrees(from, beam.to)
            val length = MathUtils.getDistance(from, beam.to).coerceAtLeast(1f)
            state.lastFrom = from
            state.lastFacing = facing
            state.lastLength = length

            val ramp = (state.activeElapsed / RAMP_IN).coerceIn(0f, 1f)
            val entity = state.entity ?: createEntity(engine, kind, from, facing, length).also { state.entity = it }
            if (entity != null) {
                updateEntity(entity, state.nodes, from, facing, length, alphaMul = ramp, widthMul = ramp)
                // 存活保活钉：setGlobalTimer 每次调用重置计时，firing 期间逐帧钉住 FULL 段；
                // advance 停更（导弹命中销毁等）时定时器自然走完 KEEPALIVE + FADE_OUT 自动淡出回收，
                // 不再依赖创建期长兜底（full=10 曾导致弹头命中后光束滞留 10s+）
                entity.setGlobalTimer(0f, KEEPALIVE, FADE_OUT)
            }
            return
        }

        // 停火/无 beam：进入或推进消散
        if (state == null || state.entity == null) {
            states.remove(weapon)
            return
        }
        if (!state.fading) {
            state.fading = true
            state.fadeElapsed = 0f
            // 兜底定时器：advance 停更（导弹被击毁等）时由 BoxUtil 自动淡出回收
            state.entity?.setGlobalTimer(0f, FADE_OUT + 0.1f, 0.1f)
        }
        state.fadeElapsed += amount
        val t = (state.fadeElapsed / FADE_OUT).coerceIn(0f, 1f)
        val entity = state.entity
        val from = state.lastFrom
        if (entity != null && from != null) {
            updateEntity(entity, state.nodes, from, state.lastFacing, state.lastLength, alphaMul = 1f - t, widthMul = lerp(1f, FADE_WIDTH_END_MUL, t))
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

    /** 每帧同步几何与生命阶段参数（长度跟随 beam；alpha/宽度乘数由 ramp-in 与消散淡出驱动）。 */
    private fun updateEntity(
        entity: TrailEntity,
        nodes: ArrayList<Vector2f>,
        from: Vector2f,
        facing: Float,
        length: Float,
        alphaMul: Float,
        widthMul: Float,
    ) {
        nodes[1].x = length
        entity.setNodes(nodes)
        entity.submitNodes()
        entity.setStateVanilla(from, BoxUtilCombatVfx.normalizeFacingDeg(facing))

        entity.startWidth = BASE_WIDTH * widthMul
        entity.endWidth = TIP_WIDTH * widthMul

        entity.setStartColorAlpha(BASE_ALPHA * alphaMul)
        entity.setEndColorAlpha(TIP_ALPHA * alphaMul)
        entity.setStartEmissiveAlpha(BASE_EMISSIVE_ALPHA * alphaMul)
        entity.setEndEmissiveAlpha(TIP_EMISSIVE_ALPHA * alphaMul)
    }

    /** 退休实体：不再跟踪，改由全局定时器做一次短淡出后自动回收。 */
    private fun retireEntity(entity: TrailEntity?) {
        entity?.setGlobalTimer(0f, 0.01f, FADE_OUT)
    }

    private fun spriteOf(kind: Kind): SpriteAPI = when (kind) {
        Kind.KINETIC -> kineticSprite
        Kind.HE -> heSprite
    }

    companion object {
        private const val TEX_ZAPPY = "graphics/fx/astd_trails_zappy.png"
        private const val TEX_FLOW = "graphics/fx/astd_trails_flow.png"

        /** 出现 ramp-in 时长（秒）。 */
        private const val RAMP_IN = 0.1f

        /** 停火消散时长（秒）。 */
        private const val FADE_OUT = 0.45f

        /** firing 期间逐帧重钉的定时器 FULL 段（秒）：advance 停更后实体最多再存活本值 + FADE_OUT。 */
        private const val KEEPALIVE = 0.25f

        /** 消散末端宽度比例（宽度从全额过渡到该倍率）。 */
        private const val FADE_WIDTH_END_MUL = 0.3f

        // 束宽：基部（导弹侧）→ 尖端（命中侧），对齐原版 payload 束宽 30 的观感
        private const val BASE_WIDTH = 12f
        private const val TIP_WIDTH = 30f

        // 节点透明度基准（乘以生命阶段系数后逐帧写入）
        private const val BASE_ALPHA = 0.85f
        private const val TIP_ALPHA = 1.0f
        private const val BASE_EMISSIVE_ALPHA = 1.0f
        private const val TIP_EMISSIVE_ALPHA = 1.4f

        // 贴图流动：256px 贴图沿束长滚动（方向/速率烟测目检面）
        private const val TEX_PIXELS = 256f
        private const val TEX_SPEED = 360f

        /** engine.customData 键：WeaponAPI → 视觉状态表（战斗域生命周期）。 */
        private const val STATES_KEY = "astd_gemini_dem_payload_vfx_states"

        private val kineticSprite: SpriteAPI by lazy { Global.getSettings().getSprite(TEX_ZAPPY) }
        private val heSprite: SpriteAPI by lazy { Global.getSettings().getSprite(TEX_FLOW) }

        @Suppress("UNCHECKED_CAST")
        private fun statesOf(engine: CombatEngineAPI): IdentityHashMap<WeaponAPI, BeamVisualState> =
            engine.customData.getOrPut(STATES_KEY) { IdentityHashMap<WeaponAPI, BeamVisualState>() }
                    as IdentityHashMap<WeaponAPI, BeamVisualState>

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }
}
