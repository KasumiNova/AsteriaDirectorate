package cn.kasuminova.astd.combat.automation.scenario.xc

import cn.kasuminova.astd.api.buff.buffHost
import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.AutomationTelemetryKeys.XC2_STAGE_MOD_ID
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingErosionStacks
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingTuning
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingWeaponEffect
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryCharge
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAIConfig
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

/**
 * XC-002 星翼场景（星翼折跃/虚数之翼/坠星残翼，断言点 XC2-A~XC2-F）。
 * 覆盖：折跃位移、现身充能结算、超翼过载、齐射星羽、振频蚀穿、主炮连射与断熵升格。
 */
class StarwingShiftScenario : AbstractAutomationScenario() {
    private var xc2Phase = XC2_PHASE_SPAWN
    private var xc2PhaseStartedAt = 0f

    // SHIFT（断言点 XC2-A/B/C）：激活锚/重试计数、折跃位移峰值、现身充能快照、主炮弹药前后对照。
    private var xc2ActivateAttempts = 0
    private var xc2ActivatedAt = -1f
    private var xc2ShiftDisplacementMax = 0f
    private var xc2DisplacementAsserted = false
    private var xc2ChargeAtEmerge = -1f
    private var xc2OverdriveSeen = false
    private var xc2AmmoBeforeShift = -1
    private var xc2AmmoReloadAsserted = false

    // WEAPON（断言点 XC2-D/E/F）：供给登记计数（主弹/断熵弹/星羽 ever-seen identity）、蚀穿层数峰值、穿透掉血。
    private var xc2MainShots = 0
    private var xc2EmpoweredSeen = 0
    private var xc2MotesSeen = 0
    private val xc2SeenMain = mutableSetOf<Int>()
    private val xc2SeenMotes = mutableSetOf<Int>()
    private val xc2EmpoweredStateSeen = mutableSetOf<Int>()
    private var xc2ErosionStacksMax = 0

    // 武器相位掉血证据：穿透 tick 累计伤害超统治者级原始结构值，改为「舞台结构冗余 +
    // 逐帧回血 + 逐帧差额累加」方式，不掉成 hulk（实体蒸发会让 findXc2Enemy 判失联），证据不冻结。
    private var xc2WeaponDamageAccum = 0f
    private var xc2EnemyHpPrevFrame = -1f
    private var xc2WeaponStageBuffed = false
    private var xc2WeaponDiagLogged = false

    override val scenarioId: String = ASTDInGameAutomationScenario.XC002_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isXc002Enabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockXc2Camera(engine)
        // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findXc2Player(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.XC002_SCENARIO_ID} combat plugin initialized")
    }

    private fun findXc2Player(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == XC2_PLAYER_HULL && !ship.isFighter }

    private fun findXc2Enemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == XC2_ENEMY_HULL && !ship.isFighter }

    /** 强制部署 mission reserves（范式同 deployGsReserveShips；已出场成员按 hull 判重跳过并移出后备）。 */
    private fun deployXc2ReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val anchor = when {
                    side == FleetSide.PLAYER && member.hullId == XC2_PLAYER_HULL -> XC2_PLAYER_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == XC2_ENEMY_HULL -> XC2_ENEMY_ANCHOR
                    else -> continue
                }
                if (findShipByHull(engine, member.hullId) != null) {
                    manager.removeFromReserves(member)
                    continue
                }
                val facing = if (side == FleetSide.ENEMY) 180f else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionXc2Phase(next: String) {
        ctx.log.info("[ASTD-Automation] xc2 phase $xc2Phase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        if (next == XC2_PHASE_FAILED) {
            ctx.log.info("[ASTD-Automation] xc2 FAILED reason: ${ctx.failureReason}")
        }
        xc2Phase = next
        xc2PhaseStartedAt = ctx.elapsed
    }

    /**
     * 舞台保活与站位：双方逐帧钉死锚点 + 舰 AI 置空。
     * [pinPlayerAt] 为 null 时不钉玩家舰位（折跃窗内滑行脚本逐帧改写 location，钉位会与其互搏），
     * 其余封锁照旧；玩家舰恒封锁相位键（星翼折跃即相位斗篷，IN/ACTIVE 期再按会提前退出）、
     * 恒回血清零辐能（舞台干净化）。靶舰护盾默认压下，
     * [enemyShieldUp] 仅在 WEAPON_SHIELD 相位放开（振频蚀穿只在星羽护盾命中路径产生）。
     */
    private fun stabilizeXc2Ships(
        engine: CombatEngineAPI,
        pinPlayerAt: Vector2f?,
        playerFacing: Float,
        blockSystem: Boolean,
        healEnemy: Boolean,
        allowFire: Boolean,
        enemyAnchor: Vector2f = XC2_ENEMY_ANCHOR,
        enemyShieldUp: Boolean = false,
        preservePlayerAI: Boolean = false,
    ) {
        val player = findXc2Player(engine)
        val enemy = findXc2Enemy(engine)
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            if (pinPlayerAt != null) {
                // 武器观测相位必须保留舰 AI：Starsector 0.98 中 setForceFireOneFrame 对无舰 AI 的舞台舰不生效；
                // 站位仍逐帧钉死，移动/系统/护盾命令照封。
                if (preservePlayerAI && player.shipAI == null) {
                    // 预备役出库的舰不带 AI，需补默认舰 AI（config 必须空实例，传 null 会让
                    // BasicShipAI.pickManeuver NPE，范式同 stabilizeGrgShips）。
                    player.shipAI = Global.getSettings().createDefaultShipAI(player, ShipAIConfig())
                }
                stabilizeShip(player, pinPlayerAt, playerFacing, allowFire = allowFire, preserveAI = preservePlayerAI)
            } else {
                player.shipAI = null
                player.shipTarget = null
                player.setControlsLocked(false)
                player.isHoldFireOneFrame = !allowFire
                player.blockCommandForOneFrame(ShipCommand.ACCELERATE)
                player.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS)
                player.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT)
                player.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT)
                player.blockCommandForOneFrame(ShipCommand.TURN_LEFT)
                player.blockCommandForOneFrame(ShipCommand.TURN_RIGHT)
                if (!allowFire) player.blockCommandForOneFrame(ShipCommand.FIRE)
            }
            player.hitpoints = player.maxHitpoints
            player.fluxTracker.currFlux = 0f
            player.fluxTracker.hardFlux = 0f
            if (blockSystem) player.blockCommandForOneFrame(ShipCommand.USE_SYSTEM)
            player.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK)
        }
        if (enemy != null && !enemy.isHulk) {
            stabilizeShip(enemy, enemyAnchor, 180f, allowFire = false, preserveAI = false)
            if (healEnemy) enemy.hitpoints = enemy.maxHitpoints
            enemy.fluxTracker.currFlux = 0f
            enemy.fluxTracker.hardFlux = 0f
            if (enemyShieldUp) {
                enemy.shield?.let { if (!it.isOn) it.toggleOn() }
            } else {
                enemy.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK)
                enemy.shield?.let { if (it.isOn) it.toggleOff() }
            }
        }
    }

    private fun lockXc2Camera(engine: CombatEngineAPI) {
        lockCameraAt(engine, XC2_CAMERA_CENTER, XC2_CAMERA_VISIBLE_HEIGHT)
    }

    /**
     * 供给登记核对：主弹按 engine.projectiles 扫描两套 spec 计数，断熵弹同时校验状态表登记；
     * 星羽按 engine.missiles 扫描 specId 计数。普通光梭走原版碰撞，不再登记弹体状态。
     */
    private fun trackXc2WeaponSupply(engine: CombatEngineAPI, weapon: WeaponAPI) {
        for (proj in engine.projectiles) {
            val specId = proj.projectileSpecId ?: continue
            val empowered = when (specId) {
                StarfallWingTuning.EMPOWERED_SHOT_SPEC_ID -> true
                StarfallWingTuning.SHOT_SPEC_ID -> false
                else -> continue
            }
            if (proj.source?.owner != 0) continue
            val key = System.identityHashCode(proj)
            if (xc2SeenMain.add(key)) {
                xc2MainShots++
                if (empowered) xc2EmpoweredSeen++
            }
        }
        for ((proj, state) in StarfallWingWeaponEffect.projectileStates(engine)) {
            if (state.ownerWeapon !== weapon) continue
            if (proj.projectileSpecId != StarfallWingTuning.EMPOWERED_SHOT_SPEC_ID) continue
            xc2EmpoweredStateSeen.add(System.identityHashCode(proj))
        }
        for (missile in engine.missiles) {
            if (missile.projectileSpecId != StarfallWingTuning.MOTE_SPEC_ID) continue
            if (missile.source?.owner != 0) continue
            val key = System.identityHashCode(missile)
            if (xc2SeenMotes.add(key)) xc2MotesSeen++
        }
    }

    /**
     * 武器相位掉血证据累计：逐帧差额累加后立即回满结构（配合 WEAPON 转段时垫的
     * [XC2_STAGE_ENEMY_HULL_BUFFER] 结构冗余），穿透 tick 伤害远超原始结构值，
     * 不回血会掉成 hulk 甚至实体蒸发（findXc2Enemy 判失联），证据也会随 hulk 冻结。
     */
    private fun accumulateXc2WeaponDamage(enemy: ShipAPI) {
        if (!xc2WeaponStageBuffed || enemy.isHulk) return
        val hpNow = enemy.hitpoints
        if (xc2EnemyHpPrevFrame >= 0f && hpNow < xc2EnemyHpPrevFrame) {
            xc2WeaponDamageAccum += xc2EnemyHpPrevFrame - hpNow
        }
        enemy.hitpoints = enemy.maxHitpoints
        xc2EnemyHpPrevFrame = enemy.hitpoints
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployXc2ReserveShips(engine)
        lockXc2Camera(engine)

        val player = findXc2Player(engine)
        val enemy = findXc2Enemy(engine)
        val system = player?.system
        val weapon = player?.allWeapons?.firstOrNull { it.id == XC2_WEAPON_ID }

        when (xc2Phase) {
            XC2_PHASE_SPAWN -> {
                stabilizeXc2Ships(
                    engine, pinPlayerAt = XC2_PLAYER_ANCHOR, playerFacing = 0f,
                    blockSystem = true, healEnemy = true, allowFire = false,
                )
                if (player != null && enemy != null && ctx.elapsed - xc2PhaseStartedAt >= XC2_SPAWN_SETTLE_SECONDS) {
                    when {
                        system == null || system.id != XC2_SYSTEM_ID -> {
                            ctx.failureReason = "xc2 system id=${system?.id}, expect $XC2_SYSTEM_ID（ship_systems.csv 生成物未刷新）"
                            transitionXc2Phase(XC2_PHASE_FAILED)
                        }

                        weapon == null -> {
                            ctx.failureReason = "xc2 starfall wing not found on xc_002（内置主炮槽位装配缺失）"
                            transitionXc2Phase(XC2_PHASE_FAILED)
                        }

                        player.variant?.hasHullMod(XC2_HULLMOD_ID) != true -> {
                            ctx.failureReason = "xc2 hullmod $XC2_HULLMOD_ID missing（.ship builtInMods 未生效）"
                            transitionXc2Phase(XC2_PHASE_FAILED)
                        }

                        else -> transitionXc2Phase(XC2_PHASE_SHIFT)
                    }
                }
            }

            XC2_PHASE_SHIFT -> {
                val sinceActivate = if (xc2ActivatedAt >= 0f) ctx.elapsed - xc2ActivatedAt else -1f
                // 折跃窗内不钉舰位（滑行脚本逐帧改写 location）；窗外钉到到达锚点保舞台
                val pinAt = when {
                    sinceActivate < 0f -> XC2_PLAYER_ANCHOR
                    sinceActivate <= XC2_SHIFT_PIN_FREE_SECONDS -> null
                    else -> XC2_PLAYER_ARRIVAL
                }
                stabilizeXc2Ships(
                    engine, pinPlayerAt = pinAt, playerFacing = 0f,
                    blockSystem = xc2ActivatedAt >= 0f, healEnemy = false, allowFire = false,
                )
                if (player != null && enemy != null && system != null && weapon != null) {
                    val pool = ImaginaryCharge.of(player)
                    val systemState = system.state
                    if (xc2ActivatedAt < 0f && (systemState == ShipSystemAPI.SystemState.IN ||
                                systemState == ShipSystemAPI.SystemState.ACTIVE)
                    ) {
                        xc2ActivatedAt = ctx.elapsed
                        xc2AmmoBeforeShift = weapon.ammoTracker?.ammo ?: -1
                        ctx.log.info("[ASTD-Automation] xc2 activated: state=$systemState attempts=$xc2ActivateAttempts")
                    }
                    if (xc2ActivatedAt < 0f && systemState == ShipSystemAPI.SystemState.IDLE && system.cooldownRemaining <= 0f) {
                        // 按帧重试 useSystem()（单次调用可能被原版闸门吞掉，范式同 GS ACTIVATE）；
                        // 舞台舰静止钉位，位移方向回退舰首 +X，满距 ImaginaryWingsTuning.SHIFT_DISTANCE
                        xc2ActivateAttempts++
                        player.useSystem()
                    }
                    if (sinceActivate >= 0f) {
                        xc2ShiftDisplacementMax = maxOf(
                            xc2ShiftDisplacementMax,
                            MathUtils.getDistance(player.location, XC2_PLAYER_ANCHOR),
                        )
                        // 现身快照：超翼过载首次点亮即 OUT 结算完成，此刻充能应≈拉满 100 - 齐射 30
                        if (!xc2OverdriveSeen && pool.inOverdrive) {
                            xc2OverdriveSeen = true
                            xc2ChargeAtEmerge = pool.charge
                        }
                        if (!xc2DisplacementAsserted && sinceActivate >= XC2_SHIFT_ASSERT_SECONDS) {
                            xc2DisplacementAsserted = true
                            if (xc2ShiftDisplacementMax !in XC2_SHIFT_DISPLACEMENT_MIN..XC2_SHIFT_DISPLACEMENT_MAX) {
                                ctx.failureReason = "xc2 shift displacement=${"%.0f".format(xc2ShiftDisplacementMax)}" +
                                        " ∉ [$XC2_SHIFT_DISPLACEMENT_MIN, $XC2_SHIFT_DISPLACEMENT_MAX]（断言点 XC2-A：" +
                                        "向飞行向量折跃 ${ImaginaryWingsTuning.SHIFT_DISTANCE.toInt()}su，界由 SHIFT_DISTANCE ±100 派生）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }
                        }
                        if (!xc2AmmoReloadAsserted && xc2OverdriveSeen) {
                            xc2AmmoReloadAsserted = true
                            val tracker = weapon.ammoTracker
                            val ammoNow = tracker?.ammo ?: -1
                            if (tracker != null && xc2AmmoBeforeShift >= 0 && xc2AmmoBeforeShift < tracker.maxAmmo &&
                                ammoNow <= xc2AmmoBeforeShift
                            ) {
                                ctx.failureReason = "xc2 ammo reload missing: before=$xc2AmmoBeforeShift after=$ammoNow" +
                                        "（断言点 XC2-C：现身主炮瞬装）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }
                        }
                        if (xc2Phase == XC2_PHASE_SHIFT && sinceActivate >= XC2_SHIFT_WINDOW_SECONDS) {
                            when {
                                !xc2OverdriveSeen -> {
                                    ctx.failureReason = "xc2 overdrive not seen（断言点 XC2-C：现身 3s 超翼过载未点亮）"
                                    transitionXc2Phase(XC2_PHASE_FAILED)
                                }

                                xc2ChargeAtEmerge !in XC2_EMERGE_CHARGE_MIN..XC2_EMERGE_CHARGE_MAX -> {
                                    ctx.failureReason = "xc2 emerge charge=${"%.1f".format(xc2ChargeAtEmerge)}" +
                                            " ∉ [$XC2_EMERGE_CHARGE_MIN, $XC2_EMERGE_CHARGE_MAX]（断言点 XC2-B：" +
                                            "现身充能拉满 100 并消耗 30，快照容忍回充与首拍弹幕）"
                                    transitionXc2Phase(XC2_PHASE_FAILED)
                                }

                                else -> {
                                    ctx.log.info(
                                        "[ASTD-Automation] xc2 shift evidence: displacement=${"%.0f".format(xc2ShiftDisplacementMax)} " +
                                                "emergeCharge=${"%.1f".format(xc2ChargeAtEmerge)}（断言点 XC2-A/B/C）",
                                    )
                                    // 舞台结构冗余：穿透 tick 单发即可打穿统治者级原始结构值，
                                    // 先垫结构池（武器相位逐帧回血 + 差额累加掉血证据）
                                    enemy.armorGrid?.grid?.forEach { row -> row.fill(0f) }
                                    enemy.mutableStats.hullBonus.modifyFlat(XC2_STAGE_MOD_ID, XC2_STAGE_ENEMY_HULL_BUFFER)
                                    enemy.hitpoints = enemy.maxHitpoints
                                    xc2EnemyHpPrevFrame = enemy.hitpoints
                                    xc2WeaponDamageAccum = 0f
                                    xc2WeaponStageBuffed = true
                                    transitionXc2Phase(XC2_PHASE_WEAPON_SHIELD)
                                }
                            }
                        }
                    }
                    if (xc2Phase == XC2_PHASE_SHIFT && xc2ActivatedAt < 0f &&
                        ctx.elapsed - xc2PhaseStartedAt >= XC2_ACTIVATE_TIMEOUT
                    ) {
                        ctx.failureReason = "xc2 activate timeout: ${XC2_ACTIVATE_TIMEOUT.toInt()}s 内系统未点亮" +
                                "（attempts=$xc2ActivateAttempts state=${system.state} cd=${"%.1f".format(system.cooldownRemaining)}）"
                        transitionXc2Phase(XC2_PHASE_FAILED)
                    }
                }
            }

            XC2_PHASE_WEAPON_SHIELD -> {
                // 盾相观测：靶舰护盾放开（蚀穿只在星羽护盾命中路径产生），充能钉满强制弹幕连发
                stabilizeXc2Ships(
                    engine, pinPlayerAt = XC2_WEAPON_PLAYER_ANCHOR, playerFacing = 0f,
                    blockSystem = true, healEnemy = false, allowFire = true,
                    enemyAnchor = XC2_WEAPON_ENEMY_ANCHOR, enemyShieldUp = true,
                    preservePlayerAI = true,
                )
                if (player != null && enemy != null && weapon != null) {
                    accumulateXc2WeaponDamage(enemy)
                    ImaginaryCharge.of(player).charge = ImaginaryWingsTuning.CHARGE_MAX
                    player.shipTarget = enemy
                    weapon.currAngle = Misc.getAngleInDegrees(weapon.location, enemy.location)
                    // 不得逐帧 setRemainingCooldownTo(0f)：该调用会把武器开火周期反复重置导致
                    // 零弹体（同电荷针刺场景注释）。
                    weapon.setForceFireOneFrame(true)
                    trackXc2WeaponSupply(engine, weapon)
                    val stacks = (enemy.buffHost().find(StarfallWingErosionStacks.BUFF_ID)
                            as? StarfallWingErosionStacks)?.stacks
                    if (stacks != null) xc2ErosionStacksMax = maxOf(xc2ErosionStacksMax, stacks)
                    if (ctx.elapsed - xc2PhaseStartedAt >= XC2_WEAPON_SHIELD_EVAL_SECONDS) {
                        when {
                            xc2MotesSeen < XC2_EXPECT_MOTES -> {
                                ctx.failureReason = "xc2 motes=$xc2MotesSeen < $XC2_EXPECT_MOTES" +
                                        "（断言点 XC2-D 前置：星羽弹幕/齐射未登记）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            xc2ErosionStacksMax < XC2_EXPECT_STACKS_MIN -> {
                                ctx.failureReason = "xc2 erosion stacks max=$xc2ErosionStacksMax" +
                                        " < $XC2_EXPECT_STACKS_MIN（断言点 XC2-D：星羽撞盾附加 1 层振频蚀穿）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            else -> {
                                ctx.log.info(
                                    "[ASTD-Automation] xc2 shield-phase evidence: stacksMax=$xc2ErosionStacksMax " +
                                            "main=$xc2MainShots empowered=$xc2EmpoweredSeen motes=$xc2MotesSeen（断言点 XC2-D）",
                                )
                                transitionXc2Phase(XC2_PHASE_WEAPON_HULL)
                            }
                        }
                    }
                }
            }

            XC2_PHASE_WEAPON_HULL -> {
                // 体相观测：靶舰压盾，主炮强火，穿透掉血 + 供给登记计数辅助评估
                stabilizeXc2Ships(
                    engine, pinPlayerAt = XC2_WEAPON_PLAYER_ANCHOR, playerFacing = 0f,
                    blockSystem = true, healEnemy = false, allowFire = true,
                    enemyAnchor = XC2_WEAPON_ENEMY_ANCHOR, enemyShieldUp = false,
                    preservePlayerAI = true,
                )
                if (player != null && enemy != null && weapon != null) {
                    accumulateXc2WeaponDamage(enemy)
                    ImaginaryCharge.of(player).charge = ImaginaryWingsTuning.CHARGE_MAX
                    player.shipTarget = enemy
                    weapon.currAngle = Misc.getAngleInDegrees(weapon.location, enemy.location)
                    weapon.setForceFireOneFrame(true)
                    if (!xc2WeaponDiagLogged) {
                        xc2WeaponDiagLogged = true
                        ctx.log.info(
                            "[ASTD-Automation] xc2 weapon diag: disabled=${weapon.isDisabled} " +
                                    "permDisabled=${weapon.isPermanentlyDisabled} cd=${"%.2f".format(weapon.cooldownRemaining)} " +
                                    "ammo=${if (weapon.usesAmmo()) weapon.ammo else -1} slot=${weapon.slot?.id} " +
                                    "arc=${weapon.slot?.arc} type=${weapon.slot?.weaponType} " +
                                    "ai=${player.shipAI != null} " +
                                    "dist=${"%.0f".format(MathUtils.getDistance(player.location, enemy.location))}",
                        )
                    }
                    trackXc2WeaponSupply(engine, weapon)
                    if (ctx.elapsed - xc2PhaseStartedAt >= XC2_WEAPON_HULL_EVAL_SECONDS) {
                        val weaponDrop = xc2WeaponDamageAccum
                        when {
                            xc2MainShots < XC2_EXPECT_MAIN_SHOTS -> {
                                ctx.failureReason = "xc2 main shots=$xc2MainShots < $XC2_EXPECT_MAIN_SHOTS" +
                                        "（断言点 XC2-E 前置：坠星残翼供给登记，onFireEffect 未产出弹体状态）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            xc2EmpoweredSeen < XC2_EXPECT_EMPOWERED -> {
                                ctx.failureReason = "xc2 empowered shots=$xc2EmpoweredSeen < $XC2_EXPECT_EMPOWERED" +
                                        "（断言点 XC2-E：断熵计数按周期升格未产出强化贯通弹）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            xc2EmpoweredStateSeen.size < XC2_EXPECT_EMPOWERED -> {
                                ctx.failureReason = "xc2 empowered states=${xc2EmpoweredStateSeen.size}" +
                                        " < $XC2_EXPECT_EMPOWERED（断言点 XC2-E：强化贯通弹未登记扫掠状态）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            weaponDrop < XC2_EXPECT_WEAPON_HP_DROP -> {
                                ctx.failureReason = "xc2 weapon damage shortfall: hpDrop=${"%.0f".format(weaponDrop)}" +
                                        " < $XC2_EXPECT_WEAPON_HP_DROP（断言点 XC2-F：普通弹全额接触结算 + 断熵穿透 tick）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            else -> {
                                ctx.log.info(
                                    "[ASTD-Automation] xc2 weapon evidence: main=$xc2MainShots empowered=$xc2EmpoweredSeen " +
                                            "motes=$xc2MotesSeen weaponDrop=${"%.0f".format(weaponDrop)}（断言点 XC2-E/F）",
                                )
                                transitionXc2Phase(XC2_PHASE_COMPLETED)
                            }
                        }
                    }
                }
            }

            XC2_PHASE_COMPLETED -> {
                stabilizeXc2Ships(
                    engine, pinPlayerAt = XC2_PLAYER_ARRIVAL, playerFacing = 0f,
                    blockSystem = true, healEnemy = true, allowFire = false,
                )
            }
        }

        val state = when {
            player == null || enemy == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason = "xc2 ships missing: player=${player != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            xc2Phase == XC2_PHASE_FAILED -> "Failed"
            xc2Phase != XC2_PHASE_COMPLETED &&
                    ctx.elapsed - xc2PhaseStartedAt > XC2_PHASE_TIMEOUT -> {
                ctx.failureReason = "xc2 phase timeout: $xc2Phase（displacement=${"%.0f".format(xc2ShiftDisplacementMax)} " +
                        "stacksMax=$xc2ErosionStacksMax main=$xc2MainShots empowered=$xc2EmpoweredSeen motes=$xc2MotesSeen）"
                "Failed"
            }

            xc2Phase == XC2_PHASE_COMPLETED -> "Completed"
            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: xc_002_rift_shift_basic shift/emerge/erosion/starfall-wing evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, null)
        }
    }

    // 捕获帧间隔 0.6s：星翼/靶舰舞台（折跃星云轨迹与光翼虚影）在三帧内进入捕获帧。
    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.6f, { findXc2Player(ctx.engine) }) { lockXc2Camera(ctx.engine) }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val xc2Player = findXc2Player(engine)
        val xc2System = xc2Player?.system
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // 机制证据（断言点 XC2-A~XC2-F）
        json.appendLine("  \"xc2Phase\": \"$xc2Phase\",")
        json.appendLine("  \"xc2SystemId\": ${jsonString(xc2System?.id)},")
        json.appendLine("  \"xc2SystemState\": ${jsonString(xc2System?.state?.name)},")
        json.appendLine("  \"xc2SystemCooldownRemaining\": ${formatFloat(xc2System?.cooldownRemaining ?: -1f)},")
        json.appendLine("  \"xc2ActivateAttempts\": $xc2ActivateAttempts,")
        json.appendLine("  \"xc2ShiftDisplacementMax\": ${formatFloat(xc2ShiftDisplacementMax)},")
        json.appendLine("  \"xc2ChargeAtEmerge\": ${formatFloat(xc2ChargeAtEmerge)},")
        json.appendLine("  \"xc2OverdriveSeen\": $xc2OverdriveSeen,")
        json.appendLine("  \"xc2MainShots\": $xc2MainShots,")
        json.appendLine("  \"xc2EmpoweredSeen\": $xc2EmpoweredSeen,")
        json.appendLine("  \"xc2MotesSeen\": $xc2MotesSeen,")
        json.appendLine(
            "  \"xc2WeaponHpDrop\": ${
                formatFloat(
                    if (!xc2WeaponStageBuffed) -1f else xc2WeaponDamageAccum
                )
            },"
        )
        json.appendLine("  \"xc2ErosionStacksMax\": $xc2ErosionStacksMax,")
        json.appendLine("  \"xc2PlayerCurrFlux\": ${formatFloat(xc2Player?.fluxTracker?.currFlux ?: -1f)},")
    }

    private companion object {
        // XC-002 星翼场景（星翼折跃/虚数之翼/坠星残翼）：相位机、锚点与期望证据（断言点 XC2-A~XC2-F）。
        private const val XC2_PHASE_SPAWN = "SPAWN"
        private const val XC2_PHASE_SHIFT = "SHIFT"
        private const val XC2_PHASE_WEAPON_SHIELD = "WEAPON_SHIELD"
        private const val XC2_PHASE_WEAPON_HULL = "WEAPON_HULL"
        private const val XC2_PHASE_COMPLETED = "COMPLETED"
        private const val XC2_PHASE_FAILED = "FAILED"
        private const val XC2_PLAYER_HULL = "astd_xc_002"
        private const val XC2_ENEMY_HULL = "dominator"
        private const val XC2_SYSTEM_ID = "astd_rift_shift"
        private const val XC2_WEAPON_ID = "astd_starfall_wing"
        private const val XC2_HULLMOD_ID = "astd_imaginary_wings"

        // 折跃舞台锚点：玩家舰静止朝 +X 激活（速度近零回退朝向，断言点 XC2-A 定向判定），
        // 到达锚点 = 起点 +X 推进 ImaginaryWingsTuning.SHIFT_DISTANCE（恰为满距，
        // 位移断言界亦由该常量派生，改折跃距离无需双处同步）；靶舰钉在到达锚点侧翼
        // （现身 250su 净空只抹弹体不伤舰，齐射星羽与弹幕以靶舰为最近目标）。
        private val XC2_PLAYER_ANCHOR = Vector2f(-800f, -100f)
        private val XC2_ENEMY_ANCHOR = Vector2f(-300f, -100f)
        private val XC2_PLAYER_ARRIVAL = Vector2f(XC2_PLAYER_ANCHOR.x + ImaginaryWingsTuning.SHIFT_DISTANCE, XC2_PLAYER_ANCHOR.y)

        // 武器舞台锚点（与折跃舞台分离，靶舰重置站位）：玩家正前方 500su（坠星残翼射程 900 内）。
        private val XC2_WEAPON_PLAYER_ANCHOR = Vector2f(-800f, 300f)
        private val XC2_WEAPON_ENEMY_ANCHOR = Vector2f(-300f, 300f)
        private val XC2_CAMERA_CENTER = Vector2f(-400f, 100f)
        private const val XC2_CAMERA_VISIBLE_HEIGHT = 1800f
        private const val XC2_SPAWN_SETTLE_SECONDS = 0.6f

        // SHIFT（断言点 XC2-A/B/C）：折跃位移界从 SHIFT_DISTANCE 常量 ±100su 派生（容忍钉位恢复帧）；
        // 折跃全程 0.85s（IN 0.15 + ACTIVE 0.6 + OUT 0.1），1.0s 内不钉舰位；1.5s 评估位移；
        // 现身充能快照界 [55, 85]（拉满 100 - 齐射 30 = 70，容忍快照前的回充与首拍弹幕消耗）；
        // 2.5s 转武器相位（超翼过载 3s 窗口内完成快照即可）。
        private const val XC2_ACTIVATE_TIMEOUT = 10f
        private const val XC2_SHIFT_PIN_FREE_SECONDS = 1.0f
        private const val XC2_SHIFT_ASSERT_SECONDS = 1.5f
        private const val XC2_SHIFT_DISPLACEMENT_TOLERANCE = 100f
        private const val XC2_SHIFT_DISPLACEMENT_MIN = ImaginaryWingsTuning.SHIFT_DISTANCE - XC2_SHIFT_DISPLACEMENT_TOLERANCE
        private const val XC2_SHIFT_DISPLACEMENT_MAX = ImaginaryWingsTuning.SHIFT_DISTANCE + XC2_SHIFT_DISPLACEMENT_TOLERANCE
        private const val XC2_EMERGE_CHARGE_MIN = 55f
        private const val XC2_EMERGE_CHARGE_MAX = 85f
        private const val XC2_SHIFT_WINDOW_SECONDS = 2.5f

        // WEAPON_SHIELD/HULL（断言点 XC2-D/E/F）：盾相 6s 评估蚀穿峰值 ≥1（充能钉满弹幕连发，
        // 0.3s 节拍 2 枚，撞盾各附加 1 层）；体相 8s 评估掉血 ≥300（普通弹接触全额 200 +
        // 断熵穿透 tick，靶舰垫舞台结构冗余并逐帧回血、掉血按逐帧差额累加，装甲已剥光）；
        // 供给登记主弹 ≥6（0.15s/发 × 8s 理论 53 发，弹匣 24 + 4/s 回充限速）、断熵 ≥1
        // （砺刃每第 6 发升格）、星羽 ≥6（现身齐射 6 枚即达标）。
        private const val XC2_WEAPON_SHIELD_EVAL_SECONDS = 6f
        private const val XC2_WEAPON_HULL_EVAL_SECONDS = 8f
        private const val XC2_EXPECT_STACKS_MIN = 1
        private const val XC2_EXPECT_WEAPON_HP_DROP = 300f
        private const val XC2_EXPECT_MAIN_SHOTS = 6
        private const val XC2_EXPECT_EMPOWERED = 1
        private const val XC2_EXPECT_MOTES = 6
        private const val XC2_PHASE_TIMEOUT = 120f

        // 武器相位舞台结构冗余：断熵穿透 tick 量级（面板×25%/0.05s 拍 × 多拍）仍超统治者级
        // 原始结构值（断言点 XC2-F 舞台保全）。
        private const val XC2_STAGE_ENEMY_HULL_BUFFER = 500000f
    }
}
