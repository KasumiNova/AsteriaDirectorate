package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.lens.GravSpaceFoldHullMod.Companion.FOLD_MARK_KEY
import cn.kasuminova.astd.combat.hullmods.lens.GravSpaceFoldHullMod.Companion.isZw101Ship
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.v
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
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

        // customData 首次写入必须调用 setCustomData；getCustomData 返回的空表不持久。
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
        proj.location.set(newPos)
        spawnFoldVfx(engine, oldPos, expanding = false)
        spawnFoldVfx(engine, newPos, expanding = true)
    }

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
        val values = GravSpaceFoldTuning.resolve(DifficultyTuningImpl, ship == null || ship.owner == 0)
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            para("ui.hullmod.grav_space_fold.desc", hl("奇点核心", HullmodThemes.LENS.nameColor))
            heading("ui.hullmod.grav_space_fold.section.fold")
            para(
                "ui.hullmod.grav_space_fold.line.fold_field",
                v("basePct", percent(values.foldChanceBase)), v("capPct", percent(values.foldChanceCap)),
                hl(percent(values.foldChanceBase), HullmodTone.HIGHLIGHT),
                hl(percent(values.foldChanceCap), HullmodTone.HIGHLIGHT),
            )
            para("ui.hullmod.grav_space_fold.line.fold_cooldown", hl("短暂失效", HullmodTone.RED))
            heading("ui.hullmod.grav_space_fold.section.beam")
            para(
                "ui.hullmod.grav_space_fold.line.beam_dr",
                v("beamPct", percent(1f - values.beamDamageTakenMult)),
                hl(percent(1f - values.beamDamageTakenMult), HullmodTone.POSITIVE),
            )
            spacer(6f)
            para("ui.hullmod.export.difficulty_note", hl("难度系数", HullmodTone.DEFAULT))
        }
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isZw101Ship()

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    companion object {
        private val log = Global.getLogger(GravSpaceFoldHullMod::class.java)

        private const val HULL_ID = "astd_zw_101"

        private const val BEAM_DR_MOD_ID = "astd_grav_space_fold_beam_dr"

        private const val FOLD_MARK_KEY = "astd_grav_space_fold_rolled"

        private const val STATUS_KEY = "astd_grav_space_fold_status"

        private const val FOLD_NEBULA_COUNT = 4
        private const val FOLD_NEBULA_OFFSET_MIN = 8f
        private const val FOLD_NEBULA_OFFSET_MAX = 24f
        private const val FOLD_NEBULA_SIZE_MIN = 40f
        private const val FOLD_NEBULA_SIZE_MAX = 80f
        private val FOLD_NEBULA_COLOR = Color(215, 45, 60, 160)

        private val ZERO = Vector2f(0f, 0f)

        private val THEME = HullmodThemes.LENS

        private fun percent(value: Float): String = "${(value * 100f).toInt()}%"

        private fun ShipAPI?.isZw101Ship(): Boolean {
            val s = this ?: return false
            return s.hullSpec?.hullId == HULL_ID || s.hullSpec?.baseHullId == HULL_ID
        }
    }
}
