package cn.kasuminova.astd.combat.effect.arc.qiongjue

import cn.kasuminova.astd.api.buff.getOrCreateBuffByWeapon
import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.impl.render.ConeImpactVfx
import cn.kasuminova.astd.impl.render.ConeImpactVfxSpec
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * “穷距”相位轨道炮的命中路由（规格 05 §2.2）：挂 `.proj` 的 `onHitEffect`。
 *
 * 结算顺序：目标类型过滤（仅舰船；战机/导弹不叠层、不触发异目标折算）
 * → 取/建 Weapon 级叠层 Buff（复合键，同舰双穷距按槽位天然隔离）
 * → 同目标 +1 / 异目标先折算后 +1 / 旧目标失效不折算直接 +1（规格裁定）
 * → 刷新最后命中时间 → 触发玩家可见反馈（异目标浮字「演算转移」、满层边沿浮字「演算完成」、
 * 命中小号锥面冲击特效）。
 *
 * 命中锥面为纯视觉（2026-07-29 审批裁定：弃白闪，改基建 [ConeImpactVfx] 小号版，
 * 约贯星 25% 规模起始：半角 12°、锥长 90su、冷白主色）——穷距没有锥状冲击机制，
 * 仅借用锥面视觉表达相位弹头命中质构，无伤害结算。
 *
 * 难度取值每次命中调用 [QiongjueStackMath.resolve] 一次（不缓存，玩家固定 v2）。
 */
class QiongjuePhaseRailgunOnHitEffect : OnHitEffectPlugin {

    /** 武器无槽位引用异常分支的「一次/实例」日志闸（BuffHostImpl 对空槽抛 IAE，03 验收判例）。 */
    private var warnedMissingSlot = false

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f?,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        if (engine.isPaused) return

        // 1. 目标过滤：仅舰船（护盾或船体命中均计）；战机/导弹不叠层、不触发异目标折算。
        val targetShip = target as? ShipAPI ?: return
        if (targetShip.isFighter || targetShip.isHulk || targetShip.isPhased) return

        // 2. 来源与武器解析：无来源无法定位宿主舰（INFO 放弃）；无武器引用无法定位武器级 Buff（WARN 异常路径）。
        val weapon = projectile.weapon
        val ship = projectile.source ?: weapon?.ship
        if (ship == null) {
            log.info("[ASTD] 穷距命中放弃结算：弹体无来源（spec=${projectile.projectileSpecId}）")
            return
        }
        if (weapon == null) {
            log.warn("[ASTD] 穷距命中放弃结算：弹体无武器引用，无法定位武器级 buff（spec=${projectile.projectileSpecId}, ship=${ship.id}）")
            return
        }
        if (weapon.slot == null) {
            if (!warnedMissingSlot) {
                warnedMissingSlot = true
                log.warn("[ASTD] 穷距武器无槽位引用，无法登记 Weapon 级叠层状态，放弃结算: weapon=${weapon.id}")
            }
            return
        }

        // 某些实体命中回调 point 可能为 null；回退弹体当前位置（对齐既有 OnHit 样板）。
        val hitPoint = point ?: projectile.location ?: return

        // 3. 叠层结算（规格 05 §2.2 第 5 步三分支，统一走 QiongjueStackMath.stacksAfterHit 语义）。
        // 伤害乘区监听器幂等登记（同 spec 武器共享 damage.modifier 底层 stat，逐武器乘区必须走逐命中通道）。
        QiongjueDamageDealtModifier.ensure(ship)
        val buff = ship.getOrCreateBuffByWeapon(QiongjueCalcStacks.BUFF_ID, weapon) {
            QiongjueCalcStacks(ship, weapon, engine)
        } as QiongjueCalcStacks
        val retainPct = QiongjueStackMath.resolve(DifficultyTuningImpl, QiongjuePhaseRailgunDifficulty.SWITCH_RETAIN, ship.owner)
        val oldTargetValid = buff.isTargetAlive()
        val sameTarget = oldTargetValid && buff.target === targetShip
        val stacksBefore = buff.stacks
        val stacksAfter = QiongjueStackMath.stacksAfterHit(stacksBefore, oldTargetValid, sameTarget, retainPct)
        buff.target = targetShip
        buff.addStacks(stacksAfter - stacksBefore)
        buff.lastHitTime = engine.getTotalElapsedTime(false)

        // 4. 玩家可见反馈（机制可视化铁律，同帧触发；浮字仅命中来源为玩家船，避免满屏 AI 浮字）。
        val isPlayerSource = ship === engine.playerShip
        if (oldTargetValid && !sameTarget) {
            if (isPlayerSource) {
                feedback.floatingText(
                    engine, hitPoint,
                    I18n[I18n.Categories.MOD, "ui.qiongjue.float.transfer"],
                    FLOAT_SIZE, FLOAT_COLOR, targetShip, 0f, FLOAT_FLASH_DURATION,
                )
            }
            bumpTelemetry(engine, if (isPlayerSource) TELEMETRY_TRANSFER_PLAYER else TELEMETRY_TRANSFER_OTHER)
        }
        if (stacksBefore < QiongjuePhaseRailgunDifficulty.MAX_STACKS && buff.stacks == QiongjuePhaseRailgunDifficulty.MAX_STACKS) {
            if (isPlayerSource) {
                feedback.floatingText(
                    engine, hitPoint,
                    I18n[I18n.Categories.MOD, "ui.qiongjue.float.full"],
                    FLOAT_SIZE, FLOAT_COLOR, targetShip, 0f, FLOAT_FLASH_DURATION,
                )
            }
            bumpTelemetry(engine, if (isPlayerSource) TELEMETRY_FULL_PLAYER else TELEMETRY_FULL_OTHER)
        }

        // 5. 命中小号锥面冲击特效（纯视觉，无结算；朝向取弹体飞行矢量）。
        ConeImpactVfx.spawn(
            engine,
            ConeImpactVfxSpec(
                origin = Vector2f(hitPoint),
                facingDeg = Misc.getAngleInDegrees(ZERO, projectile.velocity),
                halfAngleDeg = CONE_HALF_ANGLE,
                length = CONE_LENGTH,
                coreColor = CONE_CORE_COLOR,
                fringeColor = CONE_FRINGE_COLOR,
            ),
        )
        bumpTelemetry(engine, TELEMETRY_CONE_VFX)
        bumpTelemetry(engine, if (isPlayerSource) TELEMETRY_HIT_PLAYER else TELEMETRY_HIT_OTHER)
    }

    /** dev 自动化烟测证据计数（对齐 EDA 遥测先例）：engine.customData 整数自增。 */
    private fun bumpTelemetry(engine: CombatEngineAPI, key: String) {
        engine.customData[key] = (engine.customData[key] as? Int ?: 0) + 1
    }

    companion object {
        /** 玩家侧命中结算次数遥测键。 */
        const val TELEMETRY_HIT_PLAYER = "astd_qiongjue_hit_player"

        /** 非玩家侧命中结算次数遥测键（敌版叠层证据）。 */
        const val TELEMETRY_HIT_OTHER = "astd_qiongjue_hit_other"

        /** 玩家侧异目标折算次数遥测键（「演算转移」浮字证据）。 */
        const val TELEMETRY_TRANSFER_PLAYER = "astd_qiongjue_transfer_player"

        /** 非玩家侧异目标折算次数遥测键。 */
        const val TELEMETRY_TRANSFER_OTHER = "astd_qiongjue_transfer_other"

        /** 玩家侧满层边沿次数遥测键（「演算完成」浮字证据）。 */
        const val TELEMETRY_FULL_PLAYER = "astd_qiongjue_full_player"

        /** 非玩家侧满层边沿次数遥测键。 */
        const val TELEMETRY_FULL_OTHER = "astd_qiongjue_full_other"

        /** 命中锥面特效生成次数遥测键。 */
        const val TELEMETRY_CONE_VFX = "astd_qiongjue_cone_vfx"

        /** 遥测计数读取（dev 自动化烟测）。 */
        fun telemetryCount(engine: CombatEngineAPI, key: String): Int = engine.customData[key] as? Int ?: 0

        /** 命中锥面参数（约贯星 25% 规模起始提案，目检微调）。 */
        private const val CONE_HALF_ANGLE = 12f
        private const val CONE_LENGTH = 90f

        /** 冷白主色（美术口径：白色弹体与明亮拖尾）。 */
        private val CONE_CORE_COLOR = Color(235, 242, 252)
        private val CONE_FRINGE_COLOR = Color(160, 200, 255)

        /** 浮字号/色/闪烁时长（白色，克制不抢主视觉）。 */
        private const val FLOAT_SIZE = 16f
        private const val FLOAT_FLASH_DURATION = 0.6f
        private val FLOAT_COLOR = Color(240, 245, 252)

        private val ZERO = Vector2f(0f, 0f)

        /** HUD/浮字反馈通道（机制可视化铁律的统一落点）。 */
        private val feedback: CombatFeedback = CombatFeedbackImpl

        private val log = Global.getLogger(QiongjuePhaseRailgunOnHitEffect::class.java)
    }
}
