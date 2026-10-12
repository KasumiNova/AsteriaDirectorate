package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.HullmodIncompatibility
import cn.kasuminova.astd.combat.hullmods.affix.AffixShared
import cn.kasuminova.astd.combat.hullmods.base.DepartedShipCleanupWatcher
import cn.kasuminova.astd.combat.hullmods.base.IncompatibleHullmodStripper
import cn.kasuminova.astd.combat.hullmods.base.aliveEnemies
import cn.kasuminova.astd.combat.hullmods.base.hasHullId
import cn.kasuminova.astd.combat.hullmods.base.reconcileTracked
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.characters.PersonAPI
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

/**
 * 先进电子对抗网络（Advanced ECM Network，hullmod id：astd_advanced_ecm_network）——决明级（ZW-001）内置插件。
 *
 * 仅对决明级生效（[isApplicableToShip] / advanceInCombat 入口 [hasHullId] guard）。
 * advanceInCombat 每帧驱动（接入原版 ElectronicWarfareScript 结算链路，数值见
 * [AdvancedEcmNetworkTuning]）：
 *
 * 1. **演算核心**：
 *    - 网络贡献：自身强度 + Σ 存活友军按舰级分档贡献，写入本舰
 *      electronic_warfare_flat（同 id 覆盖，幂等；原版逐舰累加即得舰队总额）。
 *      友军枚举走 [AffixShared.aliveAllies]（存活、非战机/无人机/残骸）。
 *    - 敌方反制：每艘存活敌舰的 electronic_warfare_flat 被 clamp 到按舰级上限
 *      （4/8/12/16%）：先 unmodify 本网络修饰读他源终值，超出上限再写负向 flat 修正。
 * 2. **战术反制**（合并原引力电子干扰力场 astd_grav_ew_field 的全部机制）：
 *    - 最大效果提升：对本侧舰队全部指挥官的 dynamic stat electronic_warfare_max 写
 *      flat 增量（难度缩放基础值 + 每个被本侧占领战术点的额外加计，
 *      [AdvancedEcmNetworkTuning.commanderBoost]）。注意 PersonAPI 的 dynamic stat
 *      跨战斗存活，必须逐帧对账（指挥官离场立即 unmodify）并在 hulk/离场/战斗结束
 *      路径全量收口；跨战斗残留另有 [purgeEcmCommanderBoosts] 安全网。
 *    - 占领加速：对全部存活友军舰船（含本舰）的 dynamic stat
 *      [AdvancedEcmNetworkTuning.CAP_RATE_STAT] 写乘区，逐帧对账收口。
 *    - 附加四项减益：复算原版公式（[AdvancedEcmNetworkTuning.penaltyDealt]）得出本侧
 *      实际造成的 EW 效果，幅度 = 实际效果百分数（[AdvancedEcmNetworkTuning.debuffMagnitude]，
 *      与原版射程减益口径一致），对全部存活敌舰施加最终乘区（modifyMult）：
 *      empDamageTakenMult / shieldDamageTakenMult 提升（EMP 抗性与护盾效率降低）、
 *      systemCooldownBonus 提升（战术系统冷却时间增加）、systemRegenBonus 削减
 *      （战术系统充能时间增加）。受影响的玩家船走
 *      [CombatEngineAPI.maintainStatusForPlayerShip] 在状态栏 HUD 显示。
 *
 * 被修饰目标集合（clamp / 减益 / 指挥官 / 占领加速四路）逐帧对账：失效目标
 * （离场/残骸化/敌我变化/不再超限/EW 效果归零）立即 unmodify，不留 stat 残留。
 *
 * 阵营语义：按 ship.owner 判定本侧——zw_001 在敌方时增益与减益对玩家方对称生效。
 *
 * 互斥：无法安装电子对抗组件（ecm）/电子反对抗组件（eccm）——走
 * [IncompatibleHullmodStripper] 延迟清理（真相来源 [HullmodIncompatibility]，
 * SKILL: hullmod-incompatibility-guidelines）。
 *
 * 失效条件：舰船残骸化（hulk 分支全量收口）；舰船撤离战场（非残骸化）或战斗结束由
 * [DepartedShipCleanupWatcher] 探活收口（含指挥官修饰——防止泄漏到后续战斗/存档）。
 *
 * 状态隔离：HullModEffect 实例按 hullmod 规格全局共享，逐舰状态挂 [ShipAPI.getCustomData]
 * （战斗内随实体生命周期）。customData 首写必须走 [ShipAPI.setCustomData]
 * （字段惰性为 null 时 getCustomData() 返回一次性空表，同 GravEmFieldHullMod 口径）。
 */
class AdvancedEcmNetworkHullMod : BaseHullMod() {

    /** 单舰网络状态：四路被修饰目标集合（clamp/减益/指挥官/占领加速）+ 离场哨兵安装标记。 */
    private class NetworkState {
        val clamped = HashSet<ShipAPI>()
        val debuffed = HashSet<ShipAPI>()
        val boostedCommanders = HashSet<PersonAPI>()
        val captureBoosted = HashSet<ShipAPI>()
        var departedWatcherInstalled = false
    }

    override fun applyEffectsBeforeShipCreation(
        hullSize: ShipAPI.HullSize,
        stats: MutableShipStatsAPI,
        id: String,
    ) {
        val variant = stats.variant ?: return
        if (!variant.isZw001Variant()) return
        // 只检测入队：本回调可能在原版 OP 结算的 hullMods 实时迭代内触发，结构性移除会抛 CME
        IncompatibleHullmodStripper.requestStrip(variant, FORBIDDEN_HULLMOD_IDS, HULLMOD_ID)
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        // ShipFactory 迭代 getAllMods() 快照，此处入队并立即清理是安全的
        IncompatibleHullmodStripper.requestStrip(ship.variant, FORBIDDEN_HULLMOD_IDS, HULLMOD_ID)
        IncompatibleHullmodStripper.drainPending()
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        IncompatibleHullmodStripper.drainPending()
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f) return
        if (!ship.hasHullId(HULL_ID)) return

        val state = ship.customData[STATE_KEY] as? NetworkState

        // 残骸化：四路修饰全量收口、解除本舰自身的 ECM 贡献写入后移除本舰状态。
        // 注：原版 CombatFleetManager.shipDestroyed 会把阵亡舰移出 deployed 集合，残骸的
        // electronic_warfare_flat 本就不再计入 EW 汇总；此处显式收口是「死亡即失效」的
        // 明确语义落点（同时覆盖 owner 变更等原版移除前提之外的边界）
        if (ship.isHulk) {
            ship.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.ECM_FLAT_STAT).unmodifyFlat(HULLMOD_ID)
            if (state != null) {
                cleanup(state, modIdOf(ship))
                ship.removeCustomData(STATE_KEY)
            }
            return
        }

        // 首写走 setCustomData（见类注释 customData 写入契约）
        val activeState = state ?: NetworkState().also { ship.setCustomData(STATE_KEY, it) }
        if (!activeState.departedWatcherInstalled) {
            activeState.departedWatcherInstalled = true
            engine.addPlugin(DepartedShipCleanupWatcher(engine, ship, "先进电子对抗网络") {
                ship.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.ECM_FLAT_STAT).unmodifyFlat(HULLMOD_ID)
                val s = ship.customData[STATE_KEY] as? NetworkState
                if (s != null) {
                    cleanup(s, modIdOf(ship))
                    ship.removeCustomData(STATE_KEY)
                }
            })
        }

        val values = AdvancedEcmNetworkTuning.resolve(DifficultyTuningImpl, ship.owner == 0)
        val modId = modIdOf(ship)

        applyNetworkContribution(ship, values)
        applyEnemyClamp(engine, ship, activeState, modId)
        applyCommanderBoost(engine, ship, values, activeState, modId)
        applyCaptureRateBoost(engine, ship, values, activeState, modId)
        applyEwDebuff(engine, ship, activeState, modId)
    }

    /** 网络贡献：自身 + 存活友军分档，写本舰 electronic_warfare_flat（同 id 覆盖，幂等）。 */
    private fun applyNetworkContribution(ship: ShipAPI, values: AdvancedEcmNetworkTuning.Values) {
        var frigates = 0
        var destroyers = 0
        var cruisers = 0
        var capitals = 0
        for (ally in AffixShared.aliveAllies(ship)) {
            when (ally.hullSize) {
                ShipAPI.HullSize.CAPITAL_SHIP -> capitals++
                ShipAPI.HullSize.CRUISER -> cruisers++
                ShipAPI.HullSize.DESTROYER -> destroyers++
                else -> frigates++
            }
        }
        ship.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.ECM_FLAT_STAT).modifyFlat(
            HULLMOD_ID,
            AdvancedEcmNetworkTuning.totalOwnEcm(values, frigates, destroyers, cruisers, capitals),
        )
    }

    /** 敌方反制：每艘存活敌舰贡献 clamp 到舰级上限，逐帧对账收口。 */
    private fun applyEnemyClamp(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        state: NetworkState,
        modId: String,
    ) {
        val seen = HashSet<ShipAPI>()
        for (target in aliveEnemies(engine, ship)) {
            val mod = target.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.ECM_FLAT_STAT)
            mod.unmodifyFlat(modId)
            val delta = AdvancedEcmNetworkTuning.clampDelta(
                mod.computeEffective(0f),
                AdvancedEcmNetworkTuning.enemyCapFor(target.hullSize),
            )
            if (delta != 0f) {
                mod.modifyFlat(modId, delta)
                seen += target
            }
        }
        reconcileTracked(state.clamped, seen) { unclamp(it, modId) }
    }

    /** 反制收口：解除本网络在目标上的 clamp 修正。 */
    private fun unclamp(target: ShipAPI, modId: String) {
        target.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.ECM_FLAT_STAT).unmodifyFlat(modId)
    }

    /** 最大效果提升：本侧全部指挥官 electronic_warfare_max flat 增量（含占领点加计），逐帧对账收口。 */
    private fun applyCommanderBoost(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        values: AdvancedEcmNetworkTuning.Values,
        state: NetworkState,
        modId: String,
    ) {
        val capturedPoints = engine.objectives.count { it.owner == ship.owner }
        val boost = AdvancedEcmNetworkTuning.commanderBoost(values, capturedPoints)
        val commanders = engine.getFleetManager(ship.owner)?.allFleetCommanders.orEmpty()
        for (commander in commanders) {
            commander.stats.dynamic.getMod(AdvancedEcmNetworkTuning.EW_MAX_STAT)
                .modifyFlat(modId, boost)
        }
        // 对账：不再担任本侧指挥官的既有目标立即 unmodify（指挥官 stat 跨战斗存活，必须收口）
        reconcileTracked(state.boostedCommanders, commanders) {
            it.stats.dynamic.getMod(AdvancedEcmNetworkTuning.EW_MAX_STAT).unmodifyFlat(modId)
        }
    }

    /** 占领加速：全部存活友军舰船（含本舰，非战机/无人机/残骸）写占领速率乘区，逐帧对账收口。 */
    private fun applyCaptureRateBoost(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        values: AdvancedEcmNetworkTuning.Values,
        state: NetworkState,
        modId: String,
    ) {
        val allies = engine.ships.filter {
            it.owner == ship.owner && it.isAlive && !it.isHulk && !it.isFighter && !it.isDrone
        }
        for (ally in allies) {
            ally.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.CAP_RATE_STAT)
                .modifyMult(modId, values.captureRateMult)
        }
        reconcileTracked(state.captureBoosted, allies) {
            it.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.CAP_RATE_STAT).unmodifyMult(modId)
        }
    }

    /**
     * 附加四项减益：复算原版公式得本侧实际 EW 效果（上限按原版 int 截断口径），
     * 幅度 = 实际效果百分数，逐帧对账收口；受影响的玩家船写状态栏 HUD。
     * 前提：原版 ElectronicWarfareScript 只处理 0/1 两侧对抗（player/enemy），本方 owner 非 0 时对方即 0。
     */
    private fun applyEwDebuff(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        state: NetworkState,
        modId: String,
    ) {
        val enemyOwner = if (ship.owner == 0) 1 else 0
        val penalty = AdvancedEcmNetworkTuning.penaltyDealt(
            ownTotal = sideEcmTotal(engine, ship.owner),
            ownMax = sideEcmMax(engine, ship.owner),
            enemyTotal = sideEcmTotal(engine, enemyOwner),
        )
        val magnitude = AdvancedEcmNetworkTuning.debuffMagnitude(penalty)

        val seen = HashSet<ShipAPI>()
        if (magnitude > 0f) {
            for (target in aliveEnemies(engine, ship)) {
                val stats = target.mutableStats
                stats.empDamageTakenMult.modifyMult(modId, 1f + magnitude)
                stats.shieldDamageTakenMult.modifyMult(modId, 1f + magnitude)
                stats.systemCooldownBonus.modifyMult(modId, 1f + magnitude)
                stats.systemRegenBonus.modifyMult(modId, 1f - magnitude)
                seen += target
                if (target === engine.playerShip) {
                    maintainDebuffStatus(engine, magnitude)
                }
            }
        }
        reconcileTracked(state.debuffed, seen) { unmodifyDebuff(it, modId) }
    }

    /** 减益收口：解除本网络在目标上的全部减益修饰（与 [applyEwDebuff] 写入面严格一一对应）。 */
    private fun unmodifyDebuff(target: ShipAPI, modId: String) {
        val stats = target.mutableStats
        stats.empDamageTakenMult.unmodifyMult(modId)
        stats.shieldDamageTakenMult.unmodifyMult(modId)
        stats.systemCooldownBonus.unmodifyMult(modId)
        stats.systemRegenBonus.unmodifyMult(modId)
    }

    /** 阵营 ECM 总额复算（与 ElectronicWarfareScript.getTotalAndMaximum 同口径）：非战机/非站模块部署舰累加 + 传感阵列目标。 */
    private fun sideEcmTotal(engine: CombatEngineAPI, owner: Int): Float {
        val manager = engine.getFleetManager(owner) ?: return 0f
        var total = 0f
        for (member in manager.deployedCopyDFM) {
            if (member.isFighterWing || member.isStationModule) continue
            val memberShip = member.ship ?: continue
            total += memberShip.mutableStats.dynamic.getValue(AdvancedEcmNetworkTuning.ECM_FLAT_STAT, 0f)
        }
        for (objective in engine.objectives) {
            if (objective.owner == owner && objective.type == AdvancedEcmNetworkTuning.SENSOR_ARRAY_TYPE) {
                total += AdvancedEcmNetworkTuning.SENSOR_ARRAY_ECM
            }
        }
        return total
    }

    /** 阵营最大效果复算（与 ElectronicWarfareScript 同口径）：10 + 各指挥官 electronic_warfare_max 的最大值；无指挥官为 0。 */
    private fun sideEcmMax(engine: CombatEngineAPI, owner: Int): Float {
        val manager = engine.getFleetManager(owner) ?: return 0f
        var max = 0f
        for (commander in manager.allFleetCommanders) {
            max = maxOf(
                max,
                AdvancedEcmNetworkTuning.VANILLA_MAX_EFFECT +
                    commander.stats.dynamic.getValue(AdvancedEcmNetworkTuning.EW_MAX_STAT, 0f),
            )
        }
        return max
    }

    /** 全量收口：clamp + 四项减益 + 指挥官增幅 + 占领加速（hulk/离场/战斗结束路径共用）。 */
    private fun cleanup(state: NetworkState, modId: String) {
        for (target in state.clamped) unclamp(target, modId)
        state.clamped.clear()
        for (target in state.debuffed) unmodifyDebuff(target, modId)
        state.debuffed.clear()
        for (commander in state.boostedCommanders) {
            commander.stats.dynamic.getMod(AdvancedEcmNetworkTuning.EW_MAX_STAT).unmodifyFlat(modId)
        }
        state.boostedCommanders.clear()
        for (ally in state.captureBoosted) {
            ally.mutableStats.dynamic.getMod(AdvancedEcmNetworkTuning.CAP_RATE_STAT).unmodifyMult(modId)
        }
        state.captureBoosted.clear()
    }

    /** 被减益的玩家船左侧状态行（每帧调用刷新）：四项减益当前幅度。 */
    private fun maintainDebuffStatus(engine: CombatEngineAPI, magnitude: Float) {
        engine.maintainStatusForPlayerShip(
            STATUS_KEY,
            spec?.spriteName ?: "",
            I18n[I18n.Categories.MOD, "ui.hullmod.advanced_ecm_network.status.title"],
            I18n.t(
                I18n.Categories.MOD, "ui.hullmod.advanced_ecm_network.status.data",
                "pct" to percent(magnitude * 100f),
            ),
            true,
        )
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        // tooltip 展示口径：无舰上下文（装配面板）按我方档位展示（默认砺刃 v2）
        val values = AdvancedEcmNetworkTuning.resolve(DifficultyTuningImpl, ship == null || ship.owner == 0)
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            para("ui.hullmod.advanced_ecm_network.desc", hl("奇点核心", HullmodThemes.LENS.nameColor))
            heading("ui.hullmod.advanced_ecm_network.section.core")
            para(
                "ui.hullmod.advanced_ecm_network.line.self",
                v("selfPct", percent(values.selfEcm)),
                hl(percent(values.selfEcm), HullmodTone.HIGHLIGHT),
            )
            para("ui.hullmod.advanced_ecm_network.line.ally")
            table {
                row(
                    "ui.hullmod.advanced_ecm_network.table.ally.row_0.label",
                    "ui.hullmod.advanced_ecm_network.table.ally.row_0.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", percent(values.allyEcmFrigate)),
                )
                row(
                    "ui.hullmod.advanced_ecm_network.table.ally.row_1.label",
                    "ui.hullmod.advanced_ecm_network.table.ally.row_1.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", percent(values.allyEcmDestroyer)),
                )
                row(
                    "ui.hullmod.advanced_ecm_network.table.ally.row_2.label",
                    "ui.hullmod.advanced_ecm_network.table.ally.row_2.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", percent(values.allyEcmCruiser)),
                )
                row(
                    "ui.hullmod.advanced_ecm_network.table.ally.row_3.label",
                    "ui.hullmod.advanced_ecm_network.table.ally.row_3.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", percent(values.allyEcmCapital)),
                )
            }
            para(
                "ui.hullmod.advanced_ecm_network.line.cap",
                hl("4%/8%/12%/16%", HullmodTone.HIGHLIGHT),
            )
            heading("ui.hullmod.advanced_ecm_network.section.counter")
            para(
                "ui.hullmod.advanced_ecm_network.line.max",
                v("boostPct", percent(values.maxEffectBoost * 100f)),
                v("maxPct", percent(AdvancedEcmNetworkTuning.VANILLA_MAX_EFFECT * (1f + values.maxEffectBoost))),
                hl(percent(values.maxEffectBoost * 100f), HullmodTone.HIGHLIGHT),
                hl(
                    percent(AdvancedEcmNetworkTuning.VANILLA_MAX_EFFECT * (1f + values.maxEffectBoost)),
                    HullmodTone.HIGHLIGHT,
                ),
            )
            para(
                "ui.hullmod.advanced_ecm_network.line.point",
                v("pointPct", percent(values.pointMaxBoost * 100f)),
                hl(percent(values.pointMaxBoost * 100f), HullmodTone.HIGHLIGHT),
            )
            para(
                "ui.hullmod.advanced_ecm_network.line.capture",
                v("capturePct", percent((values.captureRateMult - 1f) * 100f)),
                hl(percent((values.captureRateMult - 1f) * 100f), HullmodTone.HIGHLIGHT),
            )
            para("ui.hullmod.advanced_ecm_network.line.debuff")
            table {
                row(
                    "ui.hullmod.advanced_ecm_network.table.debuff.row_0.label",
                    "ui.hullmod.advanced_ecm_network.table.debuff.row_0.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                )
                row(
                    "ui.hullmod.advanced_ecm_network.table.debuff.row_1.label",
                    "ui.hullmod.advanced_ecm_network.table.debuff.row_1.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                )
                row(
                    "ui.hullmod.advanced_ecm_network.table.debuff.row_2.label",
                    "ui.hullmod.advanced_ecm_network.table.debuff.row_2.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                )
                row(
                    "ui.hullmod.advanced_ecm_network.table.debuff.row_3.label",
                    "ui.hullmod.advanced_ecm_network.table.debuff.row_3.value",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                )
            }
            para("ui.hullmod.advanced_ecm_network.line.final", hl("最终值减益", HullmodTone.HIGHLIGHT))
            heading("ui.hullmod.advanced_ecm_network.section.limits")
            para(
                "ui.hullmod.advanced_ecm_network.line.limit_ecm",
                hl("#", HullmodTone.RED), hl("电子对抗组件", HullmodTone.RED),
            )
            para(
                "ui.hullmod.advanced_ecm_network.line.limit_eccm",
                hl("#", HullmodTone.RED), hl("电子反对抗组件", HullmodTone.RED),
            )
            spacer(6f)
            para("ui.hullmod.export.difficulty_note", hl("难度系数", HullmodTone.DEFAULT))
        }
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean {
        if (ship.variant?.let(::hasForbiddenHullMod) == true) return false
        return ship.hasHullId(HULL_ID)
    }

    override fun getUnapplicableReason(ship: ShipAPI): String? {
        if (ship.variant?.let(::hasForbiddenHullMod) == true) {
            return I18n[I18n.Categories.MOD, "ui.hullmod.advanced_ecm_network.unapplicable.ecm_mods"]
        }
        return null
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    private fun hasForbiddenHullMod(variant: ShipVariantAPI): Boolean =
        FORBIDDEN_HULLMOD_IDS.any { forbiddenId ->
            variant.hasHullMod(forbiddenId) ||
                    variant.permaMods.contains(forbiddenId) ||
                    variant.sMods.contains(forbiddenId) ||
                    variant.sModdedBuiltIns.contains(forbiddenId) ||
                    variant.suppressedMods.contains(forbiddenId)
        }

    companion object {
        /** Hullmod 注册 id（csv/注册接线由装配侧完成；互斥表控制方键）。 */
        const val HULLMOD_ID = AdvancedEcmNetworkTuning.HULLMOD_ID

        /** 禁装船插列表：真相来源 [HullmodIncompatibility]。 */
        private val FORBIDDEN_HULLMOD_IDS: Set<String> =
            HullmodIncompatibility.forbiddenByController(HULLMOD_ID)

        private const val HULL_ID = "astd_zw_001"

        /** 逐舰网络状态挂载键（ShipAPI.customData，战斗内随实体生命周期）。 */
        private const val STATE_KEY = "astd_advanced_ecm_network_state"

        /** 修饰句柄前缀：按源舰 id 区分（多网络源各自收口，互不误清）。 */
        private const val MOD_ID_PREFIX = AdvancedEcmNetworkTuning.MOD_ID_PREFIX

        /** 被减益玩家船的状态行键。 */
        private const val STATUS_KEY = "astd_advanced_ecm_network_status"

        /** 透镜线紫主题预设（与 [GravEmFieldHullMod] 一致，透镜协议视觉统一）。 */
        private val THEME = HullmodThemes.LENS

        /** 百分数单位（10 = 10%）格式化：整值去小数。 */
        private fun percent(value: Float): String =
            if (value == value.toInt().toFloat()) "${value.toInt()}%" else "$value%"

        private fun modIdOf(ship: ShipAPI): String = MOD_ID_PREFIX + ship.id

        private fun ShipVariantAPI?.isZw001Variant(): Boolean {
            val v = this ?: return false
            return v.hullSpec?.hullId == HULL_ID || v.hullSpec?.baseHullId == HULL_ID
        }
    }
}

/**
 * 战斗级安全网：清理双方舰队指挥官 electronic_warfare_max 上前一场战斗残留的网络增幅修饰。
 *
 * 指挥官 PersonAPI 的 dynamic stat 跨战斗/跨存档存活：正常路径（逐帧对账 + hulk 分支 +
 * DepartedShipCleanupWatcher）已覆盖战斗内全部收口，但直接结束战斗/读档等路径可能跳过收口，
 * 残留项会永久生效且每场叠加。本函数在每场战斗初始化时调用一次
 * （ASTDGlobalCombatPlugin.init），按 [AdvancedEcmNetworkTuning.MOD_ID_PREFIX] 与已删除的
 * 旧引力电子干扰力场前缀 [AdvancedEcmNetworkTuning.LEGACY_MOD_ID_PREFIX] 识别并清除残留。
 */
fun purgeEcmCommanderBoosts(engine: CombatEngineAPI) {
    var purged = 0
    for (owner in 0..1) {
        val manager = engine.getFleetManager(owner) ?: continue
        for (commander in manager.allFleetCommanders) {
            val mod = commander.stats.dynamic.getMod(AdvancedEcmNetworkTuning.EW_MAX_STAT)
            for (source in AdvancedEcmNetworkTuning.fieldModSources(mod.flatBonuses.keys.toList())) {
                mod.unmodifyFlat(source)
                purged++
            }
        }
    }
    if (purged > 0) {
        Global.getLogger(AdvancedEcmNetworkHullMod::class.java)
            .info("[ASTD] 先进电子对抗网络：清理跨战斗残留的指挥官增幅修饰 $purged 项")
    }
}
