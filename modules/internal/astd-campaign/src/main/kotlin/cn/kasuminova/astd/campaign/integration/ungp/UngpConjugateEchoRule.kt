package cn.kasuminova.astd.campaign.integration.ungp

import cn.kasuminova.astd.renderer.boxutil.pool.PooledCombatVfx
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.EmpArcEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import ungp.api.rules.tags.UNGP_CombatTag
import ungp.scripts.campaign.specialist.UNGP_SpecialistSettings
import ungp.scripts.utils.UNGPUtils
import java.awt.Color

/**
 * UNGP 规则「共轭回响」（负面）：敌方舰船（不含战机与模块）被摧毁时，
 * 半径内的其他敌方舰船获得战斗内永久的辐能容量/耗散/伤害减免提升（按被摧毁舰船尺寸取档，有总上限），
 * 并获得一层 10 秒线性渐弱的射速与伤害加成，同时在 3 秒内耗散一定比例的最大辐能（优先软辐能）。
 *
 * 设计文档：docs/design/integrations/ungp.md。
 */
class UngpConjugateEchoRule : UngpRuleEffect(), UNGP_CombatTag {

    private var radius: Float = 500f
    private var permFrigate: Float = 0.01f
    private var permDestroyer: Float = 0.015f
    private var permCruiser: Float = 0.02f
    private var permCapital: Float = 0.025f
    private var permCap: Float = 0.10f
    private var tempPct: Float = 0.04f
    private var fluxPct: Float = 0.04f

    override fun updateDifficultyCache(difficulty: UNGP_SpecialistSettings.Difficulty) {
        radius = getValueByDifficulty(0, difficulty)
        permFrigate = getValueByDifficulty(1, difficulty)
        permDestroyer = getValueByDifficulty(2, difficulty)
        permCruiser = getValueByDifficulty(3, difficulty)
        permCapital = getValueByDifficulty(4, difficulty)
        permCap = getValueByDifficulty(5, difficulty)
        tempPct = getValueByDifficulty(6, difficulty)
        fluxPct = getValueByDifficulty(9, difficulty)
    }

    override fun getValueByDifficulty(index: Int, difficulty: UNGP_SpecialistSettings.Difficulty): Float = when (index) {
        0 -> difficultyValue(difficulty, 500f, 600f, 700f, 800f)
        // 永久增益按被摧毁舰船尺寸取档：护卫舰/驱逐舰/巡洋舰/主力舰
        1 -> difficultyValue(difficulty, 0.01f, 0.02f, 0.03f, 0.04f)
        2 -> difficultyValue(difficulty, 0.015f, 0.03f, 0.045f, 0.06f)
        3 -> difficultyValue(difficulty, 0.02f, 0.04f, 0.06f, 0.08f)
        4 -> difficultyValue(difficulty, 0.025f, 0.05f, 0.075f, 0.10f)
        5 -> difficultyValue(difficulty, 0.10f, 0.20f, 0.30f, 0.40f)
        6 -> difficultyValue(difficulty, 0.04f, 0.06f, 0.08f, 0.10f)
        7 -> TEMP_DURATION
        8 -> FLUX_DRAIN_DURATION
        9 -> difficultyValue(difficulty, 0.04f, 0.06f, 0.08f, 0.10f)
        else -> 0f
    }

    override fun getDescriptionParams(index: Int, difficulty: UNGP_SpecialistSettings.Difficulty): String? = when (index) {
        0 -> formatPlain(getValueByDifficulty(0, difficulty))
        1 -> formatFraction(getValueByDifficulty(1, difficulty))
        2 -> formatFraction(getValueByDifficulty(2, difficulty))
        3 -> formatFraction(getValueByDifficulty(3, difficulty))
        4 -> formatFraction(getValueByDifficulty(4, difficulty))
        5 -> formatFraction(getValueByDifficulty(5, difficulty))
        6 -> formatFraction(getValueByDifficulty(6, difficulty))
        7 -> formatPlain(getValueByDifficulty(7, difficulty))
        8 -> formatPlain(getValueByDifficulty(8, difficulty))
        9 -> formatFraction(getValueByDifficulty(9, difficulty))
        else -> null
    }

    override fun advanceInCombat(engine: CombatEngineAPI, amount: Float) {
        val tracked = trackedShips(engine)

        // 增量登记：UNGP 的 init 阶段编队尚未部署，且战斗中存在增援，必须持续补录
        for (ship in engine.ships) {
            if (isValidTrigger(ship) && ship.isAlive && !ship.isHulk && !tracked.containsKey(ship.id)) {
                tracked[ship.id] = ship
            }
        }

        val iterator = tracked.entries.iterator()
        while (iterator.hasNext()) {
            val ship = iterator.next().value
            if (!ship.isAlive || ship.isHulk) {
                iterator.remove()
                onShipDestroyed(engine, ship)
            }
        }
    }

    override fun applyEnemyShipInCombat(amount: Float, ship: ShipAPI) = Unit

    override fun applyPlayerShipInCombat(amount: Float, engine: CombatEngineAPI, ship: ShipAPI) = Unit

    private fun onShipDestroyed(engine: CombatEngineAPI, dead: ShipAPI) {
        val permGain = permOf(dead.hullSize)

        for (target in engine.ships) {
            if (!target.isAlive || target.isHulk || target.isFighter) continue
            if (target.owner != dead.owner) continue
            if (MathUtils.getDistance(dead.location, target.location) > radius) continue

            val listener = target.getListeners(EchoListener::class.java).firstOrNull()
                ?: EchoListener(target, permCap, tempPct, fluxPct).also { target.addListener(it) }
            listener.onEcho(permGain)
            spawnVfx(engine, dead.location, target)
        }
    }

    private fun permOf(size: ShipAPI.HullSize): Float = when (size) {
        ShipAPI.HullSize.FRIGATE -> permFrigate
        ShipAPI.HullSize.DESTROYER -> permDestroyer
        ShipAPI.HullSize.CRUISER -> permCruiser
        else -> permCapital
    }

    private fun spawnVfx(engine: CombatEngineAPI, from: Vector2f, target: ShipAPI) {
        val arcCount = MathUtils.getRandomNumberInRange(3, 6)
        repeat(arcCount) {
            val thickness = MathUtils.getRandomNumberInRange(8f, 16f)
            val arc = engine.spawnEmpArcVisual(
                from, null, Vector2f(target.location), target,
                thickness, ARC_FRINGE, ARC_CORE, arcParams(),
            )
            arc.setSingleFlickerMode(true)
        }

        repeat(JITTER_COUNT) {
            val offset = MathUtils.getRandomPointInCircle(ZERO, target.collisionRadius)
            PooledCombatVfx.spawnSprite(
                engine, JITTER_POOL,
                target.location.x + offset.x, target.location.y + offset.y,
                velX = MathUtils.getRandomNumberInRange(-30f, 30f),
                velY = MathUtils.getRandomNumberInRange(-30f, 30f),
                scaleX = MathUtils.getRandomNumberInRange(4f, 7f),
                scaleY = MathUtils.getRandomNumberInRange(4f, 7f),
                color = JITTER_COLOR, emissiveColor = JITTER_EMISSIVE,
                fadeIn = 0.05f, full = 0.20f, fadeOut = 0.75f,
            )
        }
    }

    /**
     * 单舰回响监听器：维护永久增益（封顶）、渐弱临时增益与辐能耗散队列，
     * 每帧以固定 buffID 重放修改器（舰船 MutableStats 每帧重置，不会产生累积）。
     */
    class EchoListener(
        private val ship: ShipAPI,
        private val permCap: Float,
        private val tempPct: Float,
        private val fluxPct: Float,
    ) : AdvanceableListener {

        private var permanent: Float = 0f
        private val tempLayers = ArrayList<TempLayer>()
        private val fluxDrains = ArrayList<FluxDrain>()

        fun onEcho(permGain: Float) {
            permanent = (permanent + permGain).coerceAtMost(permCap)
            tempLayers.add(TempLayer(TEMP_DURATION))
            fluxDrains.add(FluxDrain(fluxPct * ship.mutableStats.fluxCapacity.modifiedValue, FLUX_DRAIN_DURATION))
        }

        override fun advance(amount: Float) {
            if (!ship.isAlive || ship.isHulk) {
                ship.removeListener(this)
                return
            }
            if (amount <= 0f) return

            val stats = ship.mutableStats
            if (permanent > 0f) {
                stats.fluxCapacity.modifyPercent(STAT_ID, permanent * 100f)
                stats.fluxDissipation.modifyPercent(STAT_ID, permanent * 100f)
                val takenMult = 1f - permanent
                stats.hullDamageTakenMult.modifyMult(STAT_ID, takenMult)
                stats.armorDamageTakenMult.modifyMult(STAT_ID, takenMult)
                stats.shieldDamageTakenMult.modifyMult(STAT_ID, takenMult)
            }

            var tempSum = 0f
            val tempIterator = tempLayers.iterator()
            while (tempIterator.hasNext()) {
                val layer = tempIterator.next()
                layer.remaining -= amount
                if (layer.remaining <= 0f) {
                    tempIterator.remove()
                } else {
                    tempSum += tempPct * (layer.remaining / TEMP_DURATION)
                }
            }
            if (tempSum > 0f) {
                val pct = tempSum * 100f
                stats.ballisticWeaponDamageMult.modifyPercent(STAT_ID, pct)
                stats.energyWeaponDamageMult.modifyPercent(STAT_ID, pct)
                stats.missileWeaponDamageMult.modifyPercent(STAT_ID, pct)
                stats.ballisticRoFMult.modifyMult(STAT_ID, 1f + tempSum)
                stats.energyRoFMult.modifyMult(STAT_ID, 1f + tempSum)
                stats.missileRoFMult.modifyMult(STAT_ID, 1f + tempSum)
            }

            val drainIterator = fluxDrains.iterator()
            while (drainIterator.hasNext()) {
                val drain = drainIterator.next()
                val step = (drain.rate * amount).coerceAtMost(drain.remaining)
                drain.remaining -= step
                if (step > 0f) dissipateFlux(step)
                if (drain.remaining <= 0f) drainIterator.remove()
            }
        }

        /** 优先耗散软辐能，不足部分扣硬辐能。 */
        private fun dissipateFlux(amount: Float) {
            val tracker = ship.fluxTracker
            val hard = tracker.hardFlux
            val soft = (tracker.currFlux - hard).coerceAtLeast(0f)
            val fromSoft = amount.coerceAtMost(soft)
            if (fromSoft > 0f) tracker.decreaseFlux(fromSoft)
            val fromHard = amount - fromSoft
            if (fromHard > 0f) {
                tracker.setHardFlux((hard - fromHard).coerceAtLeast(0f))
                tracker.setCurrFlux((tracker.currFlux - fromHard).coerceAtLeast(tracker.hardFlux))
            }
        }

        private class TempLayer(var remaining: Float)

        private class FluxDrain(var remaining: Float, totalDuration: Float) {
            val rate: Float = remaining / totalDuration
        }

        companion object {
            private const val STAT_ID = "astd_ungp_conjugate_echo"
        }
    }

    companion object {
        private const val TRACKED_KEY = "astd_ungp_conjugate_echo_tracked"
        private const val JITTER_COUNT = 5
        private const val TEMP_DURATION = 10f
        private const val FLUX_DRAIN_DURATION = 3f

        private val ARC_FRINGE = Color(150, 80, 255, 200)
        private val ARC_CORE = Color(230, 210, 255, 230)
        private val JITTER_COLOR = Color(186, 120, 255, 140)
        private val JITTER_EMISSIVE = Color(232, 205, 255, 200)

        private val JITTER_POOL = PooledCombatVfx.SpritePoolKey(
            spritePath = "graphics/fx/particlealpha32sq.png",
            layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
        )

        private val ZERO = Vector2f(0f, 0f)

        @Suppress("UNCHECKED_CAST")
        private fun trackedShips(engine: CombatEngineAPI): MutableMap<String, ShipAPI> =
            engine.customData.getOrPut(TRACKED_KEY) { HashMap<String, ShipAPI>() } as MutableMap<String, ShipAPI>

        private fun isValidTrigger(ship: ShipAPI): Boolean =
            UNGPUtils.isEnemyShip(ship) && !ship.isFighter && !ship.isDrone && !ship.isStationModule

        private fun arcParams(): EmpArcEntityAPI.EmpArcParams {
            val params = EmpArcEntityAPI.EmpArcParams()
            params.segmentLengthMult = 5f
            params.zigZagReductionFactor = 0.12f
            params.fadeOutDist = 72f
            params.minFadeOutMult = 12f
            params.flickerRateMult = 0.30f
            return params
        }
    }
}
