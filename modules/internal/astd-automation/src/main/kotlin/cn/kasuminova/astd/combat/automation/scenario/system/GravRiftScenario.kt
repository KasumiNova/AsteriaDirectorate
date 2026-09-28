package cn.kasuminova.astd.combat.automation.scenario.system

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning
import cn.kasuminova.astd.combat.shipsystems.GravityRiftSystemStats
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAIConfig
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f

/**
 * 茑萝引力裂隙发生器场景（断言点 A~G）。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class GravRiftScenario : AbstractAutomationScenario() {
    // ==== 茑萝引力裂隙发生器场景状态（相位机 SPAWN → WAIT_WINGS → PHASE_LINK → FLUX_RETURN → RIFT_FIRE → SCREENSHOT_VOLLEY → PHASE_SHOWCASE → COMPLETED） ====
    private var grgPhase = GRG_PHASE_SPAWN
    private var grgPhaseStartedAt = 0f

    // WAIT_WINGS：ion/lance 两联队各自在场存活数峰值与全场战机数峰值（auto_fighter 爬编需要时间）。
    private var grgWingIonSizeMax = 0
    private var grgWingLanceSizeMax = 0
    private var grgFightersInPlayMax = 0

    // PHASE_LINK：step 0=母舰已置相位等战机联动 / 1=母舰已退相位等战机恢复；两阶段各自计时。
    private var grgPhaseLinkStep = 0
    private var grgPhaseLinkStartedAt = -1f
    private var grgPhaseLinkRestoreStartedAt = -1f
    private var grgPhaseLinkFighterCount = 0
    private var grgPhaseLinkedAll = false
    private var grgPhaseRestoredAll = false
    private var grgScreenshotActiveAt = -1f
    private var grgScreenshotStagedFired = false
    private var grgScreenshotStagedLit = false

    // FLUX_RETURN：step 0=清零战机辐能待 hullmod 基线稳定 / 1=已注入等母舰软辐能返还。
    private var grgFluxStep = 0
    private var grgFluxFighter: ShipAPI? = null
    private var grgFluxSettleStartedAt = -1f
    private var grgFluxInjected = -1f
    private var grgFluxInjectedAt = -1f
    private var grgPlayerFluxBaseline = -1f
    private var grgPlayerFluxDeltaMax = 0f

    // RIFT_FIRE：step 0=无目标闸门（清空锁定后断言不可用且提示「无目标」，随后锁定靶舰）/
    // 1=点火首波 / 2=旋涡观测窗 / 3=光束观测窗 / 4=裂隙与伤害收口窗；
    // useSystem 重试计数、点火时刻（各观测窗截止的计时基准）、敌舰血量基线与在场地雷数峰值（诊断）。
    private var grgRiftStep = 0
    private var grgRiftAttempts = 0
    private var grgRiftFired = false
    private var grgRiftActivatedAt = -1f
    private var grgMinesInPlayMax = 0
    private var grgEnemyHpBeforeFire = -1f
    private var grgEnemyMinHpAfterFire = Float.MAX_VALUE
    private var grgEnemyHpDropMax = 0f

    override val scenarioId: String = ASTDInGameAutomationScenario.GRG_SCENARIO_ID
    // 定格舞台靠 setPaused(true) 维持；暂停期 advance 仍按真实 amount 推进（分发细节见枢纽类 when 分支注释）。
    override val pausePolicy: PausePolicy = PausePolicy.KEEP_PAUSED

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isGravRiftScenarioEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockGrgCamera(engine)
        // 与其他场景一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findGrgPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.GRG_SCENARIO_ID} combat plugin initialized")
    }

    private fun findGrgPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == GRG_PLAYER_HULL && !ship.isFighter }

    private fun findGrgEnemy(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == GRG_ENEMY_HULL && !ship.isFighter }

    /** 该舰全部联队的在外战机（wing 枚举口径，与引力相位甲板 advanceInCombat 同一观测面）。 */
    private fun grgPlayerFighters(player: ShipAPI): List<ShipAPI> =
        player.allWings.flatMap { wing -> wing.wingMembers }.filter { !it.isHulk }

    /** 在役光束 FX drone（原版 dem_drone 舰体，engine.ships 口径）。 */
    private fun grgBeamDrones(engine: CombatEngineAPI): List<ShipAPI> =
        engine.ships.filter { it.hullSpec?.hullId == GRG_BEAM_DRONE_HULL_ID }

    /** 读取系统写入 engine.customData 的战级遥测（键 = 遥测前缀 + 母舰 id）；未写入返回 -1。 */
    private fun grgTelemetryInt(engine: CombatEngineAPI, player: ShipAPI, key: String): Int {
        val value = engine.customData[key + player.id] ?: return -1
        if (value is Int) return value
        ctx.log.warn("[ASTD-Automation] grg telemetry ${key + player.id} 类型异常: ${value.javaClass.name}（expect Int）")
        return -1
    }

    /** 读取战级遥测的文本值（目标舰 id）；未写入返回 null。 */
    private fun grgTelemetryText(engine: CombatEngineAPI, player: ShipAPI, key: String): String? {
        val value = engine.customData[key + player.id] ?: return null
        if (value is String) return value
        ctx.log.warn("[ASTD-Automation] grg telemetry ${key + player.id} 类型异常: ${value.javaClass.name}（expect String）")
        return null
    }

    /**
     * 首波点火前的目标锁定制闸门（断言点 G-0/G-1）：清空 shipTarget 与 AI 目标旗标后，
     * 系统必须不可用且提示「无目标」；随后锁定靶舰，系统必须转为可用。
     * 返回 null 表示闸门成立，否则返回失败原因（调用方走 GRG_PHASE_FAILED 上报路径）。
     * statsScript 实例由引擎持有、插件侧无引用，故直接构造 GravityRiftSystemStats 求解。
     */
    private fun grgTargetGateFailure(engine: CombatEngineAPI, player: ShipAPI, system: ShipSystemAPI, enemy: ShipAPI): String? {
        player.shipTarget = null
        player.aiFlags.removeFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)

        // 闸门通过后各观测窗的读数必须只归因于首波：战级遥测键不随 unapply 清除，
        // 若此处已有值说明系统在封锁期被提前施放（12s 冷却还会打乱点火节奏）。
        val preTarget = grgTelemetryText(engine, player, GravityRiftSystemStats.TELEMETRY_TARGET_KEY)
        val preVortex = grgTelemetryInt(engine, player, GravityRiftSystemStats.TELEMETRY_VORTEX_KEY)
        val preBeam = grgTelemetryInt(engine, player, GravityRiftSystemStats.TELEMETRY_BEAM_KEY)
        val preMines = grgTelemetryInt(engine, player, GravityRiftSystemStats.TELEMETRY_MINES_KEY)
        if (preTarget != null || preVortex >= 0 || preBeam >= 0 || preMines >= 0) {
            return "grg target gate: 首波前遥测已被写入（target=$preTarget vortex=$preVortex beam=$preBeam mines=$preMines）" +
                    "——系统在封锁期被提前施放，首波观测窗不成立（断言点 G-0）"
        }

        val stats = GravityRiftSystemStats()
        if (stats.isUsable(system, player)) {
            return "grg target gate: 清空锁定后 isUsable=true（ship=${player.id}，断言点 G-0：无目标应不可用）"
        }
        val expectedInfo = I18n[I18n.Categories.MOD, GRG_NO_TARGET_I18N_KEY]
        val infoText = stats.getInfoText(system, player)
        if (infoText != expectedInfo) {
            return "grg target gate: getInfoText=${jsonString(infoText)}，expect \"$expectedInfo\"" +
                    "（systemState=${system.state}，断言点 G-0）"
        }

        player.shipTarget = enemy
        if (!stats.isUsable(system, player)) {
            return "grg target gate: 锁定靶舰 ${enemy.id}（dist=${"%.0f".format(Misc.getDistance(player.location, enemy.location))}）" +
                    "后 isUsable 仍为 false（断言点 G-1：锁定制）"
        }
        ctx.log.info(
            "[ASTD-Automation] grg target gate evidence: 无目标时 isUsable=false 且 infoText=\"$infoText\"" +
                    "；锁定 ${enemy.id} 后 isUsable=true（断言点 G-0/G-1）",
        )
        return null
    }

    /**
     * 强制部署 mission reserves（范式同 deployFglReserveShips）。
     * 玩家侧仅茑萝一艘（后备 == 1 → vanilla 静默 deployAll 会自动部署），已出场成员
     * 按 findShipByHull 判重跳过并移出后备，避免重复 spawn。
     */
    private fun deployGrgReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val anchor = when {
                    side == FleetSide.PLAYER && member.hullId == GRG_PLAYER_HULL -> GRG_PLAYER_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == GRG_ENEMY_HULL -> GRG_ENEMY_ANCHOR
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

    private fun transitionGrgPhase(next: String) {
        ctx.log.info("[ASTD-Automation] grg phase $grgPhase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        grgPhase = next
        grgPhaseStartedAt = ctx.elapsed
    }

    /**
     * 舞台保活与站位（范式同 stabilizeFglShips）：双方逐帧钉死锚点 + 保留舰 AI
     * （航母联队出库/补员链路依赖舰 AI 存活；系统目标锁定走 shipTarget，不依赖 AI）。
     * [zeroPlayerFlux] 仅用于 FLUX_RETURN 之前的相位：母舰保持 0 辐能锚定（fluxLevel 远低于
     * 0.8 停返闸），令断言点 D 的返还峰值归因干净；观测相位本身与之后不再清零，否则证据被抹掉。
     * [healEnemy] 在 RIFT_FIRE 起关闭：裂隙伤害需要真实 hitpoints 读数（此前逐帧奶血抵消战机
     * 陪练火力；靶舰装甲在进 RIFT_FIRE 时已一次性剥零——单波光束+散布地雷对满甲的 hull 溢出
     * 恒为 0，剥甲后承伤读数才代表系统出伤链路本身）。
     * [blockSystem] 在 RIFT_FIRE 之前逐帧封锁 USE_SYSTEM：系统自带 GravityRiftSystemAI 在敌舰
     * 进入交战距离时会自行施放（遥测为战斗级累计口径，提前施放会污染首波观测窗，12s 冷却也会
     * 打乱 RIFT_FIRE 的点火节奏）。
     */
    private fun stabilizeGrgShips(engine: CombatEngineAPI, healEnemy: Boolean, zeroPlayerFlux: Boolean, blockSystem: Boolean) {
        val player = findGrgPlayer(engine)
        val enemy = findGrgEnemy(engine)
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            if (player.shipAI == null) {
                // 补一个默认舰 AI：航母联队出库/补员链路需要舰 AI（无 AI 时联队不自动出击，
                // 断言点 A 无从观测）。config 必须给空实例：传 null 会让 BasicShipAI.config 为空，
                // pickManeuver 读 backingOffWhileNotVentingAllowed 直接 NPE 崩战斗（2026-09-19 第 5 轮实机）。
                player.shipAI = Global.getSettings().createDefaultShipAI(player, ShipAIConfig())
            }
            stabilizeShip(player, GRG_PLAYER_ANCHOR, 0f, allowFire = false, preserveAI = true)
            player.hitpoints = player.maxHitpoints
            if (zeroPlayerFlux) {
                player.fluxTracker.currFlux = 0f
                player.fluxTracker.hardFlux = 0f
            }
            if (blockSystem) player.blockCommandForOneFrame(ShipCommand.USE_SYSTEM)
        }
        if (enemy != null && !enemy.isHulk) {
            stabilizeShip(enemy, GRG_ENEMY_ANCHOR, 180f, allowFire = false, preserveAI = true)
            if (healEnemy) enemy.hitpoints = enemy.maxHitpoints
            enemy.fluxTracker.currFlux = 0f
            enemy.fluxTracker.hardFlux = 0f
            // 靶舰护盾压下：裂隙地雷近炸需结算到船体而非护盾（范式同 stabilizePlShips 的靶舰处理）。
            enemy.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK)
            enemy.shield?.let { if (it.isOn) it.toggleOff() }
        }
    }

    private fun lockGrgCamera(engine: CombatEngineAPI) {
        lockCameraAt(engine, GRG_CAMERA_CENTER, GRG_CAMERA_VISIBLE_HEIGHT)
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployGrgReserveShips(engine)
        lockGrgCamera(engine)

        val player = findGrgPlayer(engine)
        val enemy = findGrgEnemy(engine)
        val system = player?.system
        val fighters = if (player != null && !player.isHulk) grgPlayerFighters(player) else emptyList()
        grgFightersInPlayMax = maxOf(grgFightersInPlayMax, fighters.size)
        // 两联队各自在场存活数峰值全程采样（auto_fighter 爬编峰值可能晚于 WAIT_WINGS 才出现）。
        if (player != null) {
            for (wing in player.allWings) {
                val alive = wing.wingMembers.count { !it.isHulk }
                when (wing.spec?.id) {
                    GRG_ION_WING_ID -> grgWingIonSizeMax = maxOf(grgWingIonSizeMax, alive)
                    GRG_LANCE_WING_ID -> grgWingLanceSizeMax = maxOf(grgWingLanceSizeMax, alive)
                }
            }
        }

        when (grgPhase) {
            GRG_PHASE_SPAWN -> {
                stabilizeGrgShips(engine, healEnemy = true, zeroPlayerFlux = true, blockSystem = true)
                if (player != null && enemy != null && ctx.elapsed - grgPhaseStartedAt >= GRG_SPAWN_SETTLE_SECONDS) {
                    transitionGrgPhase(GRG_PHASE_WAIT_WINGS)
                }
            }

            GRG_PHASE_WAIT_WINGS -> {
                stabilizeGrgShips(engine, healEnemy = true, zeroPlayerFlux = true, blockSystem = true)
                // 断言点 A：双甲板联队齐备且各联队在场存活数达到基础编制（wing_data.csv num=2）。
                if (grgWingIonSizeMax >= GRG_EXPECT_WING_SIZE && grgWingLanceSizeMax >= GRG_EXPECT_WING_SIZE) {
                    ctx.log.info(
                        "[ASTD-Automation] grg wing evidence: ionWingSizeMax=$grgWingIonSizeMax " +
                                "lanceWingSizeMax=$grgWingLanceSizeMax fightersInPlayMax=$grgFightersInPlayMax（断言点 A：双联队齐备且各 2 架在场）",
                    )
                    transitionGrgPhase(GRG_PHASE_PHASE_LINK)
                } else if (ctx.elapsed - grgPhaseStartedAt >= GRG_WAIT_WINGS_TIMEOUT) {
                    ctx.failureReason = "grg wait wings timeout: ionWingSizeMax=$grgWingIonSizeMax lanceWingSizeMax=$grgWingLanceSizeMax" +
                            "（各 ≥$GRG_EXPECT_WING_SIZE）fighters=${fighters.size}/$grgFightersInPlayMax（断言点 A）"
                    transitionGrgPhase(GRG_PHASE_FAILED)
                }
            }

            GRG_PHASE_PHASE_LINK -> {
                stabilizeGrgShips(engine, healEnemy = true, zeroPlayerFlux = true, blockSystem = true)
                if (player != null) {
                    when (grgPhaseLinkStep) {
                        0 -> {
                            if (grgPhaseLinkStartedAt < 0f) {
                                if (fighters.isEmpty()) {
                                    ctx.failureReason = "grg phase link: 进入相位联动时无在外战机（断言点 B 无法观测）"
                                    transitionGrgPhase(GRG_PHASE_FAILED)
                                } else {
                                    player.isPhased = true
                                    grgPhaseLinkStartedAt = ctx.elapsed
                                    grgPhaseLinkFighterCount = fighters.size
                                    ctx.log.info("[ASTD-Automation] grg mothership phased at ${"%.2f".format(ctx.elapsed)}s fighters=${fighters.size}")
                                }
                            } else if (fighters.isNotEmpty() && fighters.all { it.isPhased }) {
                                grgPhaseLinkedAll = true
                                ctx.log.info("[ASTD-Automation] grg phase link evidence: ${fighters.size} 架在外战机全部 isPhased（断言点 B）")
                                player.isPhased = false
                                grgPhaseLinkRestoreStartedAt = ctx.elapsed
                                grgPhaseLinkStep = 1
                            } else if (ctx.elapsed - grgPhaseLinkStartedAt >= GRG_PHASE_LINK_TIMEOUT) {
                                ctx.failureReason = "grg phase link timeout: ${GRG_PHASE_LINK_TIMEOUT}s 内仍有战机未联动相位" +
                                        "（linked=${fighters.count { it.isPhased }}/${fighters.size}，断言点 B）"
                                transitionGrgPhase(GRG_PHASE_FAILED)
                            }
                        }

                        else -> {
                            if (fighters.all { !it.isPhased }) {
                                grgPhaseRestoredAll = true
                                ctx.log.info("[ASTD-Automation] grg phase restore evidence: 母舰退出相位后 ${fighters.size} 架战机全部恢复非相位（断言点 C）")
                                transitionGrgPhase(GRG_PHASE_FLUX_RETURN)
                            } else if (ctx.elapsed - grgPhaseLinkRestoreStartedAt >= GRG_PHASE_RESTORE_TIMEOUT) {
                                ctx.failureReason = "grg phase restore timeout: ${GRG_PHASE_RESTORE_TIMEOUT}s 内仍有战机滞留相位" +
                                        "（phased=${fighters.count { it.isPhased }}/${fighters.size}，断言点 C：phasedByThis 配对恢复）"
                                transitionGrgPhase(GRG_PHASE_FAILED)
                            }
                        }
                    }
                }
            }

            GRG_PHASE_FLUX_RETURN -> {
                stabilizeGrgShips(engine, healEnemy = true, zeroPlayerFlux = false, blockSystem = true)
                if (player != null) {
                    val fighter = grgFluxFighter?.takeIf { it.isAlive && !it.isHulk } ?: run {
                        // 首次进入选定注入目标：取第一架在外存活战机并锁定引用。
                        val picked = fighters.firstOrNull()
                        if (picked == null) {
                            ctx.failureReason = "grg flux return: 无在外存活战机可注入辐能（断言点 D 无法观测）"
                            transitionGrgPhase(GRG_PHASE_FAILED)
                        }
                        grgFluxFighter = picked
                        picked
                    }
                    if (fighter != null) {
                        when (grgFluxStep) {
                            0 -> {
                                if (grgFluxSettleStartedAt < 0f) grgFluxSettleStartedAt = ctx.elapsed
                                // 注入前持续清零战机辐能：注入增量顶不到 maxFlux 过载线，
                                // 且 hullmod 基线表（lastFluxByFighter）稳定在低读数。
                                fighter.fluxTracker.currFlux = 0f
                                if (ctx.elapsed - grgFluxSettleStartedAt >= GRG_FLUX_SETTLE_SECONDS) {
                                    grgPlayerFluxBaseline = player.fluxTracker.currFlux
                                    grgFluxInjected = fighter.fluxTracker.maxFlux * GRG_FLUX_INJECT_FRAC
                                    fighter.fluxTracker.currFlux = fighter.fluxTracker.currFlux + grgFluxInjected
                                    grgFluxInjectedAt = ctx.elapsed
                                    grgFluxStep = 1
                                    ctx.log.info(
                                        "[ASTD-Automation] grg flux injected: +${"%.0f".format(grgFluxInjected)} " +
                                                "baseline=${"%.0f".format(grgPlayerFluxBaseline)}（断言点 D：预期返还 ≈ 注入量 ×0.6）",
                                    )
                                }
                            }

                            else -> {
                                grgPlayerFluxDeltaMax = maxOf(grgPlayerFluxDeltaMax, player.fluxTracker.currFlux - grgPlayerFluxBaseline)
                                when {
                                    grgPlayerFluxDeltaMax > grgFluxInjected * GRG_FLUX_RETURN_MAX_FRAC -> {
                                        ctx.failureReason = "grg flux return overshoot: 母舰辐能峰值增量 ${"%.0f".format(grgPlayerFluxDeltaMax)}" +
                                                " > 注入量 ×$GRG_FLUX_RETURN_MAX_FRAC（${"%.0f".format(grgFluxInjected * GRG_FLUX_RETURN_MAX_FRAC)}，断言点 D 上界）"
                                        transitionGrgPhase(GRG_PHASE_FAILED)
                                    }

                                    grgPlayerFluxDeltaMax >= grgFluxInjected * GRG_FLUX_RETURN_MIN_FRAC -> {
                                        ctx.log.info(
                                            "[ASTD-Automation] grg flux return evidence: deltaMax=${"%.0f".format(grgPlayerFluxDeltaMax)}" +
                                                    " ∈ [${"%.0f".format(grgFluxInjected * GRG_FLUX_RETURN_MIN_FRAC)}, ${"%.0f".format(grgFluxInjected * GRG_FLUX_RETURN_MAX_FRAC)}]" +
                                                    "（注入 ${"%.0f".format(grgFluxInjected)}，断言点 D：战机辐能净增量 ×0.6 返还母舰软辐能）",
                                        )
                                        // 点火前剥光靶舰装甲（实机判例：单轮光束 1000dps×2s + 散布 5 雷
                                        // 对满甲 dominator（2050）的 hull 溢出恒为 0——装甲格未耗尽时
                                        // 伤害全部落格，G-4 断言的是系统出伤链路而非原版装甲数学）。
                                        enemy?.armorGrid?.grid?.forEach { row -> row.fill(0f) }
                                        transitionGrgPhase(GRG_PHASE_RIFT_FIRE)
                                    }

                                    ctx.elapsed - grgFluxInjectedAt >= GRG_FLUX_OBSERVE_TIMEOUT -> {
                                        ctx.failureReason = "grg flux return timeout: ${GRG_FLUX_OBSERVE_TIMEOUT}s 内母舰辐能峰值增量" +
                                                " ${"%.0f".format(grgPlayerFluxDeltaMax)} < 注入量 ×$GRG_FLUX_RETURN_MIN_FRAC" +
                                                "（${"%.0f".format(grgFluxInjected * GRG_FLUX_RETURN_MIN_FRAC)}，断言点 D 下界）"
                                        transitionGrgPhase(GRG_PHASE_FAILED)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            GRG_PHASE_RIFT_FIRE -> {
                // 靶舰停奶：裂隙伤害需要真实 hitpoints 读数；玩家侧继续奶血保活。
                stabilizeGrgShips(engine, healEnemy = false, zeroPlayerFlux = false, blockSystem = false)
                if (player != null && enemy != null && system != null) {
                    if (system.id != GRG_SYSTEM_ID) {
                        ctx.failureReason = "grg system id=${system.id}, expect $GRG_SYSTEM_ID（ship_data.csv 生成物未刷新）"
                        transitionGrgPhase(GRG_PHASE_FAILED)
                    } else {
                        grgMinesInPlayMax = maxOf(grgMinesInPlayMax, engine.missiles.count { it.projectileSpecId == GRG_MINE_SPEC_ID })
                        if (grgRiftFired) {
                            // 靶舰被击沉时 hitpoints 不再下降，以观测到的最低值为准（isHulk 后 stabilize 不再奶血）。
                            grgEnemyMinHpAfterFire = minOf(grgEnemyMinHpAfterFire, enemy.hitpoints)
                            grgEnemyHpDropMax = grgEnemyHpBeforeFire - grgEnemyMinHpAfterFire
                        }

                        when (grgRiftStep) {
                            // step 0：无目标闸门与锁定（断言点 G-0/G-1）。清空 shipTarget 与 AI 目标旗标后
                            // 做一次真实求解（无目标 → isUsable=false 且提示「无目标」），
                            // 再 setShipTarget(enemy) 并确认系统转为可用。
                            0 -> {
                                val gateFailure = grgTargetGateFailure(engine, player, system, enemy)
                                if (gateFailure != null) {
                                    ctx.failureReason = gateFailure
                                    transitionGrgPhase(GRG_PHASE_FAILED)
                                } else {
                                    grgRiftStep = 1
                                }
                            }
                            // step 1：点火首波。锁定制下逐帧回写 shipTarget（舰 AI 与引擎帧序都可能改写它），
                            // 系统空闲即 useSystem()（按帧重试，单次可能被原版起飞动画窗闸门吞掉，同 FGL）。
                            1 -> {
                                player.shipTarget = enemy
                                if (system.isOn) {
                                    grgRiftFired = true
                                    grgRiftActivatedAt = ctx.elapsed
                                    grgEnemyHpBeforeFire = enemy.hitpoints
                                    grgEnemyMinHpAfterFire = enemy.hitpoints
                                    grgRiftStep = 2
                                    ctx.log.info(
                                        "[ASTD-Automation] grg rift fired: dist=${
                                            "%.0f".format(
                                                Misc.getDistance(
                                                    player.location,
                                                    enemy.location
                                                )
                                            )
                                        } " +
                                                "attempts=$grgRiftAttempts enemyHp=${"%.0f".format(enemy.hitpoints)}（断言点 G-1：锁定后点火成）",
                                    )
                                } else if (ctx.elapsed - grgPhaseStartedAt >= GRG_RIFT_PHASE_TIMEOUT) {
                                    ctx.failureReason = "grg rift ignition timeout: ${GRG_RIFT_PHASE_TIMEOUT}s 内系统未点亮" +
                                            "（attempts=$grgRiftAttempts isUsable=${GravityRiftSystemStats().isUsable(system, player)}" +
                                            " cooldown=${"%.1f".format(system.cooldownRemaining)} shipTarget=${player.shipTarget?.id}" +
                                            " systemState=${system.state}，断言点 G-1）"
                                    transitionGrgPhase(GRG_PHASE_FAILED)
                                } else if (system.cooldownRemaining <= 0f) {
                                    grgRiftAttempts++
                                    player.useSystem()
                                }
                            }

                            // step 2：旋涡观测窗（断言点 G-2）。IN 首帧在目标舰体生成旋涡并写入
                            // telemetry target/vortex；光束遥测必须晚于蓄能窗出现（时序倒置即失败）。
                            2 -> {
                                player.shipTarget = enemy
                                val t = ctx.elapsed - grgRiftActivatedAt
                                val targetId = grgTelemetryText(engine, player, GravityRiftSystemStats.TELEMETRY_TARGET_KEY)
                                val vortex = grgTelemetryInt(engine, player, GravityRiftSystemStats.TELEMETRY_VORTEX_KEY)
                                val beam = grgTelemetryInt(engine, player, GravityRiftSystemStats.TELEMETRY_BEAM_KEY)
                                when {
                                    beam >= 0 -> {
                                        ctx.failureReason = "grg rift chargeUp: ${"%.2f".format(t)}s 已出现光束遥测（beam=$beam）" +
                                                "——旋涡必须先于光束（断言点 G-2 时序）"
                                        transitionGrgPhase(GRG_PHASE_FAILED)
                                    }

                                    targetId == enemy.id && vortex == 1 -> {
                                        ctx.log.info(
                                            "[ASTD-Automation] grg rift vortex evidence: t=${"%.2f".format(t)}s " +
                                                    "telemetry target=$targetId vortex=$vortex（断言点 G-2：IN 首帧在目标舰体生成旋涡）",
                                        )
                                        grgRiftStep = 3
                                    }

                                    t >= GRG_RIFT_VORTEX_DEADLINE -> {
                                        ctx.failureReason = "grg rift vortex timeout: ${"%.2f".format(t)}s 内 telemetry target=$targetId" +
                                                "（expect ${enemy.id}）vortex=$vortex（expect 1）（断言点 G-2）"
                                        transitionGrgPhase(GRG_PHASE_FAILED)
                                    }
                                }
                            }
                            // step 3：光束观测窗（断言点 G-3）。ACTIVE 首帧生成 FX drone（dem_drone）真实光束，
                            // telemetry beam 置 1，两者同帧可查。
                            3 -> {
                                player.shipTarget = enemy
                                val t = ctx.elapsed - grgRiftActivatedAt
                                val beam = grgTelemetryInt(engine, player, GravityRiftSystemStats.TELEMETRY_BEAM_KEY)
                                val drones = grgBeamDrones(engine)
                                when {
                                    beam == 1 && drones.isNotEmpty() -> {
                                        ctx.log.info(
                                            "[ASTD-Automation] grg rift beam evidence: t=${"%.2f".format(t)}s telemetry beam=$beam " +
                                                    "drones=${drones.size}（hullId=$GRG_BEAM_DRONE_HULL_ID，断言点 G-3：ACTIVE 首帧生成真实光束）",
                                        )
                                        grgRiftStep = 4
                                    }

                                    t >= GRG_RIFT_BEAM_DEADLINE -> {
                                        ctx.failureReason = "grg rift beam timeout: ${"%.2f".format(t)}s 内 telemetry beam=$beam（expect 1）" +
                                                " drones=${drones.size}（expect ≥1）systemState=${system.state}（断言点 G-3）"
                                        transitionGrgPhase(GRG_PHASE_FAILED)
                                    }
                                }
                            }
                            // step 4：裂隙与伤害收口窗（断言点 G-4）。系统周期（蓄能 1s + 生效 1.2s + 收尾 0.5s）
                            // 结束时 unapply 移除 drone（drone 自身 2s 计时是另一条移除路径），到点后 drone 应已消失；
                            // 裂隙由光束命中期间每 0.1s 布一枚，mines 为战斗级累计口径（本场景首波即 5）。
                            else -> {
                                player.shipTarget = enemy
                                val t = ctx.elapsed - grgRiftActivatedAt
                                if (t >= GRG_RIFT_SETTLE_DEADLINE) {
                                    val mines = grgTelemetryInt(engine, player, GravityRiftSystemStats.TELEMETRY_MINES_KEY)
                                    val drones = grgBeamDrones(engine)
                                    when {
                                        mines !in 1..GravityRiftTuning.MAX_RIFTS -> {
                                            ctx.failureReason = "grg rift mines out of range: telemetry mines=$mines，" +
                                                    "expect [1,${GravityRiftTuning.MAX_RIFTS}]（断言点 G-4）"
                                            transitionGrgPhase(GRG_PHASE_FAILED)
                                        }

                                        drones.isNotEmpty() -> {
                                            ctx.failureReason = "grg rift drone not removed: ${"%.2f".format(t)}s 仍有 ${drones.size} 架 " +
                                                    "$GRG_BEAM_DRONE_HULL_ID（expect 0，断言点 G-4）"
                                            transitionGrgPhase(GRG_PHASE_FAILED)
                                        }

                                        grgEnemyHpDropMax < GRG_EXPECT_ENEMY_HP_DROP -> {
                                            ctx.failureReason = "grg rift damage shortfall: hpDrop=${"%.0f".format(grgEnemyHpDropMax)}" +
                                                    " < $GRG_EXPECT_ENEMY_HP_DROP（enemyHp=${"%.0f".format(enemy.hitpoints)}" +
                                                    "/${"%.0f".format(enemy.maxHitpoints)} mines=$mines，断言点 G-4）"
                                            transitionGrgPhase(GRG_PHASE_FAILED)
                                        }

                                        else -> {
                                            if (mines != GravityRiftTuning.MAX_RIFTS) {
                                                ctx.log.warn(
                                                    "[ASTD-Automation] grg rift mines telemetry=$mines，按契约本场景应恒为 " +
                                                            "${GravityRiftTuning.MAX_RIFTS}（光束命中长度极短、数量公式取上限）——仍落在断言区间内",
                                                )
                                            }
                                            ctx.log.info(
                                                "[ASTD-Automation] grg rift evidence: t=${"%.2f".format(t)}s mines=$mines " +
                                                        "enemyHp ${"%.0f".format(grgEnemyHpBeforeFire)} -> min " +
                                                        "${"%.0f".format(grgEnemyMinHpAfterFire)}（drop=${"%.0f".format(grgEnemyHpDropMax)} " +
                                                        "≥ $GRG_EXPECT_ENEMY_HP_DROP）drone 已移除 minesInPlayMax=$grgMinesInPlayMax（断言点 G-4）",
                                            )
                                            transitionGrgPhase(GRG_PHASE_SCREENSHOT)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            GRG_PHASE_SCREENSHOT -> {
                // 取景波：证据已齐，专为截图再放一波（锁定制下同样先回写 shipTarget）。
                // 定格于 ACTIVE 首帧 +GRG_SCREENSHOT_HOLD_DELAY：ACTIVE 沿 ≈ 光束起射点（系统 IN 段
                // 为旋涡前摇 1s），+0.2s 时旋涡满亮度、裂隙雷刚起爆——锚点在目标碰撞半径内，
                // 光束是旋涡到目标的短束（实长 ~50su），满屏橙红火球是原版 hull 承伤反馈
                // （spawnDamagedExplosion），红色裂隙星云与旋涡在其下层，定格越早旋涡越清晰。
                // SSOptimizer 在 Completed 上报时刻连拍三帧，而捕获与上报间存在秒级异步延迟，
                // 故定格靠 engine.setPaused(true)（GRG 分支刻意不 unpause，见 advance 注释）。
                // 纯取景相位不引入新断言：超时兜底直接收口 COMPLETED。
                // 闩锁 stagedFired/stagedLit：只认本相位亲手放出并真正点亮的那一波——进入相位时
                // 上一波可能仍在 ACTIVE 尾段，直接门控会把别人的尾焰定格进来（实机 2026-09-20）；
                // useSystem 可能被原版起飞动画窗吞掉，故点亮前按帧重试点火（同 RIFT_FIRE）。
                stabilizeGrgShips(engine, healEnemy = true, zeroPlayerFlux = false, blockSystem = false)
                if (player != null && enemy != null && system != null) {
                    if (!grgScreenshotStagedLit) player.shipTarget = enemy
                    when {
                        // ACTIVE 沿先记录时刻，推迟 GRG_SCREENSHOT_HOLD_DELAY 再收口。
                        grgScreenshotStagedLit && system.state == ShipSystemAPI.SystemState.ACTIVE -> {
                            if (grgScreenshotActiveAt < 0f) grgScreenshotActiveAt = ctx.elapsed
                            if (ctx.elapsed - grgScreenshotActiveAt >= GRG_SCREENSHOT_HOLD_DELAY) {
                                // 取景波光束/旋涡定格完成后转入相位特效取景（不立即暂停）。
                                transitionGrgPhase(GRG_PHASE_SHOWCASE)
                            }
                        }

                        ctx.elapsed - grgPhaseStartedAt >= GRG_SCREENSHOT_TIMEOUT -> {
                            ctx.log.info(
                                "[ASTD-Automation] grg screenshot volley timeout, completing without staged frame" +
                                        "（stagedFired=$grgScreenshotStagedFired enemyHulk=${enemy.isHulk}" +
                                        " systemState=${system.state} cooldown=${"%.1f".format(system.cooldownRemaining)}）",
                            )
                            transitionGrgPhase(GRG_PHASE_COMPLETED)
                        }

                        grgScreenshotStagedFired && system.isOn && !grgScreenshotStagedLit ->
                            grgScreenshotStagedLit = true

                        !grgScreenshotStagedLit && !system.isOn && system.cooldownRemaining <= 0f -> {
                            grgRiftAttempts++
                            grgScreenshotStagedFired = true
                            grgScreenshotActiveAt = -1f
                            player.useSystem()
                        }
                    }
                }
            }

            GRG_PHASE_SHOWCASE -> {
                // 相位特效取景：截图只拍 Completed 上报时刻，而相位联动阶段是 setPhased 直驱
                // （斗篷 effectLevel 不起表），故定格前强制斗篷 ACTIVE 保持 GRG_SHOWCASE_HOLD 秒——
                // 整舰红色辉光爬满 + 残影生成后再暂停定格，三连拍即相位激活态。
                stabilizeGrgShips(engine, healEnemy = true, zeroPlayerFlux = false, blockSystem = false)
                player?.phaseCloak?.forceState(ShipSystemAPI.SystemState.ACTIVE, GRG_SHOWCASE_HOLD)
                if (ctx.elapsed - grgPhaseStartedAt >= GRG_SHOWCASE_HOLD) {
                    ctx.log.info(
                        "[ASTD-Automation] grg phase showcase freeze: cloakLevel=" +
                                "${player?.phaseCloak?.effectLevel} cloakState=${player?.phaseCloak?.state} phased=${player?.isPhased}",
                    )
                    engine.isPaused = true
                    transitionGrgPhase(GRG_PHASE_COMPLETED)
                }
            }

            GRG_PHASE_COMPLETED -> {
                stabilizeGrgShips(engine, healEnemy = true, zeroPlayerFlux = false, blockSystem = false)
            }
        }

        val state = when {
            player == null || enemy == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason = "grg ships missing: player=${player != null}, enemy=${enemy != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            grgPhase == GRG_PHASE_FAILED -> "Failed"
            grgPhase != GRG_PHASE_COMPLETED &&
                    ctx.elapsed - grgPhaseStartedAt > GRG_PHASE_TIMEOUT -> {
                ctx.failureReason = "grg phase timeout: $grgPhase（ionWingMax=$grgWingIonSizeMax lanceWingMax=$grgWingLanceSizeMax " +
                        "fighters=${fighters.size}/$grgFightersInPlayMax linked=$grgPhaseLinkedAll restored=$grgPhaseRestoredAll " +
                        "fluxDelta=${"%.0f".format(grgPlayerFluxDeltaMax)} riftStep=$grgRiftStep fired=$grgRiftFired " +
                        "mines=${grgTelemetryInt(engine, player, GravityRiftSystemStats.TELEMETRY_MINES_KEY)} " +
                        "drones=${grgBeamDrones(engine).size} hpDrop=${"%.0f".format(grgEnemyHpDropMax)}）"
                "Failed"
            }

            grgPhase == GRG_PHASE_COMPLETED -> "Completed"
            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: lens_grav_rift_zw103 wings/phase-link/flux-return/target-lock/vortex/beam/rift evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, null)
        }
    }

    // 捕获帧间隔 0.6s：定格舞台（光束 + 旋涡 + 首批裂隙近炸）与战机群/母舰/靶舰在三帧内进入捕获帧。
    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.6f, { findGrgPlayer(ctx.engine) }) { lockGrgCamera(ctx.engine) }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val grgPlayer = findGrgPlayer(engine)
        val grgSystem = grgPlayer?.system
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（联队齐备 / 相位联动 / 辐能返还 / 目标锁定+旋涡+光束+裂隙）----
        json.appendLine("  \"grgPhase\": \"$grgPhase\",")
        json.appendLine("  \"grgSystemId\": ${jsonString(grgSystem?.id)},")
        json.appendLine("  \"grgSystemState\": ${jsonString(grgSystem?.state?.name)},")
        json.appendLine("  \"grgSystemCooldownRemaining\": ${formatFloat(grgSystem?.cooldownRemaining ?: -1f)},")
        json.appendLine("  \"grgWingIonSizeMax\": $grgWingIonSizeMax,")
        json.appendLine("  \"grgWingLanceSizeMax\": $grgWingLanceSizeMax,")
        json.appendLine("  \"grgFightersInPlayMax\": $grgFightersInPlayMax,")
        json.appendLine("  \"grgFightersInPlay\": ${if (grgPlayer != null) grgPlayerFighters(grgPlayer).size else -1},")
        json.appendLine("  \"grgPhaseLinkFighterCount\": $grgPhaseLinkFighterCount,")
        json.appendLine("  \"grgPhaseLinkedAll\": $grgPhaseLinkedAll,")
        json.appendLine("  \"grgPhaseRestoredAll\": $grgPhaseRestoredAll,")
        json.appendLine("  \"grgPlayerPhased\": ${grgPlayer?.isPhased == true},")
        json.appendLine("  \"grgPlayerHasAI\": ${grgPlayer?.shipAI != null},")
        json.appendLine("  \"grgFluxInjected\": ${formatFloat(grgFluxInjected)},")
        json.appendLine("  \"grgPlayerFluxBaseline\": ${formatFloat(grgPlayerFluxBaseline)},")
        json.appendLine("  \"grgPlayerFluxDeltaMax\": ${formatFloat(grgPlayerFluxDeltaMax)},")
        json.appendLine("  \"grgRiftStep\": $grgRiftStep,")
        json.appendLine("  \"grgRiftAttempts\": $grgRiftAttempts,")
        json.appendLine("  \"grgMinesInPlayMax\": $grgMinesInPlayMax,")
        json.appendLine("  \"grgBeamDronesInPlay\": ${grgBeamDrones(engine).size},")
        // ---- 系统战级遥测（键 = 前缀 + 母舰 id，战斗级生命周期；-1/null 表示尚未写入）----
        json.appendLine(
            "  \"grgTelemetryTarget\": ${
                jsonString(grgPlayer?.let {
                    grgTelemetryText(
                        engine,
                        it,
                        GravityRiftSystemStats.TELEMETRY_TARGET_KEY
                    )
                })
            },"
        )
        json.appendLine(
            "  \"grgTelemetryVortex\": ${
                grgPlayer?.let {
                    grgTelemetryInt(
                        engine,
                        it,
                        GravityRiftSystemStats.TELEMETRY_VORTEX_KEY
                    )
                } ?: -1
            },")
        json.appendLine(
            "  \"grgTelemetryBeam\": ${
                grgPlayer?.let {
                    grgTelemetryInt(
                        engine,
                        it,
                        GravityRiftSystemStats.TELEMETRY_BEAM_KEY
                    )
                } ?: -1
            },")
        // mines 为战斗级累计口径（首波 5，取景波再 +5）。
        json.appendLine(
            "  \"grgTelemetryMines\": ${
                grgPlayer?.let {
                    grgTelemetryInt(
                        engine,
                        it,
                        GravityRiftSystemStats.TELEMETRY_MINES_KEY
                    )
                } ?: -1
            },")
        json.appendLine("  \"grgShipTargetId\": ${jsonString(grgPlayer?.shipTarget?.id)},")
        json.appendLine("  \"grgEnemyHpBeforeFire\": ${formatFloat(grgEnemyHpBeforeFire)},")
        json.appendLine("  \"grgEnemyMinHpAfterFire\": ${formatFloat(if (grgEnemyMinHpAfterFire == Float.MAX_VALUE) -1f else grgEnemyMinHpAfterFire)},")
        json.appendLine("  \"grgEnemyHpDropMax\": ${formatFloat(grgEnemyHpDropMax)},")
        json.appendLine("  \"grgPlayerCurrFlux\": ${formatFloat(grgPlayer?.fluxTracker?.currFlux ?: -1f)},")
    }

    private companion object {
        // 茑萝引力裂隙发生器场景：相位机、锚点与期望证据（断言点 A~G）。
        private const val GRG_PHASE_SPAWN = "SPAWN"
        private const val GRG_PHASE_WAIT_WINGS = "WAIT_WINGS"
        private const val GRG_PHASE_PHASE_LINK = "PHASE_LINK"
        private const val GRG_PHASE_FLUX_RETURN = "FLUX_RETURN"
        private const val GRG_PHASE_RIFT_FIRE = "RIFT_FIRE"
        private const val GRG_PHASE_SCREENSHOT = "SCREENSHOT_VOLLEY"
        private const val GRG_PHASE_SHOWCASE = "PHASE_SHOWCASE"
        private const val GRG_PHASE_COMPLETED = "COMPLETED"
        private const val GRG_PHASE_FAILED = "FAILED"
        private const val GRG_PLAYER_HULL = "astd_zw_103"
        private const val GRG_ENEMY_HULL = "dominator"
        private const val GRG_SYSTEM_ID = "astd_grav_rift_generator"
        private const val GRG_ION_WING_ID = "astd_zw_103_ion_wing"
        private const val GRG_LANCE_WING_ID = "astd_zw_103_lance_wing"

        // 靶舰锚点在母舰正前方 400su：在系统有效射程 1000su 内（锁定制可用），
        // 同时给战机陪练留出活动空间。
        private val GRG_PLAYER_ANCHOR = Vector2f(-700f, 0f)
        private val GRG_ENEMY_ANCHOR = Vector2f(-300f, 0f)
        private val GRG_CAMERA_CENTER = Vector2f(-500f, 0f)
        private const val GRG_CAMERA_VISIBLE_HEIGHT = 1500f
        private const val GRG_SPAWN_SETTLE_SECONDS = 0.6f

        // WAIT_WINGS（断言点 A）：两联队基础编制各 2（wing_data.csv num=2，无折叠甲板扩容）；
        // auto_fighter 自动出击的爬编时间给 25s 冗余。
        private const val GRG_EXPECT_WING_SIZE = 2
        private const val GRG_WAIT_WINGS_TIMEOUT = 25f

        // PHASE_LINK（断言点 B/C）：hullmod 逐帧驱动，联动与恢复都应在下一帧内完成，3s 超时纯兜底。
        private const val GRG_PHASE_LINK_TIMEOUT = 3f
        private const val GRG_PHASE_RESTORE_TIMEOUT = 3f

        // FLUX_RETURN（断言点 D）：注入 maxFlux×0.4（清零 settle 后注入，顶不到过载线）；
        // 玩家恒 v2 返还比例 0.6，界 [×0.4, ×0.85] 容忍逐帧耗散与基线量化。
        private const val GRG_FLUX_SETTLE_SECONDS = 0.5f
        private const val GRG_FLUX_INJECT_FRAC = 0.4f
        private const val GRG_FLUX_OBSERVE_TIMEOUT = 2f
        private const val GRG_FLUX_RETURN_MIN_FRAC = 0.4f
        private const val GRG_FLUX_RETURN_MAX_FRAC = 0.85f

        // RIFT_FIRE（断言点 G-0~G-4）：系统契约 = chargeUp 1s（t=0 生成旋涡 + telemetry target/vortex）
        // → active 1.2s（ACTIVE 首帧生成 dem_drone 真实光束 + telemetry beam）→ down 0.5s，冷却 12s。
        // 各观测窗截止自点火沿计：旋涡取半个蓄能窗；光束取 ACTIVE 首帧（1.0s）+0.6s 余量；
        // 裂隙/伤害/收口取系统结束（1.0 + 1.2 + 0.5 = 2.7s）与 drone 自移除（2.0s 计时，最迟 3.0s）之后的余量。
        private const val GRG_RIFT_VORTEX_DEADLINE = 0.5f
        private const val GRG_RIFT_BEAM_DEADLINE = 1.6f
        private const val GRG_RIFT_SETTLE_DEADLINE = 3.5f

        // 点火预算：进入 RIFT_FIRE 时系统空闲且无冷却（封锁期不允许施放），useSystem 按帧重试；
        // 30s 覆盖「封锁失效导致残留一次 12s 冷却」的极端情形，超时判失败并暴露遥测诊断。
        private const val GRG_RIFT_PHASE_TIMEOUT = 30f
        private const val GRG_MINE_SPEC_ID = "astd_grav_rift_mine"

        /** 光束 FX drone 舰体（GravityRiftSystemStats 内部常量同款；engine.ships 口径）。 */
        private const val GRG_BEAM_DRONE_HULL_ID = "dem_drone"

        /** 「无目标」提示键（GravityRiftSystemStats.getInfoText 同款 i18n 键）。 */
        private const val GRG_NO_TARGET_I18N_KEY = "ui.grav_rift.info.no_target"

        // 伤害阈值：单波原始能量 = 光束 1000 DPS × 2s（2000）+ 5 枚 v2 裂隙 800~1400（合计 5500），
        // 靶舰护盾已强制压下故无护盾吸收，装甲网格吃掉其中大部分。阈值取光束单源贡献的保守下界：
        // 2000 原始 × 60% 穿透 = 1200（靶舰 dominator 14000 HP 的 8.6%），裂隙 AOE 与战机陪练火力
        // 只会把读数推高，不会让达标变难。
        private const val GRG_EXPECT_ENEMY_HP_DROP = 1200f

        // SCREENSHOT_VOLLEY（纯取景，无断言）：冷却 12s + 系统周期 2.7s 内必到 ACTIVE 沿，
        // 20s 超时兜底直接收口。
        private const val GRG_SCREENSHOT_TIMEOUT = 20f

        // 取景定格推迟到 ACTIVE 首帧 +0.6s（点火后 ≈1.6s）：光束满亮度、旋涡满亮度、
        // 首批裂隙近炸星云成形（「光束 + 旋涡 + 裂隙」同框窗），避开 ACTIVE 首帧的爆炸初闪。
        private const val GRG_SCREENSHOT_HOLD_DELAY = 0.2f

        // 相位特效取景保持时长：强制引力相位斗篷 ACTIVE 并保持 1.2s（不暂停），
        // 让整舰红色辉光爬满、相位残影至少生成两次后再定格三连拍。
        private const val GRG_SHOWCASE_HOLD = 1.2f
        private const val GRG_PHASE_TIMEOUT = 90f
    }
}
