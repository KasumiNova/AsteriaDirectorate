package cn.kasuminova.astd.combat.effect.lens

import cn.kasuminova.astd.api.buff.getOrCreateBuffByWeapon
import cn.kasuminova.astd.api.buff.buffHost
import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.impl.render.AnnihilationVortexVortexComponent
import cn.kasuminova.astd.impl.render.BeamHostImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.beam.driver.BeamFrame
import cn.kasuminova.astd.renderer.beam.driver.BeamVfxDriver
import cn.kasuminova.astd.renderer.beam.driver.BeamVfxDriverImpl
import cn.kasuminova.astd.renderer.beam.driver.BeamVfxSpecs
import cn.kasuminova.astd.renderer.effect.projectile.beam.BeamLineUtil
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.roundToInt

/**
 * 湮灭涡旋 everyFrameEffect（规格 04 §2.2）：全部机制（涡旋牵引/吸收/吞噬池/坍缩 + 自绘 VFX）的唯一入口。
 *
 * 状态机（对照 GCP 三段式裁剪充能段——本武器 chargeup=0）：
 * `IDLE ─(beam != null && brightness > 0.05)→ FIRING ─(beam 消失/熄灭)→ 坍缩恰好一次 → FADING → IDLE`。
 *
 * 结算顺序铁律：每帧内 束体驱动 → 牵引/吸收 → 池记账 → HUD；
 * 停火帧 坍缩结算 → 坍缩 VFX → 池移除。坍缩只在停火首帧恰好一次（[beamStarted] 标志位保证）。
 * 宿主失效（船 hulk/换装）：池走 SELF_MANAGED 自回收 + INFO，本插件不再被调用，**不触发坍缩**（机制明确行为）。
 */
class AnnihilationVortexBeamEffect : EveryFrameWeaponEffectPlugin {

    private val absorb = AnnihilationVortexAbsorbImpl()
    private val collapse = AnnihilationVortexCollapseImpl()

    private var beamDriver: BeamVfxDriver? = null
    private var vortex: AnnihilationVortexVortexComponent? = null
    private var pool: AnnihilationVortexPoolImpl? = null

    private var beamStarted = false
    private var beamStartedAt = 0f
    private var fadeStartedAt = -1f
    private var lastLine: BeamLineUtil.BeamLine? = null

    /** 本开火周期缓存的难度取值（开火起点一次性解析，规格 04 §2.2）。 */
    private var radius = 0f
    private var aoeMult = 1f

    private var warnedNoShip = false
    private var warnedMissingTree = false

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        if (amount <= 0f) return

        val ship = weapon.ship
        if (ship == null) {
            if (!warnedNoShip) {
                warnedNoShip = true
                log.warn("[ASTD] 湮灭涡旋武器无宿主舰（weapon.ship == null），异常装配，本帧起不再推进: weapon=${weapon.spec?.weaponId}")
            }
            return
        }

        val now = engine.getTotalElapsedTime(false)
        val beam = weapon.beams?.firstOrNull()
        val brightness = (beam?.brightness ?: 0f).coerceIn(0f, 1f)
        val firing = beam != null && brightness > 0.05f

        if (firing) {
            fadeStartedAt = -1f
            if (!beamStarted) {
                // ====== 进入 FIRING（开火起点，每周期一次）：难度缓存 → 池就位 → 建 VFX 树 ======
                beamStarted = true
                beamStartedAt = now
                radius = AnnihilationVortexDifficulty.resolve(AnnihilationVortexDifficulty.RADIUS, ship.owner)
                val threshold = AnnihilationVortexDifficulty.resolve(AnnihilationVortexDifficulty.ABSORB_LIMIT, ship.owner)
                aoeMult = AnnihilationVortexDifficulty.resolve(AnnihilationVortexDifficulty.AOE_MULT, ship.owner)
                telemetryRecord(engine, if (ship.owner == 0) TELEMETRY_LAST_RADIUS_PLAYER else TELEMETRY_LAST_RADIUS_ENEMY, radius)
                telemetryRecord(engine, if (ship.owner == 0) TELEMETRY_LAST_THRESHOLD_PLAYER else TELEMETRY_LAST_THRESHOLD_ENEMY, threshold)
                telemetryRecord(engine, if (ship.owner == 0) TELEMETRY_LAST_AOEMULT_PLAYER else TELEMETRY_LAST_AOEMULT_ENEMY, aoeMult)

                pool = ship.getOrCreateBuffByWeapon(POOL_BUFF_ID, weapon) {
                    AnnihilationVortexPoolImpl(threshold, ship.buffHost(), weapon)
                } as AnnihilationVortexPoolImpl

                beamDriver?.dispose()
                val tree = BeamVfxSpecs.build(VFX_SPEC_ID)
                if (tree == null) {
                    if (!warnedMissingTree) {
                        warnedMissingTree = true
                        log.warn("[ASTD] BeamVfxSpecs 未登记 $VFX_SPEC_ID，湮灭涡旋自绘 VFX 缺失（机制结算照常）")
                    }
                    beamDriver = null
                    vortex = null
                } else {
                    vortex = tree.children.firstOrNull { it.id == VORTEX_NODE_ID } as? AnnihilationVortexVortexComponent
                    // 涡旋半径档位经树参数传入（规格 04 §3 二选一裁定：构建函数无参，
                    // 由 BeamEffect 在建树后直写组件属性；闭包捕获不可行——builders 为无参注册表）。
                    vortex?.vortexRadius = radius
                    beamDriver = BeamVfxDriverImpl(BeamHostImpl("avortex@" + System.identityHashCode(this), BASE_WIDTH), tree)
                }
            }

            val line = BeamLineUtil.fromBeamOrWeapon(weapon, beam) ?: return
            lastLine = line

            // 1) 束体驱动（前 BEAM_GROW_TIME 视觉渐长，仅视觉）。
            val reach = ((now - beamStartedAt) / BEAM_GROW_TIME).coerceIn(0f, 1f)
            driveBeam(engine, line, amount, reach, fadeMul = 1f)

            // 2) 牵引/吸收（中心 = 光束终点 beam.to）。
            val endpoint = line.to
            val outcome = absorb.advance(
                engine = engine,
                center = endpoint,
                radius = radius,
                absorbRadius = AnnihilationVortexDifficulty.absorbRadiusFor(radius),
                sourceOwner = ship.owner,
                amount = amount,
                onAbsorbedFx = { loc -> vortex?.onAbsorbed(loc) },
            )

            // 3) 池记账 + 单次吸收浮字（玩家船携带时，原生合并浮字通道）。
            val p = pool
            if (p != null && outcome.absorbed.isNotEmpty()) {
                telemetryInc(engine, if (ship.owner == 0) TELEMETRY_ABSORBED_PLAYER else TELEMETRY_ABSORBED_ENEMY, outcome.absorbed.size)
                for (shot in outcome.absorbed) {
                    val added = p.addAbsorbed(shot.type, shot.baseDamage)
                    if (ship === engine.playerShip && added > 0f) {
                        feedback.floatingDamage(engine, shot.location, added, ABSORB_FLOATY_COLOR, ship, ship)
                        telemetryInc(engine, TELEMETRY_FLOATY_COUNT)
                    }
                }
            }

            // 4) HUD 吞噬池（仅玩家船；池移除即停刷新，状态栏自然消失）。
            if (p != null && ship === engine.playerShip) {
                feedback.maintainPlayerStatus(
                    engine,
                    HUD_KEY,
                    HUD_ICON,
                    I18n[I18n.Categories.MOD, "astd.annihilation_vortex.hud.status.title"],
                    I18n.t(
                        I18n.Categories.MOD,
                        "astd.annihilation_vortex.hud.status.desc",
                        "pool" to p.convertedTotal.roundToInt(),
                        "collapse" to AnnihilationVortexDifficulty.collapseDamage(p.convertedTotal, aoeMult).roundToInt(),
                    ),
                    negative = false,
                )
                telemetryInc(engine, TELEMETRY_HUD_FRAMES)
            }
            return
        }

        if (beamStarted) {
            // ====== 停火首帧：坍缩恰好一次（坍缩结算 → 坍缩 VFX → 池移除） ======
            beamStarted = false
            fadeStartedAt = now
            val p = pool
            pool = null
            if (p != null) {
                // 宿主失效防线（第二轮烟测实证）：船被击毁后本插件仍会被调用一帧（束已灭），
                // 此时不得坍缩——宿主死亡涡旋哑火是机制明确行为；池不标记消费，
                // 移除时走 SELF_MANAGED 回收语义（onRemove 记「丢弃」INFO + 遥测）。
                val hostAlive = !ship.isHulk && ship.isAlive
                val center = lastLine?.to ?: beam?.let { Vector2f(it.from) }
                if (!hostAlive) {
                    log.info(
                        "[ASTD] 湮灭涡旋宿主失效（hulk=${ship.isHulk}, alive=${ship.isAlive}），本周期不触发坍缩，" +
                            "吞噬池 ${p.convertedTotal}（吸收 ${p.absorbedCount} 发）转自回收: weapon=${weapon.spec?.weaponId}",
                    )
                } else if (center != null) {
                    val damage = AnnihilationVortexDifficulty.collapseDamage(p.convertedTotal, aoeMult)
                    val collapseRadius = AnnihilationVortexDifficulty.collapseRadiusFor(radius)
                    val hits = collapse.resolve(engine, center, collapseRadius, damage, ship)
                    AnnihilationVortexVfx.collapse(engine, center, collapseRadius)
                    telemetryInc(engine, TELEMETRY_COLLAPSE_COUNT)
                    telemetryRecord(engine, if (ship.owner == 0) TELEMETRY_LAST_COLLAPSE_DAMAGE_PLAYER else TELEMETRY_LAST_COLLAPSE_DAMAGE_ENEMY, damage)
                    telemetryRecord(engine, if (ship.owner == 0) TELEMETRY_LAST_COLLAPSE_HITS_PLAYER else TELEMETRY_LAST_COLLAPSE_HITS_ENEMY, hits.toFloat())
                    if (ship === engine.playerShip) {
                        feedback.floatingText(
                            engine,
                            center,
                            I18n.t(I18n.Categories.MOD, "astd.annihilation_vortex.hud.collapse_release", "damage" to damage.roundToInt()),
                            28f,
                            AnnihilationVortexVfx.CORE_COLOR,
                            null,
                            0f,
                            0f,
                        )
                        telemetryInc(engine, TELEMETRY_FLOATY_COUNT)
                    }
                    p.markConsumed()
                } else {
                    log.warn("[ASTD] 湮灭涡旋停火帧无光束终点记录，本次坍缩无中心点（异常时序，不静默）: weapon=${weapon.spec?.weaponId}")
                }
                ship.buffHost().remove(p, weapon)
            }
            return
        }

        // ====== 坍缩后淡出：束体按 fadeMul 收束到消失后释放驱动 ======
        val fs = fadeStartedAt
        val line = lastLine
        if (fs >= 0f && line != null) {
            val fade = (1f - (now - fs) / END_FADE_TIME).coerceIn(0f, 1f)
            if (fade > 0f) {
                driveBeam(engine, line, amount, reach = 1f, fadeMul = fade)
            } else {
                fadeStartedAt = -1f
                lastLine = null
                beamDriver?.dispose()
                beamDriver = null
                vortex = null
            }
        }
    }

    /** 把束几何折成 [BeamFrame] 喂驱动：strength=1（无充能 ramp），fadeMul 控淡出收束。 */
    private fun driveBeam(engine: CombatEngineAPI, line: BeamLineUtil.BeamLine, amount: Float, reach: Float, fadeMul: Float) {
        val driver = beamDriver ?: return
        val visLen = line.length * reach.coerceIn(0f, 1f)
        val visTo = Vector2f(line.from.x + line.dirUnit.x * visLen, line.from.y + line.dirUnit.y * visLen)
        driver.advance(
            engine,
            BeamFrame(start = line.from, facing = line.facing, length = visLen, endpoint = visTo, firing = true, strength = 1f, fadeMul = fadeMul),
            amount,
        )
    }

    companion object {
        /** BeamVfxSpecs 注册键（与 `.wpn` id 一致）。 */
        const val VFX_SPEC_ID = "astd_annihilation_vortex"

        /** 吞噬池 Buff id（BuffHost 武器级复合键段）。 */
        const val POOL_BUFF_ID = "astd_annihilation_vortex_pool"

        /** 涡旋节点 id（树参数写入与吸收 flare 回调的定位键）。 */
        const val VORTEX_NODE_ID = "astd_annihilation_vortex_vortex"

        /** BeamHost 基宽（对应 `.wpn` width 14 的观感放大，目检可调）。 */
        const val BASE_WIDTH = 18f

        // ---- 自动化遥测键（engine.customData；玩家/敌方分列，烟测检查点证据） ----
        const val TELEMETRY_ABSORBED_PLAYER = "astd_av_absorbed_player"
        const val TELEMETRY_ABSORBED_ENEMY = "astd_av_absorbed_enemy"
        const val TELEMETRY_COLLAPSE_COUNT = "astd_av_collapse_count"
        const val TELEMETRY_LAST_COLLAPSE_DAMAGE_PLAYER = "astd_av_last_collapse_damage_player"
        const val TELEMETRY_LAST_COLLAPSE_DAMAGE_ENEMY = "astd_av_last_collapse_damage_enemy"
        const val TELEMETRY_LAST_COLLAPSE_HITS_PLAYER = "astd_av_last_collapse_hits_player"
        const val TELEMETRY_LAST_COLLAPSE_HITS_ENEMY = "astd_av_last_collapse_hits_enemy"
        const val TELEMETRY_LAST_RADIUS_PLAYER = "astd_av_last_radius_player"
        const val TELEMETRY_LAST_RADIUS_ENEMY = "astd_av_last_radius_enemy"
        const val TELEMETRY_LAST_THRESHOLD_PLAYER = "astd_av_last_threshold_player"
        const val TELEMETRY_LAST_THRESHOLD_ENEMY = "astd_av_last_threshold_enemy"
        const val TELEMETRY_LAST_AOEMULT_PLAYER = "astd_av_last_aoemult_player"
        const val TELEMETRY_LAST_AOEMULT_ENEMY = "astd_av_last_aoemult_enemy"
        const val TELEMETRY_POOL_RECYCLED = "astd_av_pool_recycled"
        /** HUD 状态条目刷新帧数（HUD 可见性证据：仅玩家船携带且池在时逐帧 +1）。 */
        const val TELEMETRY_HUD_FRAMES = "astd_av_hud_frames"
        /** 吸收入池浮字次数（单次吸收浮字证据）。 */
        const val TELEMETRY_FLOATY_COUNT = "astd_av_floaty_count"

        private const val BEAM_GROW_TIME = 0.08f
        private const val END_FADE_TIME = 0.35f

        /** HUD 状态条目键。 */
        private const val HUD_KEY = "astd_annihilation_vortex_pool"

        /** HUD 图标（LENS 阵列核心船插图，复用现成美术；美术件到位后替换）。 */
        private const val HUD_ICON = "graphics/hullmods/astd_lens_array_core.png"

        /** 单次吸收入池浮字色（LENS 深红）。 */
        private val ABSORB_FLOATY_COLOR = Color(255, 60, 70)

        /** HUD/浮字反馈通道（机制可视化铁律的统一落点）。 */
        private val feedback: CombatFeedback = CombatFeedbackImpl

        private val log = Global.getLogger(AnnihilationVortexBeamEffect::class.java)

        /** 遥测计数 +[delta]（无引擎场景静默跳过——计数仅服务烟测证据，非机制路径）。 */
        fun telemetryInc(engine: CombatEngineAPI, key: String, delta: Int = 1) {
            engine.customData[key] = counter(engine, key) + delta
        }

        /** 遥测读数（Int）。 */
        fun counter(engine: CombatEngineAPI, key: String): Int = (engine.customData[key] as? Number)?.toInt() ?: 0

        /** 遥测记录（Float 直写）。 */
        fun telemetryRecord(engine: CombatEngineAPI, key: String, value: Float) {
            engine.customData[key] = value
        }

        /** 遥测读数（Float）。 */
        fun telemetryFloat(engine: CombatEngineAPI, key: String): Float = (engine.customData[key] as? Number)?.toFloat() ?: 0f
    }
}
