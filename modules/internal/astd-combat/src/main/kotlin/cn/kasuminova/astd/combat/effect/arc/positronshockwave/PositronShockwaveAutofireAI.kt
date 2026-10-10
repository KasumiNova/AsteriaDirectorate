package cn.kasuminova.astd.combat.effect.arc.positronshockwave

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.PluginPick
import com.fs.starfarer.api.campaign.CampaignPlugin.PickPriority
import com.fs.starfarer.api.combat.AutofireAIPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil

/**
 * 正电子冲击波的自动开火 AI（`ModPlugin.pickWeaponAutofireAI` 覆盖钩子）。
 *
 * 动机（2026-10 裁定）：原版 PD 自动开火在同类武器密集列装时容易扎堆同一目标、
 * 不顾各自射界贴合度，且对高耐久重型导弹缺乏「多管齐射」的集火语义。
 * 本 AI 在同舰同类武器间协同索敌：
 * - 导弹优先于战机/无人机（战机不参与多武器集火分配，单个认领即饱和）；
 * - 候选按「密度 + 耐久威胁 − 距离 − 射界偏差」评分，优先选择更密集且更贴合本武器射界的目标；
 * - 饱和分配：目标的有效认领数达 [PositronShockwaveFireControl.neededWeapons] 后不再扎堆——
 *   导弹的认领上限为 ceil(当前耐久 ÷ 面板伤害)（例：500 耐久 → 4 管、750 耐久 → 5 管），
 *   借此自然形成对重型导弹的多管齐射；
 * - 协同状态经 engine.customData 按武器逐帧发布认领，超时
 *   [PositronShockwaveFireControl.CLAIM_STALE_AFTER] 秒未刷新即失效（武器被毁/停火不残留脏认领）。
 *
 * 开火闸门（2026-10 实机修正）：`shouldFire()=true` 在原版语义下即立刻击发，瞄准是 AI 自身职责，
 * 故目标选定后仍须满足两条才开火——提前量命中点在射界内、且炮口实际指向与命中点夹角
 * 不超过命中容差（目标碰撞半径 + 余量在距离上张开的半角，见 [hitToleranceDeg]）；
 * 未对齐期间保持认领但不开火，炮塔转向期间不再空枪。目标选择带迟滞
 * （新候选总分须领先当前目标 [TARGET_SWITCH_HYSTERESIS] 才切换），避免帧间抖动致炮口反复甩动。
 */
class PositronShockwaveAutofireAI(
    private val weapon: WeaponAPI,
) : AutofireAIPlugin {

    private var shouldFire = false
    private var target: CombatEntityAPI? = null
    private val aimPoint = Vector2f()
    private var zeroSpeedWarned = false

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return

        val ship = weapon.ship
        if (ship == null || !ship.isAlive || weapon.isDisabled) {
            disengage(engine)
            return
        }

        val weaponLoc = weapon.location ?: run {
            disengage(engine)
            return
        }
        val owner = ship.owner

        // 候选采集：射程 + 射界内的敌对导弹/战机/无人机；导弹优先（导弹池非空时无视战机）。
        val inRange = CombatUtils.getEntitiesWithinRange(weaponLoc, weapon.range)
        val missiles = ArrayList<MissileAPI>()
        val fighters = ArrayList<ShipAPI>()
        for (e in inRange) {
            if (e == null || e.owner == owner) continue
            if (weapon.distanceFromArc(e.location) > ARC_EPS) continue
            when {
                e is MissileAPI -> if (!e.isFading && e.hitpoints > 0f) missiles += e
                e is ShipAPI && (e.isFighter || e.isDrone) && !e.isHulk -> fighters += e
            }
        }
        val pool: List<CombatEntityAPI> = if (missiles.isNotEmpty()) missiles else fighters
        if (pool.isEmpty()) {
            disengage(engine)
            return
        }

        val claims = PositronShockwaveFireControl.claimsOf(engine)
        val now = engine.getTotalElapsedTime(false)
        PositronShockwaveFireControl.purgeStale(claims, now)

        // 评分 + 饱和分配：先取评分最高的未饱和目标；全部饱和（目标数少于应投入管数）时取最高分兜底。
        var best: CombatEntityAPI? = null
        var bestScore = Float.NEGATIVE_INFINITY
        var bestFresh: CombatEntityAPI? = null
        var bestFreshScore = Float.NEGATIVE_INFINITY
        var prevScore = Float.NEGATIVE_INFINITY
        var prevFresh = false
        val prev = target
        for (candidate in pool) {
            val score = score(candidate, weaponLoc, owner, inRange)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
            val needed = PositronShockwaveFireControl.neededWeapons(
                isMissile = candidate is MissileAPI,
                hitpoints = candidate.hitpoints,
            )
            val claimed = PositronShockwaveFireControl.freshClaimCount(claims, candidate, ship, weapon, now)
            val fresh = claimed < needed
            if (candidate === prev) {
                prevScore = score
                prevFresh = fresh
            }
            if (fresh && score > bestFreshScore) {
                bestFreshScore = score
                bestFresh = candidate
            }
        }

        // 目标迟滞：旧目标仍在池内且未被显著超越时保留。饱和分配优先于迟滞——
        // 旧目标已饱和且存在未饱和候选时让位；全体饱和或未饱和时按迟滞与对应候选集比较。
        val chosen = if (prev != null && prevScore > Float.NEGATIVE_INFINITY) {
            if (!prevFresh && bestFresh != null) {
                bestFresh
            } else {
                val challengerScore = if (prevFresh) bestFreshScore else bestScore
                if (challengerScore <= prevScore + TARGET_SWITCH_HYSTERESIS) prev else bestFresh ?: best
            }
        } else {
            bestFresh ?: best
        } ?: run {
            disengage(engine)
            return
        }

        claims[weapon] = PositronShockwaveFireControl.Claim(chosen, now)
        target = chosen
        updateAimPoint(chosen, ship, weaponLoc)
        shouldFire = isAligned(chosen, weaponLoc)
    }

    /**
     * 开火对齐闸门：提前量命中点须在射界内，且炮口实际指向与命中点方向的夹角
     * 不超过 [hitToleranceDeg] 给出的命中容差。未对齐时保持认领但不开火，待炮塔转到位。
     * 命中点压在炮口（距离为 0，方向无定义）时视为已对齐。
     */
    private fun isAligned(targetEntity: CombatEntityAPI, weaponLoc: Vector2f): Boolean {
        if (weapon.distanceFromArc(aimPoint) > ARC_EPS) return false
        val dist = MathUtils.getDistance(weaponLoc, aimPoint)
        if (dist <= 0f) return true
        val aimAngle = Misc.getAngleInDegrees(weaponLoc, aimPoint)
        val diff = Misc.getAngleDiff(weapon.currAngle, aimAngle)
        return diff <= hitToleranceDeg(targetEntity.collisionRadius, dist)
    }

    /**
     * 候选评分：密度（触发圈内的敌对导弹/战机/无人机数）与导弹耐久威胁加分，
     * 距离与射界偏差减分。分值仅供同帧候选间排序，无绝对语义。
     */
    private fun score(
        candidate: CombatEntityAPI,
        weaponLoc: Vector2f,
        owner: Int,
        inRange: List<CombatEntityAPI>,
    ): Float {
        val loc = candidate.location
        val dist = MathUtils.getDistance(weaponLoc, loc)
        val arcDev = abs(Misc.getAngleDiff(Misc.getAngleInDegrees(weaponLoc, loc), weapon.arcFacing))
        val density = swarmDensity(loc, owner, inRange)
        val hpThreat = if (candidate is MissileAPI) {
            candidate.hitpoints / PositronShockwaveDifficulty.PANEL_DAMAGE
        } else {
            0f
        }
        return density * DENSITY_WEIGHT + hpThreat * HP_WEIGHT - dist * DIST_WEIGHT - arcDev * ARC_WEIGHT
    }

    /** 候选点触发圈半径内的敌对导弹/战机/无人机数量（复用射程粗筛结果，不再做网格查询）。 */
    private fun swarmDensity(loc: Vector2f, owner: Int, inRange: List<CombatEntityAPI>): Int {
        var count = 0
        for (e in inRange) {
            if (e == null || e.owner == owner) continue
            val isSwarm = e is MissileAPI || (e as? ShipAPI)?.let { it.isFighter || it.isDrone } == true
            if (!isSwarm) continue
            if (MathUtils.getDistance(loc, e.location) <= DENSITY_RADIUS) count++
        }
        return count
    }

    /** 提前量瞄准：按弹速折算飞行时间，以目标与本舰的相对速度外推命中点。 */
    private fun updateAimPoint(targetEntity: CombatEntityAPI, ship: ShipAPI, weaponLoc: Vector2f) {
        val speed = weapon.projectileSpeed
        if (speed.isNaN() || speed <= 0f) {
            if (!zeroSpeedWarned) {
                zeroSpeedWarned = true
                log.warn("正电子冲击波自动开火：weapon.projectileSpeed 非法（$speed），本武器退化为直瞄（一次性告警）")
            }
            aimPoint.set(targetEntity.location)
            return
        }
        val dist = MathUtils.getDistance(weaponLoc, targetEntity.location)
        val tof = dist / speed
        aimPoint.set(
            targetEntity.location.x + (targetEntity.velocity.x - ship.velocity.x) * tof,
            targetEntity.location.y + (targetEntity.velocity.y - ship.velocity.y) * tof,
        )
    }

    /** 解除交战：撤销认领并停火。 */
    private fun disengage(engine: CombatEngineAPI) {
        PositronShockwaveFireControl.claimsOf(engine).remove(weapon)
        target = null
        shouldFire = false
    }

    override fun shouldFire(): Boolean = shouldFire

    override fun forceOff() {
        shouldFire = false
    }

    override fun getTarget(): Vector2f {
        // 无交战目标时沿炮口当前指向取点，避免把炮塔拽向过期的提前量缓存点。
        val t = target ?: return MathUtils.getPointOnCircumference(weapon.location, TARGET_POINT_DISTANCE, weapon.currAngle)
        return Vector2f(aimPoint)
    }

    override fun getTargetShip(): ShipAPI? = target as? ShipAPI

    override fun getWeapon(): WeaponAPI = weapon

    override fun getTargetMissile(): MissileAPI? = target as? MissileAPI

    companion object {
        private val log = Global.getLogger(PositronShockwaveAutofireAI::class.java)

        /** 射界判定容差（su）：distanceFromArc 恰在边界上的目标视为在射界内。 */
        private const val ARC_EPS = 0.5f

        /**
         * 密度统计半径（su）：近炸触发圈口径（砺刃 v2 锥长 × [PositronShockwaveDifficulty.FUSE_RANGE_RATIO]）。
         * 仅作索敌启发式，不随难度档位变化。
         */
        private val DENSITY_RADIUS =
            PositronShockwaveDifficulty.CONE_RANGE.v2 * PositronShockwaveDifficulty.FUSE_RANGE_RATIO

        // 评分权重：密度每项 ≈ 100su 距离价值；耐久威胁按「还需几管」加权；射界偏差每度 2 分。
        private const val DENSITY_WEIGHT = 100f
        private const val HP_WEIGHT = 50f
        private const val DIST_WEIGHT = 1f
        private const val ARC_WEIGHT = 2f

        /** 目标切换迟滞（分）：新候选总分须领先当前目标该分值才切换（≈ 多 0.75 个邻近目标的密度差）。 */
        private const val TARGET_SWITCH_HYSTERESIS = 75f

        /** 命中容差夹紧下限（度）：超远距离/大型目标也不放宽到明显打不中。 */
        internal const val MIN_HIT_TOLERANCE_DEG = 1.5f

        /** 命中容差夹紧上限（度）：贴脸距离也不苛刻到等不到开火窗口。 */
        internal const val MAX_HIT_TOLERANCE_DEG = 6f

        /** 命中容差余量（su）：目标碰撞半径外的放宽量，覆盖交汇前的机动余地。 */
        internal const val HIT_MARGIN_SU = 10f

        /** getTarget 无目标占位点的延伸距离（su，仅语义占位，不参与弹道结算）。 */
        private const val TARGET_POINT_DISTANCE = 100f

        /**
         * 命中容差（度）：目标碰撞半径 + [HIT_MARGIN_SU] 在 [dist] 处张开的半角，
         * 夹紧在 [[MIN_HIT_TOLERANCE_DEG], [MAX_HIT_TOLERANCE_DEG]] 内。
         */
        internal fun hitToleranceDeg(collisionRadius: Float, dist: Float): Float {
            if (dist <= 0f) return MAX_HIT_TOLERANCE_DEG
            val deg = Math.toDegrees(atan((collisionRadius + HIT_MARGIN_SU) / dist).toDouble()).toFloat()
            return deg.coerceIn(MIN_HIT_TOLERANCE_DEG, MAX_HIT_TOLERANCE_DEG)
        }
    }
}

/**
 * 正电子冲击波自动开火的舰内协同认领表（engine.customData 持有，逐武器逐帧发布）。
 *
 * 认领语义：某武器当前帧决定交战的目标。认领只在同一舰船（`weapon.ship`）的同类武器间
 * 参与饱和计数；超过 [CLAIM_STALE_AFTER] 秒未刷新的条目视为失效并清除。
 */
internal object PositronShockwaveFireControl {

    /** engine.customData 认领表键。 */
    private const val CLAIMS_KEY = "astd_positron_af_claims"

    /** 认领有效窗口（秒）：超时未刷新即失效（武器被毁/停火后不再发布）。 */
    const val CLAIM_STALE_AFTER = 0.5f

    /** 一条认领记录：目标 + 发布时间（engine.getTotalElapsedTime(false) 口径）。 */
    class Claim(val target: CombatEntityAPI, val time: Float)

    /**
     * 目标应投入的同类武器管数（2026-10 裁定）：
     * 导弹按 ceil(当前耐久 ÷ 面板伤害)（下限 1）——高耐久导弹形成多管齐射；
     * 战机/无人机恒为 1（不参与集火分配）。
     */
    fun neededWeapons(isMissile: Boolean, hitpoints: Float): Int {
        if (!isMissile) return 1
        return ceil(hitpoints.coerceAtLeast(0f) / PositronShockwaveDifficulty.PANEL_DAMAGE)
            .toInt()
            .coerceAtLeast(1)
    }

    /** 同舰同类武器对 [target] 的有效认领数；[self] 自身不计入，过期条目不计入。 */
    fun freshClaimCount(
        claims: Map<WeaponAPI, Claim>,
        target: CombatEntityAPI,
        ship: ShipAPI,
        self: WeaponAPI,
        now: Float,
    ): Int {
        var count = 0
        for ((w, claim) in claims) {
            if (w === self) continue
            if (claim.target !== target) continue
            if (now - claim.time > CLAIM_STALE_AFTER) continue
            if (w.ship !== ship) continue
            count++
        }
        return count
    }

    /** 清理过期认领（武器被毁/停火后不再发布，超时条目在此失效回收）。 */
    fun purgeStale(claims: MutableMap<WeaponAPI, Claim>, now: Float) {
        claims.values.removeIf { now - it.time > CLAIM_STALE_AFTER }
    }

    /** 取（或建）本战斗的认领表。键由本模组独占，cast 安全。 */
    @Suppress("UNCHECKED_CAST")
    fun claimsOf(engine: CombatEngineAPI): MutableMap<WeaponAPI, Claim> =
        engine.customData.getOrPut(CLAIMS_KEY) { HashMap<WeaponAPI, Claim>() } as MutableMap<WeaponAPI, Claim>
}

/** 正电子冲击波自动开火 AI 的指派入口（与 [StardustLauncherAutofireAiPicker] 同族，对应同一 ModPlugin 钩子）。 */
object PositronShockwaveAutofireAiPicker {

    private const val WEAPON_ID = "astd_positron_shockwave"

    /** 武器 id 命中正电子冲击波时返回专属自动开火 AI；否则返回 null 交回原版/其他模组。 */
    fun pick(weapon: WeaponAPI): PluginPick<AutofireAIPlugin>? {
        if (weapon.spec?.weaponId != WEAPON_ID) return null
        return PluginPick(PositronShockwaveAutofireAI(weapon), PickPriority.MOD_SPECIFIC)
    }
}
