package cn.kasuminova.astd.combat.effect.generic

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.input.InputEventAPI
import org.boxutil.define.BoxEnum
import org.boxutil.define.InstanceType
import org.boxutil.manager.CombatRenderingManager
import org.boxutil.units.standard.attribute.Instance2Data
import org.boxutil.units.standard.entity.SpriteEntity
import org.boxutil.units.standard.entity.TrailEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.roundToInt

/**
 * 终端打击命中 VFX 工具（原 TSM 系列通用工具，TSM 武器随 D9 废弃后由 AOD-7 命中特效沿用）：
 * - 冲击条纹喷散（BoxUtil，可自动回退到原版粒子）
 * - 同色冲击烟雾
 */
internal object ImpactStrikeFx {

    /**
     * 冲击特效朝向：
     * - OUTWARD：朝“远离敌人/命中点外侧”的方向喷散。
     *            约定为：与来袭方向相反（towardTargetFacing + 180）。
     * - INWARD：朝“指向敌人/命中点内侧”的方向喷散（即朝向敌人），
     *           约定为：沿来袭方向（towardTargetFacing）。
     */
    enum class ImpactFacingMode {
        OUTWARD,
        INWARD,
    }

    data class ImpactSmokeStyle(
        val puffCountBase: Int = 6,
        val puffCountExtra: Int = 4,
        val spreadArc: Float = 28f,
        val sizeMin: Float = 60f,
        val sizeMax: Float = 120f,
        val speedMin: Float = 80f,
        val speedMax: Float = 180f,
        val durationMin: Float = 0.45f,
        val durationMax: Float = 0.85f,
        val endSizeMult: Float = 1.35f,
    )

    data class ImpactSprayStyle(
        val baseRaysMin: Int = 22,
        val baseRaysExtra: Int = 9,
        val arc: Float = 65f,
        val lengthMin: Float = 140f,
        val lengthMax: Float = 310f,
        val widthMin: Float = 12.0f,
        val widthMax: Float = 24.0f,
        val fullMin: Float = 0.06f,
        val fullMax: Float = 0.12f,
        val fadeOutMin: Float = 0.44f,
        val fadeOutMax: Float = 0.64f,
        val speedMin: Float = 240f,
        val speedMax: Float = 560f,
        val impactScale: Float = 1f,
        val introRampSeconds: Float = 0.07f,
    )

    private fun computeImpactFxFacing(towardTargetFacing: Float, mode: ImpactFacingMode): Float {
        val f = ((towardTargetFacing % 360f) + 360f) % 360f
        return when (mode) {
            ImpactFacingMode.OUTWARD -> (f + 180f) % 360f
            ImpactFacingMode.INWARD -> f
        }
    }

    /**
     * 通用冲击反馈：同轨迹烟雾 + 条纹喷散。
     *
     * @param towardTargetFacing 建议传弹体朝向/速度朝向（即“来袭方向/朝向敌人的方向”）。
     * @param facingMode 选择喷散方向（朝向敌人 INWARD / 远离敌人 OUTWARD）。
     */
    fun spawnImpactFx(
        engine: CombatEngineAPI,
        point: Vector2f,
        towardTargetFacing: Float,
        facingMode: ImpactFacingMode,
        smokeColor: Color,
        coreColor: Color,
        fringeColor: Color,
        intensityMult: Float = 1f,
        smokeStyle: ImpactSmokeStyle = ImpactSmokeStyle(),
        sprayStyle: ImpactSprayStyle = ImpactSprayStyle(),
    ) {
        val facing = computeImpactFxFacing(towardTargetFacing, facingMode)

        spawnImpactSmoke(
            engine = engine,
            point = point,
            facing = facing,
            smokeColor = smokeColor,
            intensityMult = intensityMult,
            puffCountBase = smokeStyle.puffCountBase,
            puffCountExtra = smokeStyle.puffCountExtra,
            spreadArc = smokeStyle.spreadArc,
            sizeMin = smokeStyle.sizeMin,
            sizeMax = smokeStyle.sizeMax,
            speedMin = smokeStyle.speedMin,
            speedMax = smokeStyle.speedMax,
            durationMin = smokeStyle.durationMin,
            durationMax = smokeStyle.durationMax,
            endSizeMult = smokeStyle.endSizeMult,
        )

        spawnImpactSpray(
            engine = engine,
            point = point,
            facing = facing,
            coreColor = coreColor,
            fringeColor = fringeColor,
            intensityMult = intensityMult,
            baseRaysMin = sprayStyle.baseRaysMin,
            baseRaysExtra = sprayStyle.baseRaysExtra,
            arc = sprayStyle.arc,
            lengthMin = sprayStyle.lengthMin,
            lengthMax = sprayStyle.lengthMax,
            widthMin = sprayStyle.widthMin,
            widthMax = sprayStyle.widthMax,
            fullMin = sprayStyle.fullMin,
            fullMax = sprayStyle.fullMax,
            fadeOutMin = sprayStyle.fadeOutMin,
            fadeOutMax = sprayStyle.fadeOutMax,
            speedMin = sprayStyle.speedMin,
            speedMax = sprayStyle.speedMax,
            impactScale = sprayStyle.impactScale,
            introRampSeconds = sprayStyle.introRampSeconds,
        )
    }

    fun spawnImpactSpray(
        engine: CombatEngineAPI,
        point: Vector2f,
        facing: Float,
        coreColor: Color,
        fringeColor: Color,
        intensityMult: Float = 1f,
        // 需求：基础冲击强度 ×1.5
        baseRaysMin: Int = 22,
        baseRaysExtra: Int = 9,
        arc: Float = 65f,
        lengthMin: Float = 140f,
        lengthMax: Float = 310f,
        // 需求：冲击线宽 ×2
        widthMin: Float = 12.0f,
        widthMax: Float = 24.0f,
        fullMin: Float = 0.06f,
        fullMax: Float = 0.12f,
        fadeOutMin: Float = 0.44f,
        fadeOutMax: Float = 0.64f,
        speedMin: Float = 240f,
        speedMax: Float = 560f,
        // 额外：整体尺寸倍率（TSM-2 需要更克制一些）
        impactScale: Float = 1f,
        // 额外：短暂“渐现/缩放”过渡，避免大量线条在同一帧突然闪出来
        introRampSeconds: Float = 0.07f,
    ) {
        // 以现有效果为基准，根据“伤害加成”按比例放大，但做合理封顶避免满屏。
        val vis = intensityMult.coerceIn(0.75f, 3.0f)
        // 线条数量也需要上限，否则高倍率会刷屏。
        val baseRays = (baseRaysMin + MathUtils.getRandomNumberInRange(0, baseRaysExtra)).coerceAtLeast(1)
        val rays = (baseRays * vis).roundToInt().coerceIn(1, 80)
        val baseScale = impactScale.coerceIn(0.25f, 2.5f)

        // 小数量时直接一次性生成即可；大量线条时做一个很短的“分批渐现”，减少突兀。
        val doRamp = introRampSeconds > 0f && rays >= 12
        if (!doRamp) {
            spawnImpactSprayBatch(
                engine = engine,
                point = point,
                facing = facing,
                coreColor = coreColor,
                fringeColor = fringeColor,
                rays = rays,
                vis = vis,
                arc = arc,
                lengthMin = lengthMin,
                lengthMax = lengthMax,
                widthMin = widthMin,
                widthMax = widthMax,
                fullMin = fullMin,
                fullMax = fullMax,
                fadeOutMin = fadeOutMin,
                fadeOutMax = fadeOutMax,
                speedMin = speedMin,
                speedMax = speedMax,
                impactScale = baseScale,
            )
            return
        }

        // 3 段分批：数量/尺寸逐步抬升，视觉上更像“短缩放/展开”。
        val stepCount = 3

        // 权重：按二次曲线递增（后面比前面更多），再归一化。
        val weights = FloatArray(stepCount) { i ->
            val x0 = i.toFloat() / stepCount.toFloat()
            val x1 = (i + 1).toFloat() / stepCount.toFloat()
            (x1 * x1 - x0 * x0).coerceAtLeast(0f)
        }
        val sumW = weights.sum().takeIf { it > 0f } ?: 1f
        for (i in 0 until stepCount) weights[i] /= sumW

        val raysPerStep = IntArray(stepCount)
        var remaining = rays
        for (i in 0 until stepCount) {
            val remainingSteps = stepCount - i
            val maxAllowed = remaining - (remainingSteps - 1)
            val want = if (i == stepCount - 1) {
                remaining
            } else {
                (rays * weights[i]).roundToInt().coerceIn(1, maxAllowed)
            }
            raysPerStep[i] = want
            remaining -= want
        }

        fun smoothStep01(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        fun stepScale(step: Int): Float {
            val x = (step + 1).toFloat() / stepCount.toFloat()
            val s = smoothStep01(x)
            // 保底给一点尺寸，避免第一段“太小看不见”。
            return (0.35f + 0.65f * s).coerceIn(0.35f, 1f)
        }

        // 第一段立即生成
        spawnImpactSprayBatch(
            engine = engine,
            point = point,
            facing = facing,
            coreColor = coreColor,
            fringeColor = fringeColor,
            rays = raysPerStep[0],
            vis = vis,
            arc = arc,
            lengthMin = lengthMin,
            lengthMax = lengthMax,
            widthMin = widthMin,
            widthMax = widthMax,
            fullMin = fullMin,
            fullMax = fullMax,
            fadeOutMin = fadeOutMin,
            fadeOutMax = fadeOutMax,
            speedMin = speedMin,
            speedMax = speedMax,
            impactScale = baseScale * stepScale(0),
        )

        // 后两段分帧补齐
        engine.addPlugin(object : BaseEveryFrameCombatPlugin() {
            private var elapsed = 0f
            private var stepIndex = 1
            private val interval = (introRampSeconds / stepCount.toFloat()).coerceIn(0.010f, 0.060f)

            override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
                if (engine.isPaused) return
                elapsed += amount
                while (stepIndex < stepCount && elapsed >= interval * stepIndex.toFloat()) {
                    spawnImpactSprayBatch(
                        engine = engine,
                        point = point,
                        facing = facing,
                        coreColor = coreColor,
                        fringeColor = fringeColor,
                        rays = raysPerStep[stepIndex],
                        vis = vis,
                        arc = arc,
                        lengthMin = lengthMin,
                        lengthMax = lengthMax,
                        widthMin = widthMin,
                        widthMax = widthMax,
                        fullMin = fullMin,
                        fullMax = fullMax,
                        fadeOutMin = fadeOutMin,
                        fadeOutMax = fadeOutMax,
                        speedMin = speedMin,
                        speedMax = speedMax,
                        impactScale = baseScale * stepScale(stepIndex),
                    )
                    stepIndex++
                }
                if (stepIndex >= stepCount || elapsed >= introRampSeconds * 1.25f) {
                    engine.removePlugin(this)
                }
            }
        })
    }

    private fun spawnImpactSprayBatch(
        engine: CombatEngineAPI,
        point: Vector2f,
        facing: Float,
        coreColor: Color,
        fringeColor: Color,
        rays: Int,
        vis: Float,
        arc: Float,
        lengthMin: Float,
        lengthMax: Float,
        widthMin: Float,
        widthMax: Float,
        fullMin: Float,
        fullMax: Float,
        fadeOutMin: Float,
        fadeOutMax: Float,
        speedMin: Float,
        speedMax: Float,
        impactScale: Float,
    ) {
        val r = rays.coerceIn(1, 80)
        val scale = impactScale.coerceIn(0.15f, 2.5f)
        val lenMin = lengthMin * scale
        val lenMax = lengthMax * scale
        val widMin = widthMin * scale
        val widMax = widthMax * scale

        // 优先：用 BoxUtil TrailEntity（锥形光束）渲染“针形条纹”。比 instanced sprite 更容易做尖端。
        if (trySpawnNeedleTrails(
                engine = engine,
                point = point,
                facing = facing,
                rays = r,
                vis = vis,
                coreColor = coreColor,
                fringeColor = fringeColor,
                arc = arc,
                lengthMin = lenMin,
                lengthMax = lenMax,
                widthMin = widMin,
                widthMax = widMax,
                fullMin = fullMin,
                fullMax = fullMax,
                fadeOutMin = fadeOutMin,
                fadeOutMax = fadeOutMax,
                speedMin = speedMin,
                speedMax = speedMax,
            )
        ) {
            return
        }

        // BoxUtil：使用 SpriteEntity（ENTITY_SPRITE）生成“条纹喷散”。
        try {
            val coreSprite = Global.getSettings().getSprite("graphics/fx/beamcoreb.png")
            val fringeSprite = Global.getSettings().getSprite("graphics/fx/beamfringeb.png")

            val entity = SpriteEntity()
            entity.setAdditiveBlend()
            entity.materialData.setDiffuse(coreSprite)
            entity.materialData.setEmissive(fringeSprite)
            entity.setStateVanilla(point, 0f)
            entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)

            val dataList = ArrayList<org.boxutil.base.api.InstanceDataAPI>(r)
            var maxFull = 0f
            var maxFadeOut = 0f

            for (i in 0 until r) {
                val ang = facing + MathUtils.getRandomNumberInRange(-arc * 0.5f, arc * 0.5f)

                // 需求：长度 +20%，单条宽度 -30%，并限制高倍率时的尺寸上限
                val sizeScale = (0.80f + 0.20f * vis).coerceIn(0.80f, 1.35f)
                val finalLength = (MathUtils.getRandomNumberInRange(lenMin, lenMax) * 1.20f * sizeScale)
                    .coerceAtMost(lenMax * 1.20f * 1.35f)
                val finalWidth = (MathUtils.getRandomNumberInRange(widMin, widMax) * 0.70f * sizeScale)
                    .coerceAtMost(widMax * 0.70f * 1.35f)

                val full = MathUtils.getRandomNumberInRange(fullMin, fullMax)
                val fadeOut = MathUtils.getRandomNumberInRange(fadeOutMin, fadeOutMax)
                maxFull = maxOf(maxFull, full)
                maxFadeOut = maxOf(maxFadeOut, fadeOut)

                val total = (full + fadeOut).coerceAtLeast(0.05f)

                // 线段从内向外：初始更短；从细变粗：宽度随时间增长
                val startLength = finalLength * MathUtils.getRandomNumberInRange(0.18f, 0.30f)
                // 需求：头尾更“针形”，起始宽度更细
                val startWidth = finalWidth * MathUtils.getRandomNumberInRange(0.07f, 0.14f)
                val lengthRate = (finalLength - startLength) / total
                val widthRate = (finalWidth - startWidth) / total

                val data = Instance2Data()
                val localOffset = MathUtils.getPointOnCircumference(null, MathUtils.getRandomNumberInRange(0f, 6f) * vis * scale, ang)

                // 让条纹“尾部贴近爆点、头部向外飞”，弱化长方形的对称感
                val dir = MathUtils.getPointOnCircumference(null, 1f, ang)
                val locX = localOffset.x + dir.x * (startLength * 0.5f)
                val locY = localOffset.y + dir.y * (startLength * 0.5f)
                data.setLocation(locX, locY)

                val v = MathUtils.getPointOnCircumference(null, MathUtils.getRandomNumberInRange(speedMin, speedMax) * (0.85f + 0.15f * vis), ang)
                data.setVelocity(v.x, v.y)

                // 条纹尺寸（Instance2Data 的 scale 是半尺寸）
                data.setScale(startLength * 0.5f, startWidth * 0.5f)
                data.setFacing(ang)
                data.setTurnRate(0f)
                data.setScaleRate(lengthRate * 0.5f, widthRate * 0.5f)
                data.setTimer(0f, full, fadeOut)
                data.setColor(coreColor)
                data.setEmissiveColor(fringeColor)
                dataList.add(data)

                // 端点柔化：用少量圆形 glow 粒子“磨掉方头方尾”的边缘。
                // 注意：粒子跟随同一速度，整体仍像“针形 streak”。
                val tail = Vector2f(point.x + locX, point.y + locY)
                val tip = Vector2f(
                    tail.x + dir.x * (startLength * 0.95f),
                    tail.y + dir.y * (startLength * 0.95f),
                )
                val softenDur = (full + fadeOut).coerceIn(0.10f, 0.35f)
                engine.addSmoothParticle(
                    tail,
                    v,
                    (startWidth * 1.8f).coerceIn(6f * vis * scale, 26f * vis * scale),
                    1.35f,
                    softenDur,
                    Color(fringeColor.red, fringeColor.green, fringeColor.blue, (fringeColor.alpha * 0.55f).toInt().coerceIn(10, 255)),
                )
                engine.addSmoothParticle(
                    tip,
                    v,
                    (startWidth * 1.2f).coerceIn(5f * vis * scale, 20f * vis * scale),
                    1.55f,
                    (softenDur * 0.75f).coerceAtLeast(0.06f),
                    Color(coreColor.red, coreColor.green, coreColor.blue, (coreColor.alpha * 0.65f).toInt().coerceIn(10, 255)),
                )
            }

            entity.setInstanceData(dataList, 0f, maxFull, maxFadeOut)
            entity.setInstanceDataRefreshAllFromCurrentIndex()
            submitDynamicInstanceData(entity, dataList.size)
            entity.setRenderingCount(r)
            entity.setAlwaysRefreshInstanceData(true)

            val added = CombatRenderingManager.addEntity(entity)
            if (added == BoxEnum.STATE_SUCCESS) return
        } catch (_: Throwable) {
            // BoxUtil 在运行期不可用（依赖缺失/未初始化/版本不兼容等）时，退回到原版粒子。
        }

        // 兜底：原版粒子（效果略弱，但不至于完全没有反馈）
        for (i in 0 until r) {
            val ang = facing + MathUtils.getRandomNumberInRange(-arc * 0.5f, arc * 0.5f)
            val vel = MathUtils.getPointOnCircumference(null, MathUtils.getRandomNumberInRange(speedMin, speedMax) * (0.85f + 0.15f * vis), ang)
            engine.addSmoothParticle(
                point,
                vel,
                MathUtils.getRandomNumberInRange(20f, 32f) * vis * scale,
                1.35f,
                MathUtils.getRandomNumberInRange(0.18f, 0.28f),
                fringeColor,
            )
        }
    }

    private data class NeedleTrail(
        val entity: TrailEntity,
        val facing: Float,
        val vel: Vector2f,
        var loc: Vector2f,
    )

    private fun trySpawnNeedleTrails(
        engine: CombatEngineAPI,
        point: Vector2f,
        facing: Float,
        rays: Int,
        vis: Float,
        coreColor: Color,
        fringeColor: Color,
        arc: Float,
        lengthMin: Float,
        lengthMax: Float,
        widthMin: Float,
        widthMax: Float,
        fullMin: Float,
        fullMax: Float,
        fadeOutMin: Float,
        fadeOutMax: Float,
        speedMin: Float,
        speedMax: Float,
    ): Boolean {
        try {
            val coreSprite = Global.getSettings().getSprite("graphics/fx/beamcoreb.png")
            val fringeSprite = Global.getSettings().getSprite("graphics/fx/beamfringeb.png")

            val trails = ArrayList<NeedleTrail>(rays)
            var maxLife = 0f

            for (i in 0 until rays) {
                val ang = facing + MathUtils.getRandomNumberInRange(-arc * 0.5f, arc * 0.5f)
                val dir = MathUtils.getPointOnCircumference(null, 1f, ang)

                // 需求：长度 +20%，宽度 -30%；并限制高倍率时的尺寸上限
                // 这里不再用 *vis 线性放大长度/宽度，否则满屏。
                val sizeScale = (0.80f + 0.20f * vis).coerceIn(0.80f, 1.35f)
                val finalLength = (MathUtils.getRandomNumberInRange(lengthMin, lengthMax) * 1.20f * sizeScale)
                    .coerceAtMost(lengthMax * 1.20f * 1.35f)
                val finalWidth = (MathUtils.getRandomNumberInRange(widthMin, widthMax) * 0.70f * sizeScale)
                    .coerceAtMost(widthMax * 0.70f * 1.35f)

                // 更“针形”：尖端极细；基部也别太粗（否则看起来像粗棒）
                val tipWidth = (finalWidth * 0.045f).coerceIn(0.40f, 1.9f)
                // 基部上限再收紧一点，避免高强度时像粗棒
                val baseWidth = (finalWidth * 0.70f).coerceIn(2.2f, 12f * sizeScale)

                val full = MathUtils.getRandomNumberInRange(fullMin, fullMax)
                val fadeOut = MathUtils.getRandomNumberInRange(fadeOutMin, fadeOutMax)
                val life = (full + fadeOut).coerceAtLeast(0.05f)
                maxLife = maxOf(maxLife, life)

                // 基部内缩：把“钝的端帽”藏进爆点/烟雾里
                val baseInset = (finalLength * 0.18f).coerceIn(6f, 42f) * (0.90f + 0.10f * vis)
                val spawnLoc = Vector2f(
                    point.x - dir.x * baseInset,
                    point.y - dir.y * baseInset,
                )

                val ent = BoxUtilCombatVfx.createAndAddTaperedBeamTrail(
                    engine = engine,
                    location = spawnLoc,
                    facing = ang,
                    length = finalLength,
                    tailWidth = tipWidth,
                    headWidth = baseWidth,
                    coreColor = coreColor,
                    fringeColor = fringeColor,
                    coreSprite = coreSprite,
                    fringeSprite = fringeSprite,
                    layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    full = full,
                    // 尖端更淡一点，尾部更亮一点，强化“针尖”观感
                    tailAlphaMul = 0.10f,
                    headAlphaMul = 0.95f,
                    tailEmissiveAlphaMul = 0.55f,
                    headEmissiveAlphaMul = 2.05f,
                    mixPower = 3.0f,
                ) ?: return false

                // 手动设置淡出（createBeamVisual 默认 fadeOut=0）
                try {
                    ent.setGlobalTimer(0f, full, fadeOut)
                } catch (_: Throwable) {
                }

                // 进一步柔化端点（如果 BoxUtil 版本支持 fill 参数）：
                try {
                    ent.setFillStartAlpha(0f)
                    ent.setFillStartFactor(0.62f)
                    ent.setFillEndAlpha(0f)
                    ent.setFillEndFactor(0.92f)
                } catch (_: Throwable) {
                }

                val v = MathUtils.getPointOnCircumference(null, MathUtils.getRandomNumberInRange(speedMin, speedMax) * (0.85f + 0.15f * vis), ang)
                trails.add(
                    NeedleTrail(
                        entity = ent,
                        facing = ang,
                        vel = Vector2f(v),
                        loc = Vector2f(spawnLoc),
                    )
                )

                // 额外：尖端补一个小亮点，强化“尖”
                val tip = Vector2f(
                    spawnLoc.x + dir.x * (finalLength * 0.98f),
                    spawnLoc.y + dir.y * (finalLength * 0.98f),
                )
                engine.addSmoothParticle(
                    tip,
                    Vector2f(v.x * 0.25f, v.y * 0.25f),
                    (tipWidth * 3.5f).coerceIn(4f, 18f * vis),
                    2.2f,
                    (life * 0.25f).coerceIn(0.06f, 0.18f),
                    coreColor,
                )
            }

            if (trails.isEmpty()) return false

            // 让条纹整体有一点点“飞散”动势（只平移，不改变长度/宽度）。
            engine.addPlugin(object : BaseEveryFrameCombatPlugin() {
                private var timer = maxLife

                override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
                    if (engine.isPaused) return
                    timer -= amount
                    for (t in trails) {
                        if (t.entity.hasDelete() || t.entity.isGlobalTimerOver) continue
                        t.loc.x += t.vel.x * amount
                        t.loc.y += t.vel.y * amount
                        try {
                            t.entity.setStateVanilla(t.loc, t.facing)
                        } catch (_: Throwable) {
                        }
                    }
                    if (timer <= 0f) {
                        engine.removePlugin(this)
                    }
                }
            })

            return true
        } catch (_: Throwable) {
            return false
        }
    }

    /**
     * 命中烟雾（替代“爆闪”）：同色、沿冲击方向喷出，尽量与冲击条纹同轨迹。
     */
    fun spawnImpactSmoke(
        engine: CombatEngineAPI,
        point: Vector2f,
        facing: Float,
        smokeColor: Color,
        intensityMult: Float = 1f,
        puffCountBase: Int = 6,
        puffCountExtra: Int = 4,
        spreadArc: Float = 28f,
        sizeMin: Float = 60f,
        sizeMax: Float = 120f,
        speedMin: Float = 80f,
        speedMax: Float = 180f,
        durationMin: Float = 0.45f,
        durationMax: Float = 0.85f,
        endSizeMult: Float = 1.35f,
    ) {
        val vis = intensityMult.coerceIn(0.75f, 3.0f)
        val puffs = ((puffCountBase + MathUtils.getRandomNumberInRange(0, puffCountExtra)) * (0.75f + 0.25f * vis)).roundToInt().coerceAtLeast(1)

        for (i in 0 until puffs) {
            val ang = facing + MathUtils.getRandomNumberInRange(-spreadArc * 0.5f, spreadArc * 0.5f)
            val v = MathUtils.getPointOnCircumference(null, MathUtils.getRandomNumberInRange(speedMin, speedMax) * (0.85f + 0.15f * vis), ang)
            val loc = MathUtils.getRandomPointInCircle(point, 8f * (0.65f + 0.35f * vis))

            engine.addNebulaParticle(
                loc,
                v,
                MathUtils.getRandomNumberInRange(sizeMin, sizeMax) * (0.85f + 0.15f * vis),
                endSizeMult,
                0.08f,
                0.28f,
                MathUtils.getRandomNumberInRange(durationMin, durationMax),
                smokeColor,
                true,
            )
        }
    }

    private fun submitDynamicInstanceData(entity: org.boxutil.base.api.InstanceRenderAPI, instanceCount: Int): Boolean {
        if (instanceCount < 1) return false
        return try {
            val memory = entity.instanceDataMemory
            if (memory == null || memory.is_type_fixed()) {
                entity.mallocInstance(InstanceType.DYNAMIC_2D, instanceCount)
                entity.setInstanceDataRefreshIndex(0)
                entity.setInstanceDataRefreshOffset(0)
                entity.setInstanceDataRefreshAllFromCurrentIndex()
            }

            val after = entity.instanceDataMemory
            if (after == null || after.is_type_fixed()) return false

            entity.submitInstance()
            true
        } catch (_: Throwable) {
            false
        }
    }
}
