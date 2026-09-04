package cn.kasuminova.astd.campaign.companions

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.InteractionDialogPlugin
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin
import com.fs.starfarer.api.ui.SectorMapAPI
import com.fs.starfarer.api.ui.IntelUIAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import java.awt.Color
import cn.kasuminova.astd.internal.i18n.I18n

/**
 * Intel 入口：用于反复触发闲聊与彩蛋。
 */
class CompanionsIntel : BaseIntelPlugin() {

    companion object {
        private const val BTN_CHAT_WATCHER = "chat_watcher"
        private const val BTN_CHAT_ECHO = "chat_echo"
        private const val BTN_SHIP_REVIEW = "ship_review"
        private const val BTN_CROSSOVER = "crossover"
    }

    override fun getName(): String = I18n[I18n.Categories.MOD, "intel.companions.name"]

    override fun createIntelInfo(info: TooltipMakerAPI, mode: IntelInfoPlugin.ListInfoMode) {
        info.addPara(getName(), 0f, Misc.getHighlightColor(), getName())
    }

    override fun createSmallDescription(info: TooltipMakerAPI, width: Float, height: Float) {
        val state = CompanionState.getOrCreate()
        val h = Misc.getHighlightColor()
        val g = Misc.getGrayColor()

        info.addPara(I18n[I18n.Categories.MOD, "intel.companions.desc"], 0f)
        info.addPara("\n" + I18n[I18n.Categories.MOD, "intel.companions.status"], 10f)
        info.addPara(
            I18n.t(I18n.Categories.MOD, "intel.companions.label.watcher",
                "state" to (if (state.watcherRecruited) I18n[I18n.Categories.MOD, "intel.companions.status.watcher.joined"] else I18n[I18n.Categories.MOD, "intel.companions.status.watcher.not"])
            ),
            0f,
            h,
            if (state.watcherRecruited) I18n[I18n.Categories.MOD, "intel.companions.status.watcher.joined"] else I18n[I18n.Categories.MOD, "intel.companions.status.watcher.not"]
        )
        info.addPara(
            I18n.t(I18n.Categories.MOD, "intel.companions.label.echo",
                "state" to (if (state.echoObtained) I18n[I18n.Categories.MOD, "intel.companions.status.echo.obtained"] else I18n[I18n.Categories.MOD, "intel.companions.status.echo.not"])
            ),
            0f,
            h,
            if (state.echoObtained) I18n[I18n.Categories.MOD, "intel.companions.status.echo.obtained"] else I18n[I18n.Categories.MOD, "intel.companions.status.echo.not"]
        )

        info.addPara("\n" + I18n[I18n.Categories.MOD, "intel.companions.tip"], 10f, g, *emptyArray())

        val base = Misc.getBasePlayerColor()
        val dark = Misc.getDarkPlayerColor()
        val opad = 10f

        info.addButton(I18n[I18n.Categories.MOD, "intel.companions.btn.chat_watcher"], BTN_CHAT_WATCHER, base, dark, width, 24f, opad)
        info.addButton(I18n[I18n.Categories.MOD, "intel.companions.btn.chat_echo"], BTN_CHAT_ECHO, base, dark, width, 24f, 6f)
        info.addButton(I18n[I18n.Categories.MOD, "intel.companions.btn.ship_review"], BTN_SHIP_REVIEW, base, dark, width, 24f, 6f)
        info.addButton(I18n[I18n.Categories.MOD, "intel.companions.btn.crossover"], BTN_CROSSOVER, base, dark, width, 24f, 6f)
    }

    override fun buttonPressConfirmed(buttonId: Any?, ui: IntelUIAPI) {
        when (buttonId) {
            BTN_CHAT_WATCHER -> openDialog(CompanionChatDialogPlugin(CompanionChatDialogPlugin.Mode.WATCHER))
            BTN_CHAT_ECHO -> openDialog(CompanionChatDialogPlugin(CompanionChatDialogPlugin.Mode.ECHO))
            BTN_SHIP_REVIEW -> openDialog(CompanionChatDialogPlugin(CompanionChatDialogPlugin.Mode.SHIP_REVIEW))
            BTN_CROSSOVER -> openDialog(CompanionChatDialogPlugin(CompanionChatDialogPlugin.Mode.CROSSOVER))
            else -> super.buttonPressConfirmed(buttonId, ui)
        }
        ui.updateUIForItem(this)
    }

    private fun openDialog(plugin: InteractionDialogPlugin) {
        val sector = Global.getSector() ?: return
        val ui = sector.campaignUI ?: return
        val target = sector.playerFleet ?: return
        if (ui.isShowingDialog || ui.isShowingMenu) return
        ui.showInteractionDialog(plugin, target)
    }

    override fun getIcon(): String = "graphics/icons/intel/important.png"

    override fun getIntelTags(map: SectorMapAPI): MutableSet<String> {
        val set = LinkedHashSet<String>()
        set.add("Asteria")
        set.add(I18n[I18n.Categories.MOD, "intel.tag.easter_egg"])
        return set
    }
}
