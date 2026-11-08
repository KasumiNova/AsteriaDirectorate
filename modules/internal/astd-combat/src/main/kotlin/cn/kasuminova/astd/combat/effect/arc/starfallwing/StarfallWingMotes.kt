package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxDriverPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.loading.WeaponSlotAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

/** 星羽追踪光矢的统一发射口：翼装弹幕、折跃齐射、断熵激发共用，发射位置与朝向取自舰体系统槽位。 */
object StarfallWingMotes {

    private val log = AstdLog.logger

    private val slotMismatchWarned = HashSet<String>()

    /** 按左右舷配对的舰体系统槽位（序号一致，左舷在前）；槽位数量或左右舷不对称时记录日志并返回空表。 */
    fun wingSlotPairs(ship: ShipAPI): List<Pair<WeaponSlotAPI, WeaponSlotAPI>> {
        val slots = ship.hullSpec.allWeaponSlotsCopy.filter { it.isSystemSlot }.sortedBy { it.id }
        val left = slots.filter { it.angle > 0f }
        val right = slots.filter { it.angle < 0f }
        if (left.size != ImaginaryWingsTuning.WING_SLOT_PAIRS || right.size != left.size) {
            val hullId = ship.hullSpec.hullId
            if (slotMismatchWarned.add(hullId)) {
                log.warn("[ASTD] 星羽发射口缺失：hull=$hullId 系统槽位 左=${left.size} 右=${right.size}，期望各 ${ImaginaryWingsTuning.WING_SLOT_PAIRS}，本次光矢缺席")
            }
            return emptyList()
        }
        return left.zip(right)
    }

    fun spawnFromSlot(engine: CombatEngineAPI, source: ShipAPI, slot: WeaponSlotAPI, target: CombatEntityAPI?) {
        spawn(engine, source, slot.computePosition(source), slot.computeMidArcAngle(source), target)
    }

    /** 断熵激发的发射口：取槽位对列表居中一对，对应舰体中段槽位。 */
    fun centralSlotPair(ship: ShipAPI): Pair<WeaponSlotAPI, WeaponSlotAPI>? {
        val pairs = wingSlotPairs(ship)
        if (pairs.isEmpty()) return null
        return pairs[pairs.size / 2]
    }

    /**
     * 从 [origin] 沿 [launchAngleDeg] 发射一枚光矢，初速沿外扩方向，短暂延迟后加速追踪 [target]。
     */
    fun spawn(
        engine: CombatEngineAPI,
        source: ShipAPI,
        origin: Vector2f,
        launchAngleDeg: Float,
        target: CombatEntityAPI?,
    ) {
        val launchVel = MathUtils.getPointOnCircumference(
            Vector2f(source.velocity), ImaginaryWingsTuning.MOTE_LAUNCH_SPEED, launchAngleDeg,
        )
        val spawned = engine.spawnProjectile(
            source, null, StarfallWingTuning.MOTE_WEAPON_ID, Vector2f(origin), launchAngleDeg, launchVel,
        ) as? MissileAPI
        if (spawned == null) {
            log.warn("[ASTD] 星羽光矢生成失败：spawnProjectile 未产出 MissileAPI: weapon=${StarfallWingTuning.MOTE_WEAPON_ID}")
            return
        }
        spawned.source = source
        spawned.missileAI = StarfallWingMoteAi(spawned, target)
        spawned.empResistance = 10000
        spawned.armingTime = 0f
        spawned.damageAmount = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.MOTE_DAMAGE, source.owner == 0)
        spawned.maxFlightTime = ImaginaryWingsTuning.MOTE_FLIGHT_TIME
        spawned.setNoGlowTime(999f)
        spawned.isNoFlameoutOnFizzling = true
        spawned.interruptContrail()
        spawned.spriteAlphaOverride = 0f
        spawned.glowRadius = 0f
        // 脚本 spawn 的弹体不触发 onFireEffect，拖尾必须显式登记。
        ProjectileVfxDriverPlugin.track(engine, spawned, StarfallWingTuning.MOTE_SPEC_ID)
        StarfallWingVfx.spawnMoteSplitFlare(engine, Vector2f(spawned.location))
    }
}
