package cn.kasuminova.astd.combat.effect.lens.signature.singularity

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 奇点导弹“被击落/淡出自爆”的效果（使用 BoxUtil）。
 *
 * 注意：Starsector 默认“被点防打爆的导弹”通常不会造成伤害。
 * 本武器需求要求【击落】与【淡出】均产生伤害（且不攻击友军），因此伤害层在这里显式补齐。
 */
internal object SingularityDetonationFx {

    enum class DetonationMode {
        /** 被击落/强制移除：更亮、更“硬”。 */
        SHOT_DOWN,

        /** 命中目标：最重要的反馈，需要更清晰可读（但避免光锥刷屏）。 */
        HIT,

        /** 飞行淡出/失效：更克制，避免蜂群末端刷屏。伤害仍然完整结算。 */
        FADE_OUT,
    }

    enum class Variant {
        /** 新星（中）：更紧凑，红白高对比。 */
        NOVA,

        /** 事件视界（大）：更大、更亮，中心更硬。 */
        EVENT_HORIZON,
    }

    private val coreSprite by lazy { Global.getSettings().getSprite("graphics/fx/beamcoreb.png") }
    private val fringeSprite by lazy { Global.getSettings().getSprite("graphics/fx/beamfringeb.png") }

    private const val BASE_DAMAGE_MULT_NOVA = 0.60f
    private const val BASE_DAMAGE_MULT_EVENT_HORIZON = 0.80f

    private const val RADIUS_NOVA = 180f
    private const val RADIUS_EVENT_HORIZON = 285f

    fun spawn(engine: CombatEngineAPI, location: Vector2f, baseVel: Vector2f?, variant: Variant, mode: DetonationMode) {
        // BoxUtil 依赖未就绪时直接静默失败（不做原版粒子兜底：需求要求“所有特效用 BoxUtil”）。
        BoxUtilCombatVfx.ensureReady(engine)

        val modeMul = when (mode) {
            DetonationMode.FADE_OUT -> 0.78f
            DetonationMode.HIT -> 1.08f
            DetonationMode.SHOT_DOWN -> 1.0f
        }

        // 光锥/光刺数量：当前反馈“过于混乱”，因此整体下调，并拉开 NOVA 与 EVENT_HORIZON 的体积/刺长度差异。
        val (sizeMulBase, raysBase, coreColor, fringeColor, ringColor) = when (variant) {
            Variant.NOVA -> Quintuple(1.0f, 4, Color(255, 245, 245, 235), Color(255, 90, 90, 225), Color(255, 40, 40, 210))
            Variant.EVENT_HORIZON -> Quintuple(1.55f, 6, Color(255, 250, 255, 245), Color(235, 120, 255, 235), Color(170, 40, 255, 220))
        }

        val sizeMul = sizeMulBase * modeMul

        val rays = when (mode) {
            DetonationMode.FADE_OUT -> (raysBase * 0.50f).toInt().coerceAtLeast(2)
            DetonationMode.HIT -> (raysBase * 0.85f).toInt().coerceAtLeast(3)
            DetonationMode.SHOT_DOWN -> raysBase
        }

        // 扭曲：双层（内核塌缩 + 外环扩张），淡出模式只保留更轻的一层。
        spawnDistortionCore(engine, location, sizeMul, variant, mode)
        spawnDistortionRing(engine, location, sizeMul, variant, mode)

        // 光刺：随机 + 少量方向性长刺（根据 baseVel）
        spawnNeedles(engine, location, sizeMul, rays, coreColor, fringeColor)
        spawnDirectionalNeedles(engine, location, baseVel, sizeMul, variant, mode, coreColor, fringeColor)
        spawnRingNeedles(engine, location, sizeMul, ringColor)
    }

    /** 兼容旧调用点：默认按“击落”强度渲染。 */
    fun spawn(engine: CombatEngineAPI, location: Vector2f, baseVel: Vector2f?, variant: Variant) {
        spawn(engine, location, baseVel, variant, DetonationMode.SHOT_DOWN)
    }

    /**
     * 在 [spawn] 的基础上，额外施加一次“仅敌方”的 AoE 能量伤害。
     *
     * @param projectile 用于读取 owner/source/原始伤害，且用来避免 AI 飞行期置零导致的伤害缺失。
     */
    fun detonateWithDamage(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        location: Vector2f,
        baseVel: Vector2f?,
        variant: Variant,
        mode: DetonationMode,
    ) {
        spawn(engine, location, baseVel, variant, mode)
        applyDetonationDamage(engine, projectile, location, variant)
    }

    /** 兼容旧调用点：默认按“击落”强度渲染。 */
    fun detonateWithDamage(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        location: Vector2f,
        baseVel: Vector2f?,
        variant: Variant,
    ) {
        detonateWithDamage(engine, projectile, location, baseVel, variant, DetonationMode.SHOT_DOWN)
    }

    private fun applyDetonationDamage(
        engine: CombatEngineAPI,
        projectile: DamagingProjectileAPI,
        location: Vector2f,
        variant: Variant,
    ) {
        val owner = try {
            projectile.owner
        } catch (_: Throwable) {
            return
        }

        val source = (projectile as? MissileAPI)?.source

        // 优先取 AI 缓存的“原始面板伤害”（飞行中可能被置 0）。
        val baseDamage = (projectile.customData[SingularityKeys.MISSILE_ORIGINAL_DAMAGE] as? Float)
            ?: sanitizeNonNegativeFinite(projectile.damageAmount)
        if (baseDamage <= 0f) return

        val (radius, mult) = when (variant) {
            Variant.NOVA -> RADIUS_NOVA to BASE_DAMAGE_MULT_NOVA
            Variant.EVENT_HORIZON -> RADIUS_EVENT_HORIZON to BASE_DAMAGE_MULT_EVENT_HORIZON
        }
        val maxDamage = (baseDamage * mult).coerceAtLeast(0f)
        if (maxDamage <= 0f) return

        val ships = try {
            CombatUtils.getShipsWithinRange(location, radius)
        } catch (_: Throwable) {
            return
        }

        for (s in ships) {
            if (s == null) continue
            if (s.isHulk) continue
            if (s.owner == owner) continue // 不攻击友军

            // 距离衰减（线性）：中心满伤害，边缘为 0
            val dist = Misc.getDistance(location, s.location)
            val falloff = (1f - dist / radius).coerceIn(0f, 1f)
            if (falloff <= 0f) continue

            val dmg = (maxDamage * falloff).coerceAtLeast(0f)
            if (dmg <= 0f) continue

            // 尽量把命中点放在“朝向爆心”的船体表面，避免 point 在船外导致装甲格拾取异常。
            val hitPoint = try {
                val ang = Misc.getAngleInDegrees(s.location, location)
                MathUtils.getPointOnCircumference(s.location, (s.collisionRadius * 0.9f).coerceAtLeast(5f), ang)
            } catch (_: Throwable) {
                location
            }

            try {
                engine.applyDamage(
                    s as ShipAPI,
                    hitPoint,
                    dmg,
                    DamageType.ENERGY,
                    0f,
                    false,
                    false,
                    source,
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun sanitizeNonNegativeFinite(v: Float): Float {
        if (v.isNaN() || v.isInfinite()) return 0f
        if (v < 0f) return 0f
        return v
    }

    private fun spawnDistortionCore(engine: CombatEngineAPI, loc: Vector2f, sizeMul: Float, variant: Variant, mode: DetonationMode) {
        // 淡出自爆：更克制，内核可以不出现（减少闪屏/性能）。
        if (mode == DetonationMode.FADE_OUT) return

        try {
            val e = DistortionEntity()

            // 生命周期：快速淡入 -> 很短满值 -> 淡出
            val fadeIn = 0.02f
            val full = 0.04f
            val fadeOut = 0.14f
            e.setGlobalTimer(fadeIn, full, fadeOut)

            // “奇点透镜”中心硬、外环柔
            e.setInnerFull(0.20f, 0.20f)
            e.setInnerHardness(if (variant == Variant.EVENT_HORIZON) 0.82f else 0.75f)
            e.setRingHardness(0.62f)

            // 注意：BoxUtil DistortionEntity 的 size 使用 half-size。
            // 内核：先撑开一瞬，再快速塌缩。
            val inSize = 48f * sizeMul
            val fullSize = 96f * sizeMul
            val outSize = 18f * sizeMul

            e.setSizeIn(inSize, inSize)
            e.setSizeFull(fullSize, fullSize)
            e.setSizeOut(outSize, outSize)

            e.setPowerIn(if (variant == Variant.EVENT_HORIZON) 1.10f else 0.90f)
            e.setPowerFull(if (variant == Variant.EVENT_HORIZON) 1.45f else 1.10f)
            e.setPowerOut(0.20f)

            e.setLocation(loc)

            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_DISTORTION, e)
            if (state != 0) {
                e.delete()
            }
        } catch (_: Throwable) {
        }
    }

    private fun spawnDistortionRing(engine: CombatEngineAPI, loc: Vector2f, sizeMul: Float, variant: Variant, mode: DetonationMode) {
        try {
            val e = DistortionEntity()

            // 外环：扩张 + 淡出（淡出自爆也保留这一层）
            val fadeIn = 0.03f
            val full = if (mode == DetonationMode.FADE_OUT) 0.03f else 0.06f
            val fadeOut = if (mode == DetonationMode.FADE_OUT) 0.16f else 0.22f
            e.setGlobalTimer(fadeIn, full, fadeOut)

            e.setInnerFull(0.16f, 0.16f)
            e.setInnerHardness(if (variant == Variant.EVENT_HORIZON) 0.78f else 0.72f)
            e.setRingHardness(0.58f)

            val inSize = 90f * sizeMul
            val fullSize = 200f * sizeMul
            // 关键：fadeOut 时继续扩张，让“冲击波”有读感
            val outSize = 315f * sizeMul
            e.setSizeIn(inSize, inSize)
            e.setSizeFull(fullSize, fullSize)
            e.setSizeOut(outSize, outSize)

            val pMul = if (mode == DetonationMode.FADE_OUT) 0.80f else 1.0f
            e.setPowerIn((if (variant == Variant.EVENT_HORIZON) 0.70f else 0.55f) * pMul)
            e.setPowerFull((if (variant == Variant.EVENT_HORIZON) 0.92f else 0.72f) * pMul)
            e.setPowerOut(0.10f)

            e.setLocation(loc)

            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_DISTORTION, e)
            if (state != 0) e.delete()
        } catch (_: Throwable) {
        }
    }

    private fun spawnNeedles(
        engine: CombatEngineAPI,
        loc: Vector2f,
        sizeMul: Float,
        rays: Int,
        coreColor: Color,
        fringeColor: Color,
    ) {
        for (i in 0 until rays) {
            val ang = MathUtils.getRandomNumberInRange(0f, 360f)
            // 反馈：光刺过多会变成“光锥糊一团”。这里整体缩短/收窄一点。
            val len = MathUtils.getRandomNumberInRange(105f, 170f) * sizeMul
            val baseW = MathUtils.getRandomNumberInRange(9f, 14f) * sizeMul
            val tipW = (baseW * 0.06f).coerceIn(0.55f, 2.4f)

            try {
                val ent = BoxUtilCombatVfx.createTaperedBeamTrailFromCenter(
                    location = Vector2f(0f, 0f),
                    facing = ang,
                    length = len,
                    baseWidth = baseW,
                    tipWidth = tipW,
                    coreColor = coreColor,
                    fringeColor = fringeColor,
                    coreSprite = coreSprite,
                    fringeSprite = fringeSprite,
                    layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    full = 9999f,
                    baseAlphaMul = 0.55f,
                    tipAlphaMul = 0.10f,
                    baseEmissiveAlphaMul = 3.2f,
                    tipEmissiveAlphaMul = 1.0f,
                    mixPower = 2.6f,
                )

                // 生命周期控制：短促闪现
                ent.setGlobalTimer(0.02f, 0.05f, 0.16f)
                ent.setStateVanilla(loc, ang)

                val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_TRAIL, ent)
                if (state != 0) {
                    ent.delete()
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun spawnDirectionalNeedles(
        engine: CombatEngineAPI,
        loc: Vector2f,
        baseVel: Vector2f?,
        sizeMul: Float,
        variant: Variant,
        mode: DetonationMode,
        coreColor: Color,
        fringeColor: Color,
    ) {
        val v = baseVel
        if (v == null) return
        if ((v.x * v.x + v.y * v.y) <= 1f) return

        // 淡出自爆少一些“方向性喷射”，避免末端刷屏。
        val count = when (mode) {
            DetonationMode.FADE_OUT -> 1
            DetonationMode.HIT -> 1
            DetonationMode.SHOT_DOWN -> 2
        }
        val facing = try {
            VectorUtils.getFacing(v)
        } catch (_: Throwable) {
            return
        }

        // 朝向速度反方向：更像“被截停/解体”的喷射。
        val baseAng = facing + 180f

        val lenBase = if (variant == Variant.EVENT_HORIZON) 260f else 190f
        val wBase = if (variant == Variant.EVENT_HORIZON) 18f else 12f
        for (i in 0 until count) {
            val ang = baseAng + MathUtils.getRandomNumberInRange(-10f, 10f)
            val len = MathUtils.getRandomNumberInRange(lenBase * 0.85f, lenBase * 1.10f) * sizeMul
            val baseW = MathUtils.getRandomNumberInRange(wBase * 0.85f, wBase * 1.10f) * sizeMul
            val tipW = (baseW * 0.05f).coerceIn(0.55f, 2.2f)

            try {
                val ent = BoxUtilCombatVfx.createTaperedBeamTrailFromCenter(
                    location = Vector2f(0f, 0f),
                    facing = ang,
                    length = len,
                    baseWidth = baseW,
                    tipWidth = tipW,
                    coreColor = coreColor,
                    fringeColor = fringeColor,
                    coreSprite = coreSprite,
                    fringeSprite = fringeSprite,
                    layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    full = 9999f,
                    baseAlphaMul = 0.48f,
                    tipAlphaMul = 0.05f,
                    baseEmissiveAlphaMul = 3.6f,
                    tipEmissiveAlphaMul = 1.0f,
                    mixPower = 2.8f,
                )
                ent.setGlobalTimer(0.02f, 0.05f, if (mode == DetonationMode.FADE_OUT) 0.12f else 0.18f)
                ent.setStateVanilla(loc, ang)

                val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_TRAIL, ent)
                if (state != 0) ent.delete()
            } catch (_: Throwable) {
            }
        }
    }

    private fun spawnRingNeedles(engine: CombatEngineAPI, loc: Vector2f, sizeMul: Float, color: Color) {
        // 反馈：环刺过多会糊成一圈噪点；下调并收窄范围。
        val n = (7 * sizeMul).toInt().coerceIn(6, 14)
        val radius = 55f * sizeMul
        for (i in 0 until n) {
            val ang = (i * (360f / n.toFloat())) + MathUtils.getRandomNumberInRange(-4f, 4f)
            val len = MathUtils.getRandomNumberInRange(22f, 46f) * sizeMul
            val baseW = MathUtils.getRandomNumberInRange(4.5f, 8.5f) * sizeMul
            val tipW = (baseW * 0.18f).coerceIn(0.65f, 2.5f)

            val p = MathUtils.getPointOnCircumference(loc, radius, ang)

            try {
                val ent = BoxUtilCombatVfx.createTaperedBeamTrailFromCenter(
                    location = Vector2f(0f, 0f),
                    facing = ang,
                    length = len,
                    baseWidth = baseW,
                    tipWidth = tipW,
                    coreColor = color,
                    fringeColor = color,
                    coreSprite = coreSprite,
                    fringeSprite = fringeSprite,
                    layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                    full = 9999f,
                    baseAlphaMul = 0.22f,
                    tipAlphaMul = 0.02f,
                    baseEmissiveAlphaMul = 2.2f,
                    tipEmissiveAlphaMul = 0.65f,
                    mixPower = 2.0f,
                )

                ent.setGlobalTimer(0.02f, 0.04f, 0.13f)
                ent.setStateVanilla(p, ang)

                val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_TRAIL, ent)
                if (state != 0) {
                    ent.delete()
                }
            } catch (_: Throwable) {
            }
        }
    }

    // Kotlin 没有内置 Quintuple；用内部 data class 够用。
    private data class Quintuple<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E,
    )
}
