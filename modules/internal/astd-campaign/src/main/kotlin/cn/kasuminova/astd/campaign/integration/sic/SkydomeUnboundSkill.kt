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

/**
 * 天幕解限（astd_sc_skydome_unbound）——影响所有自动化舰船与菀星设计局舰船
 * （docs/design/skills/10-sic-skills.md 阶段四；数值声明见 [SicTuning]）。
 *
 * 机制落点：
 * - AI 核心等级提升：advance/onActivation 遍历 data.fleet 成员的 AI 核心舰长
 *   （跳过默认舰长与非 AI 核心），等级加成 = [SicTuning.UNBOUND_LEVEL_BONUS] 难度表取值取整，
 *   菀星制式核心（[StandardCores.byCommodity] 命中）再追加 [SicTuning.UNBOUND_STANDARD_CORE_EXTRA]
 *   取值取整；每级经原版 officerLevelUp 插件 [OfficerLevelupPlugin.pickLevelupSkills] 随机补发
 *   一个 2 级技能（沿用 SIC LimitBreaker 的补发口径，种子改为按成员创建一次
 *   Random(fleet.getSalvageSeed())，避免同种子逐次复用导致补发序列趋同）。
 * - 幂等与 LimitBreaker 的口径差异：已发放总额记录在核心 person memory
 *   [SicTuning.UNBOUND_GRANTED_MEM_KEY]（高水位），额度随难度变化只补差、不回扣，
 *   天赋撤下（onDeactivation）同样不回扣——LimitBreaker 玩家侧在 onDeactivation 回收
 *   等级与技能，本技能刻意不回收（设计：解限是永久性解锁而非在岗增益）。
 * - SMS 精通：advance/onActivation 写玩家 Person stats dynamic mod
 *   [SicTuning.SMS_GLOBAL_MASTERY_MOD]（modifyPercent，advance 逐帧重写以跟随难度设置变更），
 *   onDeactivation 同 sourceId unmodify；mod key 为字面字符串常量（出自 SMS
 *   MasteryEffect.GLOBAL_MASTERY_STRENGTH_MOD），本类不 import 任何 shipmastery 类——
 *   无 SMS 环境本技能也会被 SIC 加载；SMS 未启用（[isSmsEnabled]，缓存检测）或
 *   data.isNPC 时跳过。
 */
class SkydomeUnboundSkill : SCBaseSkillPlugin() {

    override fun getAffectsString(): String =
        I18n[I18n.Categories.MOD, "ui.sic.skydome_unbound.affects"]

    override fun addTooltip(data: SCData, tooltip: TooltipMakerAPI) {
        val base = DifficultyTuningImpl.valueFor(SicTuning.UNBOUND_LEVEL_BONUS, true).roundToInt()
        val extra = DifficultyTuningImpl.valueFor(SicTuning.UNBOUND_STANDARD_CORE_EXTRA, true).roundToInt()
        val masteryPct = "${DifficultyTuningImpl.valueFor(SicTuning.UNBOUND_MASTERY_PERCENT, true).roundToInt()}%"
        tooltip.hullmodCard(SicShared.SKILL_TOOLTIP_WIDTH, SicShared.CARD_THEME, null, topGapFill = 0f) {
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
        // 等级提升不回扣（见类 KDoc）；仅回收 SMS 精通加成
        removeMastery(data)
    }

    /**
     * 等级提升（幂等高水位补差）：目标额度 = 难度表基础值 + 制式核心追加值，
     * 相对核心 memory 中已发放总额只补正差；目标额度下降时不做任何处理。
     */
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

    /** SMS 精通加成写入（modifyPercent 同 sourceId 幂等，逐帧重写以跟随难度设置变更）。 */
    private fun applyMastery(data: SCData) {
        if (data.isNPC) return
        if (!isSmsEnabled()) return
        Global.getSector().playerPerson.stats.dynamic.getMod(SicTuning.SMS_GLOBAL_MASTERY_MOD)
            .modifyPercent(spec.id, DifficultyTuningImpl.valueFor(SicTuning.UNBOUND_MASTERY_PERCENT, true))
    }

    /** 停用回收：与 [applyMastery] 同 sourceId unmodify。 */
    private fun removeMastery(data: SCData) {
        if (data.isNPC) return
        if (!isSmsEnabled()) return
        Global.getSector().playerPerson.stats.dynamic.getMod(SicTuning.SMS_GLOBAL_MASTERY_MOD)
            .unmodify(spec.id)
    }

    companion object {
        private val log: Logger = Global.getLogger(SkydomeUnboundSkill::class.java)

        /** SMS 检测标志位缓存（mod 列表在运行期不变，缓存一次即可；同 SicIntegration.isEnabled 范式）。 */
        @Volatile
        private var smsDetected: Boolean? = null

        /** SMS 是否启用（本方法不引用任何 shipmastery 类）。 */
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
