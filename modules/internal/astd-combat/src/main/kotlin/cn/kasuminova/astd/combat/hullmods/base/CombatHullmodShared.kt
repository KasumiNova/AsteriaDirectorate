package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs

/**
 * 战斗级 Hullmod（逐帧光环/压制类）共用基础设施：
 * - [aliveEnemies]：存活敌舰枚举（与 affix.AffixShared.aliveAllies 对称口径）；
 * - [reconcileTracked]：被修饰目标集合逐帧对账（失效目标立即收口，不留 stat 残留）；
 * - [hasHullId]：按 hullId/baseHullId 判定舰型；
 * - [DepartedShipCleanupWatcher]：舰船离场（非残骸化）或战斗结束后的收口哨兵。
 *   advanceInCombat 在舰船撤离战场后不再被调用，写在外界目标/指挥官身上的 stat 修饰会失去
 *   收口路径（指挥官 PersonAPI 的 dynamic stat 甚至跨战斗存活），必须由哨兵探活补收口。
 */

/** 枚举战场上存活的敌军舰船（不含自身、战机、无人机与残骸）。 */
fun aliveEnemies(engine: CombatEngineAPI, ship: ShipAPI): List<ShipAPI> =
    engine.ships.filter {
        it !== ship && it.owner != ship.owner && it.isAlive && !it.isHulk && !it.isFighter && !it.isDrone
    }

/** 被修饰目标集合逐帧对账：本帧未再出现的既有目标执行 [unmodify] 并移出，本帧目标并入集合。 */
fun <T> reconcileTracked(tracked: MutableSet<T>, seen: Collection<T>, unmodify: (T) -> Unit) {
    tracked.removeAll { target ->
        val stale = target !in seen
        if (stale) unmodify(target)
        stale
    }
    tracked += seen
}

/** 舰型判定（hullId 或 baseHullId 命中；null 安全）。 */
fun ShipAPI?.hasHullId(hullId: String): Boolean {
    val s = this ?: return false
    return s.hullSpec?.hullId == hullId || s.hullSpec?.baseHullId == hullId
}

/** 相位舰船判定（相位限定效果的统一生效条件，按变体 hullSpec 判定；affix 与军官技能共用）。 */
fun isPhaseShip(stats: MutableShipStatsAPI): Boolean =
    stats.variant?.hullSpec?.isPhase == true

private const val FRONT_ARMOR_FRACTION = 0.30f
private const val SIDE_ARMOR_FRACTION_MIN = 0.20f
private const val SIDE_ARMOR_FRACTION_MAX = 0.30f
private const val REAR_ARMOR_FRACTION_MIN = 0.10f
private const val REAR_ARMOR_FRACTION_MAX = 0.20f

/**
 * 着弹方位装甲比例：以舰船朝向为基准，正面 ±30° 取满额比例，向两侧/正后线性衰减。
 * 用于把「最大装甲值」折算为着弹方向的等效装甲（ASTDPlasmaArmorShieldHullMod 与
 * 菀星战斗智能的相位上浮减伤共用同一口径）。
 */
fun directionalArmorFraction(ship: ShipAPI, hitPoint: Vector2f): Float {
    val hitAngle = Misc.getAngleInDegrees(ship.location, hitPoint)
    val relative = (((hitAngle - ship.facing) % 360f) + 540f) % 360f - 180f
    val offFront = abs(relative)
    return when {
        offFront <= 30f -> FRONT_ARMOR_FRACTION
        offFront <= 90f -> lerpArmor(SIDE_ARMOR_FRACTION_MAX, SIDE_ARMOR_FRACTION_MIN, (offFront - 30f) / 60f)
        else -> lerpArmor(REAR_ARMOR_FRACTION_MAX, REAR_ARMOR_FRACTION_MIN, (offFront - 90f) / 90f)
    }
}

private fun lerpArmor(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

/**
 * 离场/战斗结束收口哨兵：宿主舰船撤离战场（retreat 等非残骸化移除）或战斗已结束时，
 * 执行一次 [cleanup] 并自移除。残骸化不经过本路径（hulk 仍在 engine 视图中，
 * 由 hullmod advanceInCombat 的 hulk 分支收口）。
 */
class DepartedShipCleanupWatcher(
    private val engine: CombatEngineAPI,
    private val ship: ShipAPI,
    private val logTag: String,
    private val cleanup: () -> Unit,
) : BaseEveryFrameCombatPlugin() {

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (!engine.isCombatOver && engine.isEntityInPlay(ship)) return
        cleanup()
        log.info("[ASTD] $logTag：舰船离场或战斗结束（ship=${ship.id}），stat 修饰已收口")
        engine.removePlugin(this)
    }

    private companion object {
        val log = Global.getLogger(DepartedShipCleanupWatcher::class.java)
    }
}
