package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import cn.kasuminova.astd.combat.shipsystems.base.PhaseSituation
import org.apache.logging.log4j.LogManager
import org.lwjgl.util.vector.Vector2f

/**
 * 引力相位 AI 的态势采样器：每个决策节拍把战场状态折算成一拍 [PhaseSituation] 快照，
 * 供三棵决策树（[PhaseToggleTree]/[FlankAttackTree]/[FluxVentTree]）只读消费。
 *
 * 采样内容：来袭伤害两窗口、交战圈、攻击系统状态、武器可输出占比、目标机动/射界、友军接盘。
 * 实例状态仅为缓存与告警闩（payload 档案缓存、fluxTracker 空值告警闩），采样口径全部无状态。
 *
 * 威胁评估口径：直射弹体按弹道外推 + 横向偏差判定；制导导弹（含龙炎 DEM 等跟踪武器）
 * 跳过横向偏差检查并以极速一半兜底接近速度；fade 段弹体不过滤——原版 isFading 指
 * 过射程尾迹消散段（实弹）/ fizzle 末端窗口（导弹），引擎伤害判定只看 isExpired，
 * fade 段仍可全额命中，过滤会低估威胁（已解除武装的 fizzle 导弹轻微高估，保守方向可接受）；
 * 光束瞬时命中无弹体，扫描正在开火且射界/射程
 * 覆盖本舰的敌方光束武器折算威胁（持续光束 DPS × [CONT_BEAM_THREAT_WINDOW_SEC] 进 near，
 * 爆发光束爆发总伤 × [BURST_BEAM_THREAT_WEIGHT] 进 soon）。payload 导弹（behaviorSpec 带
 * payloadWeaponId：双子星 DEM、原版龙炎 DEM 及任何同行为模组弹体）不撞舰结算——抵近
 * 悬停距离锁定后由 payload 结算主伤害，按 [payloadProfileOf] 推导的悬停距离/爆发伤害
 * 折算，进入悬停距离即按 soon 窗口计全额威胁（只计 payload 爆发；弹体本体在悬停点
 * 自爆，爆心半径数十 su，对本舰不构成伤害），给下潜充能留提前量。脚本 spawn 的
 * 弹头同样在 engine.missiles 里（obf 核实：ProjectileFactory.spawnMissile 末尾无条件
 * addObject，早先「不在 missiles 里」的判例是同帧采样未 flush 所致），单通道覆盖。
 * 友军接盘只评估非制导弹药
 * （制导导弹脱靶后自行改瞄，穿透误伤口径不覆盖），穿透线段从预计命中本舰点起算。
 */
internal class GravityPhaseSituationSampler {

    companion object {
        private val log = LogManager.getLogger(GravityPhaseSituationSampler::class.java)

        /** 紧急窗口（s）：窗口内预计命中伤害计入「即将受击」。 */
        internal const val SOON_WINDOW_SEC = 0.6f

        /** 威胁窗口（s）：窗口内预计命中伤害计入「来袭威胁」。 */
        internal const val NEAR_WINDOW_SEC = 1.2f

        /** 上浮弹幕窗口（s）：窗口内预计命中伤害计入上浮安全评估（上浮僵直期承伤窗口比 soon 更长，鱼雷/齐射在途中即按住）。 */
        internal const val SURFACE_WINDOW_SEC = 2.0f

        /** 弹体扫描半径（su），覆盖 NEAR_WINDOW 内高速弹体。 */
        private const val PROJECTILE_SCAN_RANGE = 1500f

        /** 弹体命中判定的横向余量（su，加在碰撞半径上）。 */
        private const val MISS_MARGIN = 50f

        /** 弹体朝向本舰的分速度下限（su/s），低于此值视为非来袭。 */
        private const val APPROACH_SPEED_MIN = 50f

        /** 交战判定半径（su）：圈内存在有效敌舰即视为有交战对象。 */
        internal const val ENGAGE_SCAN_RANGE = 1600f

        /** 武器射界判定余量（°，加在半射界角上）。 */
        private const val COVERAGE_ARC_MARGIN = 10f

        /** 友军接盘评估窗口（s）：入相位后穿透弹药沿原弹道继续飞的评估时长。 */
        internal const val FRIENDLY_CATCH_WINDOW_SEC = 1.5f

        /** 持续光束威胁折算窗口（s）：照射中的持续光束按 DPS × 本值折算 near 窗口伤害。 */
        internal const val CONT_BEAM_THREAT_WINDOW_SEC = 1.5f

        /** 爆发光束威胁折算权重：照射中的爆发光束按爆发总伤 × 本值折算 soon 窗口伤害。 */
        internal const val BURST_BEAM_THREAT_WEIGHT = 0.5f

        /** 友军承火筛查距离（su）：只评估本舰周围本距离内的友军舰船。 */
        internal const val SCREEN_SCAN_DIST = 800f

        /** 友军承火方向锥半角（°）：友军位于本舰→主威胁方向 ±本值锥内且更靠近敌舰才算「在前承火」。 */
        internal const val SCREEN_AHEAD_HALF_ARC = 60f

        /** 低机动代理指标：极速不高于本值（su/s）直接判低机动。 */
        internal const val LOW_MOBILITY_MAX_SPEED = 70f

        /** 低机动代理指标：转向速率（°/s）与加速度（su/s²）双双低于本值判低机动。 */
        internal const val LOW_MOBILITY_TURN_RATE = 25f
        internal const val LOW_MOBILITY_ACCEL = 50f

        /** 侧后射界薄弱区口径：目标朝向与「目标→本舰」方位角差达到本值（目标背后 ±60° 锥）。 */
        internal const val REAR_ARC_MIN_DIFF = 120f

        /** 上浮贴脸判定系数：与最近敌舰间距 < 双方碰撞半径和 × 本值时不上浮（大于原版拒退重叠口径 0.35，留出漂离余量）。 */
        internal const val SURFACE_PROXIMITY_FRAC = 0.9f

        /** 正脸火力轴判定半角（°）：主威胁朝向与本舰相对方位差 ≤ 本值且距离够近时不上浮。 */
        internal const val SURFACE_FRONT_AXIS_HALF_ARC = 60f

        /** 正脸火力轴判定距离（su）：超出本距离敌舰正脸火力不构成贴脸上浮风险。 */
        internal const val SURFACE_FRONT_AXIS_MAX_DIST = 1000f

        /** 上浮落点重叠判定系数：与他舰间距 < （双方碰撞半径和）× 本值时退相位被原版拒绝（对齐原版 locationSafe 口径）。 */
        internal const val UNPHASE_UNSAFE_OVERLAP_FRAC = 0.35f

        /**
         * 绕后达成上浮的最小脱间距系数：上浮点（含 [SURFACE_WINDOW_SEC] 相对速度外推的
         * 最近接近）与主威胁间距须 ≥（本舰碰撞半径 + 敌舰有效半径）× 本值；敌舰护盾开启时
         * 有效半径取护盾半径——贴脸上浮闸（[SURFACE_PROXIMITY_FRAC]）只拦字面重叠，
         * 拦不住贴着敌舰护盾外缘上浮（实机 zw101 绕后 132su 贴脸上浮撞护盾）。
         */
        internal const val REAR_SURFACE_SEPARATION_FRAC = 1.5f

        /** 纯角度差（0~180°），不依赖 Misc 便于单测。 */
        internal fun angleDiffAbs(a: Float, b: Float): Float {
            var d = (a - b) % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return kotlin.math.abs(d)
        }

        /** 低机动目标判定（简化代理指标：极速 或 转向+加速度 双低）。 */
        internal fun isLowMobilityTarget(maxSpeed: Float, acceleration: Float, maxTurnRate: Float): Boolean =
            maxSpeed <= LOW_MOBILITY_MAX_SPEED ||
                    (maxTurnRate <= LOW_MOBILITY_TURN_RATE && acceleration <= LOW_MOBILITY_ACCEL)

        /** 绕后达成上浮的最小脱间距（纯函数）：本舰碰撞半径与敌舰有效半径之和的 [REAR_SURFACE_SEPARATION_FRAC] 倍。 */
        internal fun rearSurfaceMinSeparation(shipRadius: Float, enemyRadius: Float): Float =
            (shipRadius + enemyRadius) * REAR_SURFACE_SEPARATION_FRAC

        /**
         * 相对运动最近接近距离（纯函数）：相对位置 (px,py)、相对速度 (vx,vy)，
         * 取 [0, windowSec] 内的最近间距；相对静止或相互远离时即当前间距。
         */
        internal fun closestApproachDistance(
            px: Float, py: Float, vx: Float, vy: Float, windowSec: Float,
        ): Float {
            val vv = vx * vx + vy * vy
            var t = 0f
            if (vv > 1e-6f) {
                t = -(px * vx + py * vy) / vv
                if (t < 0f) t = 0f
                if (t > windowSec) t = windowSec
            }
            val cx = px + vx * t
            val cy = py + vy * t
            return kotlin.math.sqrt(cx * cx + cy * cy)
        }

        /**
         * 射线段-圆相交（纯函数）：弹体从 (px,py) 出发、窗口内位移 (dx,dy)，
         * 判定线段是否穿过以 (cx,cy) 为心、radius 为半径的圆。
         */
        internal fun segmentHitsCircle(
            px: Float, py: Float, dx: Float, dy: Float,
            cx: Float, cy: Float, radius: Float,
        ): Boolean {
            val lenSq = dx * dx + dy * dy
            if (lenSq < 1e-6f) return false
            // 圆心在线段方向上的投影参数 t（截断到线段内）
            var t = ((cx - px) * dx + (cy - py) * dy) / lenSq
            if (t < 0f) t = 0f
            if (t > 1f) t = 1f
            val nearX = px + dx * t - cx
            val nearY = py + dy * t - cy
            return nearX * nearX + nearY * nearY <= radius * radius
        }

        /** 光束威胁折算（纯函数）：持续光束按 DPS × [CONT_BEAM_THREAT_WINDOW_SEC] 计入 near 窗口。 */
        internal fun beamThreatNear(dps: Float): Float = dps * CONT_BEAM_THREAT_WINDOW_SEC

        /** 光束威胁折算（纯函数）：爆发光束按爆发总伤 × [BURST_BEAM_THREAT_WEIGHT] 计入 soon 窗口（爆发窗口即伤害落地窗口）。 */
        internal fun beamThreatSoon(burstDamage: Float): Float = burstDamage * BURST_BEAM_THREAT_WEIGHT
    }

    /** fluxTracker 空值告警闩：异常留痕一次，不每拍刷屏。 */
    private var nullFluxTrackerWarned = false

    /** payload 档案缓存：spec id → 档案（非 payload 弹体为 null，同样缓存避免重复解析 JSON）。 */
    private val payloadProfileCache = HashMap<String, PayloadProfile?>()

    /**
     * 态势采样：来袭伤害两窗口、交战圈、攻击系统状态、武器可输出占比、目标机动/射界、友军接盘。
     *
     * 决策时钟（已连续相位时长/距上次上浮时长/绕后意图剩余与武装窗口）由协调器持有，
     * 采样时按形参透传进快照；返回快照的 forcedSurfaceHoldSec 恒置 0，
     * 由协调器在采样后按节拍累计并 copy 覆写。
     */
    fun sample(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        cloak: ShipSystemAPI,
        phased: Boolean,
        target: ShipAPI?,
        phaseActiveTime: Float,
        timeSinceUnphase: Float,
        flankIntentRemaining: Float,
        flankIntentWindowArmed: Float,
    ): PhaseSituation {
        val incoming = FloatArray(3)
        estimateIncoming(engine.projectiles, ship, incoming, collectFriendly = false)
        estimateIncoming(engine.missiles, ship, incoming, collectFriendly = false)
        estimateBeamThreat(engine, ship, incoming, collectFriendly = false)

        // 友军火力只计 soon 窗口（防御性下潜输入）：同口径采样取 owner == 本舰一侧
        val friendlyIncoming = FloatArray(3)
        estimateIncoming(engine.projectiles, ship, friendlyIncoming, collectFriendly = true)
        estimateIncoming(engine.missiles, ship, friendlyIncoming, collectFriendly = true)
        estimateBeamThreat(engine, ship, friendlyIncoming, collectFriendly = true)

        val threat = pickThreat(engine, ship, target)
        // 机动性取基础值：modifiedValue 会被零辐能加速/燃驱等瞬时增益污染——敌舰停火
        // （相位中的本舰不可被瞄准）后零辐能加速生效，modified 口径会把低机动目标误判成高机动
        val lowMobility = threat != null &&
                isLowMobilityTarget(
                    threat.mutableStats.maxSpeed.baseValue,
                    threat.mutableStats.acceleration.baseValue,
                    threat.mutableStats.maxTurnRate.baseValue,
                )
        val threatDist = if (threat == null) Float.MAX_VALUE
        else kotlin.math.sqrt(distanceSq(ship.location, threat.location))
        // 主威胁看向本舰的绝对方位：侧后薄弱区与正脸火力轴共用同一 bearing
        val threatToUsBearing = threat?.let {
            Math.toDegrees(
                kotlin.math.atan2(
                    (ship.location.y - it.location.y).toDouble(),
                    (ship.location.x - it.location.x).toDouble(),
                )
            ).toFloat()
        }
        val attackSystem = ship.system?.takeIf { it !== cloak }
        val fluxTracker = ship.fluxTracker
        if (fluxTracker == null && !nullFluxTrackerWarned) {
            // 战斗舰船正常必有 fluxTracker；空值属异常，留痕后关闭战术耗散通道（不刷屏，每实例一次）
            nullFluxTrackerWarned = true
            log.warn("[GravityPhaseAI] ${ship.name} fluxTracker 为空，战术强制耗散通道关闭（ventTimeSec 置 MAX_VALUE）")
        }
        return PhaseSituation(
            phased = phased,
            phaseActiveTime = phaseActiveTime,
            timeSinceUnphase = timeSinceUnphase,
            hardFluxLevel = ship.hardFluxLevel,
            fluxLevel = ship.fluxLevel,
            incomingSoonDamage = incoming[0],
            incomingNearDamage = incoming[1],
            maxHull = ship.maxHitpoints,
            engagedEnemyNear = hasEngagedEnemy(engine, ship),
            systemReady = attackSystem != null &&
                    attackSystem.state == ShipSystemAPI.SystemState.IDLE &&
                    attackSystem.cooldownRemaining <= 0f,
            systemActive = attackSystem?.isOn == true,
            weaponsReadyFrac = weaponsReadyFrac(ship),
            retreating = ship.isRetreating,
            cloakReady = cloak.state == ShipSystemAPI.SystemState.IDLE &&
                    cloak.cooldownRemaining <= 0f && cloak.canBeActivated(),
            cloakCooldownRemaining = cloak.cooldownRemaining,
            softFluxLevel = ship.fluxLevel - ship.hardFluxLevel,
            weaponsDisabledFrac = weaponsDisabledFrac(ship),
            enginesDisabledFrac = enginesDisabledFrac(ship),
            targetLowMobility = lowMobility,
            inTargetRearArc = threat != null &&
                    angleDiffAbs(threat.facing, threatToUsBearing!!) >= REAR_ARC_MIN_DIFF,
            weaponCoverage = if (threat == null) 0 else weaponCoverageOn(ship, threat),
            friendlyCatchDamage = estimateFriendlyCatch(engine, ship),
            // 绕后意图生效口径：窗口未过期、相位中、且主威胁未明确换成高机动舰。
            // 威胁短暂掉出交战圈（threat == null）不取消意图——布防闸在 isFlankDive
            // 一次性把关，途中抖动取消会让布防早夭（实测 zw101 四次布防三次以
            // 上浮时 engaged=false 夭折，相位白潜）
            flankIntentActive = flankIntentRemaining > 0f && phased && (threat == null || lowMobility),
            flankIntentWindowSec = flankIntentWindowArmed,
            threatDistance = threatDist,
            unphaseUnsafe = isUnphaseUnsafe(engine, ship),
            surfaceTooClose = isSurfaceTooClose(engine, ship),
            // 上浮弹幕口径：敌方 soon+near+弹幕窗口三段合并，叠加友方 soon——
            // 上浮僵直期撞上任何一侧的已在途火力都是承伤
            incomingSurfaceDamage = incoming[0] + incoming[1] + incoming[2] + friendlyIncoming[0],
            threatFrontAxisClose = threat != null && threatDist <= SURFACE_FRONT_AXIS_MAX_DIST &&
                    angleDiffAbs(threat.facing, threatToUsBearing!!) <= SURFACE_FRONT_AXIS_HALF_ARC,
            rearSurfaceDistanceSafe = isRearSurfaceDistanceSafe(ship, threat),
            // 暂缓时长由协调器在采样后按节拍累计并 copy 覆写，采样恒置 0
            forcedSurfaceHoldSec = 0f,
            incomingFriendlySoonDamage = friendlyIncoming[0],
            ventTimeSec = fluxTracker?.timeToVent ?: Float.MAX_VALUE,
            ventRateMult = ship.mutableStats.ventRateMult.modifiedValue,
            venting = fluxTracker?.isVenting == true,
            friendlyScreenAhead = hasFriendlyScreenAhead(engine, ship, threat),
            travelDestDistance = travelDestDistance(ship),
        )
    }

    /**
     * 前方友军承火筛查：主威胁存在时，本舰周围 [SCREEN_SCAN_DIST] 内的友军舰船位于
     * 「本舰→主威胁」方向 ±[SCREEN_AHEAD_HALF_ARC]° 锥内、且比本舰更靠近主威胁才算
     * 「在前承火」——有人扛线时本舰受到的直瞄火力显著减少，规避类下潜阈值随之放宽
     * （[PhaseToggleTree.SCREENED_DIVE_THRESHOLD_MULT]，见 [EmergencyDiveRule] 的 screenMult）。
     */
    private fun hasFriendlyScreenAhead(engine: CombatEngineAPI, ship: ShipAPI, threat: ShipAPI?): Boolean {
        if (threat == null) return false
        val toThreatX = threat.location.x - ship.location.x
        val toThreatY = threat.location.y - ship.location.y
        val threatDistSq = toThreatX * toThreatX + toThreatY * toThreatY
        if (threatDistSq < 1e-6f) return false
        val threatBearing =
            Math.toDegrees(kotlin.math.atan2(toThreatY.toDouble(), toThreatX.toDouble())).toFloat()
        for (other in engine.ships) {
            if (other === ship || other.owner != ship.owner || !other.isAlive ||
                other.isHulk || other.isFighter
            ) {
                continue
            }
            val dx = other.location.x - ship.location.x
            val dy = other.location.y - ship.location.y
            val distSq = dx * dx + dy * dy
            if (distSq > SCREEN_SCAN_DIST * SCREEN_SCAN_DIST || distSq < 1e-6f) continue
            val bearing = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
            if (angleDiffAbs(bearing, threatBearing) > SCREEN_AHEAD_HALF_ARC) continue
            // 比本舰更靠近主威胁才算「在前」
            if (distanceSq(other.location, threat.location) < threatDistSq) return true
        }
        return false
    }

    /**
     * 赶路目标点距离（su）：原版 OrderResponseModule 设置的
     * [ShipwideAIFlags.AIFlags.MOVEMENT_DEST] 自定义旗标（Vector2f，燃驱 AI 同款赶路口径）；
     * 无目标点时为 [Float.MAX_VALUE]（相位赶路闸门随之失效）。
     */
    private fun travelDestDistance(ship: ShipAPI): Float {
        val dest = ship.aiFlags.getCustom(ShipwideAIFlags.AIFlags.MOVEMENT_DEST) as? Vector2f
            ?: return Float.MAX_VALUE
        return kotlin.math.sqrt(distanceSq(ship.location, dest))
    }

    /**
     * 绕后达成上浮的脱间距采样：与主威胁的当前间距及 [SURFACE_WINDOW_SEC] 相对速度外推
     * 最近接近均须 ≥ 最小脱间距（[rearSurfaceMinSeparation]）。敌舰护盾开启时有效半径取
     * 护盾半径——贴着护盾外缘上浮同样贴脸；相位穿透带速度，上浮僵直期相对速度会继续
     * 把船送进碰撞箱，故取外推窗口内的最近接近而非当前间距。
     */
    private fun isRearSurfaceDistanceSafe(ship: ShipAPI, threat: ShipAPI?): Boolean {
        if (threat == null) return true
        val shield = threat.shield
        val enemyRadius =
            if (shield != null && shield.isOn) {
                maxOf(threat.collisionRadius, shield.radius)
            } else {
                threat.collisionRadius
            }
        val minSep = rearSurfaceMinSeparation(ship.collisionRadius, enemyRadius)
        val relX = ship.location.x - threat.location.x
        val relY = ship.location.y - threat.location.y
        if (relX * relX + relY * relY < minSep * minSep) return false
        val approach = closestApproachDistance(
            relX, relY,
            ship.velocity.x - threat.velocity.x,
            ship.velocity.y - threat.velocity.y,
            SURFACE_WINDOW_SEC,
        )
        return approach >= minSep
    }

    /** 上浮贴脸采样：最近敌舰间距进入双方碰撞半径和的 [SURFACE_PROXIMITY_FRAC] 倍内（只计有效敌舰，残骸不构成火力威胁）。 */
    private fun isSurfaceTooClose(engine: CombatEngineAPI, ship: ShipAPI): Boolean {
        for (other in engine.ships) {
            if (!isValidEnemyShip(ship, other)) continue
            val limit = (other.collisionRadius + ship.collisionRadius) * SURFACE_PROXIMITY_FRAC
            if (distanceSq(ship.location, other.location) < limit * limit) return true
        }
        return false
    }

    /**
     * 上浮落点重叠判定：本舰碰撞圈与任一非战机舰船深度重叠时，原版斗篷拒绝退相位
     * （canBeDeactivated 的 locationSafe 口径：间距 < 双方碰撞半径和 × [UNPHASE_UNSAFE_OVERLAP_FRAC]）。
     * 小行星不查：原版对环带小行星豁免而公开 API 无法区分环带归属，实测唯一触发源是舰船重叠；
     * hulk 不排除——残骸物理上同样占据落点。
     */
    private fun isUnphaseUnsafe(engine: CombatEngineAPI, ship: ShipAPI): Boolean {
        for (other in engine.ships) {
            if (other === ship || other.isFighter) continue
            if (other.parentStation === ship) continue
            val limit = (other.collisionRadius + ship.collisionRadius) * UNPHASE_UNSAFE_OVERLAP_FRAC
            if (distanceSq(ship.location, other.location) < limit * limit) return true
        }
        return false
    }

    /** 主威胁目标：当前目标有效则用之，否则取交战圈内最近敌舰（无则 null；回退同样限交战圈）。 */
    private fun pickThreat(engine: CombatEngineAPI, ship: ShipAPI, target: ShipAPI?): ShipAPI? {
        if (target != null && isValidEnemyShip(ship, target) &&
            distanceSq(ship.location, target.location) <= ENGAGE_SCAN_RANGE * ENGAGE_SCAN_RANGE
        ) {
            return target
        }
        var best: ShipAPI? = null
        var bestDist = ENGAGE_SCAN_RANGE * ENGAGE_SCAN_RANGE
        for (other in engine.ships) {
            if (!isValidEnemyShip(ship, other)) continue
            val dist = distanceSq(ship.location, other.location)
            if (dist < bestDist) {
                bestDist = dist
                best = other
            }
        }
        return best
    }

    /** 武器覆盖数：主威胁目标上未禁用、有余弹、射程够得着、且射界当前覆盖本舰的非装饰武器数量。 */
    private fun weaponCoverageOn(ship: ShipAPI, threat: ShipAPI): Int {
        var count = 0
        for (weapon in threat.allWeapons) {
            if (weapon.isDecorative || weapon.isDisabled) continue
            // 无弹药武器不构成覆盖（弹尽后无法开火）
            if (weapon.usesAmmo() && weapon.ammo <= 0) continue
            val dx = ship.location.x - weapon.location.x
            val dy = ship.location.y - weapon.location.y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > weapon.range + ship.collisionRadius) continue
            val angleToUs = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
            if (angleDiffAbs(weapon.currAngle, angleToUs) <= weapon.arc / 2f + COVERAGE_ARC_MARGIN) {
                count++
            }
        }
        return count
    }

    /**
     * 友军接盘评估：当前朝本舰飞来的直射弹药（制导导弹会自行改瞄，不计），
     * 若本舰此刻入相位，弹药沿原弹道在 [FRIENDLY_CATCH_WINDOW_SEC] 窗口内
     * 穿过友军碰撞圈的累计伤害。压制非濒危下潜使用。
     */
    private fun estimateFriendlyCatch(engine: CombatEngineAPI, ship: ShipAPI): Float {
        var catch = 0f
        catch += friendlyCatchFrom(engine.projectiles, engine, ship)
        catch += friendlyCatchFrom(engine.missiles, engine, ship)
        return catch
    }

    private fun friendlyCatchFrom(
        projectiles: List<DamagingProjectileAPI>,
        engine: CombatEngineAPI,
        ship: ShipAPI,
    ): Float {
        val shipLoc = ship.location
        var catch = 0f
        for (proj in projectiles) {
            // fade 段弹体不过滤：isFading 是过射程消散段/fizzle 末端窗口，伤害判定只看 isExpired，
            // fade 段仍可全额命中——穿透误伤同样成立
            if (proj.owner == ship.owner || proj.isExpired) continue
            // 制导导弹脱靶后自行改瞄，穿透误伤口径不覆盖
            if (proj is MissileAPI && proj.isGuided) continue
            val dx = shipLoc.x - proj.location.x
            val dy = shipLoc.y - proj.location.y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > PROJECTILE_SCAN_RANGE || dist < 1e-3f) continue

            val vel = proj.velocity
            val approach = (vel.x * dx + vel.y * dy) / dist
            if (approach <= APPROACH_SPEED_MIN) continue
            // 只有本来会命中本舰的弹药才有「穿透」一说
            val eta = (dist - ship.collisionRadius) / approach
            if (eta < 0f || eta > NEAR_WINDOW_SEC) continue
            val hitX = proj.location.x + vel.x * eta - shipLoc.x
            val hitY = proj.location.y + vel.y * eta - shipLoc.y
            val hitR = ship.collisionRadius + MISS_MARGIN
            if (hitX * hitX + hitY * hitY > hitR * hitR) continue

            // 穿透后的飞行窗口：从预计命中本舰点起算（抵达本舰之前的航段不评估——
            // 弹体与本舰之间隔着友军时会先撞友军，不构成穿透误伤），窗口扣除已飞的 eta
            val startX = proj.location.x + vel.x * eta
            val startY = proj.location.y + vel.y * eta
            val remaining = FRIENDLY_CATCH_WINDOW_SEC - eta
            if (remaining <= 0f) continue
            val flyX = vel.x * remaining
            val flyY = vel.y * remaining
            for (friendly in engine.ships) {
                if (friendly === ship || friendly.owner != ship.owner ||
                    !friendly.isAlive || friendly.isHulk || friendly.isFighter
                ) {
                    continue
                }
                if (segmentHitsCircle(
                        startX, startY, flyX, flyY,
                        friendly.location.x, friendly.location.y, friendly.collisionRadius,
                    )
                ) {
                    catch += proj.damageAmount
                    break
                }
            }
        }
        return catch
    }

    /** 有效敌舰口径（非战机、非残骸、非中立 owner 100），交战圈与威胁目标共用。 */
    private fun isValidEnemyShip(ship: ShipAPI, other: ShipAPI): Boolean =
        other !== ship && other.isAlive && !other.isHulk && !other.isFighter &&
                other.owner != ship.owner && other.owner != 100

    /**
     * 光束威胁采样（折算累加进 [out]：[0]=soon，[1]=near）：
     * 光束瞬时命中、无弹体实体可被 [estimateIncoming] 采样，改为扫描敌舰武器——
     * 正在开火（isFiring，爆发光束含充能窗口）、射界与射程覆盖本舰的光束计入威胁：
     * 持续光束按 [beamThreatNear] 折算（DPS × 窗口秒）进 near；
     * 爆发光束（速子长矛级）按 [beamThreatSoon] 折算（爆发总伤 × 权重）进 soon——
     * 爆发窗口即伤害落地窗口，计入 soon 使紧急下潜直接响应高伤光束照射
     * （相位断照射是相位舰对高伤光束的标准生存手段）。
     *
     * collectFriendly=true 时改采友军舰船（不含自舰）光束：光束伤害即时落地，
     * 持续/爆发光束统一折算进 soon 窗口，仅供防御性下潜使用，不参与输出窗口判断。
     */
    private fun estimateBeamThreat(engine: CombatEngineAPI, ship: ShipAPI, out: FloatArray, collectFriendly: Boolean) {
        for (source in engine.ships) {
            val isThreatSource = if (collectFriendly) {
                source !== ship && source.owner == ship.owner &&
                        source.isAlive && !source.isHulk && !source.isFighter
            } else {
                isValidEnemyShip(ship, source)
            }
            if (!isThreatSource) continue
            for (weapon in source.allWeapons) {
                if (weapon.isDecorative || weapon.isDisabled || !weapon.isFiring) continue
                // isBurstBeam 是 isBeam 的子集，先过滤非光束
                if (!weapon.isBeam) continue
                if (weapon.usesAmmo() && weapon.ammo <= 0) continue
                val dx = ship.location.x - weapon.location.x
                val dy = ship.location.y - weapon.location.y
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist > weapon.range + ship.collisionRadius) continue
                val angleToUs = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (angleDiffAbs(weapon.currAngle, angleToUs) > weapon.arc / 2f + COVERAGE_ARC_MARGIN) {
                    continue
                }
                if (weapon.isBurstBeam) {
                    out[0] += beamThreatSoon(weapon.derivedStats.burstDamage)
                } else if (collectFriendly) {
                    out[0] += beamThreatNear(weapon.derivedStats.dps)
                } else {
                    out[1] += beamThreatNear(weapon.derivedStats.dps)
                }
            }
        }
    }

    /**
     * 来袭伤害估计（三窗口累加进 [out]：[0]=紧急窗口，[1]=威胁窗口，[2]=上浮弹幕窗口）：
     * 弹体朝本舰的分速度超阈值、按当前弹道在窗口内到达且横向偏差落在碰撞圈余量内才计入；
     * 忽略本舰机动，窗口短（≤2.0s）口径足够。
     * 制导导弹（含龙炎 DEM 等跟踪武器）放宽口径：会自行修正弹道，跳过横向偏差检查，
     * 接近速度取其极速的一半兜底（当前速度不代表命中时刻速度）。
     *
     * collectFriendly=true 时改采友方弹药（owner 与本舰相同）：友军火力只计 soon 窗口
     * （防御性下潜输入），eta 超出 soon 窗口的友方弹药不计入。
     *
     * payload 导弹（behaviorSpec 带 payloadWeaponId：双子星 DEM、原版龙炎 DEM 及任何
     * 同行为模组弹体）走 [payloadProfileOf] 口径：弹体不撞舰结算，抵近悬停距离锁定后
     * 才由 payload 结算主伤害，弹道 ETA 口径低估威胁；进入悬停距离即按 soon 窗口计
     * 全额威胁（只计 payload 爆发；弹体本体在悬停点自爆，对本舰不构成伤害），
     * 给下潜充能留提前量。只处理敌方弹体
     * （payload 指向敌方目标，友方 payload 导弹不构成对本舰的指向威胁）。
     */
    private fun estimateIncoming(
        projectiles: List<DamagingProjectileAPI>,
        ship: ShipAPI,
        out: FloatArray,
        collectFriendly: Boolean,
    ) {
        val shipLoc = ship.location
        val radius = ship.collisionRadius
        for (proj in projectiles) {
            // fade 段弹体不过滤：isFading 是过射程消散段/fizzle 末端窗口，伤害判定只看 isExpired，
            // fade 段仍可全额命中，过滤会低估威胁（已解除武装的 fizzle 导弹轻微高估，保守方向）
            if ((proj.owner == ship.owner) != collectFriendly || proj.isExpired) continue
            val guided = proj is MissileAPI && proj.isGuided
            val dx = shipLoc.x - proj.location.x
            val dy = shipLoc.y - proj.location.y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > PROJECTILE_SCAN_RANGE || dist < 1e-3f) continue

            // payload 导弹分支（先于接近速度闸：悬停锁定段弹体低速/悬停，常规口径会漏）；
            // 只计 payload 爆发——弹体本体在悬停点 bombPumped 自爆（爆心半径数十 su），
            // 对悬停距离外的本舰不构成伤害，计入 damageAmount 属虚增
            if (!collectFriendly && proj is MissileAPI) {
                val profile = payloadProfileOf(proj)
                if (profile != null) {
                    if (dist <= profile.standoffDist) {
                        out[0] += profile.burstDamage
                    }
                    continue
                }
            }

            val vel = proj.velocity
            var approach = (vel.x * dx + vel.y * dy) / dist
            if (guided) {
                val missileSpeed = (proj as MissileAPI).maxSpeed * 0.5f
                if (approach < missileSpeed) approach = missileSpeed
            }
            if (approach <= APPROACH_SPEED_MIN) continue

            val eta = (dist - radius) / approach
            if (eta < 0f || eta > SURFACE_WINDOW_SEC) continue

            if (!guided) {
                // 命中时刻弹体位置与本舰中心的横向偏差：落在碰撞圈余量内才算会命中
                val hitX = proj.location.x + vel.x * eta - shipLoc.x
                val hitY = proj.location.y + vel.y * eta - shipLoc.y
                if (hitX * hitX + hitY * hitY > (radius + MISS_MARGIN) * (radius + MISS_MARGIN)) continue
            }

            if (eta <= SOON_WINDOW_SEC) {
                out[0] += proj.damageAmount
            } else if (!collectFriendly) {
                if (eta <= NEAR_WINDOW_SEC) {
                    out[1] += proj.damageAmount
                } else {
                    out[2] += proj.damageAmount
                }
            }
        }
    }

    /** 交战判定：威胁圈内存在有效敌舰。 */
    private fun hasEngagedEnemy(engine: CombatEngineAPI, ship: ShipAPI): Boolean =
        engine.ships.any { other ->
            isValidEnemyShip(ship, other) &&
                    distanceSq(ship.location, other.location) <= ENGAGE_SCAN_RANGE * ENGAGE_SCAN_RANGE
        }

    /** 可输出武器占比：非装饰、非系统槽武器中未禁用、装填完毕、有余弹的比例（无武器时为 1）。 */
    private fun weaponsReadyFrac(ship: ShipAPI): Float {
        var total = 0
        var ready = 0
        for (weapon in ship.allWeapons) {
            if (weapon.isDecorative || weapon.slot?.isSystemSlot != false) continue
            total++
            if (!weapon.isDisabled && weapon.cooldownRemaining < 0.3f &&
                (!weapon.usesAmmo() || weapon.ammo > 0)
            ) {
                ready++
            }
        }
        return if (total == 0) 1f else ready.toFloat() / total
    }

    /** 下线武器占比：非装饰、非系统槽武器中被禁用的比例（无武器时为 0），修复下潜采样。 */
    private fun weaponsDisabledFrac(ship: ShipAPI): Float {
        var total = 0
        var disabled = 0
        for (weapon in ship.allWeapons) {
            if (weapon.isDecorative || weapon.slot?.isSystemSlot != false) continue
            total++
            if (weapon.isDisabled) disabled++
        }
        return if (total == 0) 0f else disabled.toFloat() / total
    }

    /** 下线引擎占比：整舰熄火（flameout）视为全部下线（无引擎时为 0），修复下潜采样。 */
    private fun enginesDisabledFrac(ship: ShipAPI): Float {
        val controller = ship.engineController
        if (controller.isFlamedOut) return 1f
        val engines = controller.shipEngines
        if (engines.isEmpty()) return 0f
        return engines.count { it.isDisabled }.toFloat() / engines.size
    }

    /** payload 导弹档案：悬停打击距离（su）与 payload 爆发伤害（按弹体 spec id 缓存推导结果）。 */
    private data class PayloadProfile(val standoffDist: Float, val burstDamage: Float)

    /**
     * payload 导弹档案推导（结果按 spec id 缓存；非 payload 弹体为 null）：
     * behaviorSpec 带 payloadWeaponId 的弹体（双子星 DEM、原版龙炎 DEM 及任何同行为模组
     * 弹体）不撞舰结算——抵近 triggerDistance 悬停锁定后发射 payload 结算主伤害，
     * 弹体命中口径低估威胁；悬停距离取 triggerDistance 上限，payload 伤害取 payload
     * 武器面板爆发伤害（爆发光束/鱼雷均为 burstDamage 口径）。
     * 弹体本体伤害（damageAmount）不计入：payload 行为弹体在悬停点 bombPumped 自爆
     * （爆心半径数十 su），对悬停距离外的目标不构成伤害。
     * 推导失败（payload 武器缺失/面板爆发为 0/triggerDistance 缺失）记 WARN 并缓存 null，
     * 该 spec 的威胁通道不生效——不静默兜底。
     */
    private fun payloadProfileOf(proj: DamagingProjectileAPI): PayloadProfile? {
        val specId = proj.projectileSpecId ?: return null
        if (payloadProfileCache.containsKey(specId)) return payloadProfileCache[specId]
        var profile: PayloadProfile? = null
        val params = proj.projectileSpec?.behaviorSpec?.params
        val payloadId = params?.optString("payloadWeaponId", "") ?: ""
        if (params != null && payloadId.isNotEmpty()) {
            val burst =
                Global.getSettings().getWeaponSpec(payloadId)?.derivedStats?.burstDamage ?: 0f
            val trigger = params.optJSONArray("triggerDistance")
            val standoff = if (trigger != null && trigger.length() > 0) {
                (0 until trigger.length()).maxOf { trigger.optDouble(it, 0.0) }.toFloat()
            } else {
                params.optDouble("triggerDistance", 0.0).toFloat()
            }
            if (burst > 0f && standoff > 0f) {
                profile = PayloadProfile(standoff, burst)
            } else {
                log.warn(
                    "[GravityPhaseAI] payload 导弹档案推导失败：projSpec=$specId " +
                            "payloadWeaponId=$payloadId burst=$burst standoff=$standoff，" +
                            "该弹体的 payload 威胁通道不生效",
                )
            }
        }
        payloadProfileCache[specId] = profile
        return profile
    }

    private fun distanceSq(a: Vector2f, b: Vector2f): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}
