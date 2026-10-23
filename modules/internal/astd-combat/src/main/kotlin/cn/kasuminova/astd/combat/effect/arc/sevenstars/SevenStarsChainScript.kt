package cn.kasuminova.astd.combat.effect.arc.sevenstars

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lwjgl.util.vector.Vector2f

// BALLISTIC 弹体寿命由引擎按射程/弹速推导（weapon_data 的 flightTime 列仅 MISSILE 生效），
// 弹体存活不到连跳结束，首发结算后即移除弹体，连跳锚点退化为纯位置。
class SevenStarsChainScript(
    private val anchor: Vector2f,
    private val source: ShipAPI?,
    private val tuning: SevenStarsDifficulty.SevenStarsTuning,
    private val panelDamage: Float,
    private val jumpRange: Float,
    private val owner: Int,
    initialJumps: Int,
    initialState: State,
) : BaseEveryFrameCombatPlugin() {

    enum class State {
        CHAIN_COOLDOWN,

        TERMINAL_PENDING,

        TERMINAL_MULTI,

        DISSIPATE,

        DONE,
    }

    private class PendingFlash(
        val at: Vector2f,
        val direct: CombatEntityAPI?,
        val mult: Float,
        val jumpIndex: Int,
        var timer: Float,
    )

    private class PendingTerminal(
        val ship: ShipAPI,
        val at: Vector2f,
        val fraction: Float,
        val emp: Float,
        val segmentIndex: Int,
        var timer: Float,
    )

    private var jumps = initialJumps
    private var state = initialState
    private var timer = 0f
    private var done = false

    private val pendingFlashes = ArrayList<PendingFlash>()
    private val pendingTerminals = ArrayList<PendingTerminal>()

    private var terminalShip: ShipAPI? = null
    private var terminalFractions: List<Float> = emptyList()
    private var terminalPoints: List<Vector2f> = emptyList()
    private var terminalIndex = 0
    private var terminalTimer = 0f

    fun queueFlash(at: Vector2f, direct: CombatEntityAPI?, mult: Float, jumpIndex: Int) {
        pendingFlashes += PendingFlash(Vector2f(at), direct, mult, jumpIndex, SevenStarsDifficulty.EXPLOSION_DELAY)
    }

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (done) return
        val engine = Global.getCombatEngine() ?: run {
            done = true
            return
        }
        if (engine.isPaused) return

        val dt = amount.coerceAtLeast(0f)
        tickPending(engine, dt)

        when (state) {
            State.CHAIN_COOLDOWN -> {
                timer += dt
                if (timer >= SevenStarsDifficulty.CHAIN_COOLDOWN) {
                    timer = 0f
                    doNextJump(engine)
                }
            }

            State.TERMINAL_PENDING -> enterTerminal(engine)
            State.TERMINAL_MULTI -> advanceTerminalMulti(engine, dt)
            State.DISSIPATE -> {
                SevenStarsVfx.dissipate(engine, anchor)
                state = State.DONE
            }

            State.DONE -> Unit
        }

        if (state == State.DONE && pendingFlashes.isEmpty() && pendingTerminals.isEmpty()) {
            done = true
        }
    }

    private fun tickPending(engine: CombatEngineAPI, dt: Float) {
        val flashIt = pendingFlashes.iterator()
        while (flashIt.hasNext()) {
            val pending = flashIt.next()
            pending.timer -= dt
            if (pending.timer > 0f) continue
            flashIt.remove()
            resolveFlash(engine, pending)
        }
        val terminalIt = pendingTerminals.iterator()
        while (terminalIt.hasNext()) {
            val pending = terminalIt.next()
            pending.timer -= dt
            if (pending.timer > 0f) continue
            terminalIt.remove()
            resolveTerminal(engine, pending)
        }
    }

    private fun doNextJump(engine: CombatEngineAPI) {
        val nextMult = SevenStarsChainMath.flashMult(tuning, jumps + 1)
        val target = SevenStarsTargetSelector.select(
            engine = engine,
            from = anchor,
            jumpRange = jumpRange,
            owner = owner,
            aoeDamage = panelDamage * nextMult,
        )
        if (SevenStarsChainMath.nextChainStep(jumps, hasPdCandidates = target != null) ==
            SevenStarsChainMath.ChainStep.TERMINAL
        ) {
            state = State.TERMINAL_PENDING
            return
        }

        SevenStarsVfx.teleport(engine, anchor, target!!.location, source, target as? ShipAPI)
        bumpTelemetry(engine, TELEMETRY_TELEPORT_ARC)
        anchor.set(target.location)
        jumps++
        SevenStarsVfx.riftWindup(engine, anchor, scale = flashScale(jumps))
        queueFlash(anchor, target, nextMult, jumps)
        trackChainJumps(engine, jumps)
    }

    private fun resolveFlash(engine: CombatEngineAPI, pending: PendingFlash) {
        val direct = pending.direct?.takeIf { isLiveTarget(engine, it) }
        val kills = SevenStarsDamageHandler.flashExplosion(
            engine = engine,
            at = pending.at,
            direct = direct,
            source = source,
            owner = owner,
            panelDamage = panelDamage,
            mult = pending.mult,
            aoeRadius = SevenStarsDifficulty.AOE_RADIUS,
        )
        SevenStarsVfx.riftBlast(engine, pending.at, scale = flashScale(pending.jumpIndex))
        bumpTelemetry(engine, TELEMETRY_FLASH)
        bumpTelemetry(engine, TELEMETRY_RIFT)
        addTelemetry(engine, TELEMETRY_KILLS, kills)
    }

    private fun enterTerminal(engine: CombatEngineAPI) {
        val ship = SevenStarsTargetSelector.nearestHostileShip(engine, anchor, owner) ?: run {
            bumpTelemetry(engine, TELEMETRY_DISSIPATE_NO_SHIP)
            state = State.DISSIPATE
            return
        }

        if (!tuning.multiSegmentTerminal) {
            val strikePoint = SevenStarsDamageHandler.sampleRiftCascadePoints(ship, 1, anchor).first()
            SevenStarsVfx.teleport(engine, anchor, strikePoint, source, ship)
            bumpTelemetry(engine, TELEMETRY_TELEPORT_ARC)
            anchor.set(strikePoint)
            SevenStarsVfx.riftWindup(engine, strikePoint, scale = TERMINAL_FLASH_SCALE)
            queueTerminal(ship, strikePoint, SevenStarsDifficulty.TERMINAL_BASE_FRACTION, emp = 0f, segmentIndex = -1)
            bumpTelemetry(engine, TELEMETRY_TERMINAL_SINGLE)
            state = State.DISSIPATE
            return
        }

        terminalShip = ship
        terminalFractions = SevenStarsChainMath.terminalDamageFractions(multi = true, jumps = jumps)
        terminalPoints = SevenStarsDamageHandler.sampleRiftCascadePoints(ship, terminalFractions.size, anchor)
        terminalIndex = 0
        terminalTimer = 0f
        SevenStarsVfx.teleport(engine, anchor, ship.location, source, ship)
        bumpTelemetry(engine, TELEMETRY_TELEPORT_ARC)
        anchor.set(ship.location)
        bumpTelemetry(engine, TELEMETRY_TERMINAL_MULTI)
        trackTerminalSegments(engine, terminalFractions.size)
        state = State.TERMINAL_MULTI
    }

    private fun advanceTerminalMulti(engine: CombatEngineAPI, amount: Float) {
        val ship = terminalShip ?: run {
            state = State.DISSIPATE
            return
        }
        if (ship.isHulk || !engine.isEntityInPlay(ship)) {
            state = State.DISSIPATE
            return
        }
        terminalTimer += amount
        while (terminalIndex < terminalFractions.size &&
            terminalTimer >= SevenStarsDifficulty.TERMINAL_SEGMENT_INTERVAL * (terminalIndex + 1)
        ) {
            val point = terminalPoints[terminalIndex]
            SevenStarsVfx.riftWindup(engine, point, scale = TERMINAL_FLASH_SCALE + 0.1f * terminalIndex)
            queueTerminal(ship, point, terminalFractions[terminalIndex], emp = panelDamage, segmentIndex = terminalIndex)
            terminalIndex++
        }
        if (terminalIndex >= terminalFractions.size) {
            state = State.DISSIPATE
        }
    }

    private fun resolveTerminal(engine: CombatEngineAPI, pending: PendingTerminal) {
        val scale = TERMINAL_FLASH_SCALE + if (pending.segmentIndex >= 0) 0.1f * pending.segmentIndex else 0f
        val alive = engine.isEntityInPlay(pending.ship) && !pending.ship.isHulk
        if (alive) {
            SevenStarsDamageHandler.terminalStrike(
                engine = engine,
                ship = pending.ship,
                at = pending.at,
                damage = panelDamage * pending.fraction,
                emp = pending.emp,
                source = source,
            )
        }
        SevenStarsVfx.riftBlast(engine, pending.at, scale = scale)
        bumpTelemetry(engine, TELEMETRY_RIFT)
        if (pending.segmentIndex >= 0 && alive) {
            SevenStarsVfx.terminalSegmentArc(engine, pending.at, pending.ship)
            bumpTelemetry(engine, TELEMETRY_TERMINAL_EMP_ARCS)
        }
    }

    private fun queueTerminal(ship: ShipAPI, at: Vector2f, fraction: Float, emp: Float, segmentIndex: Int) {
        pendingTerminals += PendingTerminal(ship, Vector2f(at), fraction, emp, segmentIndex, SevenStarsDifficulty.EXPLOSION_DELAY)
    }

    private fun isLiveTarget(engine: CombatEngineAPI, target: CombatEntityAPI): Boolean {
        if (!engine.isEntityInPlay(target)) return false
        if (target is ShipAPI && target.isHulk) return false
        if (target is MissileAPI && target.isExpired) return false
        return true
    }

    companion object {
        private fun flashScale(jumpIndex: Int): Float = 1f + 0.1f * (jumpIndex - 1)

        private const val TERMINAL_FLASH_SCALE = 1.2f

        const val TELEMETRY_ONFIRE = "astd_seven_stars_onfire"
        const val TELEMETRY_FLASH = "astd_seven_stars_flash"
        const val TELEMETRY_RIFT = "astd_seven_stars_rift"
        const val TELEMETRY_TELEPORT_ARC = "astd_seven_stars_teleport_arc"
        const val TELEMETRY_KILLS = "astd_seven_stars_kills"
        const val TELEMETRY_CHAIN_JUMPS_MAX = "astd_seven_stars_chain_jumps_max"
        const val TELEMETRY_DISSIPATE_NO_SHIP = "astd_seven_stars_dissipate_no_ship"
        const val TELEMETRY_TERMINAL_SINGLE = "astd_seven_stars_terminal_single"
        const val TELEMETRY_TERMINAL_MULTI = "astd_seven_stars_terminal_multi"
        const val TELEMETRY_TERMINAL_SEGMENTS_MAX = "astd_seven_stars_terminal_segments_max"
        const val TELEMETRY_TERMINAL_EMP_ARCS = "astd_seven_stars_terminal_emp_arcs"

        fun telemetryCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

        fun bumpTelemetry(engine: CombatEngineAPI, key: String) {
            engine.customData[key] = telemetryCount(engine, key) + 1
        }

        fun addTelemetry(engine: CombatEngineAPI, key: String, delta: Int) {
            if (delta == 0) return
            engine.customData[key] = telemetryCount(engine, key) + delta
        }

        fun trackChainJumps(engine: CombatEngineAPI, jumps: Int) {
            if (jumps > telemetryCount(engine, TELEMETRY_CHAIN_JUMPS_MAX)) {
                engine.customData[TELEMETRY_CHAIN_JUMPS_MAX] = jumps
            }
        }

        fun trackTerminalSegments(engine: CombatEngineAPI, segments: Int) {
            if (segments > telemetryCount(engine, TELEMETRY_TERMINAL_SEGMENTS_MAX)) {
                engine.customData[TELEMETRY_TERMINAL_SEGMENTS_MAX] = segments
            }
        }
    }
}
