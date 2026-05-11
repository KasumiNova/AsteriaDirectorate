package cn.kasuminova.astd.combat.effect.arc.signature.stellarjet

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.graphics.SpriteAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.attribute.NodeData
import org.boxutil.units.standard.entity.TrailEntity
import org.boxutil.util.CurveUtil
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 恒星喷射（Stellar Jet）的“额外光束特效”。
 *
 * 设计目标：
 * - 伤害/命中完全交给原版 beam 机制；这里仅做渲染叠加。
 * - 视觉风格参考“厚实核心 + 卷曲能量丝带/扰动边缘”，不绑定具体配色。
 */
internal class StellarJetBeamVfx(
    private val coreColor: Color,
    private val glowColor: Color,
    private val layer: CombatEngineLayers = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
) {

    data class BeamContact(
        val end: Vector2f,
        val hitTarget: CombatEntityAPI?,
        val isShieldHit: Boolean,
    )

    private data class State(
        val core: TrailEntity,
        val coreMirroredU: TrailEntity,
        val glow: TrailEntity,
        val glowMirroredU: TrailEntity,
        val wisps: List<TrailEntity>,
        var time: Float,
        var fadingOut: Boolean,

        // VFX rate limit accumulators
        var muzzleParticleAcc: Float,
        var muzzleSprayAcc: Float,
        // 炮口“短束光锥”发射索引：用于降低随机性并保证左右分布更均匀
        var muzzleSprayIndex: Int,
        var impactParticleAcc: Float,
        var impactArcAcc: Float,

        // 命中目标时的 EMP 电弧（按时间节流）
        var impactEmpAcc: Float,

        // 沿束随机散发粒子
        var ambientAcc: Float,

        // 沿束持续“烟雾”散发（密度显著低于粒子）
        var ambientSmokeAcc: Float,

        // 烟雾侧向散发索引：保证左右更均匀（避免随机导致“只在一侧”）
        var ambientSmokeIndex: Int,
    )

    private var state: State? = null
    private var cachedSprites: Pair<SpriteAPI, SpriteAPI>? = null
    private var boxVfxFailed = false

    private fun loadSprites(): Pair<SpriteAPI, SpriteAPI>? {
        // 优先使用原版 beam 贴图：配合 textureScrollSpeed 能形成“能量流动”的观感。
        // 如果获取失败再退回 BoxUtil 纯色贴图（此时滚动几乎不可见，但至少不会完全缺失）。
        return try {
            val core = Global.getSettings().getSprite("graphics/fx/beamcoreb.png")
            val fringe = Global.getSettings().getSprite("graphics/fx/beamfringeb.png")
            Pair(core, fringe)
        } catch (_: Throwable) {
            try {
                val s = Global.getSettings().getSprite("textures", "BUtil_ONE")
                Pair(s, s)
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun fadeOut() {
        val s = state ?: return
        if (s.fadingOut) {
            // 等 BoxUtil 回收后释放引用
            val allDeleted =
                s.core.hasDelete() && s.coreMirroredU.hasDelete() && s.glow.hasDelete() && s.glowMirroredU.hasDelete() && s.wisps.all { it.hasDelete() }
            if (allDeleted) state = null
            return
        }

        s.fadingOut = true
        try {
            s.core.setGlobalTimer(0f, 0f, FADE_OUT)
        } catch (_: Throwable) {
        }
        try {
            s.coreMirroredU.setGlobalTimer(0f, 0f, FADE_OUT)
        } catch (_: Throwable) {
        }
        try {
            s.glow.setGlobalTimer(0f, 0f, FADE_OUT)
        } catch (_: Throwable) {
        }
        try {
            s.glowMirroredU.setGlobalTimer(0f, 0f, FADE_OUT)
        } catch (_: Throwable) {
        }
        for (w in s.wisps) {
            try {
                w.setGlobalTimer(0f, 0f, FADE_OUT)
            } catch (_: Throwable) {
            }
        }
    }

    fun update(
        engine: CombatEngineAPI,
        amount: Float,
        source: ShipAPI,
        start: Vector2f,
        facing: Float,
        length: Float,
        // 0..1：强度因子（通常来自 system level + fluxLevel）
        strength: Float,
        // 当前面板 DPS（用于命中时的 EMP 电弧伤害分配）
        panelDps: Float,
        coreWidth: Float,
        glowWidth: Float,
        // 炮口 FX 尺寸倍率（用于 charge-up 等阶段的“更大喷口光效”）
        muzzleScale: Float = 1f,
        firing: Boolean,
        contact: BeamContact?,
    ) {
        if (boxVfxFailed) return

        BoxUtilCombatVfx.ensureReady(engine)

        val sprites = cachedSprites ?: loadSprites().also { cachedSprites = it }
        if (sprites == null) {
            boxVfxFailed = true
            return
        }

        val usableLen = length.coerceAtLeast(16f)
        val s = ensureState(engine, sprites, usableLen, coreWidth, glowWidth) ?: return

        // 若之前在淡出，重新拉回常驻
        if (s.fadingOut) {
            s.fadingOut = false
            try {
                s.core.setGlobalTimer(0f, FULL, 0f)
            } catch (_: Throwable) {
            }
            try {
                s.coreMirroredU.setGlobalTimer(0f, FULL, 0f)
            } catch (_: Throwable) {
            }
            try {
                s.glow.setGlobalTimer(0f, FULL, 0f)
            } catch (_: Throwable) {
            }
            try {
                s.glowMirroredU.setGlobalTimer(0f, FULL, 0f)
            } catch (_: Throwable) {
            }
            for (w in s.wisps) {
                try {
                    w.setGlobalTimer(0f, FULL, 0f)
                } catch (_: Throwable) {
                }
            }
        }

        s.time += amount

        // 直束：core + glow
        // - core 更细、更亮（避免“主体太粗把边缘吃掉”）
        // - mirroredU 叠加一条 UV 镜像版本：减少方向性 + 提升“微渐变/流动”层次
        updateStraight(s.core, start, facing, usableLen, coreWidth, strength, baseAlpha = 0.55f, emissiveAlpha = 3.2f, reversedU = false)
        updateStraight(s.coreMirroredU, start, facing, usableLen, coreWidth, strength, baseAlpha = 0.22f, emissiveAlpha = 1.35f, reversedU = true)

        updateStraight(s.glow, start, facing, usableLen, glowWidth, strength, baseAlpha = 0.18f, emissiveAlpha = 1.75f, reversedU = false)
        updateStraight(s.glowMirroredU, start, facing, usableLen, glowWidth, strength, baseAlpha = 0.10f, emissiveAlpha = 0.95f, reversedU = true)

        // 卷曲丝带：两条相位偏移的“扰动边缘”
        for ((idx, w) in s.wisps.withIndex()) {
            updateWisp(
                w,
                start,
                facing,
                usableLen,
                s.time,
                strength,
                coreWidth,
                glowWidth,
                idx,
            )
        }

        // 命中/喷口散射：仅在“真的在开火”时产生。
        if (firing) {
            emitAmbientBeamParticles(
                engine = engine,
                state = s,
                amount = amount,
                start = start,
                facing = facing,
                length = usableLen,
                strength = strength,
                coreWidth = coreWidth,
                glowWidth = glowWidth,
            )

            // 需求：光束发射期间持续散发烟雾（密度比粒子少 66%）
            emitAmbientBeamSmoke(
                engine = engine,
                state = s,
                amount = amount,
                start = start,
                facing = facing,
                length = usableLen,
                strength = strength,
                coreWidth = coreWidth,
                glowWidth = glowWidth,
            )

            emitMuzzleFx(
                engine = engine,
                state = s,
                amount = amount,
                start = start,
                facing = facing,
                strength = strength,
                coreWidth = coreWidth,
                muzzleScale = muzzleScale,
            )

            val hitTarget = contact?.hitTarget
            if (hitTarget != null && engine.isEntityInPlay(hitTarget)) {
                emitImpactFx(
                    engine = engine,
                    state = s,
                    amount = amount,
                    point = contact.end,
                    facing = facing,
                    strength = strength,
                    coreWidth = coreWidth,
                    isShieldHit = contact.isShieldHit,
                    hitTarget = hitTarget,
                )
            }

            // 需求：击中效果 - 周期性 EMP 电弧
            emitImpactEmpArc(
                engine = engine,
                state = s,
                amount = amount,
                source = source,
                contact = contact,
                strength = strength,
                coreWidth = coreWidth,
                panelDps = panelDps,
            )
        }
    }

    private fun ensureState(
        engine: CombatEngineAPI,
        sprites: Pair<SpriteAPI, SpriteAPI>,
        length: Float,
        coreW: Float,
        glowW: Float,
    ): State? {
        val existing = state
        if (
            existing != null &&
            !existing.core.hasDelete() && !existing.coreMirroredU.hasDelete() &&
            !existing.glow.hasDelete() && !existing.glowMirroredU.hasDelete() &&
            existing.wisps.none { it.hasDelete() }
        ) {
            return existing
        }

        // core/glow：复用 BoxUtilCombatVfx 的常驻 beam trail（两节点）
        val core = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
            engine = engine,
            location = Vector2f(0f, 0f),
            facing = 0f,
            length = length,
            baseWidth = coreW,
            tipWidth = (coreW * CORE_TIP_WIDTH_MUL).coerceAtLeast(2.2f),
            coreColor = coreColor,
            fringeColor = glowColor,
            coreSprite = sprites.first,
            fringeSprite = sprites.second,
            layer = layer,
            full = FULL,
            baseAlphaMul = 0.55f,
            tipAlphaMul = 0.35f,
            baseEmissiveAlphaMul = 3.2f,
            tipEmissiveAlphaMul = 2.6f,
            mixPower = 3.6f,
        )
        val coreMirroredU = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenterReversedU(
            engine = engine,
            location = Vector2f(0f, 0f),
            facing = 0f,
            length = length,
            baseWidth = coreW,
            tipWidth = (coreW * CORE_TIP_WIDTH_MUL).coerceAtLeast(2.2f),
            coreColor = coreColor,
            fringeColor = glowColor,
            coreSprite = sprites.first,
            fringeSprite = sprites.second,
            layer = layer,
            full = FULL,
            baseAlphaMul = 0.22f,
            tipAlphaMul = 0.12f,
            baseEmissiveAlphaMul = 1.35f,
            tipEmissiveAlphaMul = 0.95f,
            mixPower = 3.2f,
        )
        val glow = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
            engine = engine,
            location = Vector2f(0f, 0f),
            facing = 0f,
            length = length,
            baseWidth = glowW,
            tipWidth = (glowW * GLOW_TIP_WIDTH_MUL).coerceAtLeast(3.5f),
            coreColor = glowColor,
            fringeColor = glowColor,
            coreSprite = sprites.first,
            fringeSprite = sprites.second,
            layer = layer,
            full = FULL,
            baseAlphaMul = 0.18f,
            tipAlphaMul = 0.08f,
            baseEmissiveAlphaMul = 1.75f,
            tipEmissiveAlphaMul = 1.20f,
            mixPower = 3.0f,
        )
        val glowMirroredU = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenterReversedU(
            engine = engine,
            location = Vector2f(0f, 0f),
            facing = 0f,
            length = length,
            baseWidth = glowW,
            tipWidth = (glowW * GLOW_TIP_WIDTH_MUL).coerceAtLeast(3.5f),
            coreColor = glowColor,
            fringeColor = glowColor,
            coreSprite = sprites.first,
            fringeSprite = sprites.second,
            layer = layer,
            full = FULL,
            baseAlphaMul = 0.10f,
            tipAlphaMul = 0.04f,
            baseEmissiveAlphaMul = 0.95f,
            tipEmissiveAlphaMul = 0.65f,
            mixPower = 2.8f,
        )

        if (core == null || coreMirroredU == null || glow == null || glowMirroredU == null) {
            core?.delete()
            coreMirroredU?.delete()
            glow?.delete()
            glowMirroredU?.delete()
            boxVfxFailed = true
            return null
        }

        // 主束流动参数：让能量“向前流动”
        initFlowParams(core, CORE_TEX_SPEED)
        initFlowParams(coreMirroredU, CORE_TEX_SPEED * -0.92f)
        initFlowParams(glow, GLOW_TEX_SPEED)
        initFlowParams(glowMirroredU, GLOW_TEX_SPEED * -0.92f)

        // 两端更尖锐（避免“钝头”），并给喷口端也一点点 fade-in
        initEndFade(core)
        initEndFade(coreMirroredU)
        initEndFade(glow)
        initEndFade(glowMirroredU)

        // 额外丝带：多节点 trail
        val wisps = ArrayList<TrailEntity>(2)
        repeat(2) {
            val w = createMultiNodeTrail(
                engine = engine,
                nodeCount = WISP_NODES,
                coreSprite = sprites.first,
                fringeSprite = sprites.second,
                baseColor = glowColor,
                emissiveColor = glowColor,
                width = (coreW * WISP_WIDTH_BASE_MUL * WISP_WIDTH_MUL).coerceAtLeast(5f),
            )
            if (w == null) {
                for (x in wisps) x.delete()
                core.delete()
                coreMirroredU.delete()
                glow.delete()
                glowMirroredU.delete()
                boxVfxFailed = true
                return null
            }
            wisps.add(w)
        }

        return State(
            core = core,
            coreMirroredU = coreMirroredU,
            glow = glow,
            glowMirroredU = glowMirroredU,
            wisps = wisps,
            time = 0f,
            fadingOut = false,
            muzzleParticleAcc = 0f,
            muzzleSprayAcc = 0f,
            muzzleSprayIndex = (Math.random() * 997).toInt(),
            impactParticleAcc = 0f,
            impactArcAcc = 0f,
            impactEmpAcc = 0f,
            ambientAcc = 0f,
            ambientSmokeAcc = 0f,
            ambientSmokeIndex = (Math.random() * 997).toInt(),
        ).also { state = it }
    }

    private fun initFlowParams(e: TrailEntity, textureSpeed: Float) {
        try {
            e.setTexturePixels(MAIN_TEX_PIXELS)
            e.setTextureSpeed(textureSpeed)
            e.setFlowWhenPaused(false)
            // 让不同实例的 UV 不完全同步，减少“同频抖动”
            e.setUVOffset((Math.random().toFloat() * 2f) - 1f)
            // 降低 jitter，避免出现“意义不明的闪烁/抖动”
            e.setJitterPower(0.03f)
            // 关闭 flick：同步闪烁会非常像 bug；我们用纹理滚动 + 螺旋运动来提供动态感
            e.setFlick(false)
            e.setSyncFlick(false)
        } catch (_: Throwable) {
        }
    }

    private fun initEndFade(e: TrailEntity) {
        try {
            // 让喷口端与命中端都不是“硬切平头”
            // 但也不要两端完全透明，否则“头/尾太暗”
            e.setFillStartAlpha(MAIN_END_ALPHA_START)
            e.setFillStartFactor(START_FADE_FACTOR)
            e.setFillEndAlpha(MAIN_END_ALPHA_END)
            e.setFillEndFactor(END_FADE_FACTOR)
        } catch (_: Throwable) {
        }
    }

    private fun createMultiNodeTrail(
        engine: CombatEngineAPI,
        nodeCount: Int,
        coreSprite: SpriteAPI,
        fringeSprite: SpriteAPI,
        baseColor: Color,
        emissiveColor: Color,
        width: Float,
    ): TrailEntity? {
        return try {
            val e = TrailEntity()
            // 先占位节点（update 时会写入真正的 x/y）
            repeat(nodeCount.coerceAtLeast(2)) { e.addNode(Vector2f(0f, 0f)) }
            e.setNodeRefreshAllFromCurrentIndex()
            e.submitNodes()

            e.setLayer(layer)
            e.setAdditiveBlend()
            e.setGlobalTimer(0f, FULL, 0f)

            e.getMaterialData().setDiffuse(coreSprite)
            e.getMaterialData().setEmissive(fringeSprite)
            e.getMaterialData().setColor(baseColor)
            e.getMaterialData().setEmissiveColor(emissiveColor)

            // 基础参数
            e.setTexturePixels(WISP_TEX_PIXELS)
            e.setTextureSpeed(WISP_TEX_SPEED)
            e.setUVOffset((Math.random().toFloat() * 2f) - 1f)
            e.setStartWidth(width)
            e.setEndWidth(width)
            e.setMixFactor(3.4f)

            // 柔和两端
            // 提亮头/尾：以前两端 alpha=0 会显得“丝带断头/断尾”
            e.setFillStartAlpha(WISP_END_ALPHA_START)
            e.setFillStartFactor(WISP_END_FADE_START)
            e.setFillEndAlpha(WISP_END_ALPHA_END)
            e.setFillEndFactor(WISP_END_FADE_END)

            // 避免“意义不明闪烁”：关闭 flick，降低 jitter
            e.setFlick(false)
            e.setSyncFlick(false)
            e.setJitterPower(0.05f)

            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_TRAIL, e)
            if (state != 0) {
                e.delete()
                null
            } else {
                e
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun updateStraight(
        e: TrailEntity,
        start: Vector2f,
        facing: Float,
        length: Float,
        width: Float,
        strength: Float,
        baseAlpha: Float,
        emissiveAlpha: Float,
        reversedU: Boolean,
    ) {
        if (e.hasDelete()) return
        try {
            val nodes = e.nodes
            if (nodes != null && nodes.size >= 2) {
                if (!reversedU) {
                    nodes[0].set(0f, 0f)
                    nodes[1].set(length, 0f)
                } else {
                    // mirroredU trail 的 node[0] 是 tip
                    nodes[0].set(length, 0f)
                    nodes[1].set(0f, 0f)
                }
                e.setNodeRefreshIndex(0)
                e.setNodeRefreshAllFromCurrentIndex()
                e.submitNodes()
            }

            // 纹理滚动（能量流动）：pixels 用固定值，避免不同束长导致滚动观感不一致。
            e.setTexturePixels(MAIN_TEX_PIXELS)

            val s = strength.coerceIn(0f, 1f)
            val baseW = (width * (0.72f + 0.28f * s)).coerceAtLeast(2f)
            var tipW = (width * (0.20f + 0.14f * s)).coerceAtLeast(1.8f)
            // 需求：尾部宽度至少为起始位的 75%（避免“尾部细到像消失”）
            tipW = tipW.coerceAtLeast(baseW * 0.75f)
            if (!reversedU) {
                e.setStartWidth(baseW)
                e.setEndWidth(tipW)
            } else {
                // reversedU 版本：start=tip, end=base
                e.setStartWidth(tipW)
                e.setEndWidth(baseW)
            }

            val a = (baseAlpha * (0.55f + 0.75f * s)).coerceIn(0f, 1.2f)
            val ea = (emissiveAlpha * (0.6f + 1.0f * s)).coerceIn(0f, 8f)

            e.setStartColor(1f, 1f, 1f, a)
            e.setEndColor(1f, 1f, 1f, a)
            e.setStartEmissive(1f, 1f, 1f, ea)
            e.setEndEmissive(1f, 1f, 1f, ea)

            // 两端更“尖”，避免切平头
            // 但不要完全透明，否则“头/尾太暗”
            e.setFillStartAlpha(MAIN_END_ALPHA_START)
            e.setFillStartFactor(START_FADE_FACTOR)
            e.setFillEndAlpha(MAIN_END_ALPHA_END)
            e.setFillEndFactor(END_FADE_FACTOR)

            e.setStateVanilla(start, facing)
        } catch (_: Throwable) {
        }
    }

    private fun updateWisp(
        e: TrailEntity,
        start: Vector2f,
        facing: Float,
        length: Float,
        time: Float,
        strength: Float,
        coreWidth: Float,
        glowWidth: Float,
        index: Int,
    ) {
        if (e.hasDelete()) return

        val nodes = try {
            e.nodes
        } catch (_: Throwable) {
            null
        } ?: return
        if (nodes.size < 2) return

        val s = strength.coerceIn(0f, 1f)

        // 螺旋丝带宽度：+75%，并随强度略变化
        try {
            val w = (coreWidth * WISP_WIDTH_BASE_MUL * WISP_WIDTH_MUL * (0.75f + 0.25f * s)).coerceAtLeast(4.5f)
            e.setStartWidth(w)
            e.setEndWidth(w)
        } catch (_: Throwable) {
        }

        // 目标：让边缘丝带更像“绕着主束螺旋缠绕”而不是单纯贴在两侧。
        // 2D 里我们用“沿束方向推进的相位 + 正弦偏移”来模拟绕轴螺旋（并保持两端收敛）。
        val phaseBase = index * PI.toFloat() // 两条丝带相位相反，形成“对绕”
        // 螺旋圈距固定（su）：不再因为束长变化而被“拉伸/压缩”
        val turns = (length / WISP_PITCH_SU).coerceAtLeast(0.75f)
        // 螺旋推进/自转速度：不再绑定 strength（避免 system/flux 波动导致“节奏漂移”）
        val travel = time * WISP_TRAVEL_SPEED * WISP_ROT_SPEED_MUL // 相位沿束推进速度
        val spin = time * WISP_SPIN_SPEED * WISP_ROT_SPEED_MUL // 绕轴“自转”速度

        // 螺旋半径：固定距离（需求：与主束间距统一），不再随 glow/core 宽度或 strength 漂移。
        val radius = WISP_RADIUS_SU
        val fineJitter = radius * WISP_FINE_JITTER_MUL

        // 用 CurveUtil 做一个几乎直线的“中心线”，后续在其上叠加螺旋偏移（保留可扩展性）。
        val startNode = NodeData().apply {
            setLocation(0f, 0f)
            setTangentRight(length * 0.33f, 0f)
            setTangentLeft(0f, 0f)
        }
        val endNode = NodeData().apply {
            setLocation(length, 0f)
            setTangentLeft(-length * 0.33f, 0f)
            setTangentRight(0f, 0f)
        }

        val n = nodes.size
        for (i in 0 until n) {
            val t = if (n <= 1) 0f else i.toFloat() / (n - 1).toFloat()

            val p = try {
                CurveUtil.getPointOnCurve(startNode, endNode, t)
            } catch (_: Throwable) {
                null
            }
            var x = p?.x ?: (length * t)
            val baseY = p?.y ?: 0f

            // env：两端收敛，避免喷口/命中端出现“侧向甩尾”
            val env = sin(PI.toFloat() * t).coerceAtLeast(0f).pow(1.25f)

            // 螺旋相位：沿束推进（turns*t）+ 时间推进（travel/spin）+ 两条丝带相位差
            val a = 2f * PI.toFloat() * (turns * t + travel) + spin + phaseBase
            val y = baseY + sin(a) * radius * env + sin(a * 2.1f + t * 9.0f + time * 3.2f * WISP_ROT_SPEED_MUL) * fineJitter * env

            // 给一点点“前后”错位，让螺旋更立体（2D 伪 3D）
            x += cos(a) * (radius * 0.08f) * env

            nodes[i].set(x, y)
        }

        try {
            e.setNodeRefreshIndex(0)
            e.setNodeRefreshAllFromCurrentIndex()
            e.submitNodes()

            e.setTexturePixels(WISP_TEX_PIXELS)
            e.setTextureSpeed(WISP_TEX_SPEED * (0.80f + 0.35f * s))

            // 让丝带末端更淡，像“能量雾”而非硬条
            val a0 = (0.24f + 0.22f * s).coerceIn(0f, 0.9f)
            // 尾部亮度至少为发射端 75%
            val a1 = (0.08f + 0.10f * s).coerceIn(0f, 0.70f).coerceAtLeast(a0 * 0.75f)
            val ea0 = (1.6f + 1.4f * s).coerceIn(0f, 6f)
            val ea1 = (1.05f + 0.95f * s).coerceIn(0f, 6f).coerceAtLeast(ea0 * 0.75f)
            e.setStartColor(1f, 1f, 1f, a0)
            e.setEndColor(1f, 1f, 1f, a1)
            e.setStartEmissive(1f, 1f, 1f, ea0)
            e.setEndEmissive(1f, 1f, 1f, ea1)

            e.setStateVanilla(start, facing)
        } catch (_: Throwable) {
        }
    }

    private fun emitAmbientBeamParticles(
        engine: CombatEngineAPI,
        state: State,
        amount: Float,
        start: Vector2f,
        facing: Float,
        length: Float,
        strength: Float,
        coreWidth: Float,
        glowWidth: Float,
    ) {
        val s = strength.coerceIn(0f, 1f)

        val ratePerSec = (length / 50f) * AMBIENT_PARTICLES_PER_50SU_PER_SEC * (0.65f + 0.85f * s)
        state.ambientAcc += ratePerSec * amount
        val count = state.ambientAcc.toInt().coerceAtMost(AMBIENT_PARTICLES_MAX_PER_FRAME)
        if (count > 0) state.ambientAcc -= count
        if (count <= 0) return

        val rad = Math.toRadians(facing.toDouble())
        val dir = Vector2f(cos(rad).toFloat(), sin(rad).toFloat())
        val perp = Vector2f(-dir.y, dir.x)

        val baseSpread = ((glowWidth * 0.5f * AMBIENT_PARTICLE_LATERAL_MUL) + (coreWidth * 0.25f)).coerceAtLeast(6f)

        for (i in 0 until count) {
            val t = Math.random().toFloat()
            val along = length * t

            // 侧向散开：让粒子围绕束体而不是只在一条线
            val lateral = (Math.random().toFloat() - 0.5f) * 2f * baseSpread * (0.65f + 0.55f * s)
            val at = Vector2f(
                start.x + dir.x * along + perp.x * lateral,
                start.y + dir.y * along + perp.y * lateral,
            )

            val speed = lerp(AMBIENT_PARTICLE_SPEED_MIN, AMBIENT_PARTICLE_SPEED_MAX, Math.random().toFloat()) * (0.7f + 0.6f * s)
            // 速度方向：向前运动为主（沿束方向），少量侧向扩散
            val side = (Math.random().toFloat() - 0.5f) * 2f
            val vel = Vector2f(
                dir.x * speed + perp.x * speed * 0.18f * side,
                dir.y * speed + perp.y * speed * 0.18f * side,
            )
            val size = lerp(AMBIENT_PARTICLE_SIZE_MIN, AMBIENT_PARTICLE_SIZE_MAX, Math.random().toFloat()) * (0.75f + 0.45f * s)
            val dur = lerp(AMBIENT_PARTICLE_DUR_MIN, AMBIENT_PARTICLE_DUR_MAX, Math.random().toFloat())
            val bright = lerp(0.35f, 0.85f, Math.random().toFloat()) * (0.75f + 0.55f * s)

            val c = if (Math.random() < 0.35) coreColor else glowColor
            try {
                engine.addSmoothParticle(at, vel, size, bright, dur, c)
            } catch (_: Throwable) {
            }
        }
    }

    private fun emitAmbientBeamSmoke(
        engine: CombatEngineAPI,
        state: State,
        amount: Float,
        start: Vector2f,
        facing: Float,
        length: Float,
        strength: Float,
        coreWidth: Float,
        glowWidth: Float,
    ) {
        val s = strength.coerceIn(0f, 1f)

        // 参考 emitAmbientBeamParticles：先按“粒子基准密度”计算，再乘烟雾倍率
        val particleRatePerSec = (length / 50f) * AMBIENT_PARTICLES_PER_50SU_PER_SEC * (0.65f + 0.85f * s)
        val ratePerSec = (particleRatePerSec * AMBIENT_SMOKE_RATE_MUL).coerceAtLeast(0f)

        state.ambientSmokeAcc += ratePerSec * amount
        val count = state.ambientSmokeAcc.toInt().coerceAtMost(AMBIENT_SMOKE_MAX_PER_FRAME)
        if (count > 0) state.ambientSmokeAcc -= count
        if (count <= 0) return

        val rad = Math.toRadians(facing.toDouble())
        val dir = Vector2f(cos(rad).toFloat(), sin(rad).toFloat())
        val perp = Vector2f(-dir.y, dir.x)

        // 烟雾比粒子更“散”：侧向扩散更大、速度更慢、寿命更长
        val baseSpread = ((glowWidth * 0.5f * AMBIENT_SMOKE_LATERAL_MUL) + (coreWidth * 0.25f)).coerceAtLeast(10f)

        for (i in 0 until count) {
            // 略偏向束前半段：让观感更像“喷射尾流”而不是全程均匀刷屏
            val t = if (Math.random() < 0.65) rand01().pow(0.55f) else rand01()
            val along = length * t

            val lateral = (rand01() - 0.5f) * 2f * baseSpread * (0.75f + 0.45f * s)
            val at = Vector2f(
                start.x + dir.x * along + perp.x * lateral,
                start.y + dir.y * along + perp.y * lateral,
            )

            // 需求：烟雾散发方向为两侧，不向前（不沿束方向给速度）
            val idx = state.ambientSmokeIndex++
            val sign = if ((idx and 1) == 0) 1f else -1f
            val speed = lerp(AMBIENT_SMOKE_SPEED_MIN, AMBIENT_SMOKE_SPEED_MAX, rand01()) * (0.65f + 0.45f * s)
            val vel = Vector2f(
                perp.x * speed * AMBIENT_SMOKE_SIDE_SPEED_MUL * sign,
                perp.y * speed * AMBIENT_SMOKE_SIDE_SPEED_MUL * sign,
            )

            val size = lerp(AMBIENT_SMOKE_SIZE_MIN, AMBIENT_SMOKE_SIZE_MAX, rand01()) * (0.85f + 0.35f * s)
            val dur = lerp(AMBIENT_SMOKE_DUR_MIN, AMBIENT_SMOKE_DUR_MAX, rand01())
            val opacity = lerp(AMBIENT_SMOKE_OPACITY_MIN, AMBIENT_SMOKE_OPACITY_MAX, rand01()) * (0.85f + 0.25f * s)

            // 需求：烟雾颜色更接近蓝色（偏向 glowColor，并再压低红/绿）
            val base = AMBIENT_SMOKE_BASE_COLOR
            val c = Color(base.red, base.green, base.blue, AMBIENT_SMOKE_COLOR_ALPHA)
            try {
                // 需求：使用 nebula 贴图
                // 参数含义（常见约定）：size, endSizeMult, in, full, out, color
                val endSizeMult = lerp(AMBIENT_SMOKE_END_SIZE_MUL_MIN, AMBIENT_SMOKE_END_SIZE_MUL_MAX, rand01())
                val inDur = (dur * AMBIENT_SMOKE_IN_FRAC).coerceAtLeast(0.01f)
                val fullDur = (dur * AMBIENT_SMOKE_FULL_FRAC).coerceAtLeast(0.01f)
                val outDur = (dur * AMBIENT_SMOKE_OUT_FRAC).coerceAtLeast(0.01f)
                // opacity 使用 color alpha 之外的额外“亮度/不透明度”控制：这里用 opacity 作为一个乘子映射到 alpha
                val alpha = (AMBIENT_SMOKE_COLOR_ALPHA.toFloat() * opacity).toInt().coerceIn(0, 255)
                val c2 = Color(c.red, c.green, c.blue, alpha)

                engine.addNebulaSmokeParticle(at, vel, size, endSizeMult, inDur, fullDur, outDur, c2)
            } catch (_: Throwable) {
            }
        }
    }

    private fun emitMuzzleFx(
        engine: CombatEngineAPI,
        state: State,
        amount: Float,
        start: Vector2f,
        facing: Float,
        strength: Float,
        coreWidth: Float,
        muzzleScale: Float,
    ) {
        val s = strength.coerceIn(0f, 1f)
        val m = muzzleScale.coerceAtLeast(0.05f)

        // 粒子：喷口“散射”
        val particleRate = (MUZZLE_PARTICLES_PER_SEC * (0.35f + 0.65f * s)).coerceAtLeast(0f)
        state.muzzleParticleAcc += particleRate * amount
        val pCount = state.muzzleParticleAcc.toInt().coerceAtMost(MUZZLE_PARTICLES_MAX_PER_FRAME)
        if (pCount > 0) state.muzzleParticleAcc -= pCount

        for (i in 0 until pCount) {
            val ang = facing + (Math.random().toFloat() - 0.5f) * (MUZZLE_CONE_ARC_DEG * (0.65f + 0.35f * (1f - s)))
            val rad = Math.toRadians(ang.toDouble())
            val speed = lerp(MUZZLE_SPEED_MIN, MUZZLE_SPEED_MAX, Math.random().toFloat()) * (0.8f + 0.6f * s)
            val vel = Vector2f((cos(rad).toFloat() * speed), (sin(rad).toFloat() * speed))
            val size = lerp(MUZZLE_SIZE_MIN, MUZZLE_SIZE_MAX, Math.random().toFloat()) * (0.85f + 0.35f * s) * m
            val dur = lerp(MUZZLE_DUR_MIN, MUZZLE_DUR_MAX, Math.random().toFloat())
            val bright = lerp(0.75f, 1.45f, Math.random().toFloat())

            try {
                engine.addSmoothParticle(start, vel, size, bright, dur, glowColor)
            } catch (_: Throwable) {
            }
        }

        // 短束：光锥“线束散射”（数量较少，避免性能压力）
        val sprayRate = (MUZZLE_SPRAY_BEAMS_PER_SEC * (0.25f + 0.75f * s)).coerceAtLeast(0f)
        state.muzzleSprayAcc += sprayRate * amount
        val bCount = state.muzzleSprayAcc.toInt().coerceAtMost(MUZZLE_SPRAY_MAX_PER_FRAME)
        if (bCount > 0) state.muzzleSprayAcc -= bCount

        for (i in 0 until bCount) {
            // 降低随机性：用离散槽位分布角度，并按索引交替左右，保证两侧更均匀。
            val idx = state.muzzleSprayIndex++
            val sign = if ((idx and 1) == 0) 1f else -1f
            val halfArc = MUZZLE_SPRAY_ARC_DEG * 0.5f
            val slots = MUZZLE_SPRAY_ANGLE_SLOTS.coerceAtLeast(2)
            val slot = (idx / 2) % slots
            val slotT = (slot.toFloat() + 0.5f) / slots.toFloat() // 0..1
            val mag = halfArc * slotT
            val jitter = (Math.random().toFloat() - 0.5f) * 2f * (halfArc / slots.toFloat()) * MUZZLE_SPRAY_ANGLE_JITTER_MUL
            val ang = facing + sign * mag + jitter
            val len = lerp(MUZZLE_SPRAY_LEN_MIN, MUZZLE_SPRAY_LEN_MAX, Math.random().toFloat()) * m
            val baseW = (coreWidth * (0.55f + 0.25f * s)).coerceAtLeast(3.5f) * m * 2.0f
            val tipW = (baseW * 0.10f).coerceAtLeast(1.2f)

            val ent = BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
                engine = engine,
                location = start,
                facing = ang,
                length = len,
                baseWidth = baseW,
                tipWidth = tipW,
                coreColor = coreColor,
                fringeColor = glowColor,
                coreSprite = cachedSprites?.first ?: continue,
                fringeSprite = cachedSprites?.second ?: continue,
                layer = layer,
                full = 0.05f,
                baseAlphaMul = 0.28f,
                tipAlphaMul = 0.04f,
                baseEmissiveAlphaMul = 2.10f,
                tipEmissiveAlphaMul = 0.55f,
                mixPower = 3.2f,
            )

            if (ent != null) {
                try {
                    // 存活时间 +100%
                    ent.setGlobalTimer(0f, MUZZLE_SPRAY_FULL, MUZZLE_SPRAY_FADE_OUT)
                    initFlowParams(ent, CORE_TEX_SPEED * (0.6f + 0.5f * s))
                    initEndFade(ent)
                } catch (_: Throwable) {
                }

                // 淡出阶段“缓慢变小”而不是直接消失：逐帧缩短长度与宽度
                try {
                    val start = Vector2f(start)
                    val facing0 = ang
                    val total = (MUZZLE_SPRAY_FULL + MUZZLE_SPRAY_FADE_OUT).coerceAtLeast(0.01f)
                    val full = MUZZLE_SPRAY_FULL.coerceAtLeast(0f)
                    val fade = MUZZLE_SPRAY_FADE_OUT.coerceAtLeast(0.01f)

                    val len0 = len.coerceAtLeast(1f)
                    val baseW0 = baseW.coerceAtLeast(0.25f)
                    val tipW0 = tipW.coerceAtLeast(0.18f)

                    engine.addPlugin(object : BaseEveryFrameCombatPlugin() {
                        private var elapsed = 0f

                        override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
                            if (engine.isPaused) return
                            elapsed += amount

                            if (ent.hasDelete() || ent.isGlobalTimerOver || elapsed >= total + 0.10f) {
                                engine.removePlugin(this)
                                return
                            }

                            if (elapsed <= full) return

                            val fadeT = ((elapsed - full) / fade).coerceIn(0f, 1f)
                            // 缓慢变小：前期缩得慢，末尾才明显变小
                            val shrink = (1f - fadeT).pow(0.70f).coerceIn(0f, 1f)
                            val minS = MUZZLE_SPRAY_SHRINK_MIN
                            val sNow = (minS + (1f - minS) * shrink).coerceIn(0f, 1f)

                            try {
                                val nodes = ent.nodes
                                if (nodes != null && nodes.size >= 2) {
                                    nodes[0].set(0f, 0f)
                                    nodes[1].set(len0 * sNow, 0f)
                                    ent.setNodeRefreshIndex(0)
                                    ent.setNodeRefreshAllFromCurrentIndex()
                                    ent.submitNodes()
                                }
                            } catch (_: Throwable) {
                            }

                            try {
                                ent.setStartWidth(baseW0 * sNow)
                                ent.setEndWidth(tipW0 * sNow)
                            } catch (_: Throwable) {
                            }

                            // 位置/朝向保持不变：这是“炮口散发”短暂残影
                            try {
                                ent.setStateVanilla(start, facing0)
                            } catch (_: Throwable) {
                            }
                        }
                    })
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun emitImpactFx(
        engine: CombatEngineAPI,
        state: State,
        amount: Float,
        point: Vector2f,
        facing: Float,
        strength: Float,
        coreWidth: Float,
        isShieldHit: Boolean,
        hitTarget: CombatEntityAPI,
    ) {
        val s = strength.coerceIn(0f, 1f)

        // 命中点“爆闪/火花”
        val particleRate = (IMPACT_PARTICLES_PER_SEC * (0.35f + 0.65f * s)).coerceAtLeast(0f)
        state.impactParticleAcc += particleRate * amount
        val pCount = state.impactParticleAcc.toInt().coerceAtMost(IMPACT_PARTICLES_MAX_PER_FRAME)
        if (pCount > 0) state.impactParticleAcc -= pCount

        val spread = IMPACT_PARTICLE_SPREAD * (0.75f + 0.55f * s)
        for (i in 0 until pCount) {
            val ang = (Math.random().toFloat() * 360f)
            val rad = Math.toRadians(ang.toDouble())
            val speed = lerp(IMPACT_SPEED_MIN, IMPACT_SPEED_MAX, Math.random().toFloat()) * (0.8f + 0.6f * s)
            val vel = Vector2f((cos(rad).toFloat() * speed), (sin(rad).toFloat() * speed))
            val size = lerp(IMPACT_SIZE_MIN, IMPACT_SIZE_MAX, Math.random().toFloat()) * (0.85f + 0.45f * s)
            val dur = lerp(IMPACT_DUR_MIN, IMPACT_DUR_MAX, Math.random().toFloat())
            val bright = lerp(0.65f, 1.25f, Math.random().toFloat())

            val jitter = Vector2f(
                (Math.random().toFloat() - 0.5f) * spread,
                (Math.random().toFloat() - 0.5f) * spread,
            )
            val at = Vector2f(point)
            at.x += jitter.x
            at.y += jitter.y

            try {
                // 命中点“白热”核心点
                engine.addHitParticle(at, vel, size * 0.65f, bright, dur, coreColor)
                engine.addSmoothParticle(at, vel, size, bright * 0.95f, dur * 1.1f, glowColor)
            } catch (_: Throwable) {
            }
        }

        // 命中点“多条弧线束向外散发”
        val arcRate = (IMPACT_ARCS_PER_SEC * (0.25f + 0.75f * s)).coerceAtLeast(0f)
        state.impactArcAcc += arcRate * amount
        val aCount = state.impactArcAcc.toInt().coerceAtMost(IMPACT_ARCS_MAX_PER_FRAME)
        if (aCount > 0) state.impactArcAcc -= aCount

        for (i in 0 until aCount) {
            // 更偏向“侧向散射”而非沿束方向：让命中读感更像“能量打击”
            val ang = facing + 90f + (Math.random().toFloat() - 0.5f) * 220f
            val rad = Math.toRadians(ang.toDouble())
            val r = lerp(IMPACT_ARC_RADIUS_MIN, IMPACT_ARC_RADIUS_MAX, Math.random().toFloat()) * (0.75f + 0.45f * s)
            val end = Vector2f(point.x + cos(rad).toFloat() * r, point.y + sin(rad).toFloat() * r)
            val width = lerp(IMPACT_ARC_WIDTH_MIN, IMPACT_ARC_WIDTH_MAX, Math.random().toFloat()) * (0.8f + 0.4f * s)

            try {
                engine.spawnEmpArcVisual(
                    point,
                    hitTarget,
                    end,
                    hitTarget,
                    width,
                    glowColor,
                    if (isShieldHit) glowColor else coreColor,
                )
            } catch (_: Throwable) {
                // API/版本差异时，弧线可直接忽略
            }
        }
    }

    private fun emitImpactEmpArc(
        engine: CombatEngineAPI,
        state: State,
        amount: Float,
        source: ShipAPI,
        contact: BeamContact?,
        strength: Float,
        coreWidth: Float,
        panelDps: Float,
    ) {
        val c = contact ?: run {
            state.impactEmpAcc = 0f
            return
        }

        val target = c.hitTarget as? ShipAPI ?: run {
            // 未命中船体（比如空中/打到非船体实体），避免累积导致“下一次刚接触就立刻出弧”
            state.impactEmpAcc = 0f
            return
        }
        if (!engine.isEntityInPlay(target)) {
            state.impactEmpAcc = 0f
            return
        }
        if (target === source) return

        // 节流：每 0.33s
        state.impactEmpAcc += amount
        if (state.impactEmpAcc < EMP_ARC_INTERVAL) return
        state.impactEmpAcc -= EMP_ARC_INTERVAL

        // 护盾命中：50% 概率触发，且随目标硬幅能水平降低（最低 0%）
        if (c.isShieldHit) {
            val hard = try {
                target.hardFluxLevel
            } catch (_: Throwable) {
                0f
            }.coerceIn(0f, 1f)
            val chance = (EMP_ARC_SHIELD_BASE_CHANCE * (1f - hard)).coerceIn(0f, EMP_ARC_SHIELD_BASE_CHANCE)
            if (rand01() >= chance) return
        }

        val from = Vector2f(c.end)
        val to = pickEmpArcTargetPoint(state, target, from)

        // 视觉电弧：厚度随束强度略变化
        val s = strength.coerceIn(0f, 1f)
        val thickness = (coreWidth * (0.38f + 0.22f * s)).coerceIn(EMP_ARC_THICKNESS_MIN, EMP_ARC_THICKNESS_MAX)
        try {
            engine.spawnEmpArcVisual(from, source, to, target, thickness, glowColor, coreColor)
        } catch (_: Throwable) {
        }

        // 伤害：以“面板 DPS”在 0.33s 窗口内折算为单次电弧伤害
        val dps = panelDps.coerceAtLeast(0f)
        if (dps <= 0f) return
        val total = (dps * EMP_ARC_INTERVAL).coerceAtLeast(0f)
        val emp = total * EMP_ARC_EMP_FRACTION
        val energy = total * EMP_ARC_ENERGY_FRACTION

        // 需求：优先打击未瘫痪武器/引擎 —— 通过“命中点”落在子系统附近 + EMP amount 来促使引擎选取子系统
        // 护盾触发本身就是“穿透事件”，因此直接 bypassShields=true。
        try {
            engine.applyDamage(
                target,
                to,
                energy,
                DamageType.ENERGY,
                emp,
                true,
                false,
                source,
                true,
            )
        } catch (_: Throwable) {
        }
    }

    private fun pickEmpArcTargetPoint(state: State, target: ShipAPI, fallback: Vector2f): Vector2f {
        // 1) 未瘫痪武器
        val weapons = try {
            target.allWeapons
        } catch (_: Throwable) {
            null
        }?.filter {
            try {
                !it.isDecorative && !it.isDisabled && !it.isPermanentlyDisabled
            } catch (_: Throwable) {
                false
            }
        }.orEmpty()

        // 2) 未瘫痪引擎
        val engines = try {
            target.engineController.shipEngines
        } catch (_: Throwable) {
            null
        }?.filter {
            try {
                !it.isDisabled && !it.isPermanentlyDisabled
            } catch (_: Throwable) {
                false
            }
        }.orEmpty()

        val base = when {
            weapons.isNotEmpty() && engines.isNotEmpty() -> {
                // 武器优先，但保留少量“引擎打击”存在感
                if (rand01() < EMP_ARC_WEAPON_PICK_CHANCE) {
                    val w = weapons[(rand01() * weapons.size.toFloat()).toInt().coerceIn(0, weapons.size - 1)]
                    Vector2f(w.location)
                } else {
                    val e = engines[(rand01() * engines.size.toFloat()).toInt().coerceIn(0, engines.size - 1)]
                    Vector2f(e.location)
                }
            }

            weapons.isNotEmpty() -> {
                val w = weapons[(rand01() * weapons.size.toFloat()).toInt().coerceIn(0, weapons.size - 1)]
                Vector2f(w.location)
            }

            engines.isNotEmpty() -> {
                val e = engines[(rand01() * engines.size.toFloat()).toInt().coerceIn(0, engines.size - 1)]
                Vector2f(e.location)
            }

            else -> Vector2f(fallback)
        }

        // 抖动：避免每次都打同一点造成“钉死”感
        val j = EMP_ARC_TARGET_JITTER
        base.x += (rand01() - 0.5f) * 2f * j
        base.y += (rand01() - 0.5f) * 2f * j
        return base
    }

    companion object {
        private const val FULL = 9999f
        private const val FADE_OUT = 0.14f

        // 弧线节点数，调高可减少大幅扭曲下的折点感
        private const val WISP_NODES = 100

        private const val MAIN_TEX_PIXELS = 512f
        private const val WISP_TEX_PIXELS = 256f

        // 螺旋相位推进/自转速度倍率：0.25 = 降低 75%
        private const val WISP_ROT_SPEED_MUL = 0.25f

        // 主束两端保留一定亮度（避免头/尾过暗）
        private const val MAIN_END_ALPHA_START = 0.22f

        // 至少为发射端 75%
        private const val MAIN_END_ALPHA_END = 0.18f

        // wisp 两端淡出参数（更亮、更少“断头/断尾”）
        private const val WISP_END_ALPHA_START = 0.14f

        // 尽量接近“尾部 >= 75%”的观感
        private const val WISP_END_ALPHA_END = 0.11f
        private const val WISP_END_FADE_START = 0.26f
        private const val WISP_END_FADE_END = 0.34f

        // wisp 宽度倍率（相对 coreWidth）
        private const val WISP_WIDTH_BASE_MUL = 0.45f
        private const val WISP_WIDTH_MUL = 1.75f

        // 螺旋圈距（su/turn）：固定值（不随束长变化导致形态被拉伸）
        // 降低圈距（-40%）：圈距变小意味着螺旋更密
        private const val WISP_PITCH_SU = 792f

        // 螺旋半径（su）：固定值（需求：与主束间距统一）
        private const val WISP_RADIUS_SU = 42f
        private const val WISP_FINE_JITTER_MUL = 0.035f
        private const val WISP_TRAVEL_SPEED = 0.95f
        private const val WISP_SPIN_SPEED = 2.10f

        // 沿束随机粒子密度：约每 50su 一颗（按时间持续生成，带上限以控性能）
        private const val AMBIENT_PARTICLES_PER_50SU_PER_SEC = 2.0f
        private const val AMBIENT_PARTICLES_MAX_PER_FRAME = 16
        private const val AMBIENT_PARTICLE_LATERAL_MUL = 0.95f

        // 烟雾密度倍率：以沿束粒子密度为基准。
        // - 上一轮：2/9
        // - 本次需求：提高烟雾密度 +50% => (2/9) * 1.5 = 1/3
        private const val AMBIENT_SMOKE_RATE_MUL = (1f / 3f)
        // 同步提高每帧上限，避免在长束/高强度下被 cap 吃掉导致“密度提不上去”
        private const val AMBIENT_SMOKE_MAX_PER_FRAME = 9
        private const val AMBIENT_SMOKE_LATERAL_MUL = 1.30f
        private const val AMBIENT_SMOKE_SIDE_SPEED_MUL = 0.55f
        // 需求：提高烟雾亮度 +35%（主要体现在 alpha）
        private const val AMBIENT_SMOKE_COLOR_ALPHA = 95

        // 更偏蓝的烟雾基色（独立于 core/glow 以便稳定调参）
        private val AMBIENT_SMOKE_BASE_COLOR = Color(70, 160, 255)

        private const val AMBIENT_SMOKE_END_SIZE_MUL_MIN = 1.35f
        private const val AMBIENT_SMOKE_END_SIZE_MUL_MAX = 2.10f

        // nebula 粒子时间分配：总时长 = in + full + out
        private const val AMBIENT_SMOKE_IN_FRAC = 0.12f
        private const val AMBIENT_SMOKE_FULL_FRAC = 0.22f
        private const val AMBIENT_SMOKE_OUT_FRAC = 0.66f

        private const val AMBIENT_SMOKE_SPEED_MIN = 6f
        private const val AMBIENT_SMOKE_SPEED_MAX = 42f
        private const val AMBIENT_SMOKE_SIZE_MIN = 18f
        private const val AMBIENT_SMOKE_SIZE_MAX = 52f
        private const val AMBIENT_SMOKE_DUR_MIN = 0.55f
        private const val AMBIENT_SMOKE_DUR_MAX = 1.35f
        private const val AMBIENT_SMOKE_OPACITY_MIN = 0.25f
        private const val AMBIENT_SMOKE_OPACITY_MAX = 0.55f

        // 需求：沿束粒子前向速度 +100%
        private const val AMBIENT_PARTICLE_SPEED_MIN = 16f
        private const val AMBIENT_PARTICLE_SPEED_MAX = 84f
        private const val AMBIENT_PARTICLE_SIZE_MIN = 8f
        private const val AMBIENT_PARTICLE_SIZE_MAX = 18f
        private const val AMBIENT_PARTICLE_DUR_MIN = 0.22f
        private const val AMBIENT_PARTICLE_DUR_MAX = 0.55f

        // 负值：向前流动（BoxUtil 约定）
        private const val CORE_TEX_SPEED = -520f
        private const val GLOW_TEX_SPEED = -320f
        private const val WISP_TEX_SPEED = -220f

        private const val CORE_TIP_WIDTH_MUL = 0.34f
        private const val GLOW_TIP_WIDTH_MUL = 0.45f

        // 两端淡出比例：数值越小越“尖锐”；越大越“钝”
        private const val START_FADE_FACTOR = 0.018f
        private const val END_FADE_FACTOR = 0.024f

        private const val MUZZLE_PARTICLES_PER_SEC = 150f
        private const val MUZZLE_PARTICLES_MAX_PER_FRAME = 16
        private const val MUZZLE_CONE_ARC_DEG = 110f
        private const val MUZZLE_SPEED_MIN = 110f
        private const val MUZZLE_SPEED_MAX = 360f
        private const val MUZZLE_SIZE_MIN = 36f
        private const val MUZZLE_SIZE_MAX = 88f
        private const val MUZZLE_DUR_MIN = 0.10f
        private const val MUZZLE_DUR_MAX = 0.26f

        private const val MUZZLE_SPRAY_BEAMS_PER_SEC = 28f
        private const val MUZZLE_SPRAY_MAX_PER_FRAME = 5
        private const val MUZZLE_SPRAY_ARC_DEG = 92f
        private const val MUZZLE_SPRAY_LEN_MIN = 140f
        private const val MUZZLE_SPRAY_LEN_MAX = 360f

        // 炮口短束角度分布：槽位数越多越“细腻”，但随机性也更强；这里取中间值。
        private const val MUZZLE_SPRAY_ANGLE_SLOTS = 6

        // 槽内抖动比例：0=完全固定；越大越随机
        private const val MUZZLE_SPRAY_ANGLE_JITTER_MUL = 0.35f

        // 炮口光锥：存活时间（full + fade）
        private const val MUZZLE_SPRAY_FULL = 0.08f
        private const val MUZZLE_SPRAY_FADE_OUT = 0.28f

        // 淡出时最小缩放（避免一下子缩到 0 导致“跳变”）
        private const val MUZZLE_SPRAY_SHRINK_MIN = 0.12f

        private const val IMPACT_PARTICLES_PER_SEC = 95f
        private const val IMPACT_PARTICLES_MAX_PER_FRAME = 12
        private const val IMPACT_PARTICLE_SPREAD = 14f
        private const val IMPACT_SPEED_MIN = 60f
        private const val IMPACT_SPEED_MAX = 320f
        private const val IMPACT_SIZE_MIN = 10f
        private const val IMPACT_SIZE_MAX = 28f
        private const val IMPACT_DUR_MIN = 0.08f
        private const val IMPACT_DUR_MAX = 0.22f

        private const val IMPACT_ARCS_PER_SEC = 22f
        private const val IMPACT_ARCS_MAX_PER_FRAME = 3
        private const val IMPACT_ARC_RADIUS_MIN = 35f
        private const val IMPACT_ARC_RADIUS_MAX = 120f
        private const val IMPACT_ARC_WIDTH_MIN = 8f
        private const val IMPACT_ARC_WIDTH_MAX = 16f

        // 击中效果：周期性 EMP 电弧
        private const val EMP_ARC_INTERVAL = 0.33f
        private const val EMP_ARC_EMP_FRACTION = 0.67f
        private const val EMP_ARC_ENERGY_FRACTION = 0.33f
        private const val EMP_ARC_SHIELD_BASE_CHANCE = 0.50f

        private const val EMP_ARC_THICKNESS_MIN = 10f
        private const val EMP_ARC_THICKNESS_MAX = 28f
        private const val EMP_ARC_TARGET_JITTER = 18f
        private const val EMP_ARC_WEAPON_PICK_CHANCE = 0.78f

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private fun rand01(): Float = Math.random().toFloat()
    }
}
