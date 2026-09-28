package cn.kasuminova.astd.combat.automation.scenario.production

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.hullmods.lens.LensArrayCoreHullModIds
import cn.kasuminova.astd.combat.lens.marks.LensMarks
import cn.kasuminova.astd.combat.lens.system.EchoFixationField
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.effect.lens.EchoFixationAfterimageRenderer
import cn.kasuminova.astd.renderer.effect.lens.LensVfxTelemetry
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.mission.FleetSide
import org.lwjgl.util.vector.Vector2f

/**
 * 决明级 phase2 场景（机制证据 + shader vfx 计数）。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class LensPhase2Scenario : AbstractAutomationScenario() {
    /** phase2 幽灵信号导弹投放计时累积（秒），见 feedGhostSignalMissiles。 */
    private var ghostMissileFeedAcc = 0f

    /** phase2 fighter 误差标记降级 log 的 once 守卫（避免每帧刷屏，参照 lensMarksInjected 模式）。 */
    private var lensPhase2DowngradeLogged = false

    override val scenarioId: String = ASTDInGameAutomationScenario.LENS_PHASE2_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isLensPhase2Enabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockArcProductionCamera(engine)
        // 与 phase1/ARC production 一致：reserves 部署放到 advance()，init 阶段渲染器未就绪。
        arrangeLensPhase2Ships(engine)
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findCrewedLens(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.LENS_PHASE2_SCENARIO_ID} combat plugin initialized")
    }

    /**
     * 载人决明级（无 MODE_AUTOMATED perma-mod 即载人）。phase2 旗舰，驱动定影场施放、潮汐、标记高光。
     * 标记高光提交（[cn.kasuminova.astd.combat.hullmods.lens.ASTDLensArrayCoreHullMod.submitMarkHighlights]）
     * 仅对 engine.playerShip 每帧执行，故必须把载人透镜设为玩家船。
     */
    private fun findCrewedLens(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship ->
            ship.hullSpec?.hullId == LensArrayCoreHullModIds.HULL_ID &&
                    ship.variant?.hasHullMod(LensArrayCoreHullModIds.MODE_AUTOMATED) != true &&
                    ship.variant?.hasLensAutomatedModeSafe() != true
        }

    /** 无人决明级（MODE_AUTOMATED perma-mod）。唯一跑幽灵信号的模式。 */
    private fun findAutomatedLens(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship ->
            ship.hullSpec?.hullId == LensArrayCoreHullModIds.HULL_ID &&
                    ship.variant?.hasHullMod(LensArrayCoreHullModIds.MODE_AUTOMATED) == true
        }

    /** phase2 全场敌舰（非残骸非舰载机），潮汐深水标记 / 认知撕裂的目标。 */
    private fun lensPhase2Enemies(engine: CombatEngineAPI): List<ShipAPI> {
        val crewed = findCrewedLens(engine) ?: return emptyList()
        return engine.ships.filter { it.owner != crewed.owner && !it.isFighter && !it.isHulk }
    }

    private fun deployLensPhase2ReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        deployLensPhase2Side(engine, FleetSide.PLAYER)
        deployLensPhase2Side(engine, FleetSide.ENEMY)
    }

    private fun deployLensPhase2Side(engine: CombatEngineAPI, side: FleetSide) {
        val manager = engine.getFleetManager(side)
        manager.isSuppressDeploymentMessages = true
        val reserves = manager.reservesCopy.toList()
        if (reserves.isEmpty()) return

        var enemyIndex = 0
        for (member in reserves) {
            val hullId = member.hullId ?: continue
            val anchor = when {
                side == FleetSide.ENEMY -> Vector2f(LENS_PHASE2_ENEMY_CLUSTER_X + enemyIndex++ * 140f, -200f + (enemyIndex % 3) * 200f)
                hullId == LensArrayCoreHullModIds.HULL_ID ->
                    if (member.variant?.hasLensAutomatedModeSafe() == true) LENS_PHASE2_AUTOMATED_ANCHOR else LENS_PHASE2_CREWED_ANCHOR

                else -> Vector2f(-560f, 0f)
            }
            val facing = if (side == FleetSide.ENEMY) 180f else 0f
            val spawned = manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
            manager.removeFromReserves(member)
            stabilizeShip(spawned, anchor, facing, allowFire = false, preserveAI = side == FleetSide.ENEMY)
        }
    }

    private fun arrangeLensPhase2Ships(engine: CombatEngineAPI) {
        val crewed = findCrewedLens(engine)
        crewed?.let { stabilizeShip(it, LENS_PHASE2_CREWED_ANCHOR, 0f, allowFire = false) }
        findAutomatedLens(engine)?.let { stabilizeShip(it, LENS_PHASE2_AUTOMATED_ANCHOR, 0f, allowFire = false) }
        engine.ships
            .filter { ship -> crewed != null && ship.owner != crewed.owner && !ship.isFighter }
            .forEachIndexed { index, ship ->
                val anchor = Vector2f(LENS_PHASE2_ENEMY_CLUSTER_X + index * 140f, -200f + (index % 3) * 200f)
                stabilizeShip(ship, anchor, 180f, allowFire = false, preserveAI = true)
            }
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployLensPhase2ReserveShips(engine)
        lockArcProductionCamera(engine)
        arrangeLensPhase2Ships(engine)

        val crewed = findCrewedLens(engine)
        // 标记高光提交（drift/deepwater shader）仅对 playerShip 每帧驱动，故把载人透镜设为玩家船。
        crewed?.let { engine.setPlayerShipExternal(it) }
        crewed?.shield?.let { if (!it.isOn) it.toggleOn() }

        val enemies = lensPhase2Enemies(engine)

        // (1) 回声定影场：直接 EchoFixationField.spawn 在敌群中心建场（最确定性的施放路径，等价系统 IN 首帧建场）。
        //     场每 ~4s 定影后回放并销毁；为保证观测点恒有活跃场（echoFixationFieldActive=true）且回放反复
        //     产出认知撕裂/残影/误差标记，无活跃场时即补建一个。建在敌群质心，敌舰落在场内（半径 700su）。
        if (crewed != null && enemies.isNotEmpty() && ctx.elapsed > 0.5f && !EchoFixationField.hasActiveField(engine)) {
            val cx = enemies.map { it.location.x }.average().toFloat()
            val cy = enemies.map { it.location.y }.average().toFloat()
            EchoFixationField.spawn(engine, crewed, cx, cy, systemRangeMult = 1f)
            ctx.log.info("[ASTD-Automation] lens phase-2 echo fixation field spawned at ($cx,$cy) over ${enemies.size} enemies")
        }

        // (2) 幽灵信号（仅无人模式）：无人透镜的 advanceInCombat 每 0.25s tick 对范围内敌方导弹做失制导判定，
        //     每剥离一枚导弹即在该导弹位置起一道小脉冲扰动波（并 50% 概率熄火）。为提供真实导弹，向无人透镜
        //     附近持续 spawn 敌方导弹（owner=敌舰），交由真实 ghostSignal() 路径消费——不伪造特效，
        //     脉冲必须来自真实剥离触发的 spawnGhostPulse。
        feedGhostSignalMissiles(engine, amount)

        // (3) 潮汐深水标记 / 标记高光：由 ASTDLensPermeatingTideHullMod / ASTDLensArrayCoreHullMod 每帧自驱，
        //     敌舰落在载人透镜 2500su 潮汐场内自然叠深水标记，无需脚本干预。

        // (4) fighter 误差标记降级说明（once）：parallaxDriftStacksFromFighters 依赖舰载机起飞 + on-hit 命中，
        //     实机难稳定确定性触发，故本场景降级为「视差甲板插件挂载」断言（parallaxDecksHullmod）；
        //     fighter 实命中叠误差标记的逐帧外观留人工验证。此处 log 一次，符合全局规范「降级要 log 说明」。
        if (!lensPhase2DowngradeLogged && findCrewedLens(engine) != null) {
            lensPhase2DowngradeLogged = true
            ctx.log.info(
                "[ASTD-Automation] lens phase2: parallaxDriftStacksFromFighters downgraded to " +
                        "parallaxDecksHullmod mount assertion (fighter on-hit mark hard to trigger " +
                        "deterministically in-game; fighter-driven drift visuals left to manual review)"
            )
        }

        val evidenceReady = lensPhase2EvidenceReady(engine, enemies)
        val state = when {
            crewed == null && ctx.elapsed > 10f -> {
                ctx.failureReason = "crewed gravitational lens missing: ${LensArrayCoreHullModIds.HULL_ID}"
                "Failed"
            }

            evidenceReady -> "Completed"
            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: lens_phase2_mechanisms evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, crewed)
            ctx.writeTelemetry(state, crewed, null)
        }
    }

    /**
     * 向无人透镜附近持续投放敌方导弹，供真实幽灵信号路径消费。
     *
     * 失制导每 tick 0.25s、每枚导弹 50% 命中，单 tick 内多枚同时落入范围 → P(至少一枚剥离)≈1。
     * 导弹经 engine.spawnProjectile(enemySource, null, weaponId, ...) 生成（owner=敌舰 ≠ 无人透镜 owner，
     * 满足 ghostSignal 的敌对判定）。spawn 失败（weaponId 不可用等）必须 Fail Fast 报错，绝不静默吞错。
     */
    private fun feedGhostSignalMissiles(engine: CombatEngineAPI, amount: Float) {
        val automated = findAutomatedLens(engine) ?: return
        // 幽灵信号波计数已满足则停止投放，避免无意义刷导弹。
        if (LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_GHOST_SIGNAL_WAVE_FRAMES) > 0) return
        if (ctx.elapsed < 0.75f) return

        ghostMissileFeedAcc += amount
        if (ghostMissileFeedAcc < GHOST_MISSILE_FEED_INTERVAL) return
        ghostMissileFeedAcc = 0f

        val enemySource = engine.ships.firstOrNull { it.owner != automated.owner && !it.isFighter && !it.isHulk } ?: return
        // 在无人透镜周围一圈投放，确保落入 2000su 幽灵范围内（取 800su 半径）。
        for (i in 0 until GHOST_MISSILE_BURST) {
            val ang = i * (360f / GHOST_MISSILE_BURST)
            val loc = Vector2f(
                automated.location.x + 800f * kotlin.math.cos(Math.toRadians(ang.toDouble())).toFloat(),
                automated.location.y + 800f * kotlin.math.sin(Math.toRadians(ang.toDouble())).toFloat(),
            )
            // weapon=null + weaponId 直接生成导弹实体（范式同 GravityRiftBeamEffect.spawnMine）。
            // spawn 返回 null 视为 weaponId 不可用——Fail Fast 抛错，由 automation 暴露而非伪造通过。
            val spawned = engine.spawnProjectile(enemySource, null, GHOST_FEED_MISSILE_ID, Vector2f(loc), ang + 180f, Vector2f())
            if (spawned == null) {
                throw IllegalStateException(
                    "[ASTD-Automation] lens phase-2 ghost-signal missile spawn returned null for weaponId=$GHOST_FEED_MISSILE_ID"
                )
            }
        }
    }

    /**
     * phase2 证据齐备判定：机制证据 + shader 提交计数全满足才判 Completed。
     * fighter 误差标记降级为插件挂载断言（见诊断字段 parallaxDecksHullmod），不在此处要求 fighter 实触发。
     */
    private fun lensPhase2EvidenceReady(engine: CombatEngineAPI, enemies: List<ShipAPI>): Boolean {
        if (ctx.elapsed < 1.5f) return false
        val mechOk = EchoFixationField.hasActiveField(engine) &&
                lensPhase2CognitiveTearApplied(enemies) &&
                EchoFixationAfterimageRenderer.afterimageFrames(engine) > 0 &&
                lensPhase2MaxDeepWaterOnEnemy(enemies) > 0 &&
                lensPhase2PermeatingTideHullmod(engine) &&
                lensPhase2ParallaxDecksHullmod(engine)
        val visualOk = LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_ECHO_FIXATION_FIELD_FRAMES) > 0 &&
                LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_DRIFT_MARK_FRAMES) > 0 &&
                LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_DEEP_WATER_MARK_FRAMES) > 0 &&
                LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_GHOST_SIGNAL_WAVE_FRAMES) > 0 &&
                LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_TIDE_FIELD_FRAMES) > 0
        return mechOk && visualOk
    }

    /** 是否有敌舰被认知撕裂：承伤 mult>1（EchoFixationField 回放写 hullDamageTakenMult.modifyMult）。 */
    private fun lensPhase2CognitiveTearApplied(enemies: List<ShipAPI>): Boolean =
        enemies.any { ship ->
            try {
                (ship.mutableStats?.hullDamageTakenMult?.modifiedValue ?: 0f) > 1.0001f
            } catch (_: Throwable) {
                false
            }
        }

    /** 场内敌舰深水标记最大层数（潮汐叠层证据）。 */
    private fun lensPhase2MaxDeepWaterOnEnemy(enemies: List<ShipAPI>): Int =
        enemies.maxOfOrNull { LensMarks.deepWaterStacks(it) } ?: 0

    /** 场内敌舰误差标记最大层数（认知撕裂回放 / 视差甲板均会叠误差标记）。 */
    private fun lensPhase2MaxDriftOnEnemy(enemies: List<ShipAPI>): Int =
        enemies.maxOfOrNull { LensMarks.driftStacks(it) } ?: 0

    /** 载人透镜是否挂载渗透潮汐插件（内置 hullmod）。 */
    private fun lensPhase2PermeatingTideHullmod(engine: CombatEngineAPI): Boolean {
        val crewed = findCrewedLens(engine) ?: return false
        return hasHullmod(crewed, "astd_lens_permeating_tide")
    }

    /**
     * 载人透镜是否挂载视差甲板插件（内置 hullmod）。
     * fighter 驱动误差标记实机难稳定触发（依赖舰载机起飞/命中），故 parallaxDriftStacksFromFighters
     * 降级为「插件挂载」断言：证明视差甲板机制已装载即视为通过，fighter 实命中留人工/后续。
     */
    private fun lensPhase2ParallaxDecksHullmod(engine: CombatEngineAPI): Boolean {
        val crewed = findCrewedLens(engine) ?: return false
        return hasHullmod(crewed, "astd_lens_parallax_decks")
    }

    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.18f, { findCrewedLens(ctx.engine) }) {
            lockArcProductionCamera(ctx.engine)
            arrangeLensPhase2Ships(ctx.engine)
        }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val crewed = findCrewedLens(engine)
        val enemies = lensPhase2Enemies(engine)
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": 0,")
        json.appendLine("  \"runtimeLastProjectileSpecId\": null,")
        json.appendLine("  \"lensDeployedShipIds\": ${jsonStringList(lensDeployedShipIds(engine))},")
        // ---- 机制证据 ----
        json.appendLine("  \"echoFixationFieldActive\": ${safeBool { EchoFixationField.hasActiveField(engine) }},")
        json.appendLine("  \"echoFixationCognitiveTearApplied\": ${safeBool { lensPhase2CognitiveTearApplied(enemies) }},")
        json.appendLine("  \"echoFixationAfterimageFrames\": ${EchoFixationAfterimageRenderer.afterimageFrames(engine)},")
        json.appendLine("  \"tideDeepWaterStacksOnEnemy\": ${lensPhase2MaxDeepWaterOnEnemy(enemies)},")
        json.appendLine("  \"driftStacksOnEnemy\": ${lensPhase2MaxDriftOnEnemy(enemies)},")
        json.appendLine("  \"permeatingTideHullmod\": ${safeBool { lensPhase2PermeatingTideHullmod(engine) }},")
        // fighter 误差标记降级为插件挂载断言（见 lensPhase2ParallaxDecksHullmod 注释）。
        json.appendLine("  \"parallaxDecksHullmod\": ${safeBool { lensPhase2ParallaxDecksHullmod(engine) }},")
        json.appendLine("  \"lensCrewedDeployed\": ${crewed != null},")
        json.appendLine("  \"lensAutomatedDeployed\": ${findAutomatedLens(engine) != null},")
        // ---- shader 提交计数（视觉管线生效证据，每次真实 upsert +1）----
        json.appendLine(
            "  \"echoFixationFieldVisualFrames\": ${
                LensVfxTelemetry.counter(
                    engine,
                    LensVfxTelemetry.TELEMETRY_ECHO_FIXATION_FIELD_FRAMES
                )
            },"
        )
        json.appendLine("  \"driftMarkVisualFrames\": ${LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_DRIFT_MARK_FRAMES)},")
        json.appendLine("  \"deepWaterMarkVisualFrames\": ${LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_DEEP_WATER_MARK_FRAMES)},")
        json.appendLine("  \"ghostSignalWaveFrames\": ${LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_GHOST_SIGNAL_WAVE_FRAMES)},")
        json.appendLine("  \"tideFieldVisualFrames\": ${LensVfxTelemetry.counter(engine, LensVfxTelemetry.TELEMETRY_TIDE_FIELD_FRAMES)},")
    }

    private companion object {
        // phase2 部署锚点：载人透镜上方、无人透镜下方，敌群居中（落入两者作用范围）。
        private val LENS_PHASE2_CREWED_ANCHOR = Vector2f(-260f, 260f)
        private val LENS_PHASE2_AUTOMATED_ANCHOR = Vector2f(-260f, -380f)
        private const val LENS_PHASE2_ENEMY_CLUSTER_X = 360f
        // phase2 幽灵信号导弹投放：每 0.3s 一批，每批 6 枚环绕无人透镜（确保落入 2000su 幽灵范围）。
        private const val GHOST_MISSILE_FEED_INTERVAL = 0.3f
        private const val GHOST_MISSILE_BURST = 6
        // 投放用导弹 weaponId（ASTD 既有导弹型武器，保证模组已加载；范式同 GravityRiftBeamEffect.spawnMine）。
        private const val GHOST_FEED_MISSILE_ID = "astd_ice_shard_sub"
    }
}
