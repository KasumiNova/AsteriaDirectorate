package cn.kasuminova.astd.campaign.companions

import com.fs.starfarer.api.campaign.*
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.combat.EngagementResultAPI
import com.fs.starfarer.api.util.Misc
import cn.kasuminova.astd.internal.i18n.I18n

/**
 * 主线结束后的“同伴招募”选择对话。
 */
class CompanionOfferDialogPlugin : InteractionDialogPlugin {

    private lateinit var dialog: InteractionDialogAPI
    private lateinit var text: TextPanelAPI
    private lateinit var options: OptionPanelAPI

    private enum class OptionId {
        TAKE_BOTH,
        TAKE_WATCHER,
        TAKE_ECHO,
        LATER,
        LEAVE,
    }

    override fun init(dialog: InteractionDialogAPI) {
        this.dialog = dialog
        this.text = dialog.textPanel
        this.options = dialog.optionPanel
        dialog.promptText = ""
        dialog.setBackgroundDimAmount(0.55f)

        text.addPara(I18n[I18n.Categories.MOD, "companion.offer.intro.0"])
        text.addPara(I18n[I18n.Categories.MOD, "companion.offer.intro.1"])
        text.addPara(I18n[I18n.Categories.MOD, "companion.offer.intro.2"])
        text.addPara(I18n[I18n.Categories.MOD, "companion.offer.intro.3"])
        text.addPara(I18n[I18n.Categories.MOD, "companion.offer.intro.4"])

        options.addOption(I18n[I18n.Categories.MOD, "companion.offer.opt.both"], OptionId.TAKE_BOTH)
        options.addOption(I18n[I18n.Categories.MOD, "companion.offer.opt.watcher"], OptionId.TAKE_WATCHER)
        options.addOption(I18n[I18n.Categories.MOD, "companion.offer.opt.echo"], OptionId.TAKE_ECHO)
        options.addOption(I18n[I18n.Categories.MOD, "companion.offer.opt.later"], OptionId.LATER)
        options.addOption(I18n[I18n.Categories.MOD, "companion.offer.opt.leave"], OptionId.LEAVE)

        dialog.setOptionOnEscape(null, OptionId.LEAVE)
    }

    override fun optionSelected(optionText: String?, optionData: Any?) {
        if (optionData !is OptionId) return
        val state = CompanionState.getOrCreate()
        when (optionData) {
            OptionId.TAKE_BOTH -> {
                CompanionService.recruitWatcher(state)
                CompanionService.obtainEchoCore(state)
                state.offerPending = false
                text.addPara(I18n[I18n.Categories.MOD, "companion.offer.resp.both"], Misc.getHighlightColor())
                options.clearOptions()
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.continue"], OptionId.LEAVE)
            }
            OptionId.TAKE_WATCHER -> {
                CompanionService.recruitWatcher(state)
                state.offerPending = false
                text.addPara(I18n[I18n.Categories.MOD, "companion.offer.resp.watcher"], Misc.getHighlightColor())
                options.clearOptions()
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.continue"], OptionId.LEAVE)
            }
            OptionId.TAKE_ECHO -> {
                CompanionService.obtainEchoCore(state)
                state.offerPending = false
                text.addPara(I18n[I18n.Categories.MOD, "companion.offer.resp.echo"], Misc.getHighlightColor())
                options.clearOptions()
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.continue"], OptionId.LEAVE)
            }
            OptionId.LATER -> {
                state.offerPending = true
                text.addPara(I18n[I18n.Categories.MOD, "companion.offer.resp.later"])
                options.clearOptions()
                options.addOption(I18n[I18n.Categories.MOD, "companion.offer.opt.leave"], OptionId.LEAVE)
            }
            OptionId.LEAVE -> {
                // 关闭/ESC：默认视为“以后再说”，避免玩家误触后永久错过。
                if (!state.watcherRecruited || !state.echoObtained) {
                    state.offerPending = true
                }
                dialog.dismiss()
            }
        }
        state.offered = true
        CompanionService.ensureIntelAdded(state)
    }

    override fun optionMousedOver(optionText: String?, optionData: Any?) {
        // 无悬停提示需求
    }

    override fun advance(amount: Float) {}
    override fun backFromEngagement(battleResult: EngagementResultAPI?) {}
    override fun getContext(): Any? = null
    override fun getMemoryMap(): MutableMap<String, MemoryAPI> = HashMap()
}
