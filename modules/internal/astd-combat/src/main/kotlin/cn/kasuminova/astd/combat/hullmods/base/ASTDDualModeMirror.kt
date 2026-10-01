package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.fleet.FleetMemberAPI
import java.util.IdentityHashMap

/**
 * 双模式「待镜像」登记处 + 战役侧镜像脚本。
 *
 * 动机（装配界面头像选择陈旧问题）：refit 界面编辑的是 variant 工作克隆，提交前真实
 * `member.variant` 仍是旧模式；而头像（舰长/AI 核心）选择界面读的是真实 variant 的
 * `Misc.isAutomated`，导致「拆切换器切模式后立即点头像，看到的还是旧模式的选择列表」。
 *
 * 机制：[activateDualMode] 在身份门命中（被翻转 variant 即成员当前生效 variant，
 * 对应 refit 提交前的临时 swap 窗口）时把目标模式登记到这里；本脚本下一帧把模式位
 * （模式 permaMod / next marker / 原版 automated / 免惩罚标签）镜像到真实 variant——
 * 此时临时 swap 已换回，写入的就是真实 variant。
 *
 * 边界：
 * - 镜像只写模式位，不动切换器（真实 variant 上的切换器从未被拆，拆卸只发生在工作克隆上）；
 *   后续 refit 提交会用工作克隆整体覆盖真实 variant，二者模式位一致，无冲突。
 * - 自动装配预览对克隆 variant 的翻转不经过身份门，不会登记，预览不污染真实状态。
 * - 舰长清理不在此处（镜像以 stats=null 调用 [activateDualMode]）：清理由身份门路径
 *   与 [ASTDDualModeRefitListener] 的提交时兼容清理承担。
 */
object ASTDDualModeMirror {

    private val log = Global.getLogger(ASTDDualModeMirror::class.java)

    /** 待镜像队列：成员 → 目标模式 permaMod id。IdentityHashMap 避免成员 equals/hashCode 语义干扰。 */
    private val pending = IdentityHashMap<FleetMemberAPI, String>()

    /** 登记一个待镜像模式；同成员重复登记时后者覆盖前者（连续翻多次只镜像最终模式）。 */
    fun record(member: FleetMemberAPI, modeId: String) {
        pending[member] = modeId
    }

    /** 本帧内是否已有该成员的待镜像记录（ASTDAutofitPlugin 用以区分确认/预览路径）。 */
    fun isPending(member: FleetMemberAPI): Boolean = pending.containsKey(member)

    /** 由镜像脚本每帧调用：把登记的模式位落到成员当前真实 variant 上。 */
    fun drain() {
        if (pending.isEmpty()) return
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            it.remove()
            val variant = entry.key.variant ?: continue
            val config = ASTDDualModeRegistry.configForVariant(variant) ?: continue
            if (ASTDAutofitPlugin.activeDualModeId(variant, config) == entry.value) continue
            // stats=null：不触发舰长清理与镜像再登记（镜像只写模式位，幂等收敛）
            variant.activateDualMode(config, entry.value, null)
            log.info("[ASTD] 双模式镜像：${entry.key.id} 真实 variant 模式位已镜像为 ${entry.value}")
        }
    }
}

/**
 * 双模式镜像脚本：每帧排空 [ASTDDualModeMirror] 的待镜像队列。
 *
 * runWhilePaused=true：停靠空间站时战役暂停，但 refit 界面仍可编辑——镜像必须在暂停期也走帧，
 * 否则停靠状态下切模式后头像选择界面依旧陈旧。脚本为 transient（onGameLoad 注册），不入存档，
 * 读档后由新的脚本实例接管，待镜像队列天然不残留旧存档的成员引用。
 */
class ASTDDualModeMirrorScript : EveryFrameScript {
    override fun isDone(): Boolean = false
    override fun runWhilePaused(): Boolean = true
    override fun advance(amount: Float) = ASTDDualModeMirror.drain()
}
