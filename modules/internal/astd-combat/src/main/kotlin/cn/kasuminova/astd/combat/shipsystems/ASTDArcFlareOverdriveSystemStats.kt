package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.renderer.effect.system.ArcFlareOverdriveVisualState
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import org.boxutil.define.BoxEnum
import org.boxutil.units.standard.entity.DistortionEntity
import org.magiclib.util.MagicLensFlare
import org.magiclib.util.MagicRender
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx

/**
 * astd_tactical_overdrive（战术超频）
 *
 * 5s 激活窗口，20s 冷却：
 * - 武器射速 +50%（2s 线性达峰）
 * - 弹匣恢复 +25%（2s 线性达峰）
 * - 回路接口 / 重构协议效力 +100%（2s 线性达峰）
 * - 激活期间无条件零辐能加速
 * - 开启瞬间释放中心 flare 闪爆
 */
open class ASTDArcFlareOverdriveSystemStats : BaseShipSystemScript() {

    /** 子类覆盖以指定系统所属模式。 */
    protected open val isAutomatedSystem: Boolean = false

    /** 由 apply() 在每帧更新，供 getStatusData() 读取。 */
    private var isAutomatedMode: Boolean = false

    private data class ShieldVisualSnapshot(
        val innerColor: Color,
        val ringColor: Color,
        val innerRotationRate: Float,
        val ringRotationRate: Float,
    )

    companion object {
        private const val WEAPON_ROF_BONUS = 0.50f
        private const val MAGAZINE_RELOAD_BONUS = 0.25f
        private const val MANEUVER_BONUS = 0.50f
        const val HULLMOD_BOOST_MAX = 1.0f

        private const val RAMP_DURATION = 2.0f

        private val ENGINE_HOT_COLOR = Color(255, 155, 50)

        private const val RAMP_START_KEY = "astd_overdrive_ramp_start:"
        const val HULLMOD_BOOST_KEY = "astd_overdrive_hullmod_boost:"
        private const val BURST_DONE_KEY = "astd_overdrive_burst_done:"
        private const val AFTERIMAGE_KEY = "astd_overdrive_afterimage:"
        private const val FLARE_KEY = "astd_overdrive_flare:"
        private const val SHIELD_SNAPSHOT_KEY = "astd_overdrive_shield_snapshot:"
    }

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val engine = Global.getCombatEngine() ?: return
        val ship = stats.entity as? ShipAPI ?: return
        val level = effectLevel.coerceIn(0f, 1f)
        val now = engine.getTotalElapsedTime(false)
        val shipKey = System.identityHashCode(ship).toString()

        val isAutomated = isAutomatedSystem
        isAutomatedMode = isAutomated

        val rampLevel: Float
        if (isAutomated) {
            // 自动模式：750% 时流加速 + 50% 伤害减免 + 200% 加减速 + 硬辐能锁定。
            // effectLevel 随 chargeUp(1s)/chargeDown(1s) 线性变化，恰好满足"1s 内线性达到 750%"。
            val timeMult = 1f + 6.5f * level
            stats.timeMult.modifyMult(id, timeMult)
            // 玩家操作期间：固定时间流速，周围事物相对变慢（参考时流之壳）
            val playerTimeMultId = "${id}_player_timemult"
            if (ship == engine.playerShip) {
                engine.timeMult.modifyMult(playerTimeMultId, 1f / timeMult)
            } else {
                engine.timeMult.unmodify(playerTimeMultId)
            }
            stats.armorDamageTakenMult.modifyMult(id, 1f - 0.5f * level)
            stats.hullDamageTakenMult.modifyMult(id, 1f - 0.5f * level)
            stats.ventRateMult.modifyMult(id, 0f)
            stats.acceleration.modifyMult(id, 1f + 2f * level)
            stats.deceleration.modifyMult(id, 1f + 2f * level)
            rampLevel = level
        } else {
            // 载人模式：武器射速 / 弹匣 / 机动 / 零辐能增益（2s 线性达峰）。
            val rampStartKey = "$RAMP_START_KEY$shipKey"
            if (engine.customData[rampStartKey] == null) {
                engine.customData[rampStartKey] = now
            }
            val startTime = engine.customData[rampStartKey] as? Float ?: now
            rampLevel = ((now - startTime).coerceAtLeast(0f) / RAMP_DURATION).coerceIn(0f, 1f)
            val bonusMult = level * rampLevel

            stats.ballisticRoFMult.modifyMult(id, 1f + WEAPON_ROF_BONUS * bonusMult)
            stats.energyRoFMult.modifyMult(id, 1f + WEAPON_ROF_BONUS * bonusMult)
            stats.ballisticAmmoRegenMult.modifyMult(id, 1f + MAGAZINE_RELOAD_BONUS * bonusMult)
            stats.energyAmmoRegenMult.modifyMult(id, 1f + MAGAZINE_RELOAD_BONUS * bonusMult)
            stats.missileAmmoRegenMult.modifyMult(id, 1f + MAGAZINE_RELOAD_BONUS * bonusMult)
            stats.acceleration.modifyMult(id, 1f + MANEUVER_BONUS * bonusMult)
            stats.deceleration.modifyMult(id, 1f + MANEUVER_BONUS * bonusMult)
            stats.maxTurnRate.modifyMult(id, 1f + MANEUVER_BONUS * bonusMult)
            stats.turnAcceleration.modifyMult(id, 1f + MANEUVER_BONUS * bonusMult)
            stats.zeroFluxMinimumFluxLevel.modifyFlat(id, 2f * level)

            engine.customData["$HULLMOD_BOOST_KEY$shipKey"] = HULLMOD_BOOST_MAX * bonusMult
        }

        val visualLevel = computeVisualLevel(state, level)
        ArcFlareOverdriveVisualState.setLevel(engine, ship, visualLevel)
        applyVisualFeedback(ship, engine, id, visualLevel, rampLevel, isAutomated)

        val burstKey = "$BURST_DONE_KEY$shipKey"
        if (state == ShipSystemStatsScript.State.IN && engine.customData[burstKey] != true) {
            engine.customData[burstKey] = true
            if (isAutomated) spawnActivationArcBurst(ship, engine)
            else spawnActivationArcAccent(ship, engine)
        }

        val flareKey = "$FLARE_KEY$shipKey"
        val nextFlareTime = (engine.customData[flareKey] as? Float) ?: 0f
        if (now >= nextFlareTime && (state == ShipSystemStatsScript.State.IN || state == ShipSystemStatsScript.State.ACTIVE)) {
            spawnScatteredFlare(ship, engine)
            engine.customData[flareKey] = now + MathUtils.getRandomNumberInRange(0.45f, 0.75f)
        }
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        // 载人模式增益
        stats.ballisticRoFMult.unmodify(id)
        stats.energyRoFMult.unmodify(id)
        stats.ballisticAmmoRegenMult.unmodify(id)
        stats.energyAmmoRegenMult.unmodify(id)
        stats.missileAmmoRegenMult.unmodify(id)
        stats.acceleration.unmodify(id)
        stats.deceleration.unmodify(id)
        stats.maxTurnRate.unmodify(id)
        stats.turnAcceleration.unmodify(id)
        stats.zeroFluxMinimumFluxLevel.unmodify(id)
        // 自动模式增益
        stats.timeMult.unmodify(id)
        stats.armorDamageTakenMult.unmodify(id)
        stats.hullDamageTakenMult.unmodify(id)
        stats.ventRateMult.unmodify(id)

        val engine = Global.getCombatEngine() ?: return
        // 清理玩家时间流速补偿
        engine.timeMult.unmodify("${id}_player_timemult")
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk) return

        val shipKey = System.identityHashCode(ship).toString()
        engine.customData.remove("$RAMP_START_KEY$shipKey")
        engine.customData.remove("$HULLMOD_BOOST_KEY$shipKey")
        engine.customData.remove("$BURST_DONE_KEY$shipKey")
        engine.customData.remove("$AFTERIMAGE_KEY$shipKey")
        engine.customData.remove("$FLARE_KEY$shipKey")

        restoreShieldVisual(ship, engine, shipKey)
        ArcFlareOverdriveVisualState.clear(engine, ship)
    }

    private fun computeVisualLevel(state: ShipSystemStatsScript.State, effectLevel: Float): Float = when (state) {
        ShipSystemStatsScript.State.IN -> 0.55f + 0.45f * effectLevel
        ShipSystemStatsScript.State.ACTIVE -> 1f
        ShipSystemStatsScript.State.OUT -> 0.18f + 0.62f * effectLevel
        else -> 0f
    }.coerceIn(0f, 1f)

    private fun applyVisualFeedback(ship: ShipAPI, engine: CombatEngineAPI, id: String, visualLevel: Float, rampLevel: Float, isAutomated: Boolean) {
        if (visualLevel <= 0.01f) return

        val shipKey = System.identityHashCode(ship).toString()
        applyShieldVisualFeedback(ship, engine, shipKey, visualLevel, rampLevel, id, isAutomated)

        val engineRedLevel = (visualLevel * rampLevel).coerceIn(0f, 1f)
        if (engineRedLevel > 0.05f) {
            ship.engineController.fadeToOtherColor(id, ENGINE_HOT_COLOR, ENGINE_HOT_COLOR, engineRedLevel, engineRedLevel)
            ship.engineController.extendFlame(id, 0.10f + engineRedLevel * 0.30f, 0f, 0f)
        }

        val now = engine.getTotalElapsedTime(false)
        val afterKey = "$AFTERIMAGE_KEY${System.identityHashCode(ship)}"
        val nextTime = (engine.customData[afterKey] as? Float) ?: 0f
        if (now < nextTime) return

        val afterimageColor = blendColor(
            ArcFlareOverdriveVisualState.hotFringe,
            Color(255, 155, 72, 200),
            (0.30f + visualLevel * 0.70f).coerceIn(0f, 1f),
        )

        // 使用 MagicRender.battlespace 渲染残影，呈现于舰体层下方（bultach_utils.afterimage 方案）。
        val spriteApi = try { ship.spriteAPI } catch (_: Throwable) { null }
        val hullSprite = try { Global.getSettings().getSprite(ship.hullSpec.spriteName) } catch (_: Throwable) { null }
        if (spriteApi != null && hullSprite != null) {
            val facing = ship.facing
            val width = spriteApi.width
            val height = spriteApi.height
            // 修正 sprite 中心偏移，确保残影位置与舰体严格对齐。
            val rawOx = width / 2f - spriteApi.centerX
            val rawOy = height / 2f - spriteApi.centerY
            val rad = Math.toRadians((facing - 90.0))
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            val spriteLoc = Vector2f(
                ship.location.x + cosA * rawOx - sinA * rawOy,
                ship.location.y + sinA * rawOx + cosA * rawOy,
            )
            // 自动模式：存在时间翻倍（1.1s），载人模式保持原值（0.55s）
            val afDuration = if (isAutomated) 1.1f else 0.55f
            MagicRender.battlespace(
                hullSprite,
                spriteLoc,
                Vector2f(0f, 0f),
                Vector2f(width, height),
                Vector2f(0f, 0f),
                facing - 90f,
                0f,
                afterimageColor,
                true,
                0f, 0f, 0f, 0f, 0f,
                0.04f,
                afDuration * 0.25f,
                afDuration * 0.75f,
                CombatEngineLayers.BELOW_SHIPS_LAYER,
            )

            // 系统色发光覆盖：利用舰体贴图的 alpha 分布，以系统色叠加产生发光层效果。
            val glowAlpha = (visualLevel * rampLevel * 0.18f).coerceIn(0f, 0.25f)
            if (glowAlpha > 0.02f) {
                val glowColor = Color(
                    ArcFlareOverdriveVisualState.hotFringe.red,
                    ArcFlareOverdriveVisualState.hotFringe.green,
                    ArcFlareOverdriveVisualState.hotFringe.blue,
                    (glowAlpha * 255f).toInt().coerceIn(0, 255),
                )
                MagicRender.battlespace(
                    hullSprite,
                    spriteLoc,
                    Vector2f(0f, 0f),
                    Vector2f(width, height),
                    Vector2f(0f, 0f),
                    facing - 90f,
                    0f,
                    glowColor,
                    true,
                    0f, 0f, 0f, 0f, 0f,
                    0f,
                    0.10f,
                    0.06f,
                    CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                )
            }
        }
        // 自动模式：生成速率翻倍（间隔减半）
        engine.customData[afterKey] = now + if (isAutomated) {
            (0.22f - 0.10f * visualLevel).coerceAtLeast(0.08f)
        } else {
            (0.44f - 0.20f * visualLevel).coerceAtLeast(0.16f)
        }
    }

    private fun applyShieldVisualFeedback(
        ship: ShipAPI,
        engine: CombatEngineAPI,
        shipKey: String,
        visualLevel: Float,
        rampLevel: Float,
        id: String,
        isAutomated: Boolean,
    ) {
        val shield = ship.shield ?: return
        val snapshotKey = "$SHIELD_SNAPSHOT_KEY$shipKey"
        val snapshot = (engine.customData[snapshotKey] as? ShieldVisualSnapshot) ?: ShieldVisualSnapshot(
            innerColor = shield.innerColor,
            ringColor = shield.ringColor,
            innerRotationRate = shield.innerRotationRate,
            ringRotationRate = shield.ringRotationRate,
        ).also {
            engine.customData[snapshotKey] = it
        }

        val shieldLevel = (visualLevel * (0.40f + 0.60f * rampLevel)).coerceIn(0f, 1f)
        val pulse = 0.80f + 0.20f * kotlin.math.sin(engine.getTotalElapsedTime(false) * (3.0f + shieldLevel * 2.5f))
        val innerColor = blendColor(snapshot.innerColor, Color(255, 148, 50, (118f + 32f * pulse).toInt()), shieldLevel)
        val ringColor = blendColor(snapshot.ringColor, Color(255, 120, 38, (220f + 20f * pulse).toInt()), shieldLevel)

        shield.setInnerColor(innerColor)
        shield.setRingColor(ringColor)
        shield.setInnerRotationRate(snapshot.innerRotationRate * (1f + 0.75f * shieldLevel))
        shield.setRingRotationRate(snapshot.ringRotationRate * (1f + 0.62f * shieldLevel))

        if (isAutomated) {
            // 自动模式：橙色发光描边（与护盾颜色保持一致）
            val fringeBase = ArcFlareOverdriveVisualState.hotFringe
            val coreBase = ArcFlareOverdriveVisualState.hotCore
            val hotJitter = Color(fringeBase.red, fringeBase.green, fringeBase.blue, 200)
            val hotUnder = Color(coreBase.red, coreBase.green, coreBase.blue, 140)
            val jitterIntensity = (0.3f + 0.7f * shieldLevel).coerceIn(0f, 1f)
            ship.setJitterShields(true)
            ship.setJitter(id, hotJitter, 0.05f + 0.06f * shieldLevel, 3, 0f, 3f + 5f * shieldLevel)
            ship.setJitterUnder(id, hotUnder, jitterIntensity, 22, 0f, 7f + 8f * shieldLevel)
        } else {
            val hotBase = ArcFlareOverdriveVisualState.hotFringe
            val jitterColor = Color(hotBase.red, hotBase.green, hotBase.blue, 255)
            ship.setJitterShields(true)
            ship.setJitter(id, jitterColor, 0.04f + 0.05f * shieldLevel, 2, 0f, 3f + 4f * shieldLevel)
        }
    }

    private fun restoreShieldVisual(ship: ShipAPI, engine: CombatEngineAPI, shipKey: String) {
        val snapshotKey = "$SHIELD_SNAPSHOT_KEY$shipKey"
        val snapshot = engine.customData.remove(snapshotKey) as? ShieldVisualSnapshot ?: return
        val shield = ship.shield ?: return
        shield.setInnerColor(snapshot.innerColor)
        shield.setRingColor(snapshot.ringColor)
        shield.setInnerRotationRate(snapshot.innerRotationRate)
        shield.setRingRotationRate(snapshot.ringRotationRate)
        ship.setJitterShields(false)
    }

    private fun spawnScatteredFlare(ship: ShipAPI, engine: CombatEngineAPI) {
        val pos = MathUtils.getRandomPointInCircle(ship.location, ship.collisionRadius * 0.8f)
        val fringe = Color(255, 175, 85, 160)
        val core = Color(255, 248, 235, 190)
        val length = MathUtils.getRandomNumberInRange(60f, 100f)
        try {
            MagicLensFlare.createSharpFlare(engine, ship, pos, 5f, length, 0f, fringe, core)
        } catch (_: Throwable) {
        }
    }

    private fun spawnActivationArcAccent(ship: ShipAPI, engine: CombatEngineAPI) {
        val arcFringe = Color(255, 168, 72, 180)
        val arcCore = Color(255, 240, 225, 132)
        repeat(2) {
            val from = hullBoundaryPoint(ship)
            val outAngle = Misc.getAngleInDegrees(ship.location, from) + MathUtils.getRandomNumberInRange(-30f, 30f)
            val outDist = ship.collisionRadius * MathUtils.getRandomNumberInRange(0.26f, 0.42f)
            val rad = Math.toRadians(outAngle.toDouble())
            val to = Vector2f(
                from.x + (cos(rad) * outDist).toFloat(),
                from.y + (sin(rad) * outDist).toFloat(),
            )
            val params = EmpArcEntityAPI.EmpArcParams().apply {
                segmentLengthMult = 5f
                zigZagReductionFactor = 0.12f
                fadeOutDist = 35f
                minFadeOutMult = 6f
                flickerRateMult = 0.4f
            }
            val thickness = 6f + MathUtils.getRandomNumberInRange(0f, 2f)
            try {
                val arc = engine.spawnEmpArcVisual(from, ship, to, ship as CombatEntityAPI, thickness, arcFringe, arcCore, params)
                arc.setCoreWidthOverride(thickness * 0.5f)
                arc.setSingleFlickerMode(true)
                arc.setRenderGlowAtStart(false)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 自动模式激活爆发：大量闪电从船体边缘向外发散（RiftLightning 风格）。
     */
    private fun spawnActivationArcBurst(ship: ShipAPI, engine: CombatEngineAPI) {
        val arcFringe = ArcFlareOverdriveVisualState.coldFringe
        val arcCore = Color(220, 248, 255, 220)
        val count = MathUtils.getRandomNumberInRange(10f, 14f).toInt()
        repeat(count) {
            val from = hullBoundaryPoint(ship)
            val outAngle = Misc.getAngleInDegrees(ship.location, from) + MathUtils.getRandomNumberInRange(-25f, 25f)
            val outDist = ship.collisionRadius * MathUtils.getRandomNumberInRange(0.28f, 0.58f)
            val rad = Math.toRadians(outAngle.toDouble())
            val to = Vector2f(
                from.x + (cos(rad) * outDist).toFloat(),
                from.y + (sin(rad) * outDist).toFloat(),
            )
            val params = EmpArcEntityAPI.EmpArcParams().apply {
                segmentLengthMult = 6f
                zigZagReductionFactor = 0.12f
                fadeOutDist = 40f
                minFadeOutMult = 8f
                flickerRateMult = 0.35f
            }
            val thickness = 4f + MathUtils.getRandomNumberInRange(0f, 4f)
            try {
                val arc = engine.spawnEmpArcVisual(
                    from, ship, to, ship as CombatEntityAPI,
                    thickness, arcFringe, arcCore, params,
                )
                arc.setCoreWidthOverride(thickness * 0.5f)
                arc.setSingleFlickerMode(true)
                arc.setRenderGlowAtStart(false)
                arc.setFadedOutAtStart(true)
            } catch (_: Throwable) {}
        }
        repeat(3) {
            val pos = MathUtils.getRandomPointInCircle(ship.location, ship.collisionRadius * 0.55f)
            val length = MathUtils.getRandomNumberInRange(75f, 120f)
            try {
                MagicLensFlare.createSharpFlare(
                    engine, ship, pos, 6f, length, 0f,
                    ArcFlareOverdriveVisualState.coldFringe, ArcFlareOverdriveVisualState.coldCore,
                )
            } catch (_: Throwable) {}
        }
        spawnActivationDistortion(ship, engine)
    }

    /**
     * 自动模式激活：以舰体为中心生成一次向外扩散的扭曲环（BoxUtil DistortionEntity）。
     */
    private fun spawnActivationDistortion(ship: ShipAPI, engine: CombatEngineAPI) {
        try {
            val r = ship.collisionRadius
            val e = DistortionEntity()
            // 生命周期：快速淡入 -> 短暂满值 -> 向外扩散淡出
            e.setGlobalTimer(0.06f, 0.05f, 0.30f)
            // 尺寸使用 half-size（BoxUtil 规范）
            e.setSizeIn(r * 0.15f, r * 0.15f)
            e.setSizeFull(r * 0.70f, r * 0.70f)
            e.setSizeOut(r * 1.90f, r * 1.90f)
            // 扭曲强度：淡入微弱，满值明显，淡出消散
            e.setPowerIn(0.0f)
            e.setPowerFull(0.60f)
            e.setPowerOut(0.0f)
            // 内环比例：形成中空环状，向外扩散时内环增大（更像冲击波）
            e.setInnerFull(0.20f, 0.20f)
            e.setInnerOut(0.55f, 0.55f)
            e.setLocation(ship.location)
            val state = BoxUtilCombatVfx.addEntity(engine, BoxEnum.ENTITY_DISTORTION, e)
            if (state != 0) e.delete()
        } catch (_: Throwable) {}
    }

    private fun hullBoundaryPoint(ship: ShipAPI): Vector2f {
        val bounds = try { ship.exactBounds } catch (_: Throwable) { null }
        if (bounds != null) {
            try {
                bounds.update(ship.location, ship.facing)
                val segments = bounds.segments
                if (segments.isNotEmpty()) {
                    val seg = segments[MathUtils.getRandomNumberInRange(0, segments.size - 1)]
                    val t = MathUtils.getRandomNumberInRange(0f, 1f)
                    return Vector2f(
                        seg.p1.x + (seg.p2.x - seg.p1.x) * t,
                        seg.p1.y + (seg.p2.y - seg.p1.y) * t,
                    )
                }
            } catch (_: Throwable) {}
        }
        // Fallback：无 ExactBounds 时使用碰撞半径近似
        val angle = MathUtils.getRandomNumberInRange(0f, 360f)
        val radius = ship.collisionRadius * 0.90f
        val rad = Math.toRadians(angle.toDouble())
        return Vector2f(
            ship.location.x + (cos(rad) * radius).toFloat(),
            ship.location.y + (sin(rad) * radius).toFloat(),
        )
    }

    override fun getStatusData(
        index: Int,
        state: ShipSystemStatsScript.State,
        effectLevel: Float
    ): ShipSystemStatsScript.StatusData? {
        if (index != 0) return null
        val prefix = if (isAutomatedMode) "automated" else "crewed"
        val suffix = when (state) {
            ShipSystemStatsScript.State.IN     -> "in"
            ShipSystemStatsScript.State.ACTIVE -> "active"
            ShipSystemStatsScript.State.OUT    -> "out"
            else -> return null
        }
        return ShipSystemStatsScript.StatusData(
            I18n[I18n.Categories.MOD, "system.arc_flare_overdrive.status.$prefix.$suffix"],
            false,
        )
    }

    private fun blendColor(from: Color, to: Color, level: Float): Color {
        val t = level.coerceIn(0f, 1f)
        fun lerp(a: Int, b: Int): Int = (a + (b - a) * t).toInt().coerceIn(0, 255)
        return Color(lerp(from.red, to.red), lerp(from.green, to.green), lerp(from.blue, to.blue), lerp(from.alpha, to.alpha))
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
