package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.combat.lens.ui.LensMarkStatusBar
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import java.awt.Color

/**
 * 透镜阵列核心主体（基座 hullmod，spec §3）。
 *
 * 动机：双模式分模式效果中“需要运行时遍历战斗对象”的部分集中于此基座，避免散落在
 * 多个 marker hullmod 内重复遍历。模式静态属性（蜂群思维/宏观锚定的纯 stat 修改）由
 * 各自的 [ASTDLensAutomatedModeHullMod] / [ASTDLensCrewedModeHullMod] 承载，此处只做
 * 三件运行时事：
 * - 标记状态栏维护（每帧，玩家船左侧 UI）。
 * - 无人·幽灵信号：范围内敌方导弹随机失制导（剥离制导 AI，导弹直飞）。
 * - 载人·情报中枢：按友军吨位等级累加全队 ECM。
 *
 * 难度系数 m∈[1.0,2.0]（仅敌对单位）属阶段二接入：此处所有面向敌方的效果保持
 * factor=1（见 [DIFFICULTY_FACTOR]），等待阶段二的难度 provider。
 */
class ASTDLensArrayCoreHullMod : BaseHullMod() {

    companion object {
        /** 幽灵信号作用半径（su）：与 tooltip line.4 的 ~2000su 一致。 */
        private const val GHOST_RANGE = 2000f

        /** 单枚敌方导弹进入范围后的失制导判定概率。 */
        private const val GHOST_DEFUSE_CHANCE = 0.5f

        /** 幽灵信号心跳间隔（s）。 */
        private const val TICK = 0.25f

        /** 情报中枢 ECM 重算间隔（s）：友军编成变化不频繁，1s 足够。 */
        private const val ECM_TICK = 1.0f

        /**
         * 难度系数（阶段一恒为 1）。阶段二接入难度 provider 后，仅对敌对单位
         * 的幽灵信号概率/范围做 m∈[1.0,2.0] 缩放。
         */
        private const val DIFFICULTY_FACTOR = 1.0f

        /** ECM dynamic stat key：与 AffixVectorSilenceHullMod 一致（"opad_ecm_rating"）。 */
        private const val ECM_DYNAMIC_KEY = "opad_ecm_rating"

        /** 情报中枢 ECM 修改器 id（自身 dynamic stat 上的稳定句柄）。 */
        private const val INTEL_HUB_MOD_ID = "astd_lens_intel_hub"

        /** 幽灵信号 IntervalUtil 存储 key（按 shipId 拼接）。 */
        private const val INTERVAL_KEY = "astd_lens_core_interval"
        /** 情报中枢 IntervalUtil 存储 key（按 shipId 拼接）。 */
        private const val ECM_INTERVAL_KEY = "astd_lens_core_ecm_interval"

        /** 已判定过的导弹标记 key（每枚只判定一次，避免逐帧反复判定）。 */
        private const val DEFUSED_KEY = "astd_lens_ghost_defused"

        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(200, 160, 255),
            borderColor = Color(160, 110, 255),
            headerBackground = Color(40, 18, 70, 185),
            sectionBackground = Color(28, 12, 52, 120),
            accentColor = Color(150, 90, 230),
        )
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f || ship.isHulk) return

        // 标记状态栏：仅玩家船每帧维护一次。
        if (ship === engine.playerShip) {
            LensMarkStatusBar.maintain(engine)
        }

        if (!ship.isGravitationalLensShip()) return

        val automated = ship.variant?.hasLensAutomatedMode() == true
        val shipId = System.identityHashCode(ship)

        // 心跳间隔：无人→幽灵信号；载人→ECM 情报中枢。
        if (automated) {
            val key = "$INTERVAL_KEY:$shipId"
            var interval = engine.customData[key] as? IntervalUtil
            if (interval == null) {
                interval = IntervalUtil(TICK, TICK)
                engine.customData[key] = interval
            }
            interval.advance(amount)
            if (interval.intervalElapsed()) ghostSignal(ship, engine)
        } else {
            val key = "$ECM_INTERVAL_KEY:$shipId"
            var interval = engine.customData[key] as? IntervalUtil
            if (interval == null) {
                interval = IntervalUtil(ECM_TICK, ECM_TICK)
                engine.customData[key] = interval
            }
            interval.advance(amount)
            if (interval.intervalElapsed()) intelligenceHub(ship, engine)
        }
    }

    /**
     * 无人·幽灵信号：范围内每枚敌方导弹进入后做一次失制导判定（剥离制导 AI，导弹直飞）。
     * 每枚导弹只判定一次（DEFUSED_KEY 标记），命中概率 GHOST_DEFUSE_CHANCE。
     */
    private fun ghostSignal(ship: ShipAPI, engine: CombatEngineAPI) {
        val range = GHOST_RANGE * DIFFICULTY_FACTOR
        val chance = (GHOST_DEFUSE_CHANCE * DIFFICULTY_FACTOR).coerceIn(0f, 1f)
        for (missile in engine.missiles) {
            if (missile == null) continue
            if (missile.owner == ship.owner) continue
            if (missile.customData.containsKey(DEFUSED_KEY)) continue
            if (Misc.getDistance(ship.location, missile.location) > range) continue
            // 已纳入判定，标记避免下次重复（无论是否命中）。
            missile.setCustomData(DEFUSED_KEY, true)
            if (Math.random().toFloat() < chance) defuse(missile)
        }
    }

    /**
     * 失制导手段：用一个 no-op [MissileAIPlugin] 顶替原制导 AI（信息战语义——幽灵信号
     * 干扰火控电子系统，剥离制导而非熄火坠落）。导弹保留当前速度/推力，但不再被任何
     * AI 转向，因而保持当前航向直飞，符合 tooltip“失去制导”的世界观。
     * try/catch 仅防御单枚导弹此刻被引擎回收导致的 API 不可达，不吞业务错误。
     */
    private fun defuse(missile: MissileAPI) {
        try {
            // no-op：制导被剥离，导弹保持当前航向直飞（不发出任何转向/加速指令）。
            missile.setMissileAI(MissileAIPlugin { /* no-op：制导被剥离 */ })
        } catch (t: Throwable) {
            Global.getLogger(ASTDLensArrayCoreHullMod::class.java)
                .warn("[lens] ghost signal failed to strip missile AI", t)
        }
    }

    /**
     * 载人·情报中枢：统计同阵营存活友军（排除舰载机/残骸）的吨位等级，
     * 经 [LensEcmContribution] 累加为 ECM 分数，写回自身 dynamic stat。
     */
    private fun intelligenceHub(ship: ShipAPI, engine: CombatEngineAPI) {
        var fr = 0
        var de = 0
        var cr = 0
        var ca = 0
        for (other in engine.ships) {
            if (other == null || other.isHulk || other.isFighter) continue
            if (other.owner != ship.owner) continue
            if (other === ship) continue
            when (other.hullSize) {
                ShipAPI.HullSize.FRIGATE -> fr++
                ShipAPI.HullSize.DESTROYER -> de++
                ShipAPI.HullSize.CRUISER -> cr++
                ShipAPI.HullSize.CAPITAL_SHIP -> ca++
                else -> {}
            }
        }
        val ecm = LensEcmContribution.totalEcmFraction(fr, de, cr, ca)
        ship.mutableStats.dynamic.getMod(ECM_DYNAMIC_KEY).modifyFlat(INTEL_HUB_MOD_ID, ecm)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = listOf(
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.summary"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.1"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.2"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.3"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.4"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.5"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_core.line.6"),
            ),
        )
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isGravitationalLensShip()

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor
}
