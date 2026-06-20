package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.combat.lens.marks.LensMarks
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.combat.listeners.DamageListener
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import java.awt.Color

/**
 * 视差甲板（Parallax Decks，spec §4 / `purple/10-unique.md` §1 插件②）——引力透镜级内置插件②，
 * 强化「4 甲板」支柱：机群是「铺误差标记的载具」而非主体输出。
 *
 * 仅对引力透镜级生效（[isApplicableToShip] / advanceInCombat 入口 [isGravitationalLensShip] guard）。
 * advanceInCombat 每帧驱动三效果（数值/边界判定走纯函数 [ParallaxDecksMath]）：
 *
 * 1. **错位部署**：枚举本舰机群，对「刚出库（< [ParallaxDecksMath.LAUNCH_PHASE_WINDOW]）」的 fighter
 *    施加相位隐形（[ShipAPI.setPhased]）+ 时流 +100%（fighter.mutableStats.timeMult），窗口过后严格
 *    配对 unapply。出库时间用自管 [deployTimeByFighter]（首次见到某 fighter 时记录 elapsed 时间戳），
 *    fighter 消失时清理。
 * 2. **链路回响（标记闭环·铺点端）**：每条本舰 fighter 挂一个 [DriftMarkOnHitListener]，fighter 武器命中
 *    敌舰时叠 1 层误差标记；每敌每秒上限（[ParallaxDecksMath.canApplyMarkNow]，冷却单位为敌舰）防刷。
 * 3. **回收重构**：对「返航/整备意图」（[com.fs.starfarer.api.combat.FighterWingAPI.isReturning]）且位于
 *    本舰 [ParallaxDecksMath.RECOVERY_RANGE] 内的 fighter 施加 50% 承伤减免（hull/armorDamageTakenMult），
 *    严格配对 unapply。
 *
 * **fighter API 调研结论（javap 确认存在，非反射）**：
 * - 枚举本舰机群：`ShipAPI.getAllWings()` → `FighterWingAPI.getWingMembers()`（仅本舰所属机群，无需全场过滤）。
 * - 相位隐形：`ShipAPI.setPhased(boolean)` / `isPhased()` 存在，直接使用。
 * - 时流加成：`MutableShipStatsAPI.getTimeMult()`（fighter 亦有 mutableStats），`modifyMult` 修饰。
 * - 返航意图：`FighterWingAPI.isReturning(ShipAPI)` 存在，精确表达「整备/返航意图」，直接使用。
 * - 承伤减免：`getHullDamageTakenMult()` / `getArmorDamageTakenMult()`，`modifyMult(0.5)` 修饰。
 * - 出库时长：`getFullTimeDeployed()` 存在但语义对 fighter 不保证为「本次出库以来」（可能含母舰部署时长），
 *   故改用自管时间戳（首次见到该 fighter 记录战斗 elapsed），判定更可控、不依赖未文档化语义。
 */
class ASTDLensParallaxDecksHullMod : BaseHullMod() {

    /**
     * 自管出库时间戳表：fighter 身份哈希 → 首次见到该 fighter 时的战斗 elapsed（秒）。
     * 用于「刚出库窗口」判定（见类注释 fighter API 调研结论·出库时长）。
     * fighter 消失（不再出现在本舰机群中）时从表中清理，避免无限增长。
     */
    private val deployTimeByFighter = HashMap<Int, Float>()

    /** 本帧实际见到的 fighter 身份集合，用于回收 [deployTimeByFighter] 中已消失的条目。 */
    private val seenThisFrame = HashSet<Int>()

    /**
     * 由本插件设置过相位的 fighter 身份集合（相位所有权标记）。
     *
     * 动机：严格配对——只对「本插件相位过」的 fighter 执行 setPhased(false)，绝不误清其他来源
     * （如机种自带相位）的相位状态。lens 机群虽通常无自带相位，仍按所有权配对以遵守 Fail Fast/不兜底原则。
     */
    private val phasedByThis = HashSet<Int>()

    /**
     * 累计战斗经过秒数（自该 hullmod 实例首次 advance 起）。仅用作出库时间戳的单调时间基准，
     * 与各 fighter 的记录时间相减得「出库以来时长」。
     */
    private var elapsed: Float = 0f

    /**
     * 机群武器命中敌舰时叠误差标记的 on-hit 监听器（每条本舰 fighter 挂一个）。
     *
     * 动机：fighter 的武器多样（不同机种不同武器），无法逐武器在 weapon CSV 注册 onHitEffect；
     * 用 [DamageListener] 挂在 fighter 上，命中任意敌舰即叠标记（标记闭环·铺点端），与具体武器解耦。
     *
     * 每敌每秒上限（防刷）：按目标身份哈希记录上次叠标记的 [elapsed] 时间戳（[lastMarkByTarget]），
     * 经 [ParallaxDecksMath.canApplyMarkNow] 判定冷却。冷却表挂在监听器实例（即 per-fighter），
     * 「冷却单位为敌舰」即对该 fighter 而言每艘敌舰独立计时——符合 spec §4.1「冷却单位为敌舰」。
     *
     * @property fighter 持有该监听器的本舰 fighter（作为 drift 标记的 source）。
     * @property nowProvider 取当前战斗 elapsed（与外层 [elapsed] 同源），冷却判定用。
     */
    private class DriftMarkOnHitListener(
        private val fighter: ShipAPI,
        private val nowProvider: () -> Float,
    ) : DamageListener {

        /** 每敌舰上次叠标记的战斗 elapsed 时间戳（target 身份哈希 → 秒），防刷冷却。 */
        private val lastMarkByTarget = HashMap<Int, Float>()

        override fun reportDamageApplied(
            source: Any?,
            target: CombatEntityAPI?,
            result: ApplyDamageResultAPI?,
        ) {
            val engine = Global.getCombatEngine() ?: return
            val enemy = target as? ShipAPI ?: return
            if (enemy.isHulk || enemy.isFighter) return
            // 仅对敌方目标叠标记（误差标记是增伤标记，不打友军）。
            if (enemy.owner == fighter.owner) return

            val targetKey = System.identityHashCode(enemy)
            val now = nowProvider()
            val last = lastMarkByTarget[targetKey] ?: -1f
            if (!ParallaxDecksMath.canApplyMarkNow(last, now, ParallaxDecksMath.MARK_MIN_INTERVAL)) return

            lastMarkByTarget[targetKey] = now
            LensMarks.applyDriftMark(engine, fighter, enemy, addStacks = 1)
        }
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f || ship.isHulk) return
        if (!ship.isGravitationalLensShip()) return

        elapsed += amount
        seenThisFrame.clear()

        val wings = ship.allWings ?: return
        for (wing in wings) {
            if (wing == null) continue
            val members = wing.wingMembers ?: continue
            for (fighter in members) {
                if (fighter == null || fighter.isHulk) continue
                processFighter(ship, fighter, wing.isReturning(fighter))
            }
        }

        // 回收已消失（本帧未见）的 fighter 出库时间戳与相位所有权标记，避免 Map/Set 无限增长。
        // fighter 消失后其 mutableStats/监听器随实体一并回收，相位/时流/承伤减免自然失效，无需显式 unapply。
        deployTimeByFighter.keys.retainAll(seenThisFrame)
        phasedByThis.retainAll(seenThisFrame)
    }

    /**
     * 单架 fighter 的三效果处理：
     * - 错位部署：刚出库窗口内 → 设相位 + 时流 +100%；出窗 → 严格配对 unapply。
     * - 回收重构：返航意图且本舰 1000su 内 → 50% 承伤减免；否则 unapply。
     *
     * @param isReturning 该 fighter 当前是否为返航/整备意图（[com.fs.starfarer.api.combat.FighterWingAPI.isReturning]）。
     */
    private fun processFighter(carrier: ShipAPI, fighter: ShipAPI, isReturning: Boolean) {
        val key = System.identityHashCode(fighter)
        seenThisFrame += key

        // 首次见到该 fighter：记录出库时间戳 + 挂 on-hit 叠标记监听器。
        val deployTime = deployTimeByFighter.getOrPut(key) {
            if (!fighter.hasListenerOfClass(DriftMarkOnHitListener::class.java)) {
                fighter.addListener(DriftMarkOnHitListener(fighter) { elapsed })
            }
            elapsed
        }

        // ---- 错位部署：相位隐形 + 时流 +100% ----
        val sinceDeployed = elapsed - deployTime
        if (ParallaxDecksMath.isInLaunchPhaseWindow(sinceDeployed, ParallaxDecksMath.LAUNCH_PHASE_WINDOW)) {
            if (!fighter.isPhased) {
                fighter.setPhased(true)
                phasedByThis += key
            }
            fighter.mutableStats.timeMult.modifyMult(LAUNCH_TIME_MOD_ID, ParallaxDecksMath.TIME_MULT_BONUS)
        } else {
            // 窗口外严格配对 unapply：仅回切由本插件相位过的 fighter（相位所有权标记 phasedByThis）。
            if (phasedByThis.remove(key) && fighter.isPhased) fighter.setPhased(false)
            fighter.mutableStats.timeMult.unmodifyMult(LAUNCH_TIME_MOD_ID)
        }

        // ---- 回收重构：返航意图 + 本舰 1000su 内 → 50% 承伤减免 ----
        val dist = Misc.getDistance(carrier.location, fighter.location)
        val inRecovery = isReturning && ParallaxDecksMath.isInRecoveryRange(dist, ParallaxDecksMath.RECOVERY_RANGE)
        if (inRecovery) {
            fighter.mutableStats.hullDamageTakenMult.modifyMult(RECOVERY_MOD_ID, ParallaxDecksMath.RECOVERY_DAMAGE_TAKEN_MULT)
            fighter.mutableStats.armorDamageTakenMult.modifyMult(RECOVERY_MOD_ID, ParallaxDecksMath.RECOVERY_DAMAGE_TAKEN_MULT)
        } else {
            fighter.mutableStats.hullDamageTakenMult.unmodifyMult(RECOVERY_MOD_ID)
            fighter.mutableStats.armorDamageTakenMult.unmodifyMult(RECOVERY_MOD_ID)
        }
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
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_parallax_decks.summary"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_parallax_decks.line.1"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_parallax_decks.line.2"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.lens_parallax_decks.line.3"),
            ),
        )
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isGravitationalLensShip()

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    companion object {
        /** 错位部署时流加成修饰句柄（fighter.mutableStats.timeMult 上的稳定 id）。 */
        private const val LAUNCH_TIME_MOD_ID = "astd_lens_parallax_launch_time"

        /** 回收重构承伤减免修饰句柄（fighter hull/armorDamageTakenMult 上的稳定 id）。 */
        private const val RECOVERY_MOD_ID = "astd_lens_parallax_recovery"

        /** 紫主题（与 [ASTDLensArrayCoreHullMod] 一致，透镜协议视觉统一）。 */
        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(200, 160, 255),
            borderColor = Color(160, 110, 255),
            headerBackground = Color(40, 18, 70, 185),
            sectionBackground = Color(28, 12, 52, 120),
            accentColor = Color(150, 90, 230),
        )
    }
}
