package cn.kasuminova.astd.combat.automation.scenario.arc

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.effect.arc.chargeneedle.ChargeNeedleVfx
import cn.kasuminova.astd.combat.effect.arc.chargeneedle.chargeNeedleStacks
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f

/**
 * 电荷针刺场景（stacking / discharge / magazine / HUD evidence）。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class ChargeNeedleScenario : AbstractAutomationScenario() {
    // ==== charge needle 场景状态（相位机 SHIELD → HULL → CEASE → COMPLETED） ====
    private var chargeNeedlePhase = CHARGE_NEEDLE_PHASE_SHIELD
    private var chargeNeedlePhaseStartedAt = 0f
    private var chargeNeedlePeakStacks = 0
    private var chargeNeedleMinSmallAmmo = Int.MAX_VALUE
    private var chargeNeedleMinHeavyAmmo = Int.MAX_VALUE
    private var chargeNeedleSmallEmptiedAt = -1f
    private var chargeNeedleDecayVerified = false

    override val scenarioId: String = ASTDInGameAutomationScenario.CHARGE_NEEDLE_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isChargeNeedleEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockChargeNeedleCamera(engine)
        // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findChargeNeedlePlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.CHARGE_NEEDLE_SCENARIO_ID} combat plugin initialized")
    }

    private fun findChargeNeedlePlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == CHARGE_NEEDLE_PLAYER_HULL }

    private fun findChargeNeedleEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == CHARGE_NEEDLE_ENEMY_HULL }

    private fun lockChargeNeedleCamera(engine: CombatEngineAPI) {
        lockCameraAt(engine, CHARGE_NEEDLE_CAMERA_CENTER, 760f)
    }

    /** 强制部署 mission reserves（敌方伯劳鸟非旗舰，必须手动出场）。 */
    private fun deployChargeNeedleReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val anchor = if (side == FleetSide.ENEMY) CHARGE_NEEDLE_ENEMY_ANCHOR else CHARGE_NEEDLE_PLAYER_ANCHOR
                val facing = if (side == FleetSide.ENEMY) 180f else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionChargeNeedlePhase(next: String) {
        ctx.log.info("[ASTD-Automation] charge needle phase $chargeNeedlePhase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        chargeNeedlePhase = next
        chargeNeedlePhaseStartedAt = ctx.elapsed
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployChargeNeedleReserveShips(engine)
        lockChargeNeedleCamera(engine)

        val player = findChargeNeedlePlayer(engine)
        val enemy = findChargeNeedleEnemy(engine)
        if (player != null) {
            engine.setPlayerShipExternal(player)
            // 双舰逐帧钉死（位置/朝向/速度归零）但保留舰 AI（preserveAI=true）：实机验证 AI 置空后
            // OMNI 盾失去威胁追踪、停在一个朝向，弹体全部走船体路线导致淤积恒 0（第十四轮 AI 存活时叠层正常）；
            // 且敌方 AutofireAI 拒射。AI 存活 + 命令封锁 + 钉死即可兼顾稳定与机制行为。
            // 注：真正阻断开火的是每帧 setRemainingCooldownTo(0f)（已移除），而非 AI 置空与否。
            stabilizeShip(player, CHARGE_NEEDLE_PLAYER_ANCHOR, 0f, allowFire = chargeNeedlePhase != CHARGE_NEEDLE_PHASE_CEASE, preserveAI = true)
            player.shipTarget = enemy
            // 玩家盾常开：敌方针刺命中玩家护盾触发受击方淤积（victim HUD 证据）。
            player.shield?.let { if (!it.isOn) it.toggleOn() }
            // 舞台保活：场景内双方血量/辐能顶格，避免过载/击沉打断相位机（纯 staging，非机制兜底）。
            player.hitpoints = player.maxHitpoints
            player.fluxTracker.currFlux = 0f
            player.fluxTracker.hardFlux = 0f
            setWeaponGroupAutofire(player, chargeNeedlePhase != CHARGE_NEEDLE_PHASE_CEASE, CHARGE_NEEDLE_PLAYER_WEAPON_IDS)
            // 窄射界槽位（野狼 WS 004 仅 5° 弧）AutofireAI 目标采纳存在死锁：currAngle 停在弧缘 → 目标判出弧置空
            // → 无人修正 currAngle（实机判别：同槽挂小型针刺同样 aiTarget=null 拒射，与重型 spec 无关，
            // extraArcForAI=25 也不解）。舞台逐帧把针刺武器 currAngle 对准敌舰解除死锁。
            if (enemy != null) {
                for (w in player.allWeapons) {
                    if (w.id in CHARGE_NEEDLE_PLAYER_WEAPON_IDS) {
                        w.currAngle = Misc.getAngleInDegrees(w.location, enemy.location)
                    }
                }
            }
            // WS 004 重型的 AutofireAI 在本舞台目标采纳恒 null（同槽挂小型判别一致，currAngle 对准敌舰亦不解，
            // 与重型 spec 无关——槽位/组级 AI 行为）。重型直接逐帧 setForceFireOneFrame 绕过 AI 判定直控开火
            // （纯舞台手段；重型与小型机制完全同码路，此处只为取弹匣节奏与拖尾目检证据）。
            for (w in player.allWeapons) {
                if (w.id == ASTDInGameAutomationScenario.CHARGE_NEEDLE_HEAVY_WEAPON_ID) {
                    w.setForceFireOneFrame(chargeNeedlePhase != CHARGE_NEEDLE_PHASE_CEASE)
                }
            }
        }
        if (enemy != null) {
            stabilizeShip(enemy, CHARGE_NEEDLE_ENEMY_ANCHOR, 180f, allowFire = true, preserveAI = true)
            enemy.shipTarget = player
            enemy.hitpoints = enemy.maxHitpoints
            enemy.fluxTracker.currFlux = 0f
            enemy.fluxTracker.hardFlux = 0f
            // 敌方针刺开火（命中玩家护盾 → victim 淤积证据）。
            setWeaponGroupAutofire(enemy, true, CHARGE_NEEDLE_ENEMY_WEAPON_IDS)
        }

        val smallNeedle = player?.allWeapons
            ?.filter { it.id == ASTDInGameAutomationScenario.CHARGE_NEEDLE_WEAPON_ID }
            ?.minByOrNull { it.ammo }
        val heavyNeedle = player?.allWeapons
            ?.filter { it.id == ASTDInGameAutomationScenario.CHARGE_NEEDLE_HEAVY_WEAPON_ID }
            ?.minByOrNull { it.ammo }

        when (chargeNeedlePhase) {
            CHARGE_NEEDLE_PHASE_SHIELD -> {
                enemy?.shield?.let { if (!it.isOn) it.toggleOn() }
                val stacks = enemy?.chargeNeedleStacks()?.stacks ?: 0
                if (stacks > chargeNeedlePeakStacks) chargeNeedlePeakStacks = stacks
                if (stacks >= CHARGE_NEEDLE_STACK_TARGET) transitionChargeNeedlePhase(CHARGE_NEEDLE_PHASE_HULL)
            }

            CHARGE_NEEDLE_PHASE_HULL -> {
                enemy?.shield?.let { if (it.isOn) it.toggleOff() }
                val stacks = enemy?.chargeNeedleStacks()?.stacks ?: 0
                if (stacks > chargeNeedlePeakStacks) chargeNeedlePeakStacks = stacks
                if (ChargeNeedleVfx.dischargeCount(engine) >= CHARGE_NEEDLE_DISCHARGE_TARGET &&
                    chargeNeedleMinSmallAmmo <= 0
                ) {
                    transitionChargeNeedlePhase(CHARGE_NEEDLE_PHASE_CEASE)
                }
            }

            CHARGE_NEEDLE_PHASE_CEASE -> {
                enemy?.shield?.let { if (!it.isOn) it.toggleOn() }
                val stacks = enemy?.chargeNeedleStacks()?.stacks ?: 0
                if (stacks == 0 && chargeNeedlePeakStacks > 0) chargeNeedleDecayVerified = true
                if (chargeNeedleDecayVerified) transitionChargeNeedlePhase(CHARGE_NEEDLE_PHASE_COMPLETED)
            }

            else -> {
                stageChargeNeedleCompletedFrame(engine)
            }
        }

        // 弹匣节奏证据：最小弹药观测值与首次打空时刻。
        smallNeedle?.let { needle ->
            if (needle.ammo < chargeNeedleMinSmallAmmo) {
                chargeNeedleMinSmallAmmo = needle.ammo
                if (needle.ammo <= 0 && chargeNeedleSmallEmptiedAt < 0f) chargeNeedleSmallEmptiedAt = ctx.elapsed
            }
        }
        heavyNeedle?.let { needle -> if (needle.ammo < chargeNeedleMinHeavyAmmo) chargeNeedleMinHeavyAmmo = needle.ammo }

        val state = when {
            player == null || enemy == null -> {
                if (ctx.elapsed > 10f) {
                    ctx.failureReason = "charge needle ships missing: player=${player != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            chargeNeedlePhase != CHARGE_NEEDLE_PHASE_COMPLETED &&
                    ctx.elapsed - chargeNeedlePhaseStartedAt > CHARGE_NEEDLE_PHASE_TIMEOUT -> {
                ctx.failureReason = "charge needle phase timeout: $chargeNeedlePhase"
                "Failed"
            }

            chargeNeedlePhase == CHARGE_NEEDLE_PHASE_COMPLETED -> "Completed"
            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: charge_needle_basic stacking/discharge/decay evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, smallNeedle)
        }
    }

    /** 排障用武器状态串：实例存在性/弹药/冷却/禁用/所属组/自动开火状态/射界与射程几何判定。 */
    private fun chargeNeedleWeaponState(ship: ShipAPI?, weapon: WeaponAPI?): String {
        weapon ?: return "missing"
        val group = ship?.getWeaponGroupFor(weapon)
        val autofireAI = group?.getAutofirePlugin(weapon)
        val target = ship?.shipTarget
        val targetLoc = target?.location
        val dist = if (targetLoc != null) Misc.getDistance(weapon.location, targetLoc) else -1f
        val distFromArc = if (targetLoc != null) weapon.distanceFromArc(targetLoc) else -1f
        return "id=${weapon.id},slot=${weapon.slot?.id},ammo=${weapon.ammo},cd=${"%.2f".format(weapon.cooldownRemaining)}," +
                "disabled=${weapon.isDisabled},firing=${weapon.isFiring},group=${group != null},autofiring=${group?.isAutofiring}," +
                "groupType=${group?.type},shipTarget=${target?.hullSpec?.hullId}," +
                "shipAI=${ship?.shipAI != null},aiShouldFire=${autofireAI?.shouldFire()}," +
                "aiTarget=${autofireAI?.targetShip?.hullSpec?.hullId ?: autofireAI?.target}," +
                "dist=${"%.0f".format(dist)},range=${"%.0f".format(weapon.range)}," +
                "currAngle=${"%.1f".format(weapon.currAngle)},arcFacing=${"%.1f".format(weapon.arcFacing)}," +
                "arc=${"%.0f".format(weapon.arc)},distFromArc=${"%.1f".format(distFromArc)}"
    }

    /** 排障用护盾状态串：相位/开关/弧度/辐能（排查敌方盾为何未升起导致零淤积）。 */
    private fun chargeNeedleShieldState(ship: ShipAPI?): String {
        ship ?: return "missing-ship"
        val shield = ship.shield ?: return "missing-shield,phased=${ship.isPhased}"
        return "isOn=${shield.isOn},isOff=${shield.isOff},activeArc=${"%.0f".format(shield.activeArc)}," +
                "arc=${"%.0f".format(shield.arc)},upkeep=${"%.0f".format(shield.upkeep)}," +
                "phased=${ship.isPhased},flux=${"%.0f".format(ship.currFlux)},overloaded=${ship.fluxTracker.isOverloaded}"
    }

    /** COMPLETED 截图舞台：敌方盾开 + 双方自动开火（新鲜拖尾、叠层 HUD、泄放电弧进入捕获帧）。 */
    private fun stageChargeNeedleCompletedFrame(engine: CombatEngineAPI) {
        val player = findChargeNeedlePlayer(engine)
        val enemy = findChargeNeedleEnemy(engine)
        player?.let {
            stabilizeShip(it, CHARGE_NEEDLE_PLAYER_ANCHOR, 0f, allowFire = true, preserveAI = true)
            it.shipTarget = enemy
            it.shield?.let { shield -> if (!shield.isOn) shield.toggleOn() }
            setWeaponGroupAutofire(it, true, CHARGE_NEEDLE_PLAYER_WEAPON_IDS)
            // 与相位机同款窄射界 currAngle 死锁解除 + 重型直控开火（详见 advanceChargeNeedleScenario 注释）。
            if (enemy != null) {
                for (w in it.allWeapons) {
                    if (w.id in CHARGE_NEEDLE_PLAYER_WEAPON_IDS) {
                        w.currAngle = Misc.getAngleInDegrees(w.location, enemy.location)
                    }
                }
            }
            for (w in it.allWeapons) {
                if (w.id == ASTDInGameAutomationScenario.CHARGE_NEEDLE_HEAVY_WEAPON_ID) {
                    w.setForceFireOneFrame(true)
                }
            }
        }
        enemy?.let {
            stabilizeShip(it, CHARGE_NEEDLE_ENEMY_ANCHOR, 180f, allowFire = true, preserveAI = true)
            it.shield?.let { shield -> if (!shield.isOn) shield.toggleOn() }
            it.shipTarget = player
            setWeaponGroupAutofire(it, true, CHARGE_NEEDLE_ENEMY_WEAPON_IDS)
        }
        lockChargeNeedleCamera(engine)
    }

    // 捕获帧间隔 0.6s：敌方盾开 + 双方开火，叠层 HUD 在三帧内累积到可见层数，新鲜拖尾/电弧入帧。
    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.6f, { findChargeNeedlePlayer(ctx.engine) }) { stageChargeNeedleCompletedFrame(ctx.engine) }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val enemy = findChargeNeedleEnemy(engine)
        val player = findChargeNeedlePlayer(engine)
        val enemyStacks = enemy?.chargeNeedleStacks()
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（规格 §4.2 验收要点）----
        json.appendLine("  \"chargeNeedlePhase\": \"$chargeNeedlePhase\",")
        json.appendLine("  \"chargeNeedleTargetStacks\": ${enemyStacks?.stacks ?: 0},")
        json.appendLine("  \"chargeNeedleTargetMaxStacks\": ${enemyStacks?.maxStacks ?: 0},")
        json.appendLine(
            "  \"chargeNeedleTargetUpkeepMult\": ${
                formatFloat(
                    try {
                        enemy?.mutableStats?.shieldUpkeepMult?.modifiedValue ?: -1f
                    } catch (_: Throwable) {
                        -1f
                    }
                )
            },"
        )
        json.appendLine(
            "  \"chargeNeedleTargetDissipation\": ${
                formatFloat(
                    try {
                        enemy?.mutableStats?.fluxDissipation?.modifiedValue ?: -1f
                    } catch (_: Throwable) {
                        -1f
                    }
                )
            },"
        )
        json.appendLine(
            "  \"chargeNeedleTargetBaseUpkeep\": ${
                formatFloat(
                    try {
                        enemy?.hullSpec?.shieldSpec?.upkeepCost ?: -1f
                    } catch (_: Throwable) {
                        -1f
                    }
                )
            },"
        )
        json.appendLine("  \"chargeNeedlePeakStacks\": $chargeNeedlePeakStacks,")
        json.appendLine("  \"chargeNeedlePlayerVictimStacks\": ${player?.chargeNeedleStacks()?.stacks ?: 0},")
        json.appendLine("  \"chargeNeedleDischargeCount\": ${ChargeNeedleVfx.dischargeCount(engine)},")
        json.appendLine("  \"chargeNeedleMinSmallAmmoObserved\": ${if (chargeNeedleMinSmallAmmo == Int.MAX_VALUE) -1 else chargeNeedleMinSmallAmmo},")
        json.appendLine("  \"chargeNeedleMinHeavyAmmoObserved\": ${if (chargeNeedleMinHeavyAmmo == Int.MAX_VALUE) -1 else chargeNeedleMinHeavyAmmo},")
        json.appendLine("  \"chargeNeedleSmallAmmoEmptyAtSeconds\": ${formatFloat(chargeNeedleSmallEmptiedAt)},")
        json.appendLine("  \"chargeNeedleVfxTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"chargeNeedleVfxLastSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        json.appendLine("  \"chargeNeedleDecayVerified\": $chargeNeedleDecayVerified,")
        json.appendLine("  \"chargeNeedleOwnProjectiles\": ${engine.projectiles.count { it.projectileSpecId in CHARGE_NEEDLE_PROJECTILE_SPEC_IDS }},")
        // ---- 舞台排障：三武器组/自动开火/AI 判定状态 ----
        // WS 004 判别轮该槽挂的是小型针刺：heavyNeedleW 按槽位取（不拘 id），区分槽位阻断与规格阻断。
        val smallNeedleW =
            player?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.CHARGE_NEEDLE_WEAPON_ID && it.slot?.id == "WS 001" }
                ?: player?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.CHARGE_NEEDLE_WEAPON_ID }
        val heavyNeedleW = player?.allWeapons?.firstOrNull { it.slot?.id == "WS 004" }
        val enemyNeedleW = enemy?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.CHARGE_NEEDLE_WEAPON_ID }
        json.appendLine("  \"chargeNeedleSmallState\": ${jsonString(chargeNeedleWeaponState(player, smallNeedleW))},")
        json.appendLine("  \"chargeNeedleHeavyState\": ${jsonString(chargeNeedleWeaponState(player, heavyNeedleW))},")
        json.appendLine("  \"chargeNeedleEnemyState\": ${jsonString(chargeNeedleWeaponState(enemy, enemyNeedleW))},")
        json.appendLine("  \"chargeNeedleEnemyShieldState\": ${jsonString(chargeNeedleShieldState(enemy))},")
        json.appendLine("  \"chargeNeedlePlayerShieldState\": ${jsonString(chargeNeedleShieldState(player))},")
    }

    private companion object {
        // 电荷针刺场景：相位机与锚点。
        private const val CHARGE_NEEDLE_PHASE_SHIELD = "SHIELD"
        private const val CHARGE_NEEDLE_PHASE_HULL = "HULL"
        private const val CHARGE_NEEDLE_PHASE_CEASE = "CEASE"
        private const val CHARGE_NEEDLE_PHASE_COMPLETED = "COMPLETED"
        private const val CHARGE_NEEDLE_PLAYER_HULL = "wolf"
        private const val CHARGE_NEEDLE_ENEMY_HULL = "shrike"
        private val CHARGE_NEEDLE_PLAYER_ANCHOR = Vector2f(-350f, 0f)
        private val CHARGE_NEEDLE_ENEMY_ANCHOR = Vector2f(300f, 0f)
        private val CHARGE_NEEDLE_CAMERA_CENTER = Vector2f(0f, 0f)

        // 叠层相位达标层数：v2 小型针刺 40 层安全闸内、可在盾相期内稳定堆到。
        private const val CHARGE_NEEDLE_STACK_TARGET = 8
        private const val CHARGE_NEEDLE_DISCHARGE_TARGET = 1
        private const val CHARGE_NEEDLE_PHASE_TIMEOUT = 90f
        private val CHARGE_NEEDLE_PLAYER_WEAPON_IDS = setOf(
            ASTDInGameAutomationScenario.CHARGE_NEEDLE_WEAPON_ID,
            ASTDInGameAutomationScenario.CHARGE_NEEDLE_HEAVY_WEAPON_ID,
        )
        private val CHARGE_NEEDLE_ENEMY_WEAPON_IDS = setOf(ASTDInGameAutomationScenario.CHARGE_NEEDLE_WEAPON_ID)
        private val CHARGE_NEEDLE_PROJECTILE_SPEC_IDS = setOf(
            ASTDInGameAutomationScenario.CHARGE_NEEDLE_PROJECTILE_SPEC_ID,
            ASTDInGameAutomationScenario.CHARGE_NEEDLE_HEAVY_PROJECTILE_SPEC_ID,
        )
    }
}
