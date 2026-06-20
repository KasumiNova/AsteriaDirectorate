package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.affix.AffixUtil
import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.combat.lens.marks.LensMarks
import cn.kasuminova.astd.renderer.effect.lens.PermeatingTideFieldEffect
import cn.kasuminova.astd.renderer.shader.runtime.CombatShaderRuntime
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import java.awt.Color

/**
 * 渗透潮汐（Permeating Tide，spec §5 / `purple/10-unique.md` §1 插件③）——引力透镜级内置插件③，
 * 钉死「高级电战」支柱：以本舰为心、随交战时长涨落的渗透式电战压制场。
 *
 * 仅对引力透镜级生效（[isApplicableToShip] / advanceInCombat 入口 [isGravitationalLensShip] guard）。
 * advanceInCombat 每帧驱动三件事（数值/插值判定走纯函数 [PermeatingTideMath]）：
 *
 * 1. **涨潮叠深水标记**：遍历 [CombatEngineAPI.getShips] 中位于场内（dist ≤ 2500su）的敌舰，按
 *    [PermeatingTideMath.markIntervalForDistance]（越近越快、含难度 m=1+k 缩放）算该敌舰的叠标记间隔，
 *    用 **per-target 计时**（[nextMarkTimeByTarget]：target 身份哈希 → 下次允许叠标记的战斗 elapsed）
 *    到时即 [LensMarks.applyDeepWaterMark]（叠 1 层）。每个敌舰间隔不同（按距离），故必须 per-target 计时。
 * 2. **过载退潮**：本舰过载（[ShipAPI.getFluxTracker].isOverloaded，经 [PermeatingTideMath.shouldEbb]）时
 *    调 [LensMarks.clearAllLensMarks] 清空全场误差+深水标记（敌方破局窗口）。用 [overloadCleared] 守卫
 *    保证一次过载只清一次（过载期间每帧只在进入瞬间清，过载结束重置守卫）。
 * 3. **潮汐场视觉**：每帧 keyed upsert [PermeatingTideFieldEffect]（center=本舰，tideLevel 按涨落节奏）。
 *    退潮（过载）时潮位归零退去 → frame.alphaMult=0 → submitFrame 不提交 → 场实例经 staleAfter 自然退休。
 *
 * **潮位涨落驱动（[tideLevel]）**：非过载时潮位以 1/[TIDE_RISE_SECONDS] 速率线性涨向 1（涨潮）；过载时以
 * 1/[TIDE_EBB_SECONDS] 速率快速退向 0（退潮，比涨潮快，表现「水迅速退去」）。tideLevel 只驱动视觉涨落，
 * 不影响叠标记逻辑（叠标记由 per-target 间隔决定，spec 未将叠速与可见潮位耦合）。
 *
 * **难度系数 m**：[AffixUtil.getK]∈[0,1] → m=1+k∈[1,2]（仅敌对赏金舰队有 k；玩家放场 k=0→m=1）。
 * 与 [cn.kasuminova.astd.combat.lens.system.EchoFixationField] 的 difficultyFactorFor 同模式。
 *
 * 状态按 shipId 隔离存于本实例的 Map（引力透镜级为唯一舰，单实例通常只服务一条；仍按 shipId 键控以防
 * 同一 hullmod 实例被多条透镜船共用时串扰）。
 */
class ASTDLensPermeatingTideHullMod : BaseHullMod() {

    /**
     * 单条潮汐场的运行时状态（按 shipId 隔离）。
     *
     * @property nextMarkTimeByTarget 每敌舰下次允许叠深水标记的战斗 elapsed（target 身份哈希 → 秒）。
     *   到 elapsed ≥ 此值即叠 1 层并按当前距离重排下次时间。敌舰离场/消失后由本帧未见清理。
     * @property tideLevel 当前可见潮位 0→1（涨潮升 / 退潮降，驱动潮汐场 shader 的 alpha 与涟漪强度）。
     * @property overloadCleared 本轮过载是否已执行过一次清场（守卫：过载期间每帧不重复清；过载结束重置）。
     * @property elapsed 自该状态创建起的累计战斗秒数（per-target 计时与潮位涨落的单调时间基准）。
     */
    private class TideState {
        val nextMarkTimeByTarget = HashMap<Int, Float>()
        var tideLevel: Float = 0f
        var overloadCleared: Boolean = false
        var elapsed: Float = 0f
    }

    /** shipId → 该透镜船的潮汐场状态。 */
    private val stateByShip = HashMap<Int, TideState>()

    /** 本帧实际见到的、位于场内的敌舰身份集合，用于回收 [TideState.nextMarkTimeByTarget] 中已离场条目。 */
    private val seenTargetsThisFrame = HashSet<Int>()

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f || ship.isHulk) return
        if (!ship.isGravitationalLensShip()) return

        val shipId = System.identityHashCode(ship)
        val state = stateByShip.getOrPut(shipId) { TideState() }
        state.elapsed += amount

        val overloaded = PermeatingTideMath.shouldEbb(ship.fluxTracker?.isOverloaded == true)

        // ---- 过载退潮：进入过载瞬间清一次全场标记（守卫避免过载期间每帧狂清）----
        if (overloaded) {
            if (!state.overloadCleared) {
                LensMarks.clearAllLensMarks(engine)
                state.overloadCleared = true
            }
        } else {
            state.overloadCleared = false
        }

        // ---- 潮位涨落：非过载涨向 1，过载快速退向 0 ----
        state.tideLevel = if (overloaded) {
            (state.tideLevel - amount / TIDE_EBB_SECONDS).coerceAtLeast(0f)
        } else {
            (state.tideLevel + amount / TIDE_RISE_SECONDS).coerceAtMost(1f)
        }

        // ---- 涨潮叠深水标记：仅非过载时涨潮（过载退潮停叠）----
        if (!overloaded) {
            applyRisingTide(engine, ship, state)
        }

        // ---- 潮汐场视觉：每帧按当前潮位 keyed upsert（退潮归零 → 不提交 → staleAfter 退休）----
        submitTideField(engine, ship, shipId, state.tideLevel)
    }

    /**
     * 涨潮叠深水标记：遍历全场敌舰，对场内（[PermeatingTideMath.isInTideField]）敌舰按距离算叠标记间隔，
     * per-target 计时到时即叠 1 层深水标记并重排下次时间。
     *
     * 难度 m=1+[AffixUtil.getK]（仅敌对赏金舰队有 k）。出场敌舰的 interval 为 +∞（不叠），其下次时间被设为
     * +∞ 故永不触发——离场后由 [seenTargetsThisFrame] 回收其条目，重新进场视作首次（立即排程一次叠加）。
     */
    private fun applyRisingTide(engine: CombatEngineAPI, ship: ShipAPI, state: TideState) {
        seenTargetsThisFrame.clear()
        val difficultyFactor = difficultyFactorFor(engine, ship)

        for (target in engine.ships) {
            if (target == null || target.isHulk || target.isFighter) continue
            if (target.owner == ship.owner) continue // 仅压制敌舰（深水标记是电战压制标记）。

            val dist = Misc.getDistance(ship.location, target.location)
            if (!PermeatingTideMath.isInTideField(dist)) continue

            val interval = PermeatingTideMath.markIntervalForDistance(dist, difficultyFactor = difficultyFactor)
            if (interval.isInfinite()) continue // 场内但理论不叠区（>fieldRadius 已被 isInTideField 排除，防御性跳过）。

            val targetKey = System.identityHashCode(target)
            seenTargetsThisFrame += targetKey

            // 首次进场：排程在「一个间隔后」叠第一层（避免进场瞬间即叠，给敌方反应/撤离余地）。
            val nextTime = state.nextMarkTimeByTarget[targetKey] ?: (state.elapsed + interval).also {
                state.nextMarkTimeByTarget[targetKey] = it
            }

            if (state.elapsed >= nextTime) {
                LensMarks.applyDeepWaterMark(engine, ship, target, addStacks = 1)
                // 按当前距离重排下次叠加（敌舰移动时间隔随之变化）。
                state.nextMarkTimeByTarget[targetKey] = state.elapsed + interval
            }
        }

        // 回收已离场（本帧未见于场内）的敌舰计时条目，避免 Map 无限增长。
        state.nextMarkTimeByTarget.keys.retainAll(seenTargetsThisFrame)
    }

    /** 每帧提交潮汐场视觉（keyed upsert，per-ship instanceId "tide-${shipId}"，center=本舰）。 */
    private fun submitTideField(engine: CombatEngineAPI, ship: ShipAPI, shipId: Int, tideLevel: Float) {
        val sink = CombatShaderRuntime.ensure(engine).sink
        PermeatingTideFieldEffect.submitFrame(
            sink = sink,
            instanceId = "tide-$shipId",
            center = ship.location,
            frame = PermeatingTideFieldEffect.frame(tideLevel = tideLevel),
        )
    }

    /**
     * 难度系数 m∈[1,2]：玩家本舰放场 m=1（玩家不受敌方难度缩放）；敌对单位 m=1+[AffixUtil.getK]。
     * 与 EchoFixationField.difficultyFactorFor 同模式。
     */
    private fun difficultyFactorFor(engine: CombatEngineAPI, ship: ShipAPI): Float {
        val playerShip = engine.playerShip
        if (playerShip != null && ship.owner == playerShip.owner) return 1f
        return (1f + AffixUtil.getK(ship)).coerceIn(1f, 2f)
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
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_permeating_tide.summary"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_permeating_tide.line.1"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_permeating_tide.line.2"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_permeating_tide.line.3"),
            ),
        )
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isGravitationalLensShip()

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    companion object {
        /** 涨潮：潮位从 0 涨满 1 的耗时（s）。交战起涨潮渐显，呼应「随交战时长加深的渗透式压制场」。 */
        private const val TIDE_RISE_SECONDS = 4f

        /** 退潮：过载时潮位从 1 退到 0 的耗时（s，比涨潮快——表现「水迅速退去」的破局窗口）。 */
        private const val TIDE_EBB_SECONDS = 1.2f

        /** 紫主题（与 [ASTDLensArrayCoreHullMod] / [ASTDLensParallaxDecksHullMod] 一致，透镜协议视觉统一）。 */
        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(200, 160, 255),
            borderColor = Color(160, 110, 255),
            headerBackground = Color(40, 18, 70, 185),
            sectionBackground = Color(28, 12, 52, 120),
            accentColor = Color(150, 90, 230),
        )
    }
}
