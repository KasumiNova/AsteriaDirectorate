package cn.kasuminova.astd.combat.effect.arc

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import org.lwjgl.util.vector.Vector2f

/**
 * “七星”折跃发射器单发弹体的连跳状态机（规格 07 §2.1/§2.2）：
 * 每发弹体一个实例，由 [SevenStarsOnFireEffect] 在首发结算后注册（首发无 PD 候选时
 * 以 TERMINAL_PENDING 注册，首帧进终结判定）。
 *
 * 状态机：CHAIN_COOLDOWN（0.33s 计时续跳）→ TERMINAL_PENDING（选定最近敌舰并起爆）
 * → TERMINAL_MULTI（v5 多段逐段 0.12s 计时引爆）→ DISSIPATE（消散 + 收口）。
 * 五支路俱全：击杀续跳 / 0 击杀断链消散 / 7 跳硬上限 / 无处可去终结 / 无舰消散。
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

    /** 状态机节点（规格 §2.2；FIRST_STRIKE 已在 onFire 内同步完成，不在此列）。 */
    enum class State {
        /** 连跳冷却计时：0.33s 后选定下一 PD 目标续跳。 */
        CHAIN_COOLDOWN,

        /** 对舰终结判定：选定最近敌舰并起爆（单段一次 / 多段进 TERMINAL_MULTI）；无舰则消散。 */
        TERMINAL_PENDING,

        /** v5 多段终结：按段表逐段计时引爆（段间隔 0.12s），末段后消散。 */
        TERMINAL_MULTI,

        /** 消散：弹着点小星云淡出 + removeEntity 收口。 */
        DISSIPATE,
    }

    private var jumps = initialJumps
    private var state = initialState
    private var timer = 0f
    private var done = false

    // v5 多段终结进行中状态（段表 / 沿舰体取点 / 逐段计时）。
    private var terminalShip: ShipAPI? = null
    private var terminalFractions: List<Float> = emptyList()
    private var terminalPoints: List<Vector2f> = emptyList()
    private var terminalIndex = 0
    private var terminalTimer = 0f

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (done) return
        val engine = Global.getCombatEngine() ?: run {
            done = true
            return
        }
        if (engine.isPaused) return

        when (state) {
            State.CHAIN_COOLDOWN -> {
                timer += amount.coerceAtLeast(0f)
                if (timer >= SevenStarsDifficulty.CHAIN_COOLDOWN) {
                    timer = 0f
                    doNextJump(engine)
                }
            }
            State.TERMINAL_PENDING -> enterTerminal(engine)
            State.TERMINAL_MULTI -> advanceTerminalMulti(engine, amount.coerceAtLeast(0f))
            State.DISSIPATE -> dissipate(engine)
        }
    }

    /** 续跳：选定下一 PD 目标 → 折跃 → 闪光爆炸 → 按 kills 决策下一支路。 */
    private fun doNextJump(engine: CombatEngineAPI) {
        val nextMult = SevenStarsChainMath.flashMult(tuning, jumps + 1)
        val target = SevenStarsTargetSelector.select(
            engine = engine,
            from = anchor,
            jumpRange = jumpRange,
            owner = owner,
            aoeDamage = panelDamage * nextMult,
        ) ?: run {
            // 无 PD 候选：进对舰终结判定（规格 §2.2 状态机）。
            state = State.TERMINAL_PENDING
            return
        }

        SevenStarsVfx.teleport(engine, anchor, target.location, source, target as? ShipAPI)
        bumpTelemetry(engine, TELEMETRY_TELEPORT_ARC)
        anchor.set(target.location)
        jumps++
        val kills = SevenStarsDamageHandler.flashExplosion(
            engine = engine,
            at = anchor,
            direct = target,
            source = source,
            owner = owner,
            panelDamage = panelDamage,
            mult = nextMult,
            aoeRadius = SevenStarsDifficulty.AOE_RADIUS,
        )
        // 连跳第 N 跳闪光递增（+10%/跳）：连跳伤害提升的机制可视化（规格 §2.3）。
        SevenStarsVfx.crossFlash(engine, anchor, scale = 1f + 0.1f * (jumps - 1))
        bumpTelemetry(engine, TELEMETRY_FLASH)
        bumpTelemetry(engine, TELEMETRY_CROSS_FLASH)
        addTelemetry(engine, TELEMETRY_KILLS, kills)
        trackChainJumps(engine, jumps)

        when (SevenStarsChainMath.decideAfterFlash(kills, jumps, hasPdCandidates = true)) {
            SevenStarsChainMath.ChainDecision.CONTINUE -> state = State.CHAIN_COOLDOWN
            SevenStarsChainMath.ChainDecision.TERMINAL -> state = State.TERMINAL_PENDING
            SevenStarsChainMath.ChainDecision.DISSIPATE -> {
                // 未击杀断链（安全闸：连跳必须击杀才能续段）：消散，不触发终结。
                bumpTelemetry(engine, TELEMETRY_DISSIPATE_NO_KILL)
                state = State.DISSIPATE
            }
        }
    }

    /** 对舰终结判定：单段（玩家恒此）一次结算；v5 多段沿舰体取点后进 TERMINAL_MULTI；无舰消散。 */
    private fun enterTerminal(engine: CombatEngineAPI) {
        val ship = SevenStarsTargetSelector.nearestHostileShip(engine, anchor, owner) ?: run {
            // 连舰船目标也不存在：射弹直接消散（设计案原文）。
            bumpTelemetry(engine, TELEMETRY_DISSIPATE_NO_SHIP)
            state = State.DISSIPATE
            return
        }

        if (!tuning.multiSegmentTerminal) {
            // 单段（玩家恒此）：折跃至舰体，50% 面板一段，无 EMP（设计案按字面解读）。
            // 落点沿舰体采样（「多段沿舰体次第绽开」同规取点；bypassShields 口径见
            // SevenStarsDamageHandler 实机判例——盾关闭的带盾舰船 bypass=false 全额无伤害）。
            val strikePoint = SevenStarsDamageHandler.sampleHullPoints(ship, 1).first()
            SevenStarsVfx.teleport(engine, anchor, strikePoint, source, ship)
            bumpTelemetry(engine, TELEMETRY_TELEPORT_ARC)
            anchor.set(strikePoint)
            SevenStarsDamageHandler.terminalStrike(
                engine = engine,
                ship = ship,
                at = strikePoint,
                damage = panelDamage * SevenStarsDifficulty.TERMINAL_BASE_FRACTION,
                emp = 0f,
                source = source,
            )
            SevenStarsVfx.crossFlash(engine, strikePoint, scale = TERMINAL_SINGLE_FLASH_SCALE)
            bumpTelemetry(engine, TELEMETRY_CROSS_FLASH)
            bumpTelemetry(engine, TELEMETRY_TERMINAL_SINGLE)
            state = State.DISSIPATE
            return
        }

        // v5 多段（破晓敌版限定）：沿舰体取点，段表 50%→…→200%，逐段计时引爆。
        terminalShip = ship
        terminalFractions = SevenStarsChainMath.terminalDamageFractions(multi = true, jumps = jumps)
        terminalPoints = SevenStarsDamageHandler.sampleHullPoints(ship, terminalFractions.size)
        terminalIndex = 0
        terminalTimer = 0f
        SevenStarsVfx.teleport(engine, anchor, ship.location, source, ship)
        bumpTelemetry(engine, TELEMETRY_TELEPORT_ARC)
        anchor.set(ship.location)
        bumpTelemetry(engine, TELEMETRY_TERMINAL_MULTI)
        trackTerminalSegments(engine, terminalFractions.size)
        state = State.TERMINAL_MULTI
    }

    /** v5 多段终结逐段引爆：段间隔 0.12s；目标中途死亡/变 hulk 中止剩余段直接消散（规格 §2.4）。 */
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
            val fraction = terminalFractions[terminalIndex]
            val point = terminalPoints[terminalIndex]
            SevenStarsDamageHandler.terminalStrike(
                engine = engine,
                ship = ship,
                at = point,
                damage = panelDamage * fraction,
                // v5 终结每段 EMP = 面板等值（设计案 v5 终结栏）。
                emp = panelDamage,
                source = source,
            )
            // 多段次第绽开：逐段递增的十字闪光 + 每段 EMP 电弧连向武器/引擎槽（规格 §2.3）。
            SevenStarsVfx.crossFlash(engine, point, scale = TERMINAL_SINGLE_FLASH_SCALE + 0.1f * terminalIndex)
            SevenStarsVfx.terminalSegmentArc(engine, point, ship)
            bumpTelemetry(engine, TELEMETRY_CROSS_FLASH)
            bumpTelemetry(engine, TELEMETRY_TERMINAL_EMP_ARCS)
            terminalIndex++
        }
        if (terminalIndex >= terminalFractions.size) {
            state = State.DISSIPATE
        }
    }

    /**
     * 消散收口：弹着点小星云淡出（规格 §2.2 状态机终态）。弹体在首发结算后即移除（锚点设计，
     * 见类文档），此处无实体可移除；遥测计数在决策点完成（doNextJump 断链 / enterTerminal 无舰）。
     */
    private fun dissipate(engine: CombatEngineAPI) {
        SevenStarsVfx.dissipate(engine, anchor)
        done = true
    }

    companion object {
        /** 对舰终结单段闪光倍率（规格 §2.3：大号十字闪光 scale 1.2）。 */
        private const val TERMINAL_SINGLE_FLASH_SCALE = 1.2f

        // ---- dev 自动化烟测遥测键（engine.customData，对齐 PS/QJ 遥测先例）----
        const val TELEMETRY_ONFIRE = "astd_seven_stars_onfire"
        const val TELEMETRY_FLASH = "astd_seven_stars_flash"
        const val TELEMETRY_CROSS_FLASH = "astd_seven_stars_cross_flash"
        const val TELEMETRY_TELEPORT_ARC = "astd_seven_stars_teleport_arc"
        const val TELEMETRY_KILLS = "astd_seven_stars_kills"
        const val TELEMETRY_CHAIN_JUMPS_MAX = "astd_seven_stars_chain_jumps_max"
        const val TELEMETRY_DISSIPATE_NO_KILL = "astd_seven_stars_dissipate_no_kill"
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
