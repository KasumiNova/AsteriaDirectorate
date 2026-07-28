package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.combat.effect.generic.CombatVfxBootstrap
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import kotlin.math.roundToInt

/**
 * 电驱加速炮的净空加速射程机制（规格 03 §2.3）：挂 `.wpn` 的 `everyFrameEffect`。
 *
 * 每帧：代行 VFX bootstrap（`.wpn` 只有一个 everyFrame 槽，本武器独占，
 * 必须自行调用 [CombatVfxBootstrap.ensureInstalled]，否则弹体 VFX 管线不启动）
 * → 读 `ship.fluxLevel`（软+硬合计）折算射程加成并 `modifyFlat`/`unmodify`
 * → 玩家船在调试/烟测模式下维护 HUD 状态栏条目。
 *
 * 玩家可见反馈（机制可视化铁律）：射程加成直接改变原版射程提示圈半径（被动可观测）；
 * HUD 状态条目「电驱加速炮 / 射程加成：+N su」仅 devMode + 玩家船显示（2026-07-29 审批裁定：
 * 正常玩家不显示该条目），归零即消失（不再 maintain）。
 *
 * 已知副作用（规格 03 §2.3 登记，90 计划 §3.6 风险 7，收口清单 C1：十组全部收尾后单独解决，
 * 不在本分支内处置）：
 * - `ballisticWeaponRangeBonus` 是舰体乘区，加成作用于**全舰实弹武器**，不止本武器；
 * - `EveryFrameWeaponEffectPlugin` 无 dispose 钩子，战斗内卸载武器后 modifier 残留至本场结束。
 * 无 `WeaponAPI.setRange`（javap 全表核实）；`WeaponSpecAPI.setMaxRange` 改的是加载期共享 spec
 * （全场同型武器一起变），禁用。
 */
class ElectricDriveAcceleratorWeaponEffect : EveryFrameWeaponEffectPlugin {

    /** NaN 辐能 / 空槽位两个异常分支的「一次/实例」日志闸（不静默、不刷屏）。 */
    private var warnedNaNFlux = false
    private var warnedMissingSlot = false

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        // §0-3：本武器独占 everyFrame 槽，必须代行 bootstrap（漏掉则弹体 VFX 全灭）。
        CombatVfxBootstrap.ensureInstalled(engine)

        val ship = weapon.ship ?: return
        // hulk 后 stats 不再结算，modifier 随实体终结。
        if (ship.isHulk) return

        // 软+硬合计（getFluxLevel 语义，jar 已核实）。
        val levelRaw = ship.fluxLevel
        if (levelRaw.isNaN() && !warnedNaNFlux) {
            warnedNaNFlux = true
            log.warn("[ASTD] EDA 读到 NaN 辐能水平，按 0 加成处理: ship=${ship.id}, hull=${ship.hullSpec?.hullId}")
        }
        val bonus = ElectricDriveAcceleratorDifficulty.rangeBonus(DifficultyTuningImpl, ship.owner, levelRaw)

        // modifierId 带 slotId：同舰多件电驱互不覆盖。
        val modId = RANGE_MOD_PREFIX + modIdSuffix(weapon)
        if (bonus > 0f) {
            ship.mutableStats.ballisticWeaponRangeBonus.modifyFlat(modId, bonus)
        } else {
            // 不挂 0 值 flat 污染 stat 明细。
            ship.mutableStats.ballisticWeaponRangeBonus.unmodify(modId)
        }

        // HUD：仅调试/烟测模式 + 玩家船可见（2026-07-29 审批裁定：正常玩家不显示该条目；
        // maintainStatusForPlayerShip 本就只渲染玩家船，此处显式守门省无效调用）。
        if (bonus > 0f && ship === engine.playerShip && Global.getSettings().isDevMode()) {
            feedback.maintainPlayerStatus(
                engine,
                HUD_KEY,
                HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.eda.range_status.title"],
                I18n.t(I18n.Categories.MOD, "ui.eda.range_status.desc", "bonus" to bonus.roundToInt()),
                negative = false,
            )
        }
    }

    /**
     * modifierId 后缀：正常取 slotId；`weapon.slot` 为空属理论边界（弹道装饰武器场景，
     * 本武器为中槽实弹不会走到），退化用武器实例 id 且 WARN 一次（不静默）。
     */
    private fun modIdSuffix(weapon: WeaponAPI): String {
        val slotId = weapon.slot?.id
        if (slotId != null) return slotId
        if (!warnedMissingSlot) {
            warnedMissingSlot = true
            log.warn("[ASTD] EDA 武器无槽位引用，射程 modifierId 退化用武器实例 id: weapon=${weapon.id}")
        }
        return weapon.id ?: "unknown"
    }

    companion object {
        /** 射程加成 modifierId 前缀（+ slotId 构成全键）。 */
        private const val RANGE_MOD_PREFIX = "astd_eda_range_bonus:"

        /** HUD 状态条目键。 */
        private const val HUD_KEY = "astd_eda_range_status"

        /** HUD 图标（ARC 回路接口船插图，复用现成美术；美术确认后替换）。 */
        private const val HUD_ICON = "graphics/hullmods/astd_arc_loop_interface.png"

        /** HUD/浮字反馈通道（机制可视化铁律的统一落点）。 */
        private val feedback: CombatFeedback = CombatFeedbackImpl

        private val log = Global.getLogger(ElectricDriveAcceleratorWeaponEffect::class.java)
    }
}
