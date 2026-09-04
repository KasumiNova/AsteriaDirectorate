package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.buff.getOrCreateBuffByWeapon
import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.impl.combat.CombatRandom
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 电驱加速炮的不稳定装药命中结算（规格 03 §2.3）：挂 `.proj` 的 `onHitEffect`。
 *
 * 命中时按三锚点上限 [0, maxPct] 均匀随机追加一次 [DamageType.KINETIC] 伤害
 * （以命中当发实际伤害为基准，随修正自然缩放），同帧触发玩家可见反馈：
 * 白色追加伤害浮字 + 弹着点小型能量闪（中心亮粒 + 径向散射粒，克制不抢 500su 拖尾主视觉）。
 *
 * 结算随机走共享 [CombatRandom] 确定性序列：每武器实例一条（Weapon 级状态
 * [ElectricDriveChargeState] 记 callIndex），同帧 LINKED 双管两发是两个独立事件、各自取值，
 * 同事件不重掷。
 *
 * 浮字 spike 验证结论（2026-07-29，字节码证据）：脚本侧 8 参 `CombatEngineAPI.applyDamage`
 * 末位布尔恒传 false，原生不产生伤害浮字（该布尔驱动的是命中音效/粒子路径；
 * 原版弹体浮字由弹体命中代码另行调用 `addFloatingDamageText` 产生，脚本路径不经过）。
 * 故显式 [CombatFeedback.floatingDamage] 必须保留，不会与原生浮字叠字；烟测截图复核。
 *
 * `applyDamage` 不触发二次 onHit 回环（00 §2 已核实），无连锁爆字。
 */
class ElectricDriveAcceleratorOnHitEffect : OnHitEffectPlugin {

    /** 装药上限为 0 / 无槽位两个异常分支的「一次/实例」日志闸（不静默、不刷屏）。 */
    private var warnedZeroMaxPct = false
    private var warnedMissingSlot = false

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f?,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        // 1. 目标边界：hulk/phased 舰船不结算（对齐 HighFluxShieldPressureOnHitEffect 样板）；
        //    战机/导弹等非 Ship 目标照常结算（设计「射弹击中目标时」不限目标类型）。
        val ship = target as? ShipAPI
        if (ship != null && (ship.isHulk || ship.isPhased)) return
        // 陨石等场景 point 可为 null；回退弹体当前位置（样板同款兜底）。
        val hitPoint = point ?: projectile.location ?: return

        // 2. 来源解析：优先 projectile.source → 退化 weapon.ship；两者皆无 → INFO 日志并放弃结算（不静默）。
        val weapon = projectile.weapon
        val source = projectile.source ?: weapon?.ship
        if (source == null) {
            log.info("[ASTD] EDA 装药放弃结算：弹体无来源（spec=${projectile.projectileSpecId}）")
            return
        }
        if (weapon == null) {
            log.info("[ASTD] EDA 装药放弃结算：弹体无武器引用（spec=${projectile.projectileSpecId}）")
            return
        }
        // weapon.slot 为空属理论边界（弹道装饰武器场景）：Weapon 级 Buff 复合键必须有槽位
        // （BuffHostImpl 对空槽抛 IllegalArgumentException），无法退化登记，WARN 一次并放弃结算。
        if (weapon.slot == null) {
            if (!warnedMissingSlot) {
                warnedMissingSlot = true
                log.warn("[ASTD] EDA 武器无槽位引用，无法登记 Weapon 级装药状态，放弃结算: weapon=${weapon.id}")
            }
            return
        }

        // 3. 难度取值：玩家固定 v2（每次命中取一次，不缓存）。
        val maxPct = ElectricDriveAcceleratorDifficulty.chargeMaxPct(DifficultyTuningImpl, source.owner)
        if (maxPct <= 0f) {
            if (!warnedZeroMaxPct) {
                warnedZeroMaxPct = true
                log.warn("[ASTD] EDA 装药上限为 0（k_s 数据异常？），跳过结算: maxPct=$maxPct, ship=${source.id}")
            }
            return
        }

        // 4. 确定性随机：BuffHost Weapon 级状态取 callIndex；同帧双管两发是两个独立事件，各自取值（00 §4.1）。
        val state = source.getOrCreateBuffByWeapon(ElectricDriveChargeState.BUFF_ID, weapon) {
            ElectricDriveChargeState(
                source,
                weapon,
                ElectricDriveAcceleratorDifficulty.seedOf(source.id, weapon.slot.id),
            )
        } as ElectricDriveChargeState
        val rollPct = CombatRandom.nextFloatIn(state.seed, state.nextCallIndex(), 0f..maxPct)

        // 5. 结算：以命中当发实际伤害为基准；低于阈值不产生事件、不飘浮字（显式判定，非静默吞掉）。
        val extra = ElectricDriveAcceleratorDifficulty.extraDamage(projectile.damageAmount, rollPct)
        if (!ElectricDriveAcceleratorDifficulty.shouldApplyExtra(extra)) return
        engine.applyDamage(target, hitPoint, extra, DamageType.KINETIC, 0f, false, true, source)

        // 6. 玩家可见反馈（机制可视化铁律，同帧至少一个通道）：伤害浮字 + 小型能量闪。
        feedback.floatingDamage(engine, hitPoint, extra, FLOATY_COLOR, target, source)
        spawnChargeFlash(engine, hitPoint)
        recordTelemetry(engine, source, extra)
    }

    /**
     * 弹着点小型能量闪（规格 03 §3.2）：1 枚短寿命中心亮粒 + 5 粒径向散射粒，
     * 表达装药不稳定泄能的瞬态爆点；白色微冷调，克制不抢 500su 拖尾主视觉。
     * 粒子散布为一次性纯视觉随机，直接用 Misc.random / MathUtils 惯例（00 §4.1-2）。
     */
    private fun spawnChargeFlash(engine: CombatEngineAPI, point: Vector2f) {
        engine.addHitParticle(point, ZERO_VEL, CENTER_FLASH_SIZE, 1f, CENTER_FLASH_DURATION, FLASH_COLOR)
        repeat(SCATTER_PARTICLES) {
            val dir = Misc.random.nextFloat() * 360f
            val speed = MathUtils.getRandomNumberInRange(SCATTER_SPEED_MIN, SCATTER_SPEED_MAX)
            val vel = MathUtils.getPointOnCircumference(null, speed, dir)
            engine.addHitParticle(
                point,
                vel,
                MathUtils.getRandomNumberInRange(SCATTER_SIZE_MIN, SCATTER_SIZE_MAX),
                0.9f,
                MathUtils.getRandomNumberInRange(SCATTER_DURATION_MIN, SCATTER_DURATION_MAX),
                FLASH_COLOR,
            )
        }
    }

    /** dev 自动化烟测证据计数（对齐 ChargeNeedleVfx 遥测先例）：按攻击方归属分开记次数与峰值。 */
    private fun recordTelemetry(engine: CombatEngineAPI, source: ShipAPI, extra: Float) {
        val playerCaused = source.owner == 0
        val countKey = if (playerCaused) TELEMETRY_EXTRA_COUNT_PLAYER else TELEMETRY_EXTRA_COUNT_OTHER
        val maxKey = if (playerCaused) TELEMETRY_EXTRA_MAX_PLAYER else TELEMETRY_EXTRA_MAX_OTHER
        engine.customData[countKey] = (engine.customData[countKey] as? Int ?: 0) + 1
        val max = engine.customData[maxKey] as? Float ?: 0f
        if (extra > max) engine.customData[maxKey] = extra
    }

    companion object {
        /** 玩家侧追加伤害结算次数遥测键（engine.customData）。 */
        const val TELEMETRY_EXTRA_COUNT_PLAYER = "astd_eda_extra_damage_count_player"

        /** 玩家侧追加伤害峰值遥测键（玩家档应 ≤ 80 × 56.25% = 45）。 */
        const val TELEMETRY_EXTRA_MAX_PLAYER = "astd_eda_extra_damage_max_player"

        /** 非玩家侧追加伤害结算次数遥测键（敌版三档证据）。 */
        const val TELEMETRY_EXTRA_COUNT_OTHER = "astd_eda_extra_damage_count_other"

        /** 非玩家侧追加伤害峰值遥测键（k_s=5 时上限 80 × 150% = 120）。 */
        const val TELEMETRY_EXTRA_MAX_OTHER = "astd_eda_extra_damage_max_other"

        /** 本场战斗玩家侧累计追加伤害结算次数（dev 自动化烟测读取）。 */
        fun extraDamageCountPlayer(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_EXTRA_COUNT_PLAYER] as? Int ?: 0

        /** 本场战斗非玩家侧累计追加伤害结算次数（dev 自动化烟测读取）。 */
        fun extraDamageCountOther(engine: CombatEngineAPI): Int = engine.customData[TELEMETRY_EXTRA_COUNT_OTHER] as? Int ?: 0

        /** 浮字色（白，与弹体调色板同族）。 */
        private val FLOATY_COLOR = Color(235, 242, 250)

        /** 命中闪光色（白色微冷调）。 */
        private val FLASH_COLOR = Color(240, 245, 252)

        private val ZERO_VEL = Vector2f(0f, 0f)
        private const val CENTER_FLASH_SIZE = 30f
        private const val CENTER_FLASH_DURATION = 0.15f
        private const val SCATTER_PARTICLES = 5
        private const val SCATTER_SPEED_MIN = 60f
        private const val SCATTER_SPEED_MAX = 120f
        private const val SCATTER_SIZE_MIN = 14f
        private const val SCATTER_SIZE_MAX = 22f
        private const val SCATTER_DURATION_MIN = 0.25f
        private const val SCATTER_DURATION_MAX = 0.4f

        /** HUD/浮字反馈通道（机制可视化铁律的统一落点）。 */
        private val feedback: CombatFeedback = CombatFeedbackImpl

        private val log = Global.getLogger(ElectricDriveAcceleratorOnHitEffect::class.java)
    }
}
