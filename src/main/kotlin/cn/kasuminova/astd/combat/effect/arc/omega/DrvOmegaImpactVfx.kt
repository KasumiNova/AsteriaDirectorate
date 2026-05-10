package cn.kasuminova.astd.combat.effect.arc.omega

import cn.kasuminova.astd.renderer.effect.projectile.beam.OglEllipseRingRenderer
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.combat.effect.arc.signature.tsm.TsmTerminalStrikeFx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.manager.CombatRenderingManager
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.sqrt

/**
 * DRV-Ω "相对论聚能炮"命中 VFX。
 *
 * 设计需求（40-omega.md）：
 * - **伪超速光束**：在命中瞬间生成从炮口到命中点的"闪光束"，表现近乎 Hitscan 的打击感。
 * - **命中反馈**：固定在命中点的环状激波 + 扭曲坍缩，表现"抵达即毁灭"。
 *
 * 实现：
 * - 闪光束：BoxUtil TrailEntity（core + glow + U 镜像），从武器位置到命中点，生命周期极短。
 * - 环状激波：OglEllipseRingRenderer，固定在命中点展开。
 * - 扭曲：BoxUtil DistortionEntity，命中点坍缩。
 */
internal object DrvOmegaImpactVfx {

    private val log = Global.getLogger(DrvOmegaImpactVfx::class.java)

    enum class Theme(
        val core: Color,
        val fringe: Color,
        val hot: Color,
        val glow: Color,
        val widthMul: Float,
        val ringAlphaMul: Float,
        val distortionPowerMul: Float,
        val distortionSizeMul: Float,
    ) {
        CYAN(
            core = Color(250, 252, 255, 235),
            fringe = Color(140, 235, 255, 220),
            hot = Color(220, 250, 255, 245),
            glow = Color(120, 200, 255, 180),
            widthMul = 1.0f,
            ringAlphaMul = 1.0f,
            distortionPowerMul = 1.0f,
            distortionSizeMul = 1.0f,
        ),

        REDSHIFT(
            core = Color(255, 235, 235, 235),
            fringe = Color(255, 75, 75, 220),
            hot = Color(255, 185, 185, 245),
            glow = Color(255, 80, 80, 155),
            widthMul = 2.0f,
            ringAlphaMul = 1.25f,
            distortionPowerMul = 1.45f,
            distortionSizeMul = 1.25f,
        ),
    }

    // region ---- 颜色 ----
    // 历史常量保留：默认主题使用 Theme.CYAN
    // endregion

    // region ---- 束体参数 ----

    private const val CORE_SPRITE = "graphics/fx/beamcoreb.png"
    private const val FRINGE_SPRITE = "graphics/fx/beamfringeb.png"

    private const val MIX_POWER_CORE = 2.4f
    private const val MIX_POWER_GLOW = 3.0f

    /** 闪光束生命周期：极短 fadeIn + 短 full + 中等 fadeOut，模拟"瞬闪" */
    private const val BEAM_FADE_IN = 0.03f
    private const val BEAM_FULL = 0.10f
    private const val BEAM_FADE_OUT = 0.32f

    // 需求：继续降低束体宽度（在已 -50% 基础上再 -40%）
    private const val CORE_BASE_W = 5.4f
    private const val CORE_TIP_W = CORE_BASE_W

    // endregion

    // region ---- 环状激波参数 ----

    private const val RING_A_HALF = 55f
    private const val RING_B_HALF = 40f
    private const val RING_DURATION = 0.38f
    private const val RING_EXPAND = 110f
    private val RING_COLOR = Color(140, 235, 255, 120)

    // region ---- 束体装饰环（沿束） ----

    /** 需求：像 GCP 一样束体周围有环，并且要“可淡化”。参考命中一次性扩散环（OGL）。 */
    private const val BODY_RING_SPACING = 120f
    private const val BODY_RING_A_HALF = 18f
    private const val BODY_RING_B_HALF = 10f
    private const val BODY_RING_DURATION = 0.42f
    // 对齐 AOD-7：expandSpeed=45
    private const val BODY_RING_EXPAND = 45f
    private val BODY_RING_COLOR = Color(140, 235, 255, 95)

    // endregion

    // endregion

    /**
     * 在命中点生成完整的 DRV-Ω 命中 VFX。
     *
     * @param from 闪光束起点（通常为武器位置）
     * @param to   命中点
     */
    fun spawnFullImpact(engine: CombatEngineAPI, from: Vector2f, to: Vector2f, shieldHit: Boolean, theme: Theme = Theme.CYAN) {
        // onHit 时机可能早于某些 bootstrap effect；确保 BoxUtil 管线已就绪。
        try {
            BoxUtilCombatVfx.ensureReady(engine)
        } catch (_: Throwable) {
        }

        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy)
        if (len <= 4f) return

        val facing = VectorUtils.getFacing(Vector2f(dx, dy))

        spawnBeamFlash(engine, from, facing, len, theme)
        spawnBeamBodyRings(engine, from, facing, len, theme)
        spawnMuzzleFlash(engine, from, theme)
        spawnMuzzlePulse(engine, from, facing, theme)
        spawnImpactBurst(engine, to, facing, shieldHit, theme)
        spawnRingShockwave(engine, to, facing, theme)
        spawnHitDistortion(engine, to, theme)
    }

    // region ---- 闪光束 (Beam Flash) ----

    /**
     * 从 [from] 到命中点（[from] + [len] 沿 [facing]）生成短寿命的 BoxUtil 束体。
     * 4 条 trail（core / core-U / glow / glow-U）叠加出"瞬间闪电连线"观感。
     */
    private fun spawnBeamFlash(engine: CombatEngineAPI, from: Vector2f, facing: Float, len: Float, theme: Theme) {
        val coreSpr = try {
            Global.getSettings().getSprite(CORE_SPRITE)
        } catch (_: Throwable) {
            return
        }

        val baseW = CORE_BASE_W * theme.widthMul
        val tipW = CORE_TIP_W * theme.widthMul
        val frgSpr = try {
            Global.getSettings().getSprite(FRINGE_SPRITE)
        } catch (_: Throwable) {
            return
        }

        // ---- core ----
        val core = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
            engine = engine,
            location = from,
            facing = facing,
            length = len,
            baseWidth = baseW,
            tipWidth = tipW,
            coreColor = theme.core,
            fringeColor = theme.fringe,
            coreSprite = coreSpr,
            fringeSprite = frgSpr,
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
            full = 9999f,
            baseAlphaMul = 0.70f,
            tipAlphaMul = 0.40f,
            baseEmissiveAlphaMul = 3.20f,
            tipEmissiveAlphaMul = 1.80f,
            mixPower = MIX_POWER_CORE,
        )

        // ---- core U 镜像 ----
        val coreU = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenterReversedU(
            engine = engine,
            location = from,
            facing = facing,
            length = len,
            baseWidth = baseW,
            tipWidth = tipW,
            coreColor = theme.core,
            fringeColor = theme.fringe,
            coreSprite = coreSpr,
            fringeSprite = frgSpr,
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
            full = 9999f,
            baseAlphaMul = 0.45f,
            tipAlphaMul = 0.25f,
            baseEmissiveAlphaMul = 1.60f,
            tipEmissiveAlphaMul = 0.90f,
            mixPower = MIX_POWER_CORE,
        )

        // ---- glow（更宽、更透） ----
        val glowBaseW = baseW * 2.2f
        val glowTipW = glowBaseW

        val glow = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
            engine = engine,
            location = from,
            facing = facing,
            length = len,
            baseWidth = glowBaseW,
            tipWidth = glowTipW,
            coreColor = theme.fringe,
            fringeColor = theme.glow,
            coreSprite = coreSpr,
            fringeSprite = frgSpr,
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
            full = 9999f,
            baseAlphaMul = 0.22f,
            tipAlphaMul = 0.12f,
            baseEmissiveAlphaMul = 2.20f,
            tipEmissiveAlphaMul = 1.00f,
            mixPower = MIX_POWER_GLOW,
        )

        // ---- glow U 镜像 ----
        val glowU = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenterReversedU(
            engine = engine,
            location = from,
            facing = facing,
            length = len,
            baseWidth = glowBaseW,
            tipWidth = glowTipW,
            coreColor = theme.fringe,
            fringeColor = theme.glow,
            coreSprite = coreSpr,
            fringeSprite = frgSpr,
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
            full = 9999f,
            baseAlphaMul = 0.16f,
            tipAlphaMul = 0.08f,
            baseEmissiveAlphaMul = 1.30f,
            tipEmissiveAlphaMul = 0.65f,
            mixPower = MIX_POWER_GLOW,
        )

        // 统一设置生命周期：极短闪光
        listOf(core, coreU, glow, glowU).forEach { trail ->
            trail?.let {
                try {
                    it.setGlobalTimer(BEAM_FADE_IN, BEAM_FULL, BEAM_FADE_OUT)
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * 沿束生成一组“装饰扩散环”（OGL 线圈），淡化行为与命中环一致。
     *
     * 注意：这是一次性 VFX，不做 upsert；由 renderer 自己按 duration 淡出。
     */
    private fun spawnBeamBodyRings(engine: CombatEngineAPI, from: Vector2f, facing: Float, len: Float, theme: Theme) {
        if (len <= BODY_RING_SPACING * 1.2f) return

        val dx = kotlin.math.cos(Math.toRadians(facing.toDouble())).toFloat()
        val dy = kotlin.math.sin(Math.toRadians(facing.toDouble())).toFloat()

        var dist = BODY_RING_SPACING
        val maxDist = (len - BODY_RING_SPACING * 0.5f).coerceAtLeast(0f)
        while (dist <= maxDist) {
            val c = Vector2f(from.x + dx * dist, from.y + dy * dist)
            try {
                OglEllipseRingRenderer.spawn(
                    engine,
                    OglEllipseRingRenderer.RingSpec(
                        center = c,
                        facing = facing,
                        aSideHalf = BODY_RING_A_HALF,
                        bAlongHalf = BODY_RING_B_HALF,
                        duration = BODY_RING_DURATION,
                        color = Color(
                            theme.fringe.red,
                            theme.fringe.green,
                            theme.fringe.blue,
                            (BODY_RING_COLOR.alpha * theme.ringAlphaMul).toInt().coerceIn(0, 255),
                        ),
                        lineWidthPx = 1.25f,
                        segments = 72,
                        expandSpeed = BODY_RING_EXPAND,
                        tangentialSpeed = 0f,
                    ),
                )
            } catch (_: Throwable) {
            }
            dist += BODY_RING_SPACING
        }
    }

    // endregion

    // region ---- 炮口闪光 ----

    private fun spawnMuzzleFlash(engine: CombatEngineAPI, from: Vector2f, theme: Theme) {
        try {
            engine.addHitParticle(from, Vector2f(0f, 0f), 80f * theme.widthMul, 1.2f, 0.08f, theme.hot)
        } catch (_: Throwable) {
        }
        try {
            engine.addSmoothParticle(from, Vector2f(0f, 0f), 160f * theme.widthMul, 1.0f, 0.18f, theme.glow)
        } catch (_: Throwable) {
        }
    }

    /**
     * 发射端“爆发瞬间”光圈（参考 GCP 的开火脉冲读感）。
     * 使用 OGL 环以获得稳定淡化。
     */
    private fun spawnMuzzlePulse(engine: CombatEngineAPI, from: Vector2f, facing: Float, theme: Theme) {
        try {
            OglEllipseRingRenderer.spawn(
                engine,
                OglEllipseRingRenderer.RingSpec(
                    center = from,
                    facing = facing,
                    aSideHalf = 26f * theme.widthMul,
                    bAlongHalf = 12f * theme.widthMul,
                    duration = 0.22f,
                    color = Color(theme.fringe.red, theme.fringe.green, theme.fringe.blue, 135),
                    lineWidthPx = 1.35f,
                    segments = 72,
                    expandSpeed = 220f,
                    tangentialSpeed = 0f,
                ),
            )
        } catch (_: Throwable) {
        }
        try {
            OglEllipseRingRenderer.spawn(
                engine,
                OglEllipseRingRenderer.RingSpec(
                    center = from,
                    facing = facing + 45f,
                    aSideHalf = 18f * theme.widthMul,
                    bAlongHalf = 9f * theme.widthMul,
                    duration = 0.18f,
                    color = Color(theme.fringe.red, theme.fringe.green, theme.fringe.blue, 85),
                    lineWidthPx = 1.15f,
                    segments = 64,
                    expandSpeed = 300f,
                    tangentialSpeed = -2.0f,
                ),
            )
        } catch (_: Throwable) {
        }
    }

    // endregion

    // region ---- 命中爆发 ----

    private fun spawnImpactBurst(engine: CombatEngineAPI, point: Vector2f, facing: Float, shieldHit: Boolean, theme: Theme) {
        // 大闪光
        try {
            engine.addHitParticle(point, Vector2f(0f, 0f), 180f * theme.widthMul, 1.8f, 0.10f, theme.hot)
        } catch (_: Throwable) {
        }
        // 柔和辉光
        try {
            engine.addSmoothParticle(point, Vector2f(0f, 0f), 320f * theme.widthMul, 1.2f, 0.28f, theme.glow)
        } catch (_: Throwable) {
        }
        // 小范围爆闪
        try {
            engine.spawnExplosion(point, Vector2f(0f, 0f), theme.core, 100f * theme.widthMul, 0.18f)
        } catch (_: Throwable) {
        }

        // 命中光环环（OGL ellipse ring）：快速扩散的圆形光晕
        try {
            OglEllipseRingRenderer.spawn(
                engine,
                OglEllipseRingRenderer.RingSpec(
                    center = point,
                    facing = facing,
                    aSideHalf = 40f * theme.widthMul,
                    bAlongHalf = 40f * theme.widthMul,
                    duration = 0.16f,
                    color = Color(theme.hot.red, theme.hot.green, theme.hot.blue, 120),
                    lineWidthPx = 1.6f,
                    segments = 80,
                    expandSpeed = 360f,
                    tangentialSpeed = 0f,
                ),
            )
        } catch (_: Throwable) {
        }

        // 补回：TSM 风格锥状冲击波（一次性）
        try {
            // 需求：
            // - 光锥消失更快（存活时间 -50%）
            // - 不改光锥长度参数，仅降低飞出速度
            val speedMul = 0.34f
            // 需求：光锥存活时间提升（50% -> 75%）
            val lifeMul = 0.75f

            val finisherMul = if (theme == Theme.REDSHIFT) 1.5f else 1.0f
            TsmTerminalStrikeFx.spawnImpactFx(
                engine = engine,
                point = point,
                towardTargetFacing = facing,
                // 需求：击中船体用 INWARD；击中护盾保持 OUTWARD
                facingMode = if (shieldHit) TsmTerminalStrikeFx.ImpactFacingMode.OUTWARD else TsmTerminalStrikeFx.ImpactFacingMode.INWARD,
                smokeColor = Color(theme.fringe.red, theme.fringe.green, theme.fringe.blue, 70),
                coreColor = Color(theme.hot.red, theme.hot.green, theme.hot.blue, 235),
                fringeColor = Color(theme.fringe.red, theme.fringe.green, theme.fringe.blue, 215),
                intensityMult = 0.95f,
                sprayStyle = TsmTerminalStrikeFx.ImpactSprayStyle(
                    impactScale = 0.80f * finisherMul,
                    baseRaysMin = 14,
                    baseRaysExtra = 6,
                    widthMin = 6f * finisherMul,
                    widthMax = 13f * finisherMul,
                    lengthMin = 110f * finisherMul,
                    lengthMax = 260f * finisherMul,
                    // 仅降低速度：在更短生命周期内飞出更短距离
                    speedMin = 240f * speedMul,
                    speedMax = 560f * speedMul,
                    // 生命周期 -50%
                    fullMin = 0.06f * lifeMul,
                    fullMax = 0.12f * lifeMul,
                    fadeOutMin = 0.44f * lifeMul,
                    fadeOutMax = 0.64f * lifeMul,
                ),
                smokeStyle = TsmTerminalStrikeFx.ImpactSmokeStyle(
                    puffCountBase = 3,
                    puffCountExtra = 2,
                    sizeMin = 42f * finisherMul,
                    sizeMax = 95f * finisherMul,
                    durationMin = 0.32f * lifeMul,
                    durationMax = 0.62f * lifeMul,
                    speedMin = 80f * speedMul,
                    speedMax = 180f * speedMul,
                ),
            )
        } catch (_: Throwable) {
        }
    }

    // endregion

    // region ---- 环状激波 ----

    /**
     * 固定在命中点的椭圆环激波（OGL 线圈版）：
     * - 主环：沿弹体方向展开
     * - 副环：更小更淡、反转旋转，增加层次
     */
    private fun spawnRingShockwave(engine: CombatEngineAPI, point: Vector2f, facing: Float) {
        spawnRingShockwave(engine, point, facing, Theme.CYAN)
    }

    private fun spawnRingShockwave(engine: CombatEngineAPI, point: Vector2f, facing: Float, theme: Theme) {
        // 主环
        try {
            OglEllipseRingRenderer.spawn(
                engine, OglEllipseRingRenderer.RingSpec(
                    center = point,
                    facing = facing,
                    aSideHalf = RING_A_HALF,
                    bAlongHalf = RING_B_HALF,
                    duration = RING_DURATION,
                    color = Color(
                        theme.fringe.red,
                        theme.fringe.green,
                        theme.fringe.blue,
                        (RING_COLOR.alpha * theme.ringAlphaMul).toInt().coerceIn(0, 255),
                    ),
                    lineWidthPx = 1.55f,
                    segments = 96,
                    expandSpeed = RING_EXPAND,
                    tangentialSpeed = 2.0f,
                )
            )
        } catch (_: Throwable) {
        }

        // 副环（更小、更快扩散、反向旋转）
        try {
            OglEllipseRingRenderer.spawn(
                engine, OglEllipseRingRenderer.RingSpec(
                    center = point,
                    facing = facing + 45f,
                    aSideHalf = RING_A_HALF * 0.72f,
                    bAlongHalf = RING_B_HALF * 0.72f,
                    duration = RING_DURATION * 0.75f,
                    color = Color(
                        theme.fringe.red,
                        theme.fringe.green,
                        theme.fringe.blue,
                        (75 * theme.ringAlphaMul).toInt().coerceIn(0, 255),
                    ),
                    lineWidthPx = 1.25f,
                    segments = 72,
                    expandSpeed = RING_EXPAND * 1.5f,
                    tangentialSpeed = -3.0f,
                )
            )
        } catch (_: Throwable) {
        }
    }

    // endregion

    // region ---- 命中扭曲 ----

    /**
     * 命中点坍缩扭曲（BoxUtil DistortionEntity）。
     * 相比 GCP 的重型坍缩，DRV-Ω 的扭曲更轻量（动能武器，非引力武器）。
     */
    private fun spawnHitDistortion(engine: CombatEngineAPI, point: Vector2f, theme: Theme) {
        try {
            BoxUtilCombatVfx.ensureReady(engine)

            val e = DistortionEntity()
            e.setGlobalTimer(0.04f, 0.08f, 0.40f)

            e.setInnerFull(0.28f, 0.28f)
            e.setInnerHardness(0.82f)
            e.setRingHardness(0.58f)

            // 需求：降低扭曲范围（-40%）；终结技略扩大
            e.setSizeIn(30f * theme.distortionSizeMul, 30f * theme.distortionSizeMul)
            e.setSizeFull(84f * theme.distortionSizeMul, 84f * theme.distortionSizeMul)
            e.setSizeOut(192f * theme.distortionSizeMul, 192f * theme.distortionSizeMul)

            e.setPowerIn(0.00f)
            e.setPowerFull(0.55f * theme.distortionPowerMul)
            e.setPowerOut(0f)

            e.setLocation(point)

            val result = CombatRenderingManager.addEntity(e)
            if (result.toInt() != 0) {
                log.warn("DRV-Ω: BoxUtil addEntity(DistortionEntity) failed, state=$result")
            }
        } catch (t: Throwable) {
            log.warn("DRV-Ω: spawnHitDistortion failed", t)
        }
    }

    // endregion
}
