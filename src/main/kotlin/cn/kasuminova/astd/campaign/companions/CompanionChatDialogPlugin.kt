package cn.kasuminova.astd.campaign.companions

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.*
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.combat.EngagementResultAPI
import com.fs.starfarer.api.util.Misc
import java.util.Random
import cn.kasuminova.astd.internal.i18n.I18n

/**
 * 轻量级“闲聊对话框”：不依赖 rules.csv / bar event。
 */
class CompanionChatDialogPlugin(
    private val mode: Mode,
) : InteractionDialogPlugin {

    enum class Mode {
        WATCHER,
        ECHO,
        SHIP_REVIEW,
        CROSSOVER,
    }

    private lateinit var dialog: InteractionDialogAPI
    private lateinit var text: TextPanelAPI
    private lateinit var options: OptionPanelAPI

    private val rnd = Random((Global.getSector()?.clock?.timestamp ?: System.currentTimeMillis()) xor mode.hashCode().toLong())

    override fun init(dialog: InteractionDialogAPI) {
        this.dialog = dialog
        this.text = dialog.textPanel
        this.options = dialog.optionPanel
        dialog.promptText = ""
        dialog.setBackgroundDimAmount(0.5f)

        when (mode) {
            Mode.WATCHER -> {
                text.addPara(I18n[I18n.Categories.MOD, "companion.chat.watcher.open.0"])
                text.addPara(I18n[I18n.Categories.MOD, "companion.chat.watcher.open.1"])
                showWatcherMenu()
            }
            Mode.ECHO -> {
                text.addPara(I18n[I18n.Categories.MOD, "companion.chat.echo.open.0"])
                text.addPara(I18n[I18n.Categories.MOD, "companion.chat.echo.open.1"])
                showEchoMenu()
            }
            Mode.SHIP_REVIEW -> {
                text.addPara(I18n[I18n.Categories.MOD, "companion.chat.ship.open.0"])
                text.addPara(I18n[I18n.Categories.MOD, "companion.chat.ship.open.1"])
                showShipMenu()
            }
            Mode.CROSSOVER -> {
                text.addPara(I18n[I18n.Categories.MOD, "companion.chat.crossover.open.0"])
                text.addPara(I18n[I18n.Categories.MOD, "companion.chat.crossover.open.1"])
                showCrossoverMenu()
            }
        }
        dialog.setOptionOnEscape(null, OptionId.LEAVE)
    }

    private enum class OptionId {
        LEAVE,
        TALK_SMALL,
        TALK_WORK,
        TALK_PAST,
        TALK_PROTOCOL,
        TALK_AI,
        TALK_LENS,
        TALK_JOKE,
        SHIP_ARC_FLARE,
        SHIP_LENS,
        SHIP_NEBULA_ECHO,
        SHIP_APEX,
        SHIP_EVENT_HORIZON,
        SHIP_PLAYER_FLAGSHIP,
        CROSS_POLARIS,
        CROSS_CONSTELLATE,
        CROSS_NEX,
        BACK,
    }

    private fun clearOptions() {
        options.clearOptions()
    }

    private fun showWatcherMenu() {
        clearOptions()
        options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.small"], OptionId.TALK_SMALL)
        options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.protocol"], OptionId.TALK_PROTOCOL)
        options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.past"], OptionId.TALK_PAST)
        options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.work"], OptionId.TALK_WORK)
        options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.LEAVE)
    }

    private fun showEchoMenu() {
        clearOptions()
        options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.small"], OptionId.TALK_SMALL)
        options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.lens"], OptionId.TALK_LENS)
        options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.ai"], OptionId.TALK_AI)
        options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.joke"], OptionId.TALK_JOKE)
        options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.LEAVE)
    }

    private fun showShipMenu() {
        clearOptions()
        options.addOption(I18n[I18n.Categories.MOD, "companion.ship.opt.arc_flare"], OptionId.SHIP_ARC_FLARE)
        options.addOption(I18n[I18n.Categories.MOD, "companion.ship.opt.lens"], OptionId.SHIP_LENS)
        options.addOption(I18n[I18n.Categories.MOD, "companion.ship.opt.nebula_echo"], OptionId.SHIP_NEBULA_ECHO)
        options.addOption(I18n[I18n.Categories.MOD, "companion.ship.opt.event_horizon"], OptionId.SHIP_EVENT_HORIZON)
        options.addOption(I18n[I18n.Categories.MOD, "companion.ship.opt.apex"], OptionId.SHIP_APEX)
        options.addOption(I18n[I18n.Categories.MOD, "companion.ship.opt.player_flagship"], OptionId.SHIP_PLAYER_FLAGSHIP)
        options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.LEAVE)
    }

    private fun showCrossoverMenu() {
        clearOptions()
        options.addOption(I18n[I18n.Categories.MOD, "companion.crossover.opt.polaris"], OptionId.CROSS_POLARIS)
        options.addOption(I18n[I18n.Categories.MOD, "companion.crossover.opt.constellate"], OptionId.CROSS_CONSTELLATE)
        options.addOption(I18n[I18n.Categories.MOD, "companion.crossover.opt.nex"], OptionId.CROSS_NEX)
        options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.LEAVE)
    }

    override fun optionSelected(optionText: String?, optionData: Any?) {
        if (optionData == null) return
        when (optionData as OptionId) {
            OptionId.LEAVE -> dialog.dismiss()
            OptionId.BACK -> when (mode) {
                Mode.WATCHER -> showWatcherMenu()
                Mode.ECHO -> showEchoMenu()
                Mode.SHIP_REVIEW -> showShipMenu()
                Mode.CROSSOVER -> showCrossoverMenu()
            }

            OptionId.TALK_SMALL -> {
                val line = when (mode) {
                    Mode.WATCHER -> I18n[I18n.Categories.MOD, "companion.chat.watcher.small.${rnd.nextInt(4)}"]
                    Mode.ECHO -> I18n[I18n.Categories.MOD, "companion.chat.echo.small.${rnd.nextInt(4)}"]
                    else -> ""
                }
                if (line.isNotBlank()) {
                    text.addPara("\n" + line, Misc.getTextColor())
                }
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.continue"], OptionId.BACK)
            }

            OptionId.TALK_WORK -> {
                text.addPara(I18n[I18n.Categories.MOD, "companion.chat.watcher.work"])
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.BACK)
            }

            OptionId.TALK_PAST -> {
                text.addPara(I18n[I18n.Categories.MOD, "companion.chat.watcher.past"])
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.BACK)
            }

            OptionId.TALK_PROTOCOL -> {
                text.addPara(I18n[I18n.Categories.MOD, "companion.chat.watcher.protocol"])
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.BACK)
            }

            OptionId.TALK_AI -> {
                text.addPara(I18n[I18n.Categories.MOD, "companion.chat.echo.ai"])
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.BACK)
            }

            OptionId.TALK_LENS -> {
                text.addPara(I18n[I18n.Categories.MOD, "companion.chat.echo.lens"])
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.BACK)
            }

            OptionId.TALK_JOKE -> {
                text.addPara(I18n[I18n.Categories.MOD, "companion.chat.echo.joke"])
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.BACK)
            }

            OptionId.SHIP_ARC_FLARE -> {
                text.addPara(I18n[I18n.Categories.MOD, "companion.ship.arc_flare"])
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.BACK)
            }

            OptionId.SHIP_LENS -> {
                text.addPara(I18n[I18n.Categories.MOD, "companion.ship.lens"])
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.BACK)
            }

            OptionId.SHIP_NEBULA_ECHO -> {
                text.addPara(I18n[I18n.Categories.MOD, "companion.ship.nebula_echo"])
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.BACK)
            }

            OptionId.SHIP_EVENT_HORIZON -> {
                text.addPara(I18n[I18n.Categories.MOD, "companion.ship.event_horizon"])
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.BACK)
            }

            OptionId.SHIP_APEX -> {
                text.addPara(I18n[I18n.Categories.MOD, "companion.ship.apex"])
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.BACK)
            }

            OptionId.SHIP_PLAYER_FLAGSHIP -> {
                val pf = Global.getSector()?.playerFleet
                val flagship = pf?.flagship
                if (flagship == null) {
                    text.addPara(I18n[I18n.Categories.MOD, "companion.ship.player_flagship.none"])
                } else {
                    val hid = flagship.hullSpec.hullId
                    text.addPara(I18n[I18n.Categories.MOD, "companion.ship.player_flagship.send"] + "\n\"$hid\"", Misc.getHighlightColor(), hid)
                    val msg = if (hid.startsWith("astd_")) {
                        I18n[I18n.Categories.MOD, "companion.ship.player_flagship.smd"]
                    } else {
                        I18n[I18n.Categories.MOD, "companion.ship.player_flagship.other"]
                    }
                    text.addPara(msg)
                }
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.BACK)
            }

            OptionId.CROSS_POLARIS -> {
                val enabled = Global.getSettings().modManager.isModEnabled("Polaris_Prime")
                if (enabled) {
                    text.addPara(I18n[I18n.Categories.MOD, "companion.crossover.polaris.enabled"])
                } else {
                    text.addPara(I18n[I18n.Categories.MOD, "companion.crossover.polaris.disabled"])
                }
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.BACK)
            }

            OptionId.CROSS_CONSTELLATE -> {
                val enabled = Global.getSettings().modManager.isModEnabled("Galactic_Constellate")
                if (enabled) {
                    text.addPara(I18n[I18n.Categories.MOD, "companion.crossover.constellate.enabled"])
                } else {
                    text.addPara(I18n[I18n.Categories.MOD, "companion.crossover.constellate.disabled"])
                }
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.BACK)
            }

            OptionId.CROSS_NEX -> {
                val enabled = Global.getSettings().modManager.isModEnabled("nexerelin")
                if (enabled) {
                    text.addPara(I18n[I18n.Categories.MOD, "companion.crossover.nex.enabled"])
                } else {
                    text.addPara(I18n[I18n.Categories.MOD, "companion.crossover.nex.disabled"])
                }
                options.addOption(I18n[I18n.Categories.MOD, "companion.chat.opt.back"], OptionId.BACK)
            }
        }
    }

    override fun optionMousedOver(optionText: String?, optionData: Any?) {
        // 无悬停提示需求
    }

    override fun advance(amount: Float) {}
    override fun backFromEngagement(battleResult: EngagementResultAPI?) {}
    override fun getContext(): Any? = null
    override fun getMemoryMap(): MutableMap<String, MemoryAPI> = HashMap()
}
