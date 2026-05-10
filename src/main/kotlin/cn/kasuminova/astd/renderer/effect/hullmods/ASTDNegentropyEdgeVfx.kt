package cn.kasuminova.astd.renderer.effect.hullmods

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.entity.DistortionEntity
import org.boxutil.units.standard.entity.SpriteEntity
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/** 断熵弧刃专属战斗视觉。优先使用 BoxUtil Trail/Distortion。 */
object ASTDNegentropyEdgeVfx {
    private const val CORE_SPRITE = "graphics/fx/beamcoreb.png"
    private const val FRINGE_SPRITE = "graphics/fx/beamfringeb.png"
    private const val DUST_SPRITE = "graphics/fx/glow64.png"

    private val core = Color(180, 232, 255, 220)
    private val fringe = Color(92, 178, 255, 190)
    private val hot = Color(245, 252, 255, 235)
    private val pursuitCore = Color(255, 220, 150, 230)
    private val pursuitFringe = Color(255, 142, 54, 205)
    private val pursuitHot = Color(255, 248, 225, 240)

    fun spawnParticleBirth(engine: CombatEngineAPI, center: Vector2f, level: Float) {
        BoxUtilCombatVfx.ensureReady(engine)
        val s = level.coerceIn(0f, 1f)
        spawnDistortion(engine, center, 18f + 22f * s, 0.18f + 0.22f * s, 0.30f)
        val coreSprite = getSprite(CORE_SPRITE) ?: return
        val fringeSprite = getSprite(FRINGE_SPRITE) ?: return
        val rays = 4 + (s * 3f).toInt()
        for (i in 0 until rays) {
            val facing = 360f * i / rays + MathUtils.getRandomNumberInRange(-18f, 18f)
            BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
                engine = engine,
                location = Vector2f(center),
                facing = facing,
                length = MathUtils.getRandomNumberInRange(42f, 82f) * (0.7f + 0.7f * s),
                baseWidth = 5.5f + 5f * s,
                tipWidth = 0.8f,
                coreColor = hot,
                fringeColor = fringe,
                coreSprite = coreSprite,
                fringeSprite = fringeSprite,
                layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                full = 0.18f,
                baseAlphaMul = 0.58f,
                tipAlphaMul = 0.08f,
                baseEmissiveAlphaMul = 2.8f,
                tipEmissiveAlphaMul = 0.8f,
                mixPower = 2.6f,
            )?.setGlobalTimer(0.02f, 0.12f, 0.22f)
        }
    }

    fun spawnDustMote(engine: CombatEngineAPI, loc: Vector2f, level: Float) {
        BoxUtilCombatVfx.ensureReady(engine)
        val s = level.coerceIn(0f, 1f)
        spawnDustMoteSprite(
            engine = engine,
            loc = loc,
            facing = MathUtils.getRandomNumberInRange(0f, 360f),
            radius = 2.2f + 1.2f * s,
            fadeIn = 0.01f,
            full = 0.04f,
            fadeOut = 0.12f,
            alpha = 0.34f + 0.16f * s,
            color = Color(120, 220, 255),
            emissiveColor = Color(210, 248, 255),
        )
    }

    fun spawnPursuitDustMote(engine: CombatEngineAPI, loc: Vector2f, level: Float) {
        BoxUtilCombatVfx.ensureReady(engine)
        val s = level.coerceIn(0f, 1f)
        spawnDustMoteSprite(
            engine = engine,
            loc = loc,
            facing = MathUtils.getRandomNumberInRange(0f, 360f),
            radius = 2.2f + 1.2f * s,
            fadeIn = 0.01f,
            full = 0.04f,
            fadeOut = 0.12f,
            alpha = 0.34f + 0.16f * s,
            color = pursuitFringe,
            emissiveColor = pursuitHot,
        )
    }

    fun spawnTrackingDustMote(engine: CombatEngineAPI, loc: Vector2f, facing: Float, level: Float) {
        BoxUtilCombatVfx.ensureReady(engine)
        val s = level.coerceIn(0f, 1f)
        spawnDustMoteSprite(
            engine = engine,
            loc = loc,
            facing = facing,
            radius = 2.6f + 1.6f * s,
            fadeIn = 0.0f,
            full = 0.025f,
            fadeOut = 0.055f,
            alpha = 0.45f + 0.18f * s,
            color = Color(120, 220, 255),
            emissiveColor = Color(210, 248, 255),
        )
    }

    fun spawnPursuitTrackingDustMote(engine: CombatEngineAPI, loc: Vector2f, facing: Float, level: Float) {
        BoxUtilCombatVfx.ensureReady(engine)
        val s = level.coerceIn(0f, 1f)
        spawnDustMoteSprite(
            engine = engine,
            loc = loc,
            facing = facing,
            radius = 2.6f + 1.6f * s,
            fadeIn = 0.0f,
            full = 0.025f,
            fadeOut = 0.055f,
            alpha = 0.45f + 0.18f * s,
            color = pursuitFringe,
            emissiveColor = pursuitHot,
        )
    }

    fun spawnDustTrail(engine: CombatEngineAPI, from: Vector2f, to: Vector2f, level: Float) {
        BoxUtilCombatVfx.ensureReady(engine)
        val delta = Vector2f.sub(to, from, null)
        val len = delta.length()
        if (len < 1.5f) return
        val coreSprite = getSprite(CORE_SPRITE) ?: return
        val fringeSprite = getSprite(FRINGE_SPRITE) ?: return
        val s = level.coerceIn(0f, 1f)
        BoxUtilCombatVfx.createAndAddTaperedBeamTrail(
            engine = engine,
            location = Vector2f(to),
            facing = VectorUtils.getFacing(delta) + 180f,
            length = len.coerceIn(10f, 58f) * (1.05f + 0.35f * s),
            tailWidth = 0.35f + 0.35f * s,
            headWidth = 2.4f + 2.2f * s,
            coreColor = core,
            fringeColor = fringe,
            coreSprite = coreSprite,
            fringeSprite = fringeSprite,
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
            full = 0.10f,
            tailAlphaMul = 0.04f,
            headAlphaMul = 0.28f + 0.18f * s,
            tailEmissiveAlphaMul = 0.25f,
            headEmissiveAlphaMul = 1.8f + 0.9f * s,
            mixPower = 2.7f,
        )?.setGlobalTimer(0.01f, 0.06f, 0.22f)
    }

    fun spawnPursuitDustTrail(engine: CombatEngineAPI, from: Vector2f, to: Vector2f, level: Float) {
        BoxUtilCombatVfx.ensureReady(engine)
        val delta = Vector2f.sub(to, from, null)
        val len = delta.length()
        if (len < 1.5f) return
        val coreSprite = getSprite(CORE_SPRITE) ?: return
        val fringeSprite = getSprite(FRINGE_SPRITE) ?: return
        val s = level.coerceIn(0f, 1f)
        BoxUtilCombatVfx.createAndAddTaperedBeamTrail(
            engine = engine,
            location = Vector2f(to),
            facing = VectorUtils.getFacing(delta) + 180f,
            length = len.coerceIn(10f, 58f) * (1.05f + 0.35f * s),
            tailWidth = 0.35f + 0.35f * s,
            headWidth = 2.4f + 2.2f * s,
            coreColor = pursuitCore,
            fringeColor = pursuitFringe,
            coreSprite = coreSprite,
            fringeSprite = fringeSprite,
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
            full = 0.10f,
            tailAlphaMul = 0.04f,
            headAlphaMul = 0.28f + 0.18f * s,
            tailEmissiveAlphaMul = 0.25f,
            headEmissiveAlphaMul = 1.8f + 0.9f * s,
            mixPower = 2.7f,
        )?.setGlobalTimer(0.01f, 0.06f, 0.22f)
    }

    private fun spawnDustMoteSprite(
        engine: CombatEngineAPI,
        loc: Vector2f,
        facing: Float,
        radius: Float,
        fadeIn: Float,
        full: Float,
        fadeOut: Float,
        alpha: Float,
        color: Color,
        emissiveColor: Color,
    ) {
        val sprite = getSprite(DUST_SPRITE) ?: return
        try {
            val entity = SpriteEntity(sprite)
            entity.setAdditiveBlend()
            entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
            entity.setStateVanilla(Vector2f(loc), facing, Vector2f(radius, radius))
            entity.setGlobalTimer(fadeIn, full, fadeOut)
            entity.materialData.setDiffuse(sprite)
            entity.materialData.setEmissive(sprite)
            entity.materialData.setColor(Color(color.red, color.green, color.blue, (255f * alpha * 0.22f).toInt().coerceIn(0, 255)))
            entity.materialData.setEmissiveColor(Color(emissiveColor.red, emissiveColor.green, emissiveColor.blue, (255f * alpha * 0.70f).toInt().coerceIn(0, 255)))
            entity.materialData.setAlphaToEmissive(0f)
            entity.materialData.setColorToEmissive(0f)
            entity.materialData.setGlowPower(0.75f)
            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_SPRITE, entity)
            if (state != 0) entity.delete()
        } catch (_: Throwable) {
        }
    }

    fun spawnCollapseStrike(engine: CombatEngineAPI, from: Vector2f, to: Vector2f, level: Float) {
        BoxUtilCombatVfx.ensureReady(engine)
        val delta = Vector2f.sub(to, from, null)
        val len = delta.length()
        if (len < 8f) return
        val facing = VectorUtils.getFacing(delta)
        val coreSprite = getSprite(CORE_SPRITE) ?: return
        val fringeSprite = getSprite(FRINGE_SPRITE) ?: return
        val s = level.coerceIn(0f, 1f)
        BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
            engine = engine,
            location = Vector2f(from),
            facing = facing,
            length = len,
            baseWidth = 7f + 8f * s,
            tipWidth = 1.0f + 2.0f * s,
            coreColor = hot,
            fringeColor = core,
            coreSprite = coreSprite,
            fringeSprite = fringeSprite,
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
            full = 0.12f,
            baseAlphaMul = 0.34f,
            tipAlphaMul = 0.26f,
            baseEmissiveAlphaMul = 1.9f,
            tipEmissiveAlphaMul = 1.6f,
            mixPower = 2.9f,
        )?.setGlobalTimer(0.02f, 0.10f, 0.20f)
        spawnDistortion(engine, to, 22f + 30f * s, 0.22f + 0.32f * s, 0.26f)
    }

    fun spawnDefensiveArcImpact(engine: CombatEngineAPI, loc: Vector2f, level: Float) {
        BoxUtilCombatVfx.ensureReady(engine)
        val s = level.coerceIn(0f, 1f)
        spawnDistortion(engine, loc, 12f + 16f * s, 0.08f + 0.12f * s, 0.12f)
        spawnDustMote(engine, loc, 0.45f + 0.45f * s)
    }

    fun spawnLargeShiftDistortion(engine: CombatEngineAPI, loc: Vector2f, level: Float) {
        BoxUtilCombatVfx.ensureReady(engine)
        val s = level.coerceIn(0f, 1f)
        spawnDistortion(engine, loc, 52f + 48f * s, 0.23f + 0.21f * s, 0.36f + 0.12f * s)
    }

    fun spawnShift(engine: CombatEngineAPI, from: Vector2f, to: Vector2f, level: Float) {
        BoxUtilCombatVfx.ensureReady(engine)
        val delta = Vector2f.sub(to, from, null)
        val len = delta.length()
        if (len < 8f) return
        val facing = VectorUtils.getFacing(delta)
        val coreSprite = getSprite(CORE_SPRITE) ?: return
        val fringeSprite = getSprite(FRINGE_SPRITE) ?: return
        val s = level.coerceIn(0f, 1f)
        BoxUtilCombatVfx.createAndAddTaperedBeamTrailFromCenter(
            engine = engine,
            location = Vector2f(from),
            facing = facing,
            length = len,
            baseWidth = 36f + 22f * s,
            tipWidth = 8f + 8f * s,
            coreColor = Color(210, 245, 255, 150),
            fringeColor = Color(80, 180, 255, 125),
            coreSprite = coreSprite,
            fringeSprite = fringeSprite,
            layer = CombatEngineLayers.BELOW_SHIPS_LAYER,
            full = 0.16f,
            baseAlphaMul = 0.35f,
            tipAlphaMul = 0.12f,
            baseEmissiveAlphaMul = 2.3f,
            tipEmissiveAlphaMul = 0.8f,
            mixPower = 2.1f,
        )?.setGlobalTimer(0.01f, 0.12f, 0.32f)
        spawnDistortion(engine, from, 60f + 54f * s, 0.35f + 0.35f * s, 0.35f)
        spawnDistortion(engine, to, 72f + 62f * s, 0.40f + 0.40f * s, 0.42f)
    }

    private fun spawnDistortion(engine: CombatEngineAPI, loc: Vector2f, size: Float, power: Float, full: Float) {
        try {
            val e = DistortionEntity()
            e.setGlobalTimer(0.04f, full, 0.28f)
            e.setLocation(Vector2f(loc))
            e.setInnerFull(0.2f, 0.2f)
            e.setInnerHardness(0.78f)
            e.setRingHardness(0.52f)
            e.setSizeIn(size * 0.35f, size * 0.35f)
            e.setSizeFull(size, size)
            e.setSizeOut(size * 0.25f, size * 0.25f)
            e.setPowerIn(power * 0.55f)
            e.setPowerFull(power)
            e.setPowerOut(power * 0.12f)
            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_DISTORTION, e)
            if (state != 0) e.delete()
        } catch (_: Throwable) {
        }
    }

    private fun getSprite(path: String) = try {
        Global.getSettings().getSprite(path)
    } catch (_: Throwable) {
        null
    }
}