package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.effect.lens.GravityRiftVortexVisual
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.loading.WeaponGroupSpec
import com.fs.starfarer.api.loading.WeaponGroupType
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

/**
 * 引力裂隙发生器（Gravity Rift Generator）——茑萝级（ZW-103）舰船系统
 * （purple/20-production.md §2，2026-09 D27 重做；2026-09-20 二轮重做：
 * 目标锁定 + 贴图旋涡 + **真实光束**）。
 *
 * 目标锁定（对齐原版熵放大器 [com.fs.starfarer.api.impl.combat.EntropyAmplifierStats] 模式）：
 * - 玩家操控取 shipTarget；AI 取 [ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM]
 *   （由 [GravityRiftSystemAI] 决策后写入）；
 * - 无有效目标时系统不可激活（[isUsable] = false），HUD 提示「无目标」；
 *   已锁定但超出系统射程（[GravityRiftTuning.SYSTEM_RANGE] 经 systemRangeBonus 折算）时
 *   提示「超出射程」。
 *
 * 三段行为（CSV：chargeUp 1.0 / active 1.0 / down 1.0 / cooldown 12）：
 * 1. **IN（蓄能 1s）**：首帧锁定目标舰，在目标舰体随机位置采样旋涡锚点
 *    （8 次采样，优先取无护盾覆盖点：shield 不存在/未开启/不在护盾弧内），
 *    生成红色贴图旋涡（[GravityRiftVortexVisual]，60°/s 自旋，1s 淡入）；
 * 2. **ACTIVE（1s）**：首帧自旋涡中心向目标发射**真实光束**——隐藏光束武器
 *    astd_grav_rift_beam 挂载在 FX drone（dem_drone）上（mod 界成熟技巧，
 *    spawnProjectile 不支持 specClass=beam），源舰的 energyWeaponRangeBonus /
 *    beamWeaponRangeBonus / energyWeaponDamageMult / beamWeaponDamageMult 折算到
 *    drone；光束机制与布雷间隔直接复用原版裂隙洪流发射极
 *    （beamEffect = [cn.kasuminova.astd.combat.effect.lens.GravityRiftBeamEffect]，
 *    红色正色、不使用反色星云）；光束全程 [GravityRiftTuning.BEAM_DURATION]s，
 *    由 drone 上的 [AdvanceableListener] 每帧定位/瞄准/强火；
 * 3. **OUT（1s）/结束**：旋涡淡出并自移除；光束 drone 到时自移除。
 *    系统被打断（unapply）时：旋涡 forceFadeOut 收口、drone 立即移除，
 *    避免渲染插件与实体滞留到战斗结束。
 *
 * 自动化观测：目标 id / 旋涡生成 / 光束生成写入 engine.customData
 * （[TELEMETRY_TARGET_KEY] / [TELEMETRY_VORTEX_KEY] / [TELEMETRY_BEAM_KEY] 前缀 + ship.id）；
 * 裂隙计数由 GravityRiftBeamEffect 按母舰 id 写入 [TELEMETRY_MINES_KEY] 前缀。
 * 这些键**不**在 unapply 清除（战斗级生命周期），供 ASTDAutomationCombatPlugin 轮询断言。
 *
 * Fail Fast：战斗内 engine/weapon API 均为安全访问，全程不包 try；
 * drone 缺失母舰旗标、目标在激活瞬间失效等异常路径均有日志。
 */
class GravityRiftSystemStats : BaseShipSystemScript() {

    /** 一次激活的触发闩（IN 首帧建立，unapply 清除）。 */
    private class RiftActivation(
        val target: ShipAPI,
        /** 旋涡锚点相对目标位置的偏移（激活时采样，随目标移动）。 */
        val anchorOffset: Vector2f,
        val vortex: GravityRiftVortexVisual,
    ) {
        /** 光束 drone（ACTIVE 首帧生成）。 */
        var beamDrone: ShipAPI? = null
    }

    override fun apply(
        stats: MutableShipStatsAPI,
        id: String,
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
    ) {
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk) return
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return

        when (state) {
            ShipSystemStatsScript.State.IN -> prepareActivation(engine, ship)
            ShipSystemStatsScript.State.ACTIVE -> fireBeam(engine, ship)
            else -> Unit
        }
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        val ship = stats.entity as? ShipAPI ?: return
        val engine = Global.getCombatEngine() ?: return
        val activation = engine.customData[activationKey(ship)] as? RiftActivation ?: return
        // 系统被打断：光束 drone 立即移除（正常到时自移除，重复 removeEntity 为空操作安全路径——
        // 但 drone 到时已不在引擎中，这里仅在仍存活时移除）
        activation.beamDrone?.let { if (it.isAlive) engine.removeEntity(it) }
        activation.vortex.forceFadeOut()
        engine.customData.remove(activationKey(ship))
    }

    /**
     * IN 首帧（activation key 缺席即首帧闩）：锁定目标舰、采样旋涡锚点、生成旋涡。
     * 目标在激活瞬间失效属于异常路径（isUsable 已挡），记录错误并放弃本次激活视觉。
     */
    private fun prepareActivation(engine: CombatEngineAPI, ship: ShipAPI) {
        if (engine.customData[activationKey(ship)] != null) return

        val target = findTarget(ship)
        if (target == null) {
            log.error("引力裂隙发生器激活瞬间无有效目标（ship=${ship.id}），isUsable 闸门被绕过？")
            return
        }

        val anchorOffset = sampleVortexAnchor(target)
        val vortexRadius = 65f
        val vortex = GravityRiftVortexVisual.spawn(engine, target, anchorOffset, vortexRadius)

        engine.customData[activationKey(ship)] = RiftActivation(target, anchorOffset, vortex)
        engine.customData[TELEMETRY_TARGET_KEY + ship.id] = target.id
        engine.customData[TELEMETRY_VORTEX_KEY + ship.id] = 1
    }

    /**
     * ACTIVE 首帧（beamDrone 缺席即首帧闩）：生成 FX drone 真实光束。
     * drone 定位/瞄准/强火由其上的 [BeamDroneController] 每帧驱动，到时自移除。
     */
    private fun fireBeam(engine: CombatEngineAPI, ship: ShipAPI) {
        val activation = engine.customData[activationKey(ship)] as? RiftActivation ?: return
        if (activation.beamDrone != null) return

        val hullSpec = Global.getSettings().getHullSpec(FX_DRONE_HULL_ID)
        val variant = Global.getSettings().createEmptyVariant(FX_DRONE_HULL_ID, hullSpec)
        variant.addWeapon(FX_DRONE_SLOT_ID, BEAM_WEAPON_ID)
        val group = WeaponGroupSpec(WeaponGroupType.LINKED)
        group.addSlot(FX_DRONE_SLOT_ID)
        variant.addWeaponGroup(group)

        val drone = engine.createFXDrone(variant)
        drone.mutableStats.hullDamageTakenMult.modifyMult(FX_DRONE_MOD_ID, 0f)
        // 射程/伤害加成继承：光束基础射程 1000 受能量武器+光束武器射程影响（规格 §2）
        drone.mutableStats.energyWeaponRangeBonus.applyMods(ship.mutableStats.energyWeaponRangeBonus)
        drone.mutableStats.beamWeaponRangeBonus.applyMods(ship.mutableStats.beamWeaponRangeBonus)
        drone.mutableStats.energyWeaponDamageMult.applyMods(ship.mutableStats.energyWeaponDamageMult)
        drone.mutableStats.beamWeaponDamageMult.applyMods(ship.mutableStats.beamWeaponDamageMult)
        drone.aiFlags.setFlag(ShipwideAIFlags.AIFlags.DRONE_MOTHERSHIP, Float.MAX_VALUE, ship)
        drone.owner = ship.owner
        drone.setCollisionClass(CollisionClass.NONE)
        drone.giveCommand(ShipCommand.SELECT_GROUP, null, 0)
        drone.setDrone(true)

        @Suppress("UNCHECKED_CAST")
        val beamWeapon = drone.allWeapons[0] as WeaponAPI
        val anchor = activation.vortex.currentAnchor()
        drone.location.set(anchor)
        drone.facing = Misc.getAngleInDegrees(anchor, activation.target.location)
        drone.addListener(
            BeamDroneController(
                engine, drone, beamWeapon, activation.target, activation.vortex,
            ),
        )
        engine.addEntity(drone)

        activation.beamDrone = drone
        engine.customData[TELEMETRY_BEAM_KEY + ship.id] = 1
    }

    /**
     * 光束 drone 每帧驱动：定位到旋涡中心、瞄准目标（目标消亡后保持最后朝向直射）、
     * 强火一帧并刷新光束端点；到 [GravityRiftTuning.BEAM_DURATION] 停止强火，
     * 再等 [DRONE_LINGER_SECONDS]（光束 chargedown 0.3s 淡出 + 余量）后自移除，
     * 避免光束末端硬切。
     */
    private class BeamDroneController(
        private val engine: CombatEngineAPI,
        private val drone: ShipAPI,
        private val weapon: WeaponAPI,
        private val target: ShipAPI,
        private val vortex: GravityRiftVortexVisual,
    ) : AdvanceableListener {
        private var elapsed = 0f
        private var lastFacing = drone.facing

        override fun advance(amount: Float) {
            elapsed += amount
            if (elapsed >= GravityRiftTuning.BEAM_DURATION + DRONE_LINGER_SECONDS || !drone.isAlive) {
                if (drone.isAlive) engine.removeEntity(drone)
                return
            }
            val from = vortex.currentAnchor()
            drone.location.set(from)
            if (target.isAlive && !target.isHulk) {
                lastFacing = Misc.getAngleInDegrees(from, target.location)
            }
            drone.facing = lastFacing
            weapon.setCurrAngle(lastFacing)
            if (elapsed < GravityRiftTuning.BEAM_DURATION) {
                weapon.setForceFireOneFrame(true)
            }
            weapon.updateBeamFromPoints()
        }
    }

    /**
     * 旋涡锚点采样（[ANCHOR_SAMPLES] 次）：在目标碰撞半径 [ANCHOR_RADIUS_MIN_FRAC, ANCHOR_RADIUS_MAX_FRAC]
     * 倍处随机取点，优先返回无护盾覆盖点（shield 不存在/未开启/不在护盾弧内）；
     * 全部被护盾覆盖时取首个采样点（护盾上撕开旋涡同样成立）。
     */
    private fun sampleVortexAnchor(target: ShipAPI): Vector2f {
        var first: Vector2f? = null
        repeat(ANCHOR_SAMPLES) {
            val angle = MathUtils.getRandomNumberInRange(0f, 360f)
            val radiusFrac = MathUtils.getRandomNumberInRange(ANCHOR_RADIUS_MIN_FRAC, ANCHOR_RADIUS_MAX_FRAC)
            val offset = Misc.getUnitVectorAtDegreeAngle(angle)
            offset.scale(target.collisionRadius * radiusFrac)
            if (first == null) first = Vector2f(offset)
            val point = Vector2f.add(target.location, offset, null)
            val shield = target.shield
            if (shield == null || !shield.isOn || !shield.isWithinArc(point)) {
                return Vector2f(offset)
            }
        }
        return first ?: Vector2f(0f, 0f)
    }

    /**
     * 目标解析（熵放大器模式）：AI 旗标 [ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM] 优先，
     * 否则取 shipTarget（玩家锁定目标）；校验敌我/战机/残骸/相位/存活与系统射程。
     */
    private fun findTarget(ship: ShipAPI): ShipAPI? {
        var target: ShipAPI? = null
        if (ship.shipAI != null && ship.aiFlags.hasFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)) {
            target = ship.aiFlags.getCustom(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM) as? ShipAPI
        }
        if (target == null) target = ship.shipTarget
        if (!isValidTarget(ship, target)) return null

        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, GravityRiftTuning.SYSTEM_RANGE)
        val dist = Misc.getDistance(ship.location, target!!.location)
        val radSum = ship.collisionRadius + target.collisionRadius
        return if (dist <= range + radSum) target else null
    }

    private fun isValidTarget(ship: ShipAPI, target: ShipAPI?): Boolean =
        target != null && target !== ship && target.owner != ship.owner &&
            !target.isFighter && !target.isHulk && !target.isPhased && target.isAlive

    override fun isUsable(system: ShipSystemAPI, ship: ShipAPI): Boolean =
        findTarget(ship) != null

    override fun getInfoText(system: ShipSystemAPI, ship: ShipAPI): String? {
        if (system.isOutOfAmmo) return null
        if (system.state != ShipSystemAPI.SystemState.IDLE) return null
        if (findTarget(ship) != null) return null
        // 有锁定目标但不可用（超射程/相位等）→ 超出射程；完全无锁定 → 无目标
        val raw = ship.shipTarget
        return if (raw != null && raw !== ship && raw.owner != ship.owner && raw.isAlive && !raw.isHulk && !raw.isFighter) {
            I18n[I18n.Categories.MOD, "ui.grav_rift.info.out_of_range"]
        } else {
            I18n[I18n.Categories.MOD, "ui.grav_rift.info.no_target"]
        }
    }

    companion object {
        private val log = Global.getLogger(GravityRiftSystemStats::class.java)

        /** 隐藏光束武器（真实光束载体，挂载在 FX drone 上）。 */
        private const val BEAM_WEAPON_ID = "astd_grav_rift_beam"

        /** FX drone 舰体（原版 dem_drone：不可见、collisionRadius 2、自带 WS 001 槽）。 */
        private const val FX_DRONE_HULL_ID = "dem_drone"
        private const val FX_DRONE_SLOT_ID = "WS 001"

        /** drone 免伤修饰句柄（FX drone 不应被击毁）。 */
        private const val FX_DRONE_MOD_ID = "astd_grav_rift_fx"

        /** 停止强火后 drone 滞留时长（秒）：覆盖光束 chargedown 0.3s 淡出 + 余量。 */
        private const val DRONE_LINGER_SECONDS = 0.5f

        /** 旋涡锚点采样：次数与半径比例区间（相对目标碰撞半径）。 */
        private const val ANCHOR_SAMPLES = 8
        private const val ANCHOR_RADIUS_MIN_FRAC = 2f
        private const val ANCHOR_RADIUS_MAX_FRAC = 2.5f

        /** 触发性 customData 键前缀（每船一条，unapply 清除）；键用 ship.id（战斗内唯一）。 */
        private const val ACTIVATION_KEY = "astd_grav_rift_activation:"

        /** 自动化观测键前缀（战斗级生命周期，不在 unapply 清除）。 */
        const val TELEMETRY_TARGET_KEY = "astd_grav_rift_telemetry_target:"
        const val TELEMETRY_VORTEX_KEY = "astd_grav_rift_telemetry_vortex:"
        const val TELEMETRY_BEAM_KEY = "astd_grav_rift_telemetry_beam:"
        const val TELEMETRY_MINES_KEY = "astd_grav_rift_telemetry_mines:"

        private fun activationKey(ship: ShipAPI): String = "$ACTIVATION_KEY${ship.id}"
    }
}
