package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.campaign.bounty.StandardCores
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.plugins.OfficerLevelupPlugin
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import java.util.Random
import kotlin.math.roundToInt
import org.apache.log4j.Logger
import org.magiclib.kotlin.getSalvageSeed
import second_in_command.SCData
import second_in_command.specs.SCBaseSkillPlugin

class SkydomeUnboundSkill : SCBaseSkillPlugin() {

    override fun getAffectsString(): String =
        I18n[I18n.Categories.MOD, "ui.sic.skydome_unbound.affects"]

    override fun addTooltip(data: SCData, tooltip: TooltipMakerAPI) {
        val base = DifficultyTuningImpl.valueFor(SicTuning.UNBOUND_LEVEL_BONUS, true).roundToInt()
        val extra = DifficultyTuningImpl.valueFor(SicTuning.UNBOUND_STANDARD_CORE_EXTRA, true).roundToInt()
        val masteryPct = "${DifficultyTuningImpl.valueFor(SicTuning.UNBOUND_MASTERY_PERCENT, true).roundToInt()}%"
        tooltip.hullmodCard(
            SicShared.SKILL_TOOLTIP_WIDTH,
            SicShared.CARD_THEME,
            null,
            topGapFill = 0f,
            contentInset = SicShared.CARD_CONTENT_INSET,
        ) {
            para(
                "ui.sic.skydome_unbound.line.levels",
                Misc.getHighlightColor(),
                8f,
                v("levels", base),
            )
            para("ui.sic.skydome_unbound.sub.levels", Misc.getHighlightColor(), 2f)

            para(
                "ui.sic.skydome_unbound.line.standard",
                Misc.getHighlightColor(),
                8f,
                v("levels", extra),
            )

            if (isSmsEnabled()) {
                para(
                    "ui.sic.skydome_unbound.line.mastery",
                    Misc.getHighlightColor(),
                    8f,
                    v("pct", masteryPct),
                )
            } else {
                custom {
                    SicShared.grayLine(
                        it,
                        I18n.t(I18n.Categories.MOD, "ui.sic.skydome_unbound.line.mastery_missing", "pct" to masteryPct),
                        8f,
                        masteryPct,
                    )
                }
            }
            custom { SicShared.grayLine(it, I18n[I18n.Categories.MOD, "ui.sic.skydome_unbound.flavor"], 10f) }
        }
    }

    override fun advance(data: SCData, amunt: Float) {
        applyLevelBonuses(data)
        applyMastery(data)
    }

    override fun onActivation(data: SCData) {
        applyLevelBonuses(data)
        applyMastery(data)
    }

    override fun onDeactivation(data: SCData) {
        removeMastery(data)
    }

    private fun applyLevelBonuses(data: SCData) {
        val base = DifficultyTuningImpl.valueFor(SicTuning.UNBOUND_LEVEL_BONUS, data.isPlayer)
        val extra = DifficultyTuningImpl.valueFor(SicTuning.UNBOUND_STANDARD_CORE_EXTRA, data.isPlayer)
        val levelupPlugin = Global.getSettings().getPlugin("officerLevelUp") as OfficerLevelupPlugin

        for (member in data.fleet.fleetData.membersListCopy) {
            val core = member.captain ?: continue
            if (core.isDefault) continue
            if (!core.isAICore) continue

            val target = SicTuning.unboundTargetBonus(
                base,
                extra,
                standardCore = StandardCores.byCommodity(core.aiCoreId) != null,
            )
            val granted = (core.memoryWithoutUpdate.get(SicTuning.UNBOUND_GRANTED_MEM_KEY) as? Int) ?: 0
            val delta = SicTuning.unboundPendingDelta(target, granted)
            if (delta <= 0) continue

            val random = Random(data.fleet.getSalvageSeed())
            repeat(delta) {
                core.stats.level += 1
                val skills = levelupPlugin.pickLevelupSkills(core, random)
                if (skills.isNotEmpty()) {
                    core.stats.setSkillLevel(skills.random(), 2f)
                }
            }
            core.memoryWithoutUpdate.set(SicTuning.UNBOUND_GRANTED_MEM_KEY, target)
            log.info(
                "[ASTD] 天幕解限：${data.fleet.name} 的核心舰长 ${core.nameString}" +
                    " 等级 +$delta（累计 $target，当前 L${core.stats.level}）",
            )
        }
    }

    private fun applyMastery(data: SCData) {
        if (data.isNPC) return
        if (!isSmsEnabled()) return
        Global.getSector().playerPerson.stats.dynamic.getMod(SicTuning.SMS_GLOBAL_MASTERY_MOD)
            .modifyPercent(spec.id, DifficultyTuningImpl.valueFor(SicTuning.UNBOUND_MASTERY_PERCENT, true))
    }

    private fun removeMastery(data: SCData) {
        if (data.isNPC) return
        if (!isSmsEnabled()) return
        Global.getSector().playerPerson.stats.dynamic.getMod(SicTuning.SMS_GLOBAL_MASTERY_MOD)
            .unmodify(spec.id)
    }

    companion object {
        private val log: Logger = Global.getLogger(SkydomeUnboundSkill::class.java)

        @Volatile
        private var smsDetected: Boolean? = null

        fun isSmsEnabled(): Boolean {
            smsDetected?.let { return it }
            val enabled = try {
                Global.getSettings().modManager.isModEnabled(SicTuning.SMS_MOD_ID)
            } catch (t: Throwable) {
                log.error("[ASTD] 舰船精通系统（SMS）检测失败", t)
                false
            }
            smsDetected = enabled
            return enabled
        }
    }
}
