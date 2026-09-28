package cn.kasuminova.astd.combat.automation.scenario.xc

import cn.kasuminova.astd.api.buff.buffHost
import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.AutomationTelemetryKeys.XC2_STAGE_MOD_ID
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingAdaptationStacks
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingOnFireEffect
import cn.kasuminova.astd.combat.effect.arc.starfallwing.StarfallWingTuning
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning
import cn.kasuminova.astd.combat.shipsystems.RiftShiftTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAIConfig
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

/**
 * XC-002 星翼场景（裂隙折跃/虚数之翼/坠星残翼，断言点 XC2-A~XC2-G）。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class RiftShiftScenario : AbstractAutomationScenario() {
    // ==== XC-002 星翼场景状态（相位机 SPAWN → WINGS_OBSERVE → SHIFT → CLOSURE → WEAPON_SHIELD → WEAPON_HULL → COMPLETED） ====
    private var xc2Phase = XC2_PHASE_SPAWN
    private var xc2PhaseStartedAt = 0f

    // WINGS_OBSERVE（断言点 XC2-A）：静止（0% 航速）伤害乘区谷值与静止 maxSpeed（含零幅能加速等常驻修正，
    // 作为 XC2-C 速度窗口乘区的同口径分母——窗口峰值 +100% 是百分比乘区，与零幅能 flat 叠乘后绝对比值不是 2.0）。
    private var xc2RestDamageMultMin = Float.MAX_VALUE
    private var xc2RestMaxSpeed = -1f

    // SHIFT（断言点 XC2-B/C/D）：激活锚/重试计数、折跃位移峰值、速度窗口 maxSpeed 乘区峰值、接触期靶舰 HP 谷值。
    private var xc2ActivateAttempts = 0
    private var xc2ActivatedAt = -1f
    private var xc2ShiftDisplacementMax = 0f
    private var xc2SpeedMultMax = 0f
    private var xc2DisplacementAsserted = false
    private var xc2EnemyHpAtShiftStart = -1f
    private var xc2EnemyHpMinBeforeClosure = Float.MAX_VALUE

    // CLOSURE（断言点 XC2-E）：闭合窗前后靶舰 HP 对账。
    private var xc2EnemyHpAtClosureStart = -1f
    private var xc2EnemyHpMinAfterClosure = Float.MAX_VALUE

    // WEAPON（断言点 XC2-F/G）：供给登记计数（主弹/子射弹 ever-seen identity）、盾相叠层峰值、体相穿透掉血。
    private var xc2MainShots = 0
    private var xc2MotesSeen = 0
    private val xc2SeenMain = mutableSetOf<Int>()
    private val xc2SeenMotes = mutableSetOf<Int>()
    private var xc2AdaptationStacksMax = 0f

    // 武器相位掉血证据：穿透结算（单点 20% 面板/0.1s 拍）累计伤害超统治者级原始结构值，改为「舞台结构冗余 +
    // 逐帧奶回 + 逐帧差额累加」口径——不掉成 hulk（实体蒸发会让 findXc2Enemy 判失联），证据不冻结。
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
     * 舞台保活与站位（范式同 stabilizeGsShips）：双方逐帧钉死锚点 + 舰 AI 置空。
     * [pinPlayerAt] 为 null 时不钉玩家舰位（折跃窗内裂隙插件逐帧改写 location，钉位会与其互搏），
     * 其余封锁照旧；玩家舰恒封锁相位键（裂隙折跃即相位斗篷，IN/ACTIVE 期再按会提前退出）、
     * 恒奶血清零辐能（舞台干净化）。靶舰护盾默认压下（接触/爆炸/穿透结算落船体），
     * [enemyShieldUp] 仅在 WEAPON_SHIELD 相位放开（振频适应叠层只在护盾命中路径产生）。
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
                // 武器观测相位必须保留舰 AI：setForceFireOneFrame 对无舰 AI 的舞台舰不生效
                // （电荷针刺场景实机 90s 零发射判例）；站位仍逐帧钉死，移动/系统/护盾命令照封。
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
     * WEAPON 相位供给登记对账（范式同 pollGdWarheads 的登记簿口径）：主弹走 onFireEffect
     * 登记表 ever-seen identity；子射弹已回归原版碰撞（不登记脚本状态表），改按
     * engine.missiles 扫描 projectileSpecId 计数。
     */
    private fun trackXc2WeaponSupply(engine: CombatEngineAPI, weapon: WeaponAPI) {
        for ((proj, state) in StarfallWingOnFireEffect.projectileStates(engine)) {
            if (state.ownerWeapon !== weapon) continue
            val key = System.identityHashCode(proj)
            if (xc2SeenMain.add(key)) xc2MainShots++
        }
        for (missile in engine.missiles) {
            if (missile.projectileSpecId != StarfallWingTuning.MOTE_SPEC_ID) continue
            if (missile.source?.owner != 0) continue
            val key = System.identityHashCode(missile)
            if (xc2SeenMotes.add(key)) xc2MotesSeen++
        }
    }

    /**
     * 武器相位掉血证据累计：逐帧差额累加后立即奶回满结构（配合 CLOSURE 转段时垫的
     * [XC2_STAGE_ENEMY_HULL_BUFFER] 结构冗余）——全格穿透单拍伤害远超原始结构值，
     * 不奶回会掉成 hulk 甚至实体蒸发（findXc2Enemy 判失联），证据也会随 hulk 冻结。
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
                    transitionXc2Phase(XC2_PHASE_WINGS_OBSERVE)
                }
            }

            XC2_PHASE_WINGS_OBSERVE -> {
                stabilizeXc2Ships(
                    engine, pinPlayerAt = XC2_PLAYER_ANCHOR, playerFacing = 0f,
                    blockSystem = true, healEnemy = true, allowFire = false,
                )
                if (player != null && !player.isHulk) {
                    xc2RestDamageMultMin = minOf(
                        xc2RestDamageMultMin,
                        player.mutableStats.energyWeaponDamageMult.modifiedValue,
                    )
                    xc2RestMaxSpeed = player.mutableStats.maxSpeed.modifiedValue
                }
                if (player != null && enemy != null && ctx.elapsed - xc2PhaseStartedAt >= XC2_WINGS_OBSERVE_SECONDS) {
                    when {
                        system == null || system.id != XC2_SYSTEM_ID -> {
                            ctx.failureReason = "xc2 system id=${system?.id}, expect $XC2_SYSTEM_ID（ship_data.csv 生成物未刷新）"
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

                        xc2RestDamageMultMin !in XC2_REST_DAMAGE_MULT_MIN..XC2_REST_DAMAGE_MULT_MAX -> {
                            ctx.failureReason = "xc2 rest damageMult=${"%.3f".format(xc2RestDamageMultMin)}" +
                                    " ∉ [$XC2_REST_DAMAGE_MULT_MIN, $XC2_REST_DAMAGE_MULT_MAX]（断言点 XC2-A：虚数之翼 0% 航速 −25%）"
                            transitionXc2Phase(XC2_PHASE_FAILED)
                        }

                        else -> {
                            ctx.log.info(
                                "[ASTD-Automation] xc2 wings evidence: restDamageMultMin=${"%.3f".format(xc2RestDamageMultMin)}（断言点 XC2-A）",
                            )
                            enemy.armorGrid?.grid?.forEach { row -> row.fill(0f) }
                            xc2EnemyHpAtShiftStart = enemy.hitpoints
                            transitionXc2Phase(XC2_PHASE_SHIFT)
                        }
                    }
                }
            }

            XC2_PHASE_SHIFT -> {
                val sinceActivate = if (xc2ActivatedAt >= 0f) ctx.elapsed - xc2ActivatedAt else -1f
                // 折跃窗内不钉舰位（裂隙插件逐帧改写 location）；窗外钉到到达锚点保舞台
                val pinAt = when {
                    sinceActivate < 0f -> XC2_PLAYER_ANCHOR
                    sinceActivate <= XC2_SHIFT_PIN_FREE_SECONDS -> null
                    else -> XC2_PLAYER_ARRIVAL
                }
                stabilizeXc2Ships(
                    engine, pinPlayerAt = pinAt, playerFacing = 0f,
                    blockSystem = false, healEnemy = false, allowFire = false,
                )
                if (player != null && enemy != null && system != null) {
                    val systemState = system.state
                    if (xc2ActivatedAt < 0f && (systemState == ShipSystemAPI.SystemState.IN ||
                                systemState == ShipSystemAPI.SystemState.ACTIVE)
                    ) {
                        xc2ActivatedAt = ctx.elapsed
                        ctx.log.info("[ASTD-Automation] xc2 activated: state=$systemState attempts=$xc2ActivateAttempts")
                    }
                    if (xc2ActivatedAt < 0f && systemState == ShipSystemAPI.SystemState.IDLE && system.cooldownRemaining <= 0f) {
                        // 按帧重试 useSystem()（单次调用可能被原版闸门吞掉，范式同 GS ACTIVATE）；
                        // 变距折跃目标点经 AI 决策通道 SYSTEM_TARGET_COORDS 注入到达锚点
                        // （原版 MineStrikeStats 同款口径；舞台舰无鼠标，确定性满距 1000su）
                        xc2ActivateAttempts++
                        player.aiFlags.setFlag(
                            ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS, 1f, Vector2f(XC2_PLAYER_ARRIVAL),
                        )
                        player.useSystem()
                    }
                    if (sinceActivate >= 0f) {
                        xc2ShiftDisplacementMax = maxOf(
                            xc2ShiftDisplacementMax,
                            MathUtils.getDistance(player.location, XC2_PLAYER_ANCHOR),
                        )
                        xc2SpeedMultMax = maxOf(
                            xc2SpeedMultMax,
                            player.mutableStats.maxSpeed.modifiedValue /
                                (if (xc2RestMaxSpeed > 0f) xc2RestMaxSpeed else player.mutableStats.maxSpeed.baseValue),
                        )
                        if (!enemy.isHulk) {
                            xc2EnemyHpMinBeforeClosure = minOf(xc2EnemyHpMinBeforeClosure, enemy.hitpoints)
                        }
                        if (!xc2DisplacementAsserted && sinceActivate >= XC2_SHIFT_ASSERT_SECONDS) {
                            xc2DisplacementAsserted = true
                            if (xc2ShiftDisplacementMax !in XC2_SHIFT_DISPLACEMENT_MIN..XC2_SHIFT_DISPLACEMENT_MAX) {
                                ctx.failureReason = "xc2 shift displacement=${"%.0f".format(xc2ShiftDisplacementMax)}" +
                                        " ∉ [$XC2_SHIFT_DISPLACEMENT_MIN, $XC2_SHIFT_DISPLACEMENT_MAX]（断言点 XC2-B：" +
                                        "向飞行向量折跃 ${RiftShiftTuning.SHIFT_DISTANCE.toInt()}su，界由 SHIFT_DISTANCE ±100 派生）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }
                        }
                        if (xc2Phase == XC2_PHASE_SHIFT && sinceActivate >= XC2_SHIFT_WINDOW_SECONDS) {
                            // 期望峰值按统计合成式实算：maxSpeed = (base × (1+窗口峰值%) + 常驻 flat 增量) / 静止值——
                            // 原版零幅能加速 flat 不吃百分比乘区（实机判例：115×2+50=280），硬编码绝对区间会误判。
                            val baseSpeed = player.mutableStats.maxSpeed.baseValue
                            val flatDelta = (xc2RestMaxSpeed - baseSpeed).coerceAtLeast(0f)
                            val peakPercent = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.SPEED_BOOST_PERCENT, true)
                            val expectedPeak = if (xc2RestMaxSpeed > 0f) {
                                (baseSpeed * (1f + peakPercent / 100f) + flatDelta) / xc2RestMaxSpeed
                            } else {
                                1f + peakPercent / 100f
                            }
                            val speedMin = expectedPeak - XC2_SPEED_MULT_TOLERANCE
                            val speedMax = expectedPeak + XC2_SPEED_MULT_TOLERANCE
                            if (xc2SpeedMultMax !in speedMin..speedMax) {
                                ctx.failureReason = "xc2 speedMultMax=${"%.3f".format(xc2SpeedMultMax)}" +
                                        " ∉ [${"%.3f".format(speedMin)}, ${"%.3f".format(speedMax)}]（断言点 XC2-C：" +
                                        "虚数之翼窗口砺刃 +${"%.0f".format(peakPercent)}%，期望峰值 ${"%.3f".format(expectedPeak)}，" +
                                        "相对静止 maxSpeed=${"%.1f".format(xc2RestMaxSpeed)} 的乘区口径）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            } else {
                                ctx.log.info(
                                    "[ASTD-Automation] xc2 shift evidence: displacement=${"%.0f".format(xc2ShiftDisplacementMax)} " +
                                            "speedMultMax=${"%.3f".format(xc2SpeedMultMax)}（断言点 XC2-B/C）",
                                )
                                xc2EnemyHpAtClosureStart = enemy.hitpoints
                                transitionXc2Phase(XC2_PHASE_CLOSURE)
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

            XC2_PHASE_CLOSURE -> {
                stabilizeXc2Ships(
                    engine, pinPlayerAt = XC2_PLAYER_ARRIVAL, playerFacing = 0f,
                    blockSystem = true, healEnemy = false, allowFire = false,
                )
                if (player != null && enemy != null && xc2ActivatedAt >= 0f) {
                    if (!enemy.isHulk) {
                        xc2EnemyHpMinAfterClosure = minOf(xc2EnemyHpMinAfterClosure, enemy.hitpoints)
                    }
                    if (ctx.elapsed - xc2ActivatedAt >= XC2_CLOSURE_EVAL_SECONDS) {
                        val contactDrop = xc2EnemyHpAtShiftStart - xc2EnemyHpMinBeforeClosure
                        val closureDrop = xc2EnemyHpAtClosureStart - xc2EnemyHpMinAfterClosure
                        when {
                            contactDrop < XC2_EXPECT_CONTACT_HP_DROP -> {
                                ctx.failureReason = "xc2 contact damage shortfall: hpDrop=${"%.0f".format(contactDrop)}" +
                                        " < $XC2_EXPECT_CONTACT_HP_DROP（断言点 XC2-D：成形掠过 0.1s/拍×400 + 驻留 0.2s/拍×200，靶舰钉在第 5 点位正中且装甲已剥光）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            closureDrop < XC2_EXPECT_CLOSURE_HP_DROP -> {
                                ctx.failureReason = "xc2 closure damage shortfall: hpDrop=${"%.0f".format(closureDrop)}" +
                                        " < $XC2_EXPECT_CLOSURE_HP_DROP（断言点 XC2-E：闭合同向扫掠——扫过点位即席结算一拍 400 + 扫前残余驻留，靶舰钉在第 5 点位正中）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            else -> {
                                ctx.log.info(
                                    "[ASTD-Automation] xc2 rift evidence: contactDrop=${"%.0f".format(contactDrop)} " +
                                            "closureDrop=${"%.0f".format(closureDrop)}（断言点 XC2-D/E）",
                                )
                                // 舞台结构冗余：全格穿透单拍即可打穿统治者级原始结构值，
                                // 先垫结构池（xc2 武器相位逐帧奶回 + 差额累加掉血证据）
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
            }

            XC2_PHASE_WEAPON_SHIELD -> {
                // 盾相观测：靶舰护盾放开（叠层只在护盾命中路径产生），主炮强火
                stabilizeXc2Ships(
                    engine, pinPlayerAt = XC2_WEAPON_PLAYER_ANCHOR, playerFacing = 0f,
                    blockSystem = true, healEnemy = false, allowFire = true,
                    enemyAnchor = XC2_WEAPON_ENEMY_ANCHOR, enemyShieldUp = true,
                    preservePlayerAI = true,
                )
                if (player != null && enemy != null && weapon != null) {
                    accumulateXc2WeaponDamage(enemy)
                    player.shipTarget = enemy
                    weapon.currAngle = Misc.getAngleInDegrees(weapon.location, enemy.location)
                    // 不得逐帧 setRemainingCooldownTo(0f)：实机验证会把武器开火周期反复重置导致
                    // 零弹体（范式同电荷针刺场景注释）。
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
                    val stacks = (enemy.buffHost().find(StarfallWingAdaptationStacks.BUFF_ID)
                            as? StarfallWingAdaptationStacks)?.stacks
                    if (stacks != null) xc2AdaptationStacksMax = maxOf(xc2AdaptationStacksMax, stacks)
                    if (ctx.elapsed - xc2PhaseStartedAt >= XC2_WEAPON_SHIELD_EVAL_SECONDS) {
                        if (xc2AdaptationStacksMax < XC2_EXPECT_STACKS_MIN) {
                            ctx.failureReason = "xc2 adaptation stacks max=${"%.1f".format(xc2AdaptationStacksMax)}" +
                                    " < $XC2_EXPECT_STACKS_MIN（断言点 XC2-F：主弹穿盾首触 +1 层/子射弹撞盾 +0.5 层）"
                            transitionXc2Phase(XC2_PHASE_FAILED)
                        } else {
                            ctx.log.info(
                                "[ASTD-Automation] xc2 shield-phase evidence: stacksMax=${"%.1f".format(xc2AdaptationStacksMax)} " +
                                        "main=$xc2MainShots motes=$xc2MotesSeen（断言点 XC2-F）",
                            )
                            transitionXc2Phase(XC2_PHASE_WEAPON_HULL)
                        }
                    }
                }
            }

            XC2_PHASE_WEAPON_HULL -> {
                // 体相观测：靶舰压盾，穿透掉血 + 供给登记计数兜底评估
                stabilizeXc2Ships(
                    engine, pinPlayerAt = XC2_WEAPON_PLAYER_ANCHOR, playerFacing = 0f,
                    blockSystem = true, healEnemy = false, allowFire = true,
                    enemyAnchor = XC2_WEAPON_ENEMY_ANCHOR, enemyShieldUp = false,
                    preservePlayerAI = true,
                )
                if (player != null && enemy != null && weapon != null) {
                    accumulateXc2WeaponDamage(enemy)
                    player.shipTarget = enemy
                    weapon.currAngle = Misc.getAngleInDegrees(weapon.location, enemy.location)
                    weapon.setForceFireOneFrame(true)
                    trackXc2WeaponSupply(engine, weapon)
                    if (ctx.elapsed - xc2PhaseStartedAt >= XC2_WEAPON_HULL_EVAL_SECONDS) {
                        val weaponDrop = xc2WeaponDamageAccum
                        when {
                            xc2MainShots < XC2_EXPECT_MAIN_SHOTS -> {
                                ctx.failureReason = "xc2 main shots=$xc2MainShots < $XC2_EXPECT_MAIN_SHOTS" +
                                        "（断言点 XC2-G 前置：坠星残翼供给登记，onFireEffect 未产出弹体状态）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            xc2MotesSeen < XC2_EXPECT_MOTES -> {
                                ctx.failureReason = "xc2 motes=$xc2MotesSeen < $XC2_EXPECT_MOTES" +
                                        "（断言点 XC2-G 前置：主弹 0.2s 散发子射弹未登记）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            weaponDrop < XC2_EXPECT_WEAPON_HP_DROP -> {
                                ctx.failureReason = "xc2 pierce damage shortfall: hpDrop=${"%.0f".format(weaponDrop)}" +
                                        " < $XC2_EXPECT_WEAPON_HP_DROP（断言点 XC2-G：穿透单点 面板×穿透比例/穿透节拍 + 子射弹结算）"
                                transitionXc2Phase(XC2_PHASE_FAILED)
                            }

                            else -> {
                                ctx.log.info(
                                    "[ASTD-Automation] xc2 weapon evidence: main=$xc2MainShots motes=$xc2MotesSeen " +
                                            "weaponDrop=${"%.0f".format(weaponDrop)}（断言点 XC2-G）",
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
                        "stacksMax=${"%.1f".format(xc2AdaptationStacksMax)} main=$xc2MainShots motes=$xc2MotesSeen）"
                "Failed"
            }

            xc2Phase == XC2_PHASE_COMPLETED -> "Completed"
            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: xc_002_rift_shift_basic wings/shift/rift-closure/starfall-wing evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, null)
        }
    }

    // 捕获帧间隔 0.6s：星翼/靶舰舞台（裂隙星云残响与光翼虚影）在三帧内进入捕获帧。
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
        // ---- 机制证据（断言点 XC2-A~XC2-G）----
        json.appendLine("  \"xc2Phase\": \"$xc2Phase\",")
        json.appendLine("  \"xc2SystemId\": ${jsonString(xc2System?.id)},")
        json.appendLine("  \"xc2SystemState\": ${jsonString(xc2System?.state?.name)},")
        json.appendLine("  \"xc2SystemCooldownRemaining\": ${formatFloat(xc2System?.cooldownRemaining ?: -1f)},")
        json.appendLine("  \"xc2RestDamageMultMin\": ${formatFloat(if (xc2RestDamageMultMin == Float.MAX_VALUE) -1f else xc2RestDamageMultMin)},")
        json.appendLine("  \"xc2ActivateAttempts\": $xc2ActivateAttempts,")
        json.appendLine("  \"xc2ShiftDisplacementMax\": ${formatFloat(xc2ShiftDisplacementMax)},")
        json.appendLine("  \"xc2SpeedMultMax\": ${formatFloat(xc2SpeedMultMax)},")
        json.appendLine("  \"xc2RestMaxSpeed\": ${formatFloat(xc2RestMaxSpeed)},")
        json.appendLine(
            "  \"xc2ContactHpDrop\": ${
                formatFloat(
                    if (xc2EnemyHpAtShiftStart < 0f || xc2EnemyHpMinBeforeClosure == Float.MAX_VALUE) -1f
                    else xc2EnemyHpAtShiftStart - xc2EnemyHpMinBeforeClosure
                )
            },"
        )
        json.appendLine(
            "  \"xc2ClosureHpDrop\": ${
                formatFloat(
                    if (xc2EnemyHpAtClosureStart < 0f || xc2EnemyHpMinAfterClosure == Float.MAX_VALUE) -1f
                    else xc2EnemyHpAtClosureStart - xc2EnemyHpMinAfterClosure
                )
            },"
        )
        json.appendLine("  \"xc2MainShots\": $xc2MainShots,")
        json.appendLine("  \"xc2MotesSeen\": $xc2MotesSeen,")
        json.appendLine(
            "  \"xc2WeaponHpDrop\": ${
                formatFloat(
                    if (!xc2WeaponStageBuffed) -1f else xc2WeaponDamageAccum
                )
            },"
        )
        json.appendLine("  \"xc2AdaptationStacksMax\": ${formatFloat(xc2AdaptationStacksMax)},")
        json.appendLine("  \"xc2PlayerCurrFlux\": ${formatFloat(xc2Player?.fluxTracker?.currFlux ?: -1f)},")
    }

    private companion object {
        // XC-002 星翼场景（裂隙折跃/虚数之翼/坠星残翼）：相位机、锚点与期望证据（断言点 XC2-A~XC2-G）。
        private const val XC2_PHASE_SPAWN = "SPAWN"
        private const val XC2_PHASE_WINGS_OBSERVE = "WINGS_OBSERVE"
        private const val XC2_PHASE_SHIFT = "SHIFT"
        private const val XC2_PHASE_CLOSURE = "CLOSURE"
        private const val XC2_PHASE_WEAPON_SHIELD = "WEAPON_SHIELD"
        private const val XC2_PHASE_WEAPON_HULL = "WEAPON_HULL"
        private const val XC2_PHASE_COMPLETED = "COMPLETED"
        private const val XC2_PHASE_FAILED = "FAILED"
        private const val XC2_PLAYER_HULL = "astd_xc_002"
        private const val XC2_ENEMY_HULL = "dominator"
        private const val XC2_SYSTEM_ID = "astd_rift_shift"
        private const val XC2_WEAPON_ID = "astd_starfall_wing"
        private const val XC2_HULLMOD_ID = "astd_imaginary_wings"

        // 折跃舞台锚点：玩家舰静止朝 +X 激活（速度近零回退朝向，断言点 XC2-B 定向口径），
        // 变距折跃目标点经 SYSTEM_TARGET_COORDS 注入到达锚点（= 起点 +X 推进 RiftShiftTuning.SHIFT_DISTANCE，
        // 恰为满距——到达锚点同源派生，位移断言界亦由该常量派生，改折跃距离无需双处同步）；
        // 伤害点位/虚空锚雷沿路径步进 100su（anchorPoints 按路径实长展开），靶舰钉在路径中段
        // 第 5 点位正中（断言点 XC2-D/E 口径：全程吃成形掠过/驻留/闭合扫掠三类结算）。
        private val XC2_PLAYER_ANCHOR = Vector2f(-800f, -100f)
        private val XC2_ENEMY_ANCHOR = Vector2f(-300f, -100f)
        private val XC2_PLAYER_ARRIVAL = Vector2f(XC2_PLAYER_ANCHOR.x + RiftShiftTuning.SHIFT_DISTANCE, XC2_PLAYER_ANCHOR.y)

        // 武器舞台锚点（与折跃舞台分离，靶舰重置站位）：玩家正前方 500su（坠星残翼射程 1200 内）。
        private val XC2_WEAPON_PLAYER_ANCHOR = Vector2f(-800f, 300f)
        private val XC2_WEAPON_ENEMY_ANCHOR = Vector2f(-300f, 300f)
        private val XC2_CAMERA_CENTER = Vector2f(-400f, 100f)
        private const val XC2_CAMERA_VISIBLE_HEIGHT = 1800f
        private const val XC2_SPAWN_SETTLE_SECONDS = 0.6f

        // WINGS_OBSERVE（断言点 XC2-A）：静止伤害乘区砺刃档 0.75（界 [0.70, 0.80] 容忍帧量化）。
        private const val XC2_WINGS_OBSERVE_SECONDS = 1.2f
        private const val XC2_REST_DAMAGE_MULT_MIN = 0.70f
        private const val XC2_REST_DAMAGE_MULT_MAX = 0.80f

        // SHIFT（断言点 XC2-B/C/D）：折跃位移界从 RiftShiftTuning.SHIFT_DISTANCE 常量 ±100su 派生
        // （容忍钉位恢复帧；与 XC2_PLAYER_ARRIVAL 同源，改折跃距离无需双处同步）；折跃窗 1.2s
        // 内不钉舰位（裂隙插件逐帧改写 location，钉位互搏会掩盖位移证据）；速度窗口 maxSpeed 峰值
        // 砺刃 +100%（期望峰值按 (base×(1+峰值%)+常驻 flat)/静止值 实算——零幅能加速 flat 不吃百分比
        // 乘区，容差 ±0.1 覆盖帧量化）；5.0s 转入 CLOSURE（须在闭合扫掠前快照靶舰 HP：裂隙时钟为
        // 舰船时间，满距闭合起点 = 1s 拉开 + 5s 驻留，相位时流折算世界钟 ≈激活 +5.3~5.6s）。
        private const val XC2_ACTIVATE_TIMEOUT = 10f
        private const val XC2_SHIFT_PIN_FREE_SECONDS = 1.2f
        private const val XC2_SHIFT_ASSERT_SECONDS = 1.5f
        private const val XC2_SHIFT_DISPLACEMENT_TOLERANCE = 100f
        private const val XC2_SHIFT_DISPLACEMENT_MIN = RiftShiftTuning.SHIFT_DISTANCE - XC2_SHIFT_DISPLACEMENT_TOLERANCE
        private const val XC2_SHIFT_DISPLACEMENT_MAX = RiftShiftTuning.SHIFT_DISTANCE + XC2_SHIFT_DISPLACEMENT_TOLERANCE
        private const val XC2_SPEED_MULT_TOLERANCE = 0.1f
        private const val XC2_SHIFT_WINDOW_SECONDS = 5.0f

        // CLOSURE（断言点 XC2-D/E 评估）：接触掉血下界由常量派生（装甲已在 XC2-A 剥光，固定值结算：
        // 靶舰钉在第 5 点位正中——成形后段 ≈5 拍×400 + 快照前驻留 ≈23 拍×200 ≈6600 名义，
        // 保守口径取 3 拍掠过 + 10 拍驻留）；闭合掉血下界 = 一拍掠过（快照后残余驻留拍 +
        // 扫掠头抵达第 5 点位前的驻留拍 + 扫过时刻即席结算一拍 400，保守口径只取即席一拍）；
        // +7.3s 评估（闭合扫掠 ≈6.3~6.6s 完毕后余量充足）。
        private const val XC2_CLOSURE_EVAL_SECONDS = 7.3f
        private const val XC2_EXPECT_CONTACT_HP_DROP = RiftShiftTuning.GRAZE_DAMAGE * 3 + RiftShiftTuning.CONTACT_DAMAGE * 10
        private const val XC2_EXPECT_CLOSURE_HP_DROP = RiftShiftTuning.GRAZE_DAMAGE

        // WEAPON_SHIELD/HULL（断言点 XC2-F/G）：盾相 5s 评估叠层峰值 ≥0.5（主弹穿盾首触 +1/子射弹
        // 撞盾 +0.5）；体相 6s 评估穿透掉血 ≥300（穿透单点 面板×穿透比例 × 穿透节拍 + 子射弹撞船体全额，
        // 靶舰垫舞台结构冗余并逐帧奶回、掉血按逐帧差额累加——装甲已在 XC2-A 后剥光，主弹穿越期 ≥2 拍/发
        // （内部点口径：采样点在碰撞箱多边形内即接触，中段不再漏拍），实机 8 发 2947，300 为保守口径）、
        // 供给登记主弹 ≥3（固定 1.5s/发 × 11s 两相 ≈7 发）、子射弹 ≥1（0.2s 散发节拍）。
        private const val XC2_WEAPON_SHIELD_EVAL_SECONDS = 5f
        private const val XC2_WEAPON_HULL_EVAL_SECONDS = 6f
        private const val XC2_EXPECT_STACKS_MIN = 0.5f
        private const val XC2_EXPECT_WEAPON_HP_DROP = 300f
        private const val XC2_EXPECT_MAIN_SHOTS = 3
        private const val XC2_EXPECT_MOTES = 1
        private const val XC2_PHASE_TIMEOUT = 120f

        // 武器相位舞台结构冗余：穿透单拍量级（面板×穿透比例/拍 × 多拍多发）仍超统治者级原始结构值
        // （断言点 XC2-G 舞台保全）。
        private const val XC2_STAGE_ENEMY_HULL_BUFFER = 500000f
    }
}
