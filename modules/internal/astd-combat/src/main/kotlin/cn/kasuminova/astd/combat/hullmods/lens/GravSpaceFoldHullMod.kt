package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.combat.hullmods.lens.GravSpaceFoldHullMod.Companion.isZw101Ship
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.ui.dsl.buildWith
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import org.boxutil.units.standard.entity.DistortionEntity
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 引力空间折跃器（Gravity Space Folder，hullmod id：astd_grav_space_fold）——舜华级（ZW-101）内置插件。
 *
 * 仅对舜华级生效（[isApplicableToShip] / advanceInCombat 入口 [isZw101Ship] guard）。
 * advanceInCombat 每帧驱动两效果：
 *
 * 1. **空间折跃**：舰船碰撞圈 + [GravSpaceFoldTuning.FOLD_RANGE_BONUS]su 范围内的所有敌对
 *    射弹与导弹（owner 与本舰不同；engine.projectiles 与 engine.missiles 两表并查，
 *    弹体判定标记天然去重），每个弹体进入范围时一次性判定
 *    （三态标记写入弹体 customData [FOLD_MARK_KEY]：未判定 → 判定不折跃 / 已折跃，
 *    两个终态均不再触发，成功折跃只发生一次）：
 *    命中则镜像折跃至舰船中心对称点（[GravSpaceFoldTuning.mirroredPosition]），
 *    保持原速度向量与朝向，弹体由此飞离舰船。
 *    概率 = 基础值 + 按弹体伤害线性加计，触及上限封顶（[GravSpaceFoldTuning.foldChance]）。
 *    每次成功折跃：起始位置一次收拢扭曲（60→20su）+ 4 个红色星云；
 *    目标位置一次扩散扭曲（20→60su）+ 4 个红色星云（[spawnFoldVfx]）。
 * 2. **光束散射**：舰船光束承伤乘区（beamDamageTakenMult，[GravSpaceFoldTuning.BEAM_DAMAGE_TAKEN_REDUCTION]）。
 *    减免随失效条件动态开关，故每帧 unmodify → 条件满足再 modify（不走
 *    applyEffectsAfterShipCreation 的一次性口径）。
 *
 * 失效条件（两效果共用）：舰船处于相位状态（[ShipAPI.isPhased]），或舰船系统处于冷却
 * （[ShipSystemAPI.SystemState.COOLDOWN]；IN/ACTIVE/OUT 激活过程不失效）。
 *
 * 玩家状态行：BaseHullMod 无 getStatusData 接口，状态行走官方
 * [CombatEngineAPI.maintainStatusForPlayerShip]（每帧调用刷新，仅玩家船渲染）。
 *
 * 状态隔离：HullModEffect 实例按 hullmod 规格全局共享，本插件无逐舰字段状态
 * （折跃判定标记挂在弹体实体 customData 上，随实体回收）。
 */
class GravSpaceFoldHullMod : BaseHullMod() {

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f) return
        if (!ship.isZw101Ship()) return

        val stats = ship.mutableStats
        if (ship.isHulk) {
            stats.beamDamageTakenMult.unmodifyMult(BEAM_DR_MOD_ID)
            return
        }

        val systemState = ship.system?.state
        val active = !ship.isPhased && systemState != ShipSystemAPI.SystemState.COOLDOWN
        val values = GravSpaceFoldTuning.resolve(DifficultyTuningImpl, ship.owner == 0)

        stats.beamDamageTakenMult.unmodifyMult(BEAM_DR_MOD_ID)
        if (!active) return

        stats.beamDamageTakenMult.modifyMult(BEAM_DR_MOD_ID, values.beamDamageTakenMult)
        if (ship === engine.playerShip) {
            maintainStatusBar(engine, ship, values)
        }
        foldProjectiles(engine, ship, values)
    }

    /** 敌对弹体折跃判定与执行（两表并查：射弹表 + 导弹表；三态判定标记去重防双表重入）。 */
    private fun foldProjectiles(engine: CombatEngineAPI, ship: ShipAPI, values: GravSpaceFoldTuning.Values) {
        val foldRange = ship.collisionRadius + GravSpaceFoldTuning.FOLD_RANGE_BONUS
        for (proj in engine.projectiles) {
            tryFold(engine, ship, proj, foldRange, values)
        }
        for (missile in engine.missiles) {
            tryFold(engine, ship, missile, foldRange, values)
        }
    }

    private fun tryFold(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        proj: DamagingProjectileAPI,
        foldRange: Float,
        values: GravSpaceFoldTuning.Values,
    ) {
        if (proj.owner == ship.owner) return
        if (proj.isFading || proj.isExpired) return
        if (Misc.getDistance(ship.location, proj.location) > foldRange) return

        // 每弹体只判定一次：三态标记（未判定 / 判定不折跃 / 已折跃），两个终态入口直接跳过——
        // 折跃成功的弹体镜像后可能仍在范围内，不得再次触发镜像与特效。
        // 首写必须走 setCustomData（实体级 customData 惰性为 null 时 getCustomData()
        // 返回一次性空表，直接 put 写入虚空）。
        val mark = proj.customData[FOLD_MARK_KEY] as? String
        val roll = if (mark == null) MathUtils.getRandomNumberInRange(0f, 1f) else 0f
        val (newMark, doFold) = GravSpaceFoldTuning.resolveFold(
            mark, roll,
            GravSpaceFoldTuning.foldChance(values.foldChanceBase, values.foldChanceCap, proj.damageAmount),
        )
        if (mark == null) proj.setCustomData(FOLD_MARK_KEY, newMark)
        if (!doFold) return

        val oldPos = Vector2f(proj.location)
        val newPos = GravSpaceFoldTuning.mirroredPosition(ship.location, proj.location)
        // 保持原速度向量与朝向：只改写位置，弹体由此飞离舰船
        proj.location.set(newPos)
        spawnFoldVfx(engine, oldPos, expanding = false)
        spawnFoldVfx(engine, newPos, expanding = true)
    }

    /**
     * 折跃一次性视觉：扭曲（时序 0.25s/0.5s/0.25s）+ 4 个红色星云（40~80su）。
     * 起始位置收拢（60→20su），目标位置扩散（20→60su）。
     */
    private fun spawnFoldVfx(engine: CombatEngineAPI, point: Vector2f, expanding: Boolean) {
        BoxUtilCombatVfx.ensureReady(engine)

        val distortion = DistortionEntity()
        distortion.setGlobalTimer(0.25f, 0.5f, 0.25f)
        distortion.setInnerIn(0.35f, 0.35f)
        distortion.setInnerFull(0.35f, 0.35f)
        distortion.setInnerOut(0.35f, 0.35f)
        distortion.innerHardness = 0.90f
        distortion.ringHardness = 0.70f
        if (expanding) {
            distortion.setSizeIn(20f, 20f)
            distortion.setSizeFull(40f, 40f)
            distortion.setSizeOut(60f, 60f)
            distortion.powerIn = 0.75f
            distortion.powerFull = 0.55f
            distortion.powerOut = 0f
        } else {
            distortion.setSizeIn(60f, 60f)
            distortion.setSizeFull(40f, 40f)
            distortion.setSizeOut(20f, 20f)
            distortion.powerIn = 0.35f
            distortion.powerFull = 0.55f
            distortion.powerOut = 0.75f
        }
        distortion.setLocation(point)
        val addState = BoxUtilCombatVfx.addEntity(engine, distortion)
        if (addState != 0) {
            log.warn("[ASTD] 引力空间折跃器折跃扭曲注册失败（addEntity 返回 $addState，expanding=$expanding），本次扭曲视觉缺席，星云照常")
            distortion.delete()
        }

        repeat(FOLD_NEBULA_COUNT) {
            val dir = Misc.getUnitVectorAtDegreeAngle(MathUtils.getRandomNumberInRange(0f, 360f))
            dir.scale(MathUtils.getRandomNumberInRange(FOLD_NEBULA_OFFSET_MIN, FOLD_NEBULA_OFFSET_MAX))
            val pos = Vector2f(point.x + dir.x, point.y + dir.y)
            BoxUtilCombatVfx.addNebulaParticle(
                engine, pos, ZERO,
                MathUtils.getRandomNumberInRange(FOLD_NEBULA_SIZE_MIN, FOLD_NEBULA_SIZE_MAX),
                1.4f, 0.1f, 0.3f, 0.8f, FOLD_NEBULA_COLOR,
            )
        }
    }

    /** 玩家船左侧状态行（每帧调用刷新）：台词短句 + 当前难度档位的折跃概率区间与光束减免。 */
    private fun maintainStatusBar(engine: CombatEngineAPI, ship: ShipAPI, values: GravSpaceFoldTuning.Values) {
        engine.maintainStatusForPlayerShip(
            STATUS_KEY,
            spec?.spriteName ?: "",
            I18n[I18n.Categories.MOD, "ui.hullmod.grav_space_fold.status.title"],
            I18n.t(
                I18n.Categories.MOD, "ui.hullmod.grav_space_fold.status.data",
                "basePct" to percent(values.foldChanceBase),
                "capPct" to percent(values.foldChanceCap),
                "beamPct" to percent(1f - values.beamDamageTakenMult),
            ),
            false,
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
        val values = GravSpaceFoldTuning.resolve(DifficultyTuningImpl, ship == null || ship.owner == 0)
        tooltip.buildWith {
            spacer(6f)
            withLatticePulseBackground(accentColor = THEME.accentColor, width = width) {
                heading(spec?.displayName ?: "", THEME.nameColor, THEME.headerBackground, 6f)
                spacer(2f)
                para(I18n.Categories.MOD, "ui.hullmod.grav_space_fold.summary", Misc.getTextColor(), 4f)
                para(
                    I18n.Categories.MOD, "ui.hullmod.grav_space_fold.line.fold", LINE_COLOR, 2f,
                    "range" to GravSpaceFoldTuning.FOLD_RANGE_BONUS.toInt(),
                    "basePct" to percent(values.foldChanceBase),
                    "capPct" to percent(values.foldChanceCap),
                )
                para(
                    I18n.Categories.MOD, "ui.hullmod.grav_space_fold.line.beam", LINE_COLOR, 2f,
                    "beamPct" to percent(1f - values.beamDamageTakenMult),
                )
                para(I18n.Categories.MOD, "ui.hullmod.grav_space_fold.line.fail", LINE_COLOR, 2f)
            }
        }
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isZw101Ship()

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    companion object {
        private val log = Global.getLogger(GravSpaceFoldHullMod::class.java)

        private const val HULL_ID = "astd_zw_101"

        /** 光束承伤减免修饰句柄。 */
        private const val BEAM_DR_MOD_ID = "astd_grav_space_fold_beam_dr"

        /** 弹体折跃判定标记键（弹体实体 customData，值为三态字符串，随实体回收）。 */
        private const val FOLD_MARK_KEY = "astd_grav_space_fold_rolled"

        /** 玩家船状态行键。 */
        private const val STATUS_KEY = "astd_grav_space_fold_status"

        // 折跃星云爆发口径
        private const val FOLD_NEBULA_COUNT = 4
        private const val FOLD_NEBULA_OFFSET_MIN = 8f
        private const val FOLD_NEBULA_OFFSET_MAX = 24f
        private const val FOLD_NEBULA_SIZE_MIN = 40f
        private const val FOLD_NEBULA_SIZE_MAX = 80f
        private val FOLD_NEBULA_COLOR = Color(215, 45, 60, 160)

        private val LINE_COLOR = Color(200, 200, 210)

        private val ZERO = Vector2f(0f, 0f)

        /** 紫主题（与 [ASTDGravPhaseDeckHullMod] 一致，透镜协议视觉统一）。 */
        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(200, 160, 255),
            borderColor = Color(160, 110, 255),
            headerBackground = Color(40, 18, 70, 185),
            sectionBackground = Color(28, 12, 52, 120),
            accentColor = Color(150, 90, 230),
        )

        private fun percent(value: Float): String = "${(value * 100f).toInt()}%"

        private fun ShipAPI?.isZw101Ship(): Boolean {
            val s = this ?: return false
            return s.hullSpec?.hullId == HULL_ID || s.hullSpec?.baseHullId == HULL_ID
        }
    }
}
