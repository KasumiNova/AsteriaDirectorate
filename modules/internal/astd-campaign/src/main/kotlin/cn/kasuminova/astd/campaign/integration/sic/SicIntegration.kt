package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.impl.difficulty.LunaLibSupport
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import java.util.Random
import lunalib.lunaRefit.LunaRefitManager
import org.apache.log4j.Logger
import second_in_command.SCUtils

/**
 * SIC 联动的软依赖隔离入口：公开方法签名不得引用任何 SIC / LunaLib 类型，
 * 缺依赖时 JVM 才能安全加载本类；类型引用全部收在方法体内，先过门控再触碰。
 */
object SicIntegration {

    private val log: Logger = Global.getLogger(SicIntegration::class.java)

    const val MOD_ID: String = "second_in_command"

    @Volatile
    private var detected: Boolean? = null

    fun isEnabled(): Boolean {
        detected?.let { return it }
        val enabled = try {
            Global.getSettings().modManager.isModEnabled(MOD_ID)
        } catch (t: Throwable) {
            log.error("[ASTD] SecondInCommand 检测失败", t)
            false
        }
        detected = enabled
        return enabled
    }

    fun onApplicationLoad() {
        if (!isEnabled()) {
            log.info("[ASTD] 未检测到 SecondInCommand：跳过智能联结改造按钮注册")
            return
        }
        if (!LunaLibSupport.isAvailable()) {
            log.info("[ASTD] 检测到 SecondInCommand 但未检测到 LunaLib：智能联结改造按钮不可用，跳过注册")
            return
        }
        LunaRefitManager.addRefitButton(IntelLinkRefitButton())
        log.info("[ASTD] SIC 联动已注册：智能联结改造按钮（LunaLib 额外改造选项）")
    }

    fun attachBountyOfficerIfEnabled(fleet: CampaignFleetAPI) {
        if (!isEnabled()) return
        // SCData.init 只在首次创建时执行，关闭随机副官生成的 tag 必须先于 getFleetData
        fleet.addTag(SicTuning.SC_NO_RANDOM_OFFICERS_TAG)
        val data = SCUtils.getFleetData(fleet)
        if (data.hasAptitudeInFleet(SicTuning.BOUNTY_OFFICER_APTITUDE_ID)) return

        val officer = SCUtils.createRandomSCOfficer(
            SicTuning.BOUNTY_OFFICER_APTITUDE_ID,
            fleet.faction,
            Random(fleet.id.hashCode().toLong()),
        )
        officer.person.portraitSprite = SicTuning.BOUNTY_OFFICER_PORTRAIT
        SicTuning.BOUNTY_OFFICER_SKILL_IDS.forEach { officer.addSkill(it) }
        officer.increaseLevel(officer.getMaxLevel() - 1)
        data.addOfficerToFleet(officer)

        val slot = data.getAssignedOfficers().indexOfFirst { it == null }
        if (slot < 0) {
            log.error("[ASTD] 赏金舰队 ${fleet.name} 的 SIC 副官槽已满，菀星副官未能指派上岗")
            return
        }
        data.setOfficerInSlot(slot, officer)
        log.info(
            "[ASTD] 赏金副官已挂载：舰队=${fleet.name} 副官=${officer.person.nameString}" +
                " 技能数=${SicTuning.BOUNTY_OFFICER_SKILL_IDS.size} 槽位=$slot",
        )
    }
}
