package cn.kasuminova.astd.combat.automation.scenario.lens

import cn.kasuminova.astd.combat.automation.api.AutomationCombatContext
import cn.kasuminova.astd.combat.automation.api.AutomationTelemetryKeys.ICE_SHARD_SUB_SHARD_SPEC_ID
import cn.kasuminova.astd.combat.automation.api.PausePolicy
import cn.kasuminova.astd.combat.automation.base.AbstractAutomationScenario
import cn.kasuminova.astd.combat.effect.lens.iceshard.IceShardAttachScript
import cn.kasuminova.astd.combat.effect.lens.iceshard.IceShardMirvOnFireEffect
import cn.kasuminova.astd.combat.effect.lens.iceshard.IceShardMirvSplitScript
import cn.kasuminova.astd.combat.effect.lens.iceshard.IceShardMirvVfx
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxSpecs
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f

/**
 * 源生冰晶 MIRV 场景（玩家版基础验证）。
 *
 * 拆分自 ASTDAutomationCombatPlugin 的同名 if-else 分支，相位机/断言/证据写出口径原样迁移。
 */
class IceShardMirvScenario : AbstractAutomationScenario() {
    // ==== 源生冰晶 MIRV 场景状态（相位机 MOUNT → SPLIT → ATTACH → COMPLETED；玩家版基础验证） ====
    private var iceShardPhase = ICE_SHARD_PHASE_MOUNT
    private var iceShardPhaseStartedAt = 0f

    // COMPLETED 截图门控：最近一次附着/周期伤害事件时刻（截图帧需含附着星云/冰晶散布）。
    private var iceShardLastEventAt = -1f
    private var iceShardLastTrackedEventCount = 0

    override val scenarioId: String = ASTDInGameAutomationScenario.ICE_SHARD_SCENARIO_ID
    override val pausePolicy: PausePolicy = PausePolicy.FORCE_UNPAUSE

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isIceShardMirvEnabled()

    override fun init(ctx: AutomationCombatContext) {
        super.init(ctx)
        val engine = ctx.engine
        engine.setDoNotEndCombat(true)
        lockIceShardCamera(engine)
        ctx.writeDiagnostics("CombatReady")
        ctx.writeTelemetry("CombatReady", findIceShardPlayer(engine), null)
        ctx.log.info("[ASTD-Automation] scenario=${ASTDInGameAutomationScenario.ICE_SHARD_SCENARIO_ID} combat plugin initialized")
    }

    private fun findIceShardPlayer(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner == 0 && ship.hullSpec?.hullId == ICE_SHARD_PLAYER_HULL && !ship.isFighter }

    private fun findIceShardTarget(engine: CombatEngineAPI): ShipAPI? =
        engine.ships.firstOrNull { ship -> ship.owner != 0 && ship.hullSpec?.hullId == ICE_SHARD_TARGET_HULL && !ship.isFighter }

    private fun findIceShardMirv(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.ICE_SHARD_MIRV_WEAPON_ID }

    private fun findIceShardPod(ship: ShipAPI?): WeaponAPI? =
        ship?.allWeapons?.firstOrNull { it.id == ASTDInGameAutomationScenario.ICE_SHARD_POD_WEAPON_ID }

    private fun iceShardTeleCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

    private fun lockIceShardCamera(engine: CombatEngineAPI) {
        lockCameraAt(engine, ICE_SHARD_CAMERA_CENTER, ICE_SHARD_CAMERA_VISIBLE_HEIGHT)
    }

    /** 强制部署 mission reserves（玩家狮鹫 / 敌方统治者级巡洋舰靶舰均非旗舰，范式同 deploySmReserveShips）。 */
    private fun deployIceShardReserveShips(engine: CombatEngineAPI) {
        engine.setDoNotEndCombat(true)
        for (side in listOf(FleetSide.PLAYER, FleetSide.ENEMY)) {
            val manager = engine.getFleetManager(side)
            manager.isSuppressDeploymentMessages = true
            for (member in manager.reservesCopy.toList()) {
                val anchor = when {
                    side == FleetSide.PLAYER && member.hullId == ICE_SHARD_PLAYER_HULL -> ICE_SHARD_PLAYER_ANCHOR
                    side == FleetSide.ENEMY && member.hullId == ICE_SHARD_TARGET_HULL -> ICE_SHARD_TARGET_ANCHOR
                    else -> continue
                }
                val facing = if (side == FleetSide.ENEMY) 180f else 0f
                manager.spawnFleetMember(member, Vector2f(anchor), facing, 0f)
                manager.removeFromReserves(member)
            }
        }
    }

    private fun transitionIceShardPhase(next: String) {
        ctx.log.info("[ASTD-Automation] ice shard phase $iceShardPhase -> $next at ${"%.2f".format(ctx.elapsed)}s")
        iceShardPhase = next
        iceShardPhaseStartedAt = ctx.elapsed
    }

    /**
     * 舞台保活与站位（范式同 stabilizeSmShips）：双舰逐帧奶 + 辐能清零 + 钉死锚点 +
     * force fire 独占驱动（autofire 关闭）。玩家武器瞄准敌方巡洋舰（原版追踪导弹 AI
     * 目标源 = shipTarget，分裂引信读 AI 目标判定 600su）；靶舰盾常关
     * （子冰晶仅命中舰体附着，命中护盾无附加效果）。
     */
    private fun stabilizeIceShardShips(engine: CombatEngineAPI, playerFire: Boolean) {
        val player = findIceShardPlayer(engine)
        val target = findIceShardTarget(engine)
        if (player != null && !player.isHulk) {
            engine.setPlayerShipExternal(player)
            stabilizeShip(player, ICE_SHARD_PLAYER_ANCHOR, 0f, allowFire = true, preserveAI = true)
            player.hitpoints = player.maxHitpoints
            player.fluxTracker.currFlux = 0f
            player.fluxTracker.hardFlux = 0f
            player.shield?.let { if (it.isOn) it.toggleOff() }
            setIceShardAutofire(player, false)
            player.shipTarget = target
            for (weapon in listOfNotNull(findIceShardMirv(player), findIceShardPod(player))) {
                if (target != null) weapon.currAngle = Misc.getAngleInDegrees(weapon.location, target.location)
                weapon.setForceFireOneFrame(playerFire)
            }
        }
        if (target != null && !target.isHulk) {
            stabilizeShip(target, ICE_SHARD_TARGET_ANCHOR, 180f, allowFire = false)
            target.hitpoints = target.maxHitpoints
            target.fluxTracker.currFlux = 0f
            target.fluxTracker.hardFlux = 0f
            target.shield?.let { if (it.isOn) it.toggleOff() }
        }
    }

    /** 冰晶 MIRV 武器组 autofire 总开关（范式同 setSmAutofire）：force fire 独占驱动时关闭。 */
    private fun setIceShardAutofire(ship: ShipAPI?, enabled: Boolean) {
        ship ?: return
        for (group in ship.weaponGroupsCopy) {
            if (group.weaponsCopy.none {
                    it.id == ASTDInGameAutomationScenario.ICE_SHARD_MIRV_WEAPON_ID ||
                            it.id == ASTDInGameAutomationScenario.ICE_SHARD_POD_WEAPON_ID
                }
            ) continue
            if (enabled && !group.isAutofiring) group.toggleOn()
            if (!enabled && group.isAutofiring) group.toggleOff()
        }
    }

    override fun advance(ctx: AutomationCombatContext, amount: Float) {
        val engine = ctx.engine

        engine.setDoNotEndCombat(true)
        deployIceShardReserveShips(engine)
        lockIceShardCamera(engine)

        val player = findIceShardPlayer(engine)
        val target = findIceShardTarget(engine)
        val mirv = findIceShardMirv(player)
        val pod = findIceShardPod(player)

        val fusesRegistered = iceShardTeleCount(engine, IceShardMirvOnFireEffect.TELEMETRY_FUSES_REGISTERED)
        val splits = IceShardMirvSplitScript.splits(engine)
        val shardsSpawned = IceShardMirvSplitScript.shardsSpawned(engine)
        val lastSplitDist = engine.customData[IceShardMirvSplitScript.TELEMETRY_LAST_SPLIT_DIST] as? Float ?: -1f
        val attaches = iceShardTeleCount(engine, IceShardAttachScript.TELEMETRY_ATTACHES)
        val ticks = iceShardTeleCount(engine, IceShardAttachScript.TELEMETRY_TICKS)
        val splitVfx = iceShardTeleCount(engine, IceShardMirvVfx.TELEMETRY_SPLIT_VFX)
        val attachNebula = iceShardTeleCount(engine, IceShardMirvVfx.TELEMETRY_ATTACH_NEBULA)

        when (iceShardPhase) {
            ICE_SHARD_PHASE_MOUNT -> {
                stabilizeIceShardShips(engine, playerFire = false)
                if (ctx.elapsed - iceShardPhaseStartedAt >= ICE_SHARD_MOUNT_SETTLE_SECONDS) {
                    val mirvSlot = mirv?.slot?.id
                    val podSlot = pod?.slot?.id
                    val mirvRange = mirv?.spec?.maxRange ?: -1f
                    val podRange = pod?.spec?.maxRange ?: -1f
                    val mirvOp = try {
                        mirv?.spec?.getOrdnancePointCost(null, null) ?: -1f
                    } catch (_: Throwable) {
                        -1f
                    }
                    val podOp = try {
                        pod?.spec?.getOrdnancePointCost(null, null) ?: -1f
                    } catch (_: Throwable) {
                        -1f
                    }
                    val tagsOk = mirv?.spec?.tags?.containsAll(ICE_SHARD_REQUIRED_TAGS) == true &&
                            pod?.spec?.tags?.containsAll(ICE_SHARD_REQUIRED_TAGS) == true
                    val slotOk = mirv?.slot?.slotSize == WeaponAPI.WeaponSize.SMALL &&
                            mirv?.slot?.weaponType == WeaponAPI.WeaponType.MISSILE &&
                            pod?.slot?.slotSize == WeaponAPI.WeaponSize.MEDIUM &&
                            pod?.slot?.weaponType == WeaponAPI.WeaponType.MISSILE
                    when {
                        mirv == null || pod == null ||
                                mirvSlot != ICE_SHARD_PLAYER_MIRV_SLOT || podSlot != ICE_SHARD_PLAYER_POD_SLOT -> {
                            ctx.failureReason = "ice shard mount mismatch: mirvSlot=$mirvSlot podSlot=$podSlot"
                            transitionIceShardPhase(ICE_SHARD_PHASE_FAILED)
                        }

                        !slotOk -> {
                            ctx.failureReason =
                                "ice shard slot type/size mismatch: mirv=${mirv.slot?.slotSize}/${mirv.slot?.weaponType} pod=${pod.slot?.slotSize}/${pod.slot?.weaponType}"
                            transitionIceShardPhase(ICE_SHARD_PHASE_FAILED)
                        }

                        kotlin.math.abs(mirvRange - ICE_SHARD_EXPECT_RANGE) > ICE_SHARD_RANGE_TOLERANCE ||
                                kotlin.math.abs(podRange - ICE_SHARD_EXPECT_RANGE) > ICE_SHARD_RANGE_TOLERANCE -> {
                            ctx.failureReason = "ice shard range=$mirvRange/$podRange, expect $ICE_SHARD_EXPECT_RANGE"
                            transitionIceShardPhase(ICE_SHARD_PHASE_FAILED)
                        }

                        mirv.spec?.maxAmmo != ICE_SHARD_MIRV_AMMO || pod.spec?.maxAmmo != ICE_SHARD_POD_AMMO -> {
                            ctx.failureReason = "ice shard spec maxAmmo=${mirv.spec?.maxAmmo}/${pod.spec?.maxAmmo}, expect $ICE_SHARD_MIRV_AMMO/$ICE_SHARD_POD_AMMO"
                            transitionIceShardPhase(ICE_SHARD_PHASE_FAILED)
                        }

                        pod.spec?.burstSize != ICE_SHARD_POD_BURST -> {
                            ctx.failureReason = "ice shard pod burstSize=${pod.spec?.burstSize}, expect $ICE_SHARD_POD_BURST（发射舱单次两发）"
                            transitionIceShardPhase(ICE_SHARD_PHASE_FAILED)
                        }

                        kotlin.math.abs(mirvOp - ICE_SHARD_MIRV_OP) > 0.01f || kotlin.math.abs(podOp - ICE_SHARD_POD_OP) > 0.01f -> {
                            ctx.failureReason = "ice shard OP=$mirvOp/$podOp, expect $ICE_SHARD_MIRV_OP/$ICE_SHARD_POD_OP"
                            transitionIceShardPhase(ICE_SHARD_PHASE_FAILED)
                        }

                        !tagsOk -> {
                            ctx.failureReason = "ice shard tags 缺 no_drop 两件套: mirv=${mirv.spec?.tags} pod=${pod.spec?.tags}"
                            transitionIceShardPhase(ICE_SHARD_PHASE_FAILED)
                        }

                        !ProjectileVfxSpecs.has(ASTDInGameAutomationScenario.ICE_SHARD_MIRV_PROJECTILE_SPEC_ID) -> {
                            ctx.failureReason = "ice shard projectile VFX 未登记: mirv shot"
                            transitionIceShardPhase(ICE_SHARD_PHASE_FAILED)
                        }

                        else -> {
                            ctx.log.info(
                                "[ASTD-Automation] ice shard mount ok: slots=$mirvSlot/$podSlot range=$mirvRange " +
                                        "ammo=${mirv.spec?.maxAmmo}/${pod.spec?.maxAmmo} burst=${pod.spec?.burstSize} " +
                                        "OP=$mirvOp/$podOp tags=no_drop 两件套",
                            )
                            transitionIceShardPhase(ICE_SHARD_PHASE_SPLIT)
                        }
                    }
                }
            }

            ICE_SHARD_PHASE_SPLIT -> {
                stabilizeIceShardShips(engine, playerFire = true)
                // 携弹续航（dev 舞台）：1+2 携弹耗尽即补满，令母弹流持续到分裂证据齐备。
                mirv?.let { if (it.ammo <= 0) it.resetAmmo() }
                pod?.let { if (it.ammo <= 0) it.resetAmmo() }
                if (fusesRegistered >= 1 && splits >= 1) {
                    if (shardsSpawned != splits * ICE_SHARD_EXPECT_SHARDS_PER_SPLIT) {
                        ctx.failureReason =
                            "ice shard shardsSpawned=$shardsSpawned, expect ${splits * ICE_SHARD_EXPECT_SHARDS_PER_SPLIT}（15×splits，不足即有子冰晶生成失败）"
                        transitionIceShardPhase(ICE_SHARD_PHASE_FAILED)
                    } else {
                        ctx.log.info(
                            "[ASTD-Automation] ice shard split evidence: fuses=$fusesRegistered splits=$splits " +
                                    "shards=$shardsSpawned lastSplitDist=${"%.0f".format(lastSplitDist)} splitVfx=$splitVfx" +
                                    "（600su 分裂 15 枚子冰晶全数生成）",
                        )
                        transitionIceShardPhase(ICE_SHARD_PHASE_ATTACH)
                    }
                }
            }

            ICE_SHARD_PHASE_ATTACH -> {
                stabilizeIceShardShips(engine, playerFire = true)
                mirv?.let { if (it.ammo <= 0) it.resetAmmo() }
                pod?.let { if (it.ammo <= 0) it.resetAmmo() }
                if (attaches >= 1 && ticks >= 1) {
                    ctx.log.info(
                        "[ASTD-Automation] ice shard attach evidence: attaches=$attaches ticks=$ticks " +
                                "attachNebula=$attachNebula（命中舰体附着 + 周期伤害 + 附着星云）",
                    )
                    transitionIceShardPhase(ICE_SHARD_PHASE_COMPLETED)
                }
            }

            ICE_SHARD_PHASE_COMPLETED -> {
                stabilizeIceShardShips(engine, playerFire = true)
                // 截图舞台携弹续航：令母弹分裂与附着星云持续入帧。
                mirv?.let { if (it.ammo <= 0) it.resetAmmo() }
                pod?.let { if (it.ammo <= 0) it.resetAmmo() }
            }
        }

        // 最近一次附着/周期伤害事件时刻（COMPLETED 截图门控：事件近期发生才上报，令附着星云入帧）
        val eventCount = attaches + ticks
        if (eventCount > iceShardLastTrackedEventCount) {
            iceShardLastTrackedEventCount = eventCount
            iceShardLastEventAt = ctx.elapsed
        }

        val state = when {
            player == null || target == null -> {
                if (ctx.elapsed > 12f) {
                    ctx.failureReason = "ice shard ships missing: player=${player != null}, target=${target != null}"
                    "Failed"
                } else {
                    "CombatReady"
                }
            }

            iceShardPhase == ICE_SHARD_PHASE_FAILED -> "Failed"
            iceShardPhase != ICE_SHARD_PHASE_COMPLETED &&
                    ctx.elapsed - iceShardPhaseStartedAt > ICE_SHARD_PHASE_TIMEOUT -> {
                ctx.failureReason =
                    "ice shard phase timeout: $iceShardPhase（fuses=$fusesRegistered splits=$splits shards=$shardsSpawned lastSplitDist=${"%.0f".format(lastSplitDist)} attaches=$attaches ticks=$ticks splitVfx=$splitVfx nebula=$attachNebula）"
                "Failed"
            }

            iceShardPhase == ICE_SHARD_PHASE_COMPLETED -> {
                val recentEvent = iceShardLastEventAt >= 0f && ctx.elapsed - iceShardLastEventAt <= ICE_SHARD_COMPLETED_EVENT_WINDOW
                if (recentEvent || ctx.elapsed - iceShardPhaseStartedAt >= ICE_SHARD_COMPLETED_STAGE_TIMEOUT) "Completed" else "CombatReady"
            }

            else -> "CombatReady"
        }
        if (state == "Completed" && !ctx.completed) {
            ctx.markCompleted()
            ctx.log.info("[ASTD-Automation] Completed: ice_shard_mirv_basic mount/split/attach (fuse/shards/attach-tick/vfx) evidence observed")
        }
        if (ctx.elapsed - ctx.lastWriteAt >= 0.18f || state == "Completed" || state == "Failed") {
            ctx.lastWriteAt = ctx.elapsed
            ctx.writeDiagnostics(state, player)
            ctx.writeTelemetry(state, player, pod)
        }
    }

    // 捕获帧间隔 0.6s：分裂爆发/冰晶散布/附着星云在三帧内进入捕获帧。
    override fun renderCapture(ctx: AutomationCombatContext) {
        renderCompletedFrames(0.6f, { findIceShardPlayer(ctx.engine) }) { lockIceShardCamera(ctx.engine) }
    }

    override fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot) {
        val engine = ctx.engine
        val iceShardPlayer = findIceShardPlayer(engine)
        val iceShardMirv = findIceShardMirv(iceShardPlayer)
        val iceShardPod = findIceShardPod(iceShardPlayer)
        json.appendLine("  \"runtimeElapsedSeconds\": 0,")
        json.appendLine("  \"runtimeTrackedCount\": ${vfxTelemetry.trackedCount},")
        json.appendLine("  \"runtimeLastProjectileSpecId\": ${jsonString(vfxTelemetry.lastProjectileSpecId)},")
        // ---- 机制证据（源生冰晶 MIRV 烟测检查点：装配 / 引信+分裂 / 附着周期伤害+特效）----
        json.appendLine("  \"iceShardPhase\": \"$iceShardPhase\",")
        json.appendLine("  \"iceShardMirvSlotId\": ${jsonString(iceShardMirv?.slot?.id)},")
        json.appendLine("  \"iceShardPodSlotId\": ${jsonString(iceShardPod?.slot?.id)},")
        json.appendLine("  \"iceShardWeaponRange\": ${formatFloat(iceShardMirv?.range ?: -1f)},")
        json.appendLine("  \"iceShardMirvAmmo\": ${iceShardMirv?.ammo ?: -1},")
        json.appendLine("  \"iceShardPodAmmo\": ${iceShardPod?.ammo ?: -1},")
        json.appendLine("  \"iceShardFusesRegistered\": ${iceShardTeleCount(engine, IceShardMirvOnFireEffect.TELEMETRY_FUSES_REGISTERED)},")
        json.appendLine("  \"iceShardSplits\": ${IceShardMirvSplitScript.splits(engine)},")
        json.appendLine("  \"iceShardShardsSpawned\": ${IceShardMirvSplitScript.shardsSpawned(engine)},")
        json.appendLine("  \"iceShardLastSplitDist\": ${formatFloat(engine.customData[IceShardMirvSplitScript.TELEMETRY_LAST_SPLIT_DIST] as? Float ?: -1f)},")
        json.appendLine("  \"iceShardAttaches\": ${iceShardTeleCount(engine, IceShardAttachScript.TELEMETRY_ATTACHES)},")
        json.appendLine("  \"iceShardTicks\": ${iceShardTeleCount(engine, IceShardAttachScript.TELEMETRY_TICKS)},")
        json.appendLine("  \"iceShardSplitVfx\": ${iceShardTeleCount(engine, IceShardMirvVfx.TELEMETRY_SPLIT_VFX)},")
        json.appendLine("  \"iceShardAttachNebula\": ${iceShardTeleCount(engine, IceShardMirvVfx.TELEMETRY_ATTACH_NEBULA)},")
        json.appendLine("  \"iceShardOwnProjectiles\": ${engine.projectiles.count { it.projectileSpecId == ASTDInGameAutomationScenario.ICE_SHARD_MIRV_PROJECTILE_SPEC_ID }},")
        // 子冰晶弹体 spec id（astd_ice_shard_sub_msl，目录登记口径；子武器隐藏不装配）。
        json.appendLine("  \"iceShardSubShardsInPlay\": ${engine.missiles.count { it.projectileSpecId == ICE_SHARD_SUB_SHARD_SPEC_ID }},")
    }

    private companion object {
        // 源生冰晶 MIRV 场景：相位机、锚点与期望证据（玩家版基础验证）。
        private const val ICE_SHARD_PHASE_MOUNT = "MOUNT"
        private const val ICE_SHARD_PHASE_SPLIT = "SPLIT"
        private const val ICE_SHARD_PHASE_ATTACH = "ATTACH"
        private const val ICE_SHARD_PHASE_COMPLETED = "COMPLETED"
        private const val ICE_SHARD_PHASE_FAILED = "FAILED"
        private const val ICE_SHARD_PLAYER_HULL = "gryphon"
        private const val ICE_SHARD_TARGET_HULL = "dominator"
        private const val ICE_SHARD_PLAYER_POD_SLOT = "WS 008"
        private const val ICE_SHARD_PLAYER_MIRV_SLOT = "WS 010"
        private val ICE_SHARD_PLAYER_ANCHOR = Vector2f(-700f, 0f)

        // 靶舰距玩家 1300su（<1600 射程）：母弹 ≤600su 分裂后子冰晶射程 1000su 必然覆盖。
        private val ICE_SHARD_TARGET_ANCHOR = Vector2f(600f, 0f)
        private val ICE_SHARD_CAMERA_CENTER = Vector2f(150f, 0f)
        private const val ICE_SHARD_CAMERA_VISIBLE_HEIGHT = 1700f
        private const val ICE_SHARD_MOUNT_SETTLE_SECONDS = 0.6f

        // MOUNT 相位校验：射程 1600 / ammo 1/2 / 发射舱 burst 2 / OP 6/12 / no_drop 两件套（weapon_data.csv 口径）。
        private const val ICE_SHARD_EXPECT_RANGE = 1600f
        private const val ICE_SHARD_RANGE_TOLERANCE = 5f
        private const val ICE_SHARD_MIRV_AMMO = 1
        private const val ICE_SHARD_POD_AMMO = 2
        private const val ICE_SHARD_POD_BURST = 2
        private const val ICE_SHARD_MIRV_OP = 6f
        private const val ICE_SHARD_POD_OP = 12f
        private val ICE_SHARD_REQUIRED_TAGS = setOf("no_drop", "no_drop_salvage")

        // SPLIT 相位校验：子冰晶生成数 == 分裂次数 ×15（设计案定稿单母弹 15 枚）。
        private const val ICE_SHARD_EXPECT_SHARDS_PER_SPLIT = 15

        // COMPLETED 截图门控：附着/周期伤害事件近 2.5s 内发生才上报（附着星云/冰晶散布入帧）；保底舞台超时。
        private const val ICE_SHARD_COMPLETED_EVENT_WINDOW = 2.5f
        private const val ICE_SHARD_COMPLETED_STAGE_TIMEOUT = 30f
        private const val ICE_SHARD_PHASE_TIMEOUT = 90f
    }
}
