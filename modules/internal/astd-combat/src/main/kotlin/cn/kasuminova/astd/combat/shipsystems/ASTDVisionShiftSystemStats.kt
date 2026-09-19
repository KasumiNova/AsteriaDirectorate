package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.effect.joint.VisionShiftTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.effect.system.ASTDAfterimageEffect
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 视界变速（飞星 (LENS) / astd_lh_002_vision_shift）：自身时流提升 + 单目标时流压制与承伤转嫁。
 *
 * 设计案 20-joint.md §战术系统-紫菀：激活时锁定一艘敌对舰船（玩家取 shipTarget/鼠标位置附近敌舰，
 * AI 取 shipTarget/最近敌舰），10s 窗口内：
 * - 自身 timeMult 提升（玩家船经 engine.timeMult 反补偿，口径同落叶飞花）；
 * - 目标 timeMult 按体型分档压制（每帧 unmodify+modify 幂等刷写，窗口结束/目标失效即清理）；
 * - 目标承伤方向修正由挂目标舰的 [VisionShiftDamageListener] 结算
 *   （先例：AffixAggressiveSwarmNetworkHullMod.MarkedTargetListener）。
 *
 * 多源共存：同一目标被多艘飞星 (LENS) 锁定时，时流压制与承伤修正均按
 * per-source stat id（[targetStatId]）各自写入、各自清理，互不覆盖；
 * listener 每标记一份实例（创建 mark 时挂接，标记消失/目标死亡/源舰死亡时自移除）。
 *
 * 目标失效清理路径：系统离开 ACTIVE 态（OUT/COOLDOWN/IDLE）首帧即解除目标时流压制、
 * 目标死亡/hulk/退场（每帧校验 + listener 心跳，
 * 激活窗口内目标失效即 deactivate 提前结束系统）、
 * 本舰死亡（listener 心跳校验 mark.source 存活，系统脚本随舰终止不再刷写后标记即收）、
 * 激活期目标切换不追随（锁定口径：施放瞬间定格，不重选）。
 * 激活门禁：[isUsable] 仅在存在有效目标候选（锁定目标/鼠标附近/最近敌舰）时放行，
 * 无有效目标时系统不可激活（HUD 灰置，AI 侧由 ASTDVisionShiftSystemAI 自行门禁）。
 * 数值三锚点见 [VisionShiftTuning]；与奇点稳定器交互：目标舰的时间流速下限钳制
 * 在 hullmod advanceInCombat 中每帧执行，天然免疫本压制（设计预期）。
 */
class ASTDVisionShiftSystemStats : BaseShipSystemScript() {

    companion object {
        /** stat 源 id 前缀：目标时流压制与目标承伤修正（实际 id 带源舰身份后缀，见 [targetStatId]）。 */
        const val MOD_ID = "astd_vision_shift"

        private const val MARK_KEY_PREFIX = "astd_vision_shift_mark:"
        private const val MOUSE_PICK_TOLERANCE = 100f
        private const val SELF_AFTERIMAGE_INTERVAL = 0.3f
        private const val TARGET_AFTERIMAGE_INTERVAL = 1.0f
        private const val PLAYER_TIME_MULT_OWNER_KEY = "astd_vision_shift_player_time_mult_owner"
        private const val SELF_AFTERIMAGE_KEY_PREFIX = "astd_vision_shift_self_afterimage:"
        private const val TARGET_AFTERIMAGE_KEY_PREFIX = "astd_vision_shift_target_afterimage:"
        private val AFTERIMAGE_COLOR = Color(186, 120, 255, 96)
        private val JITTER_UNDER = Color(168, 96, 255, 150)
        private val JITTER = Color(168, 96, 255, 55)

        internal fun markKey(ship: ShipAPI): String = MARK_KEY_PREFIX + System.identityHashCode(ship)

        /** 目标侧 per-source stat id（多源共存时各自写入/清理，互不覆盖）。 */
        internal fun targetStatId(source: ShipAPI): String = "$MOD_ID:${System.identityHashCode(source)}"
    }

    /** 激活窗口的锁定状态（施放瞬间定格；存 engine.customData）。 */
    class VisionMark(
        val source: ShipAPI,
        val target: ShipAPI,
        val values: VisionShiftTuning.Values,
    )

    /** 本脚本实例所属舰（stats 脚本 per-ship 实例化；getStatusData 无 stats 形参，经此缓存反查）。 */
    private var ownerShip: ShipAPI? = null

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val ship = stats.entity as? ShipAPI
        val engine = Global.getCombatEngine()
        if (ship == null || engine == null) {
            unapply(stats, id)
            return
        }
        ownerShip = ship
        if (!engine.isPaused) {
            renderSelfStreak(ship, id, state, engine)
        }
        if (state != ShipSystemStatsScript.State.ACTIVE) {
            // 离开 ACTIVE 即解除目标时流压制（OUT/COOLDOWN/IDLE 均清理；IN 态无标记时为 no-op）
            clearMark(ship, engine)
            unapply(stats, id)
            return
        }

        // 窗口内首次 apply：锁定目标并解析数值（施放瞬间定格）
        val mark = obtainMark(ship, engine)
        if (mark == null && !engine.isPaused) {
            // 锁定目标失效（被摧毁/停机/退场）或施放瞬间已无有效目标：系统提前结束进入冷却
            ship.system?.deactivate()
        }
        val isPlayer = ship.owner == 0
        val values = mark?.values ?: VisionShiftTuning.resolve(DifficultyTuningImpl, isPlayer, null)

        stats.timeMult.modifyMult(id, values.selfTimeMult)
        if (!engine.isPaused && ship === engine.playerShip) {
            engine.timeMult.modifyMult("${id}_player", 1f / values.selfTimeMult)
            engine.customData[PLAYER_TIME_MULT_OWNER_KEY] = System.identityHashCode(ship)
        }

        if (mark != null && !engine.isPaused) {
            applyToTarget(mark, engine)
        }
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        stats.timeMult.unmodifyMult(id)
        val ship = stats.entity as? ShipAPI
        val engine = Global.getCombatEngine()
        if (ship != null && engine?.customData?.get(PLAYER_TIME_MULT_OWNER_KEY) == System.identityHashCode(ship)) {
            engine.timeMult.unmodifyMult("${id}_player")
            engine.customData.remove(PLAYER_TIME_MULT_OWNER_KEY)
        }
        ship ?: return
        ship.setJitterShields(false)
        engine?.customData?.remove(SELF_AFTERIMAGE_KEY_PREFIX + System.identityHashCode(ship))
    }

    /** 激活门禁：仅当存在有效目标候选时放行（判定口径与施放时 [pickTarget] 一致，无副作用）。 */
    override fun isUsable(system: ShipSystemAPI, ship: ShipAPI): Boolean {
        val engine = Global.getCombatEngine() ?: return false
        return pickTarget(ship, engine) != null
    }

    override fun getStatusData(
        index: Int,
        state: ShipSystemStatsScript.State,
        effectLevel: Float
    ): ShipSystemStatsScript.StatusData? {
        if (state != ShipSystemStatsScript.State.ACTIVE) return null
        val ship = ownerShip ?: return null
        val engine = Global.getCombatEngine() ?: return null
        val mark = engine.customData[markKey(ship)] as? VisionMark ?: return null
        return when (index) {
            0 -> ShipSystemStatsScript.StatusData(
                I18n.t(I18n.Categories.MOD, "system.vision_shift.status.self", "percent" to formatPercent(mark.values.selfTimeMult - 1f)),
                false,
            )
            1 -> ShipSystemStatsScript.StatusData(
                I18n.t(
                    I18n.Categories.MOD, "system.vision_shift.status.target",
                    "name" to (mark.target.name ?: mark.target.hullSpec.hullName),
                    "percent" to formatPercent(1f - mark.values.targetTimeMult),
                ),
                false,
            )
            else -> null
        }
    }

    /** 取当前锁定标记；无则尝试锁定（目标失效返回 null 且不留标记）。 */
    private fun obtainMark(ship: ShipAPI, engine: CombatEngineAPI): VisionMark? {
        val key = markKey(ship)
        val existing = engine.customData[key] as? VisionMark
        if (existing != null) {
            if (isValidTarget(ship, existing.target, engine)) return existing
            releaseTarget(existing)
            engine.customData.remove(key)
            return null
        }
        val target = pickTarget(ship, engine) ?: return null
        val values = VisionShiftTuning.resolve(DifficultyTuningImpl, isPlayer = ship.owner == 0, target.hullSize)
        val mark = VisionMark(ship, target, values)
        engine.customData[key] = mark
        // 每标记一份 listener 实例（多源共存各自结算；标记消失后由 listener 心跳自移除）
        target.addListener(VisionShiftDamageListener(target, key, targetStatId(ship)))
        return mark
    }

    private fun clearMark(ship: ShipAPI, engine: CombatEngineAPI) {
        val key = markKey(ship)
        val mark = engine.customData[key] as? VisionMark ?: return
        releaseTarget(mark)
        engine.customData.remove(key)
    }

    /** 解除目标侧本源的时流修正与残影计时键（承伤 listener 由标记消失后自行移除）。 */
    private fun releaseTarget(mark: VisionMark) {
        mark.target.mutableStats.timeMult.unmodifyMult(targetStatId(mark.source))
        Global.getCombatEngine()?.customData?.remove(TARGET_AFTERIMAGE_KEY_PREFIX + System.identityHashCode(mark.target))
    }

    private fun applyToTarget(mark: VisionMark, engine: CombatEngineAPI) {
        val target = mark.target
        val statId = targetStatId(mark.source)
        val stat = target.mutableStats.timeMult
        stat.unmodifyMult(statId)
        stat.modifyMult(statId, mark.values.targetTimeMult)

        target.setJitterShields(false)
        target.setJitterUnder(statId, JITTER_UNDER, 0.7f, 18, 0f, 6f)
        target.setJitter(statId, JITTER, 0.25f, 3, 0f, 0f)

        val timerKey = TARGET_AFTERIMAGE_KEY_PREFIX + System.identityHashCode(target)
        val elapsed = (engine.customData[timerKey] as? Float ?: TARGET_AFTERIMAGE_INTERVAL) + engine.elapsedInLastFrame
        if (elapsed >= TARGET_AFTERIMAGE_INTERVAL) {
            engine.customData[timerKey] = elapsed - TARGET_AFTERIMAGE_INTERVAL
            spawnAfterimage(engine, target)
        } else {
            engine.customData[timerKey] = elapsed
        }
    }

    private fun pickTarget(ship: ShipAPI, engine: CombatEngineAPI): ShipAPI? {
        val locked = ship.shipTarget
        if (isValidTarget(ship, locked, engine)) return locked
        if (ship === engine.playerShip) {
            // mouseTarget 是鼠标位置（Vector2f）：取鼠标附近的有效敌舰作为玩家意图目标
            val mouse = ship.mouseTarget
            if (mouse != null) {
                val nearMouse = engine.ships
                    .asSequence()
                    .filter { isValidTarget(ship, it, engine) }
                    .filter { MathUtils.getDistance(mouse, it.location) <= it.collisionRadius + MOUSE_PICK_TOLERANCE }
                    .minByOrNull { MathUtils.getDistance(mouse, it.location) }
                if (nearMouse != null) return nearMouse
            }
        }
        return engine.ships
            .asSequence()
            .filter { isValidTarget(ship, it, engine) }
            .minByOrNull { MathUtils.getDistance(ship.location, it.location) }
    }

    private fun isValidTarget(ship: ShipAPI, target: ShipAPI?, engine: CombatEngineAPI): Boolean {
        if (target == null || target === ship) return false
        if (target.owner == ship.owner || !target.isAlive || target.isHulk) return false
        if (target.isFighter || target.isDrone) return false
        return engine.isEntityInPlay(target)
    }

    private fun renderSelfStreak(ship: ShipAPI, id: String, state: ShipSystemStatsScript.State, engine: CombatEngineAPI) {
        val level = when (state) {
            ShipSystemStatsScript.State.IN -> 0.5f
            ShipSystemStatsScript.State.ACTIVE -> 1f
            ShipSystemStatsScript.State.OUT -> 0.45f
            else -> 0f
        }
        if (level > 0f) {
            ship.setJitterShields(false)
            ship.setJitterUnder(id, JITTER_UNDER, level, 25, 0f, 7f)
            ship.setJitter(id, JITTER, 0.30f * level, 3, 0f, 0f)
        }
        if (state != ShipSystemStatsScript.State.ACTIVE) return

        val timerKey = SELF_AFTERIMAGE_KEY_PREFIX + System.identityHashCode(ship)
        val elapsed = (engine.customData[timerKey] as? Float ?: 0f) + engine.elapsedInLastFrame
        if (elapsed < SELF_AFTERIMAGE_INTERVAL) {
            engine.customData[timerKey] = elapsed
            return
        }
        engine.customData[timerKey] = elapsed - SELF_AFTERIMAGE_INTERVAL
        spawnAfterimage(engine, ship)
    }

    private fun spawnAfterimage(engine: CombatEngineAPI, ship: ShipAPI) {
        ASTDAfterimageEffect.spawn(
            engine,
            ASTDAfterimageEffect.Snapshot(
                spritePath = ship.hullSpec.spriteName,
                location = Vector2f(ship.location),
                facing = ship.facing,
                width = ship.spriteAPI.width,
                height = ship.spriteAPI.height,
                color = AFTERIMAGE_COLOR,
                startAlpha = 0.42f,
                duration = 0.42f,
                growth = 0.035f,
            ),
        )
    }

    private fun formatPercent(value: Float): String {
        val pct = value * 100f
        val rounded = kotlin.math.round(pct * 10f) / 10f
        return if (rounded == kotlin.math.floor(rounded)) rounded.toInt().toString() else rounded.toString()
    }

    /**
     * 目标侧承伤修正监听器：窗口内目标受到来自本舰的伤害提高、来自他单位的伤害降低（最终乘区）。
     * 每标记一份实例（多源共存各自结算，stat id 带源舰身份后缀）；
     * 标记消失 / 目标死亡 / 源舰死亡任一路径触发即自移除并清理本源时流修正。
     */
    class VisionShiftDamageListener(
        private val host: ShipAPI,
        private val markKey: String,
        private val statId: String,
    ) : DamageTakenModifier, AdvanceableListener {

        override fun advance(amount: Float) {
            val engine = Global.getCombatEngine()
            val mark = engine?.customData?.get(markKey) as? VisionMark
            if (mark == null || mark.target !== host || !host.isAlive || host.isHulk ||
                !mark.source.isAlive || mark.source.isHulk
            ) {
                host.mutableStats.timeMult.unmodifyMult(statId)
                host.removeListener(this)
            }
        }

        override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            if (target !== host || damage == null) return null
            val engine = Global.getCombatEngine() ?: return null
            val mark = engine.customData[markKey] as? VisionMark ?: return null
            if (mark.target !== host) return null
            val attacker = when (param) {
                is ShipAPI -> param
                is DamagingProjectileAPI -> param.source
                is BeamAPI -> param.source
                else -> null
            }
            val mult = if (attacker === mark.source) mark.values.damageFromSelfMult else mark.values.damageFromOthersMult
            if (mult != 1f) {
                damage.modifier.modifyMult(statId, mult)
            }
            return null
        }
    }
}
