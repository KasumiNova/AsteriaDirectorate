package cn.kasuminova.astd.campaign.integration.sic

import cn.kasuminova.astd.impl.difficulty.LunaLibSupport
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import java.util.Random
import lunalib.lunaRefit.LunaRefitManager
import org.apache.log4j.Logger
import second_in_command.SCUtils

/**
 * SecondInCommand（SIC）联动的软依赖隔离入口（本包唯一对外接线点，
 * docs/design/skills/10-sic-skills.md；范式同
 * [cn.kasuminova.astd.campaign.world.IndEvoWorldExtras]）。
 *
 * 调用约定：
 * - 本类的公开方法签名不引用任何 SIC / LunaLib 类型，缺依赖时 JVM 可安全加载本类；
 *   SIC / LunaLib 类型引用全部收在方法体内，先过 [isEnabled] / [LunaLibSupport.isAvailable]
 *   门控再触碰；
 * - 技能插件本体（本包各 Skill 类）由 SIC 经 SCSkills.csv 数据驱动加载，SIC 缺失时
 *   该 CSV 不会被读取，天然软依赖，不经本类门控。
 *
 * 接线点：
 * - [onApplicationLoad]：AsteriaDirectoratePlugin.onApplicationLoad 末尾调用，
 *   注册智能联结的 LunaLib「额外改造选项」按钮；
 * - [attachBountyOfficerIfEnabled]：BountyFleetTunerImpl 舰队调整流程末尾调用，
 *   为全部本模组赏金舰队固定挂载菀星智能副官。
 */
object SicIntegration {

    private val log: Logger = Global.getLogger(SicIntegration::class.java)

    /** SIC 的模组 id（mod_info.json）。 */
    const val MOD_ID: String = "second_in_command"

    /** 检测标志位缓存（mod 列表在运行期不变，缓存一次即可）。 */
    @Volatile
    private var detected: Boolean? = null

    /** SIC 是否启用（入口门控；本方法本身不引用任何 SIC 类）。 */
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

    /**
     * 注册智能联结改造按钮（LunaRefitManager.addRefitButton）。
     *
     * 双门控：SIC 提供天赋判定（按钮 shouldShow 调 SCUtils），LunaLib 提供按钮 API
     * （IntelLinkRefitButton 类签名触碰 lunalib 类型，未装 LunaLib 时连实例化都会
     * NoClassDefFoundError），任一缺失都必须跳过并说明原因。
     */
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

    /**
     * 赏金副官适配（docs/design/skills/10-sic-skills.md）：为本模组赏金舰队固定挂载
     * 菀星智能副官，携带全部 9 个天赋技能（addSkill 不检查技能点上限）。
     *
     * 调用序列（SIC 1.8.0 源码口径）：
     * 1. 打 tag [SicTuning.SC_NO_RANDOM_OFFICERS_TAG]：关闭 SIC 对 NPC 舰队的随机副官生成
     *    （SCData.init 判定），必须在 getFleetData 之前——init 只在首次创建时执行；
     * 2. [SCUtils.getFleetData]（幂等：初始化 SCData 并给成员挂 sc_skill_controller）；
     * 3. 幂等去重：舰队已有菀星天赋副官则跳过；
     * 4. [SCUtils.createRandomSCOfficer]（种子取舰队 id 散列，组成确定）+ 人格化头像；
     * 5. 9 个技能逐个 addSkill（未指派时 addSkill 不触发 onActivation，
     *    统一由 setOfficerInSlot 触发）；
     * 6. increaseLevel 拉满等级（maxLevel - 1，起始 1 级）；
     * 7. addOfficerToFleet → 找首个空槽 setOfficerInSlot（触发各技能 onActivation）。
     */
    fun attachBountyOfficerIfEnabled(fleet: CampaignFleetAPI) {
        if (!isEnabled()) return
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
