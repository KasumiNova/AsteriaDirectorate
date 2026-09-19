package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.impl.render.BeamSprites
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.renderer.effect.lens.GravityRiftVortexVfx
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.impl.combat.RiftCascadeMineExplosion
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 引力裂隙发生器（Gravity Rift Generator）——茑萝级（ZW-103）舰船系统
 * （purple/20-production.md §2，2026-09 D27 重做）。
 *
 * 以原版裂隙洪流发射极（riftcascade / [com.fs.starfarer.api.impl.combat.RiftCascadeEffect]）
 * 为基线的红色复刻，三段行为（CSV：chargeUp 1.0 / active 1.2 / down 0.5 / cooldown 12）：
 *
 * 1. **IN（蓄能 1s）**：首帧锁定落点——玩家舰（含自动操纵）取鼠标点，AI 取
 *    [ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS] 的预判点（原版定点系统同款约定），
 *    皆无则沿舰船朝向取满射程兜底；
 *    落点按系统射程（[GravityRiftTuning.SYSTEM_RANGE] 经 systemRangeBonus 折算）限幅。
 *    同帧在落点生成红色引力旋涡（[GravityRiftVortexVfx]，蓄能期渐显收拢）。
 * 2. **ACTIVE（1.2s）**：首帧旋涡 detonate（脉冲增亮后扩散淡出），并在旋涡
 *    [VICTIM_ACQUIRE_RANGE] 内锁定光束目标舰——锁定时**自旋涡侧向目标舰**发射一道
 *    红色裂隙光束（纯视觉，BoxUtil 锥形光束 trail，素材复用原版 riftcascade 的
 *    LASER 束体贴图对，全局计时器自收口）；未锁定则不发光束。随后按
 *    [GravityRiftTuning.SPAWN_INTERVAL] 逐个生成裂隙地雷——数量由开火距离决定
 *    （越近越多，[GravityRiftTuning.riftCount]，上限 5），单发伤害按序位在难度区间上
 *    插值递增（[GravityRiftTuning.riftDamage]），首个落在散布中心、其余在
 *    [GravityRiftTuning.SCATTER_RADIUS] 内随机散布；散布中心 = 光束目标存活时的实时
 *    位置，否则为旋涡锁定点。地雷初始化七步与原版 spawnMine 完全一致
 *    （SIZE_MULT_KEY 同时驱动爆炸视觉尺寸与伤害乘区）。
 * 3. **OUT / 结束**：无持续效果；全部触发闩（落点/旋涡/生成计划）在 unapply 清除。
 *    若系统在 ACTIVE 前被打断（如舰船被击毁控制），未 detonate 的旋涡由 unapply
 *    补发 detonate 让其自然淡出，避免渲染插件滞留到战斗结束。
 *
 * 自动化观测：每次激活把「本次计划裂隙数 / 累计已生成数」写入 engine.customData
 * （[TELEMETRY_PLANNED_KEY] / [TELEMETRY_SPAWNED_KEY] 前缀 + ship.id），
 * 这两个键**不**在 unapply 清除（战斗级生命周期），供 ASTDAutomationCombatPlugin 轮询断言。
 *
 * Fail Fast：战斗内 engine/projectile API 均为安全访问，全程不包 try；
 * BeamSprites.load 内部已带 warn 兜底，返回 null 时仅跳过光束视觉（机制不受影响）。
 */
class GravityRiftSystemStats : BaseShipSystemScript() {

    /** 一次激活的生成计划（IN 首帧建立，unapply 清除）。 */
    private class RiftPlan(
        val target: Vector2f,
        val distance: Float,
        val planned: Int,
        val damageMin: Float,
        val damageMax: Float,
    ) {
        var spawned: Int = 0
        var elapsed: Float = 0f

        /** ACTIVE 首帧的 detonate + 光束是否已执行。 */
        var fired: Boolean = false

        /**
         * 光束目标（ACTIVE 首帧在旋涡 [VICTIM_ACQUIRE_RANGE] 内锁定的最近敌舰，可为空）：
         * 非空时光束自旋涡射向该舰、裂隙逐帧以该舰实时位置为中心展开（光束命中的叙事）；
         * 为空（纯区域拒止施放）时无光束、裂隙在旋涡处展开。
         */
        var victim: ShipAPI? = null
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
            ShipSystemStatsScript.State.IN -> preparePlan(engine, ship)
            ShipSystemStatsScript.State.ACTIVE -> advancePlan(engine, ship)
            else -> Unit
        }
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        val ship = stats.entity as? ShipAPI ?: return
        val engine = Global.getCombatEngine() ?: return
        // ACTIVE 被打断时未生成的裂隙不得静默丢失（正常收口 spawned == planned，不告警）
        (engine.customData[planKey(ship)] as? RiftPlan)?.let { plan ->
            if (plan.fired && plan.spawned < plan.planned) {
                log.warn("引力裂隙发生器 ACTIVE 中断：planned=${plan.planned} spawned=${plan.spawned}，${plan.planned - plan.spawned} 枚裂隙未生成（ship=${ship.id}）")
            }
        }
        // 未进入 ACTIVE 就被打断时，旋涡还在蓄能渐显态（不会自移除）：补发 detonate 让其淡出。
        (engine.customData[vortexKey(ship)] as? GravityRiftVortexVfx)?.detonate()
        engine.customData.remove(vortexKey(ship))
        engine.customData.remove(planKey(ship))
    }

    /**
     * IN 首帧（plan key 缺席即首帧闩）：锁定落点（限幅到有效射程）、按落点距离确定裂隙数量、
     * 解析难度伤害区间、生成引力旋涡。
     */
    private fun preparePlan(engine: CombatEngineAPI, ship: ShipAPI) {
        if (engine.customData[planKey(ship)] != null) return

        val range = ASTDArcCombatUtil.effectiveSystemRange(ship, GravityRiftTuning.SYSTEM_RANGE)
        val raw = resolveRawTarget(engine, ship, range)
        val dist = MathUtils.getDistance(ship.location, raw)
        val target = if (dist > range) {
            val dir = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(ship.location, raw))
            dir.scale(range)
            Vector2f.add(ship.location, dir, null)
        } else {
            raw
        }
        val clampedDist = dist.coerceAtMost(range)

        val values = GravityRiftTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
        val plan = RiftPlan(
            target = target,
            distance = clampedDist,
            planned = GravityRiftTuning.riftCount(clampedDist),
            damageMin = values.damageMin,
            damageMax = values.damageMax,
        )
        engine.customData[planKey(ship)] = plan
        engine.customData[vortexKey(ship)] = GravityRiftVortexVfx.spawn(engine, target)
        engine.customData[telemetryPlannedKey(ship)] = plan.planned
    }

    /**
     * 落点来源：玩家舰（含自动操纵）取鼠标点 → AI 预判点（SYSTEM_TARGET_COORDS）→ 朝向满射程兜底。
     * 自动操纵时 shipAI 非空但鼠标点仍是玩家意图，必须先判玩家舰再读 AI 预判
     * （否则手动施放静默退化为舰首满射程）。
     */
    private fun resolveRawTarget(engine: CombatEngineAPI, ship: ShipAPI, range: Float): Vector2f {
        if (engine.playerShip === ship) {
            val mouse = ship.mouseTarget
            if (mouse != null) return Vector2f(mouse)
        }
        val aim = ship.aiFlags?.getCustom(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS) as? Vector2f
        // 拷贝一份：flag 里的向量归 AI 所有，后续限幅/散布不得原地改写。
        if (aim != null) return Vector2f(aim)
        val dir = Misc.getUnitVectorAtDegreeAngle(ship.facing)
        dir.scale(range)
        return Vector2f.add(ship.location, dir, null)
    }

    /**
     * ACTIVE 每帧：首帧 detonate 旋涡、在旋涡 [VICTIM_ACQUIRE_RANGE] 内锁定光束目标舰
     * （锁定成功才发射光束——光束自旋涡侧射向目标舰，「光束命中产生裂隙」的叙事；
     * 未锁定则不发光束，裂隙直接在旋涡处展开），随后按间隔逐个生成裂隙地雷。
     * 生成节奏用 elapsedInLastFrame 累积（与暂停守卫配合：暂停时 apply 直接返回，节奏自然冻结）。
     */
    private fun advancePlan(engine: CombatEngineAPI, ship: ShipAPI) {
        val plan = engine.customData[planKey(ship)] as? RiftPlan ?: return
        val amount = engine.elapsedInLastFrame
        if (amount <= 0f) return
        plan.elapsed += amount

        if (!plan.fired) {
            plan.fired = true
            (engine.customData[vortexKey(ship)] as? GravityRiftVortexVfx)?.detonate()
            plan.victim = findRiftVictim(engine, ship, plan.target)
            plan.victim?.let { fireBeamVisual(engine, plan.target, it) }
        }

        while (plan.spawned < plan.planned && plan.elapsed >= plan.spawned * GravityRiftTuning.SPAWN_INTERVAL) {
            spawnMine(engine, ship, plan, plan.spawned)
            plan.spawned++
            val total = (engine.customData[telemetrySpawnedKey(ship)] as? Int ?: 0) + 1
            engine.customData[telemetrySpawnedKey(ship)] = total
        }
    }

    /** 光束目标锁定：旋涡 [VICTIM_ACQUIRE_RANGE] 内最近的存活非战机敌舰（相位/残骸不算）。 */
    private fun findRiftVictim(engine: CombatEngineAPI, ship: ShipAPI, vortex: Vector2f): ShipAPI? {
        var best: ShipAPI? = null
        var bestDist = Float.MAX_VALUE
        for (candidate in engine.ships) {
            if (candidate.owner == ship.owner) continue
            if (candidate.isFighter || candidate.isHulk || candidate.isPhased || !candidate.isAlive) continue
            val dist = MathUtils.getDistance(vortex, candidate.location)
            if (dist <= VICTIM_ACQUIRE_RANGE && dist < bestDist) {
                best = candidate
                bestDist = dist
            }
        }
        return best
    }

    /**
     * 红色裂隙光束（纯视觉）：自旋涡侧 [from] 射向目标舰船 [victim]——文档叙事是
     * 「旋涡释放裂隙喷流」，光束起点必须在裂隙侧而非本舰。
     * 素材复用原版裂隙洪流（riftcascade）的 LASER 束体贴图对（beam_laser_core/fringe），
     * 白芯红边、旋涡侧略宽；锥形光束 trail + 全局计时器自动收口。
     */
    private fun fireBeamVisual(engine: CombatEngineAPI, from: Vector2f, victim: ShipAPI) {
        if (MathUtils.getDistance(from, victim.location) < MIN_BEAM_LENGTH) return
        val sprites = BeamSprites.load(BEAM_CORE_PATH, BEAM_FRINGE_PATH) ?: return
        val facing = Misc.getAngleInDegrees(from, victim.location)
        val length = MathUtils.getDistance(from, victim.location)
        val beam = BoxUtilCombatVfx.createAndAddTaperedBeamTrail(
            engine = engine,
            location = Vector2f(from),
            facing = facing,
            length = length,
            tailWidth = BEAM_TIP_WIDTH,
            headWidth = BEAM_BASE_WIDTH,
            coreColor = BEAM_CORE_COLOR,
            fringeColor = BEAM_FRINGE_COLOR,
            coreSprite = sprites.first,
            fringeSprite = sprites.second,
            // 必须压过粒子层：裂隙近炸的负爆黑洞/烟雾与光束同刻迸发，
            // 低于粒子层时光束会被爆炸粒子整段遮盖（实机 2026-09-19 验证）。
            layer = CombatEngineLayers.ABOVE_PARTICLES,
            full = 9999f,
            tailAlphaMul = 0.55f,
            headAlphaMul = 0.85f,
            tailEmissiveAlphaMul = 1.0f,
            headEmissiveAlphaMul = 1.5f,
            mixPower = 0.5f,
        )
        beam?.setGlobalTimer(BEAM_FADE_IN, BEAM_HOLD, BEAM_FADE_OUT)
    }

    /**
     * 裂隙地雷生成（对齐原版 RiftCascadeEffect.spawnMine 七步）：
     * 伤害 = [GravityRiftTuning.riftDamage] 序位插值 × 难度区间，换算为伤害乘区后与
     * 视觉尺寸共用同一 sizeMult 写入 [RiftCascadeMineExplosion.SIZE_MULT_KEY]（原版同一约定）。
     * 散布中心：光束目标存活时取其**实时位置**（裂隙随光束命中在目标周围展开），
     * 目标已毁或无目标时取旋涡锁定点。
     */
    private fun spawnMine(engine: CombatEngineAPI, ship: ShipAPI, plan: RiftPlan, index: Int) {
        val victim = plan.victim
        val anchor = if (victim != null && victim.isAlive && !victim.isHulk) victim.location else plan.target
        val loc = if (index == 0) {
            Vector2f(anchor)
        } else {
            Misc.getPointWithinRadius(anchor, GravityRiftTuning.SCATTER_RADIUS)
        }
        val damage = GravityRiftTuning.riftDamage(plan.damageMin, plan.damageMax, index, plan.planned)
        val sizeMult = GravityRiftTuning.riftDamageMult(damage)

        val mine = engine.spawnProjectile(
            ship, null, MINELAYER_ID, loc, MathUtils.getRandomNumberInRange(0f, 360f), null,
        ) as MissileAPI
        mine.setCustomData(RiftCascadeMineExplosion.SIZE_MULT_KEY, sizeMult)
        engine.applyDamageModifiersToSpawnedProjectileWithNullWeapon(
            ship, WeaponAPI.WeaponType.ENERGY, false, mine.damage,
        )
        mine.damage.modifier.modifyMult(MINE_SIZE_MULT_MOD_ID, sizeMult)
        mine.velocity.scale(0f)
        mine.fadeOutThenIn(MINE_FADE_IN)
        mine.flightTime = mine.maxFlightTime
        mine.addDamagedAlready(ship)
        mine.setNoMineFFConcerns(true)
    }

    companion object {
        private val log = Global.getLogger(GravityRiftSystemStats::class.java)

        private const val MINELAYER_ID = "astd_grav_rift_minelayer"

        /** 地雷伤害乘区修饰句柄（对齐原版 "mine_sizeMult"）。 */
        private const val MINE_SIZE_MULT_MOD_ID = "mine_sizeMult"

        /** 地雷淡入时长（秒，对齐原版 0.05）。 */
        private const val MINE_FADE_IN = 0.05f

        /** 光束目标锁定半径（su，以旋涡为中心）：原版 RiftBeamEffect 的 100su 贴束寻的放大到系统尺度。 */
        private const val VICTIM_ACQUIRE_RANGE = 600f

        /** 光束最小长度（su）：旋涡几乎压在目标舰上时不画光束（裂隙直接在目标处展开）。 */
        private const val MIN_BEAM_LENGTH = 60f

        /** 光束束体贴图（原版 riftcascade 的 LASER textureType 贴图对：流苏边缘激光束）。 */
        private const val BEAM_CORE_PATH = "graphics/fx/beam_laser_core.png"
        private const val BEAM_FRINGE_PATH = "graphics/fx/beam_laser_fringe.png"

        /** 光束宽度（su）：起端（旋涡侧）略宽、末端（目标舰侧）收束；对齐原版束宽 35 量级。 */
        private const val BEAM_BASE_WIDTH = 34f
        private const val BEAM_TIP_WIDTH = 22f

        /** 光束生命周期（秒）：快速闪光。 */
        private const val BEAM_FADE_IN = 0.05f
        private const val BEAM_HOLD = 0.35f
        private const val BEAM_FADE_OUT = 0.5f

        /** 光束配色：白芯红边（原版 riftcascade 为白芯蓝紫边，本模组统一红色正色）。 */
        private val BEAM_CORE_COLOR = Color(255, 245, 245, 255)
        private val BEAM_FRINGE_COLOR = Color(255, 60, 60, 255)

        /** 触发性 customData 键前缀（每船一条，unapply 清除）；键用 ship.id（战斗内唯一）。 */
        private const val PLAN_KEY = "astd_grav_rift_plan:"
        private const val VORTEX_KEY = "astd_grav_rift_vortex:"

        /** 自动化观测键前缀（战斗级生命周期，不在 unapply 清除）。 */
        const val TELEMETRY_PLANNED_KEY = "astd_grav_rift_telemetry_planned:"
        const val TELEMETRY_SPAWNED_KEY = "astd_grav_rift_telemetry_spawned:"

        private fun planKey(ship: ShipAPI): String = "$PLAN_KEY${ship.id}"
        private fun vortexKey(ship: ShipAPI): String = "$VORTEX_KEY${ship.id}"
        private fun telemetryPlannedKey(ship: ShipAPI): String = "$TELEMETRY_PLANNED_KEY${ship.id}"
        private fun telemetrySpawnedKey(ship: ShipAPI): String = "$TELEMETRY_SPAWNED_KEY${ship.id}"
    }
}
