package cn.kasuminova.astd.combat.effect.arc

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lwjgl.util.vector.Vector2f

/**
 * “七星”折跃发射器单发弹体的连跳状态机（规格 07 §2.1/§2.2，2026-08 裂隙改版）：
 * 每发弹体一个实例，由 [SevenStarsOnFireEffect] 在首发折跃后注册（首发无 PD 候选时
 * 以 TERMINAL_PENDING 注册，首帧进终结判定）。
 *
 * 状态机：CHAIN_COOLDOWN（0.3s 计时续跳）→ TERMINAL_PENDING（选定最近敌舰并起爆）
 * → TERMINAL_MULTI（v5 多段逐段 0.12s 计时挂裂隙）→ DISSIPATE（消散视觉）→ DONE
 * （延迟爆炸队列排空后收口）。
 *
 * 固定 7 跳定案（移除击杀续跳门槛）：连跳不再以击杀数为续段条件，仅「达 7 跳上限 /
 * 无 PD 候选」进终结判定、「无舰」消散；击杀数仅作遥测证据面。
 *
 * 裂隙延迟爆炸（对齐原版裂隙洪流发射极地雷 0.5s windup）：每次折跃落点立即产生裂隙
 * 征兆（ping 光圈 + windup 音），伤害结算挂入 pending 队列延迟
 * [SevenStarsDifficulty.EXPLOSION_DELAY] 秒后执行（伤害量与爆炸范围不变）；队列随脚本
 * 逐帧推进，状态机进 DISSIPATE/DONE 后仍排空存量爆炸才收口——末跳爆炸（第 7 跳
 * +0.5s）晚于「无处可去消散」判定点属预期时序。
 *
 * 锚点设计（规格 §1 flightTime 6.0 意图的等效落地）：BALLISTIC 弹体寿命由引擎按
 * 「武器射程/弹速」推导（实机判例：射程 1280/弹速 3000 = 0.43s 即 fade），
 * weapon_data.csv「flight time」列仅对 MISSILE 类生效——弹体实体无法存活到 7 跳链
 * 跑完。故首发结算后弹体即移除，连跳锚点为纯位置 [anchor]（弹体不可见且无碰撞，
 * 移除无可观测差异）；脚本生命周期随引擎（战斗结束插件随引擎回收），天然规避
 * 弹体引用失效风险（规格 §2.4 防线守护的故障面在锚点设计下不存在）。
 */
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

    /** 状态机节点（规格 §2.2；FIRST_STRIKE 折跃已在 onFire 内同步完成，不在此列）。 */
    enum class State {
        /** 连跳冷却计时：0.3s 后选定下一 PD 目标续跳。 */
        CHAIN_COOLDOWN,

        /** 对舰终结判定：选定最近敌舰并挂裂隙（单段一次 / 多段进 TERMINAL_MULTI）；无舰则消散。 */
        TERMINAL_PENDING,

        /** v5 多段终结：按段表逐段计时挂裂隙征兆（段间隔 0.12s），末段挂完后消散。 */
        TERMINAL_MULTI,

        /** 消散：弹着点小星云淡出（只播一次，随即转 DONE）。 */
        DISSIPATE,

        /** 收口：状态机终态，继续排空延迟爆炸队列后置 done。 */
        DONE,
    }

    /** 连跳裂隙延迟爆炸（落点世界坐标 + 结算参数 + 剩余征兆秒数）。 */
    private class PendingFlash(
        val at: Vector2f,
        val direct: CombatEntityAPI?,
        val mult: Float,
        val jumpIndex: Int,
        var timer: Float,
    )

    /** 对舰终结延迟爆炸（逐段：目标舰 + 落点 + 段倍率/EMP + 剩余征兆秒数；segmentIndex < 0 为单段）。 */
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

    /** 连跳/终结延迟爆炸队列（DISSIPATE/DONE 后仍排空，见类文档时序注）。 */
    private val pendingFlashes = ArrayList<PendingFlash>()
    private val pendingTerminals = ArrayList<PendingTerminal>()

    // v5 多段终结进行中状态（段表 / 碰撞箱取点 / 逐段计时）。
    private var terminalShip: ShipAPI? = null
    private var terminalFractions: List<Float> = emptyList()
    private var terminalPoints: List<Vector2f> = emptyList()
    private var terminalIndex = 0
    private var terminalTimer = 0f

    /**
     * 预挂一发连跳裂隙延迟爆炸（[SevenStarsOnFireEffect] 首发：构造后、addPlugin 前调用；
     * 脚本内部续跳同路径）。[at] 快照为独立向量（锚点后续续跳会原地改写）。
     */
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

    /** 延迟爆炸队列推进：征兆到期即结算（连跳 AOE / 终结逐段各走各的清算）。 */
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

    /** 续跳：选定下一 PD 目标 → 折跃 → 落点挂裂隙征兆 + 延迟爆炸；达上限/无候选进终结判定。 */
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
            // 达 7 跳上限或无 PD 候选：进对舰终结判定（规格 §2.2 状态机）。
            state = State.TERMINAL_PENDING
            return
        }

        SevenStarsVfx.teleport(engine, anchor, target!!.location, source, target as? ShipAPI)
        bumpTelemetry(engine, TELEMETRY_TELEPORT_ARC)
        anchor.set(target.location)
        jumps++
        // 裂隙征兆先行，爆炸延迟结算（连跳第 N 跳裂隙递增 +10%/跳：伤害提升的机制可视化，规格 §2.3）。
        SevenStarsVfx.riftWindup(engine, anchor, scale = flashScale(jumps))
        queueFlash(anchor, target, nextMult, jumps)
        trackChainJumps(engine, jumps)
    }

    /** 连跳裂隙爆炸结算：征兆到期 → 区域伤害 + 裂隙视觉；直击目标已失效时退化为纯区域爆。 */
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

    /** 对舰终结判定：单段（玩家恒此）挂一发裂隙；v5 多段碰撞箱取点后进 TERMINAL_MULTI；无舰消散。 */
    private fun enterTerminal(engine: CombatEngineAPI) {
        val ship = SevenStarsTargetSelector.nearestHostileShip(engine, anchor, owner) ?: run {
            // 连舰船目标也不存在：射弹直接消散（设计案原文）。
            bumpTelemetry(engine, TELEMETRY_DISSIPATE_NO_SHIP)
            state = State.DISSIPATE
            return
        }

        if (!tuning.multiSegmentTerminal) {
            // 单段（玩家恒此）：折跃至舰缘，50% 面板一段，无 EMP（设计案按字面解读）。
            // 落点走碰撞箱贴边取点（RiftCascade 同款走位，见 sampleRiftCascadePoints 注）；
            // bypassShields 口径见 SevenStarsDamageHandler 实机判例。
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

        // v5 多段（破晓敌版限定）：碰撞箱贴边取点，段表 50%→…→200%，逐段计时挂裂隙。
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

    /** v5 多段终结逐段挂裂隙：段间隔 0.12s；目标中途死亡/变 hulk 中止剩余段直接消散（规格 §2.4）。 */
    private fun advanceTerminalMulti(engine: CombatEngineAPI, amount: Float) {
        val ship = terminalShip ?: run {
            state = State.DISSIPATE
            return
        }
        if (ship.isHulk || !engine.isEntityInPlay(ship)) {
            // 目标中途死亡/移除：剩余段表不结算（规格 §2.4）。
            state = State.DISSIPATE
            return
        }
        terminalTimer += amount
        while (terminalIndex < terminalFractions.size &&
            terminalTimer >= SevenStarsDifficulty.TERMINAL_SEGMENT_INTERVAL * (terminalIndex + 1)
        ) {
            val point = terminalPoints[terminalIndex]
            // 多段次第绽开：逐段递增的裂隙征兆 + 每段 EMP = 面板等值（设计案 v5 终结栏）。
            SevenStarsVfx.riftWindup(engine, point, scale = TERMINAL_FLASH_SCALE + 0.1f * terminalIndex)
            queueTerminal(ship, point, terminalFractions[terminalIndex], emp = panelDamage, segmentIndex = terminalIndex)
            terminalIndex++
        }
        if (terminalIndex >= terminalFractions.size) {
            state = State.DISSIPATE
        }
    }

    /** 终结裂隙爆炸结算：目标存活才结算伤害/EMP 电弧；裂隙视觉恒执行（落点已定在舰缘）。 */
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
            // 多段逐段 EMP 电弧连向武器/引擎槽（规格 §2.3）。
            SevenStarsVfx.terminalSegmentArc(engine, pending.at, pending.ship)
            bumpTelemetry(engine, TELEMETRY_TERMINAL_EMP_ARCS)
        }
    }

    private fun queueTerminal(ship: ShipAPI, at: Vector2f, fraction: Float, emp: Float, segmentIndex: Int) {
        pendingTerminals += PendingTerminal(ship, Vector2f(at), fraction, emp, segmentIndex, SevenStarsDifficulty.EXPLOSION_DELAY)
    }

    /** 延迟结算时点的直击目标有效性（位移不追踪——裂隙落点钉死，目标失效即退化为纯区域爆）。 */
    private fun isLiveTarget(engine: CombatEngineAPI, target: CombatEntityAPI): Boolean {
        if (!engine.isEntityInPlay(target)) return false
        if (target is ShipAPI && target.isHulk) return false
        if (target is MissileAPI && target.isExpired) return false
        return true
    }

    companion object {
        /** 连跳第 N 跳（1 起）的裂隙尺寸倍率：+10%/跳（连跳伤害提升的机制可视化）。 */
        private fun flashScale(jumpIndex: Int): Float = 1f + 0.1f * (jumpIndex - 1)

        /** 对舰终结裂隙尺寸倍率（规格 §2.3：大号裂隙 scale 1.2）。 */
        private const val TERMINAL_FLASH_SCALE = 1.2f

        // ---- dev 自动化烟测遥测键（engine.customData，对齐 PS/QJ 遥测先例）----
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

        /** 读整数遥测计数（无记录为 0）。 */
        fun telemetryCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

        /** 整数遥测自增 1。 */
        fun bumpTelemetry(engine: CombatEngineAPI, key: String) {
            engine.customData[key] = telemetryCount(engine, key) + 1
        }

        /** 整数遥测加 [delta]（kills 等批量计数）。 */
        fun addTelemetry(engine: CombatEngineAPI, key: String, delta: Int) {
            if (delta == 0) return
            engine.customData[key] = telemetryCount(engine, key) + delta
        }

        /** 单发弹体连跳次数峰值追踪（连跳上限 7 的实证面）。 */
        fun trackChainJumps(engine: CombatEngineAPI, jumps: Int) {
            if (jumps > telemetryCount(engine, TELEMETRY_CHAIN_JUMPS_MAX)) {
                engine.customData[TELEMETRY_CHAIN_JUMPS_MAX] = jumps
            }
        }

        /** v5 多段终结段数峰值追踪（破晓多段解锁的实证面）。 */
        fun trackTerminalSegments(engine: CombatEngineAPI, segments: Int) {
            if (segments > telemetryCount(engine, TELEMETRY_TERMINAL_SEGMENTS_MAX)) {
                engine.customData[TELEMETRY_TERMINAL_SEGMENTS_MAX] = segments
            }
        }
    }
}
