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
 * 机制：拆即切的翻转实际发生在 refit 预览 Ship 的 stats 上（`stats.variant` = 工作克隆，
 * `member.variant` 仍是真实 variant），[activateDualMode] 的身份门（`member.variant === this`）
 * 永不命中——因此两个 mode hullmod 的「拆即切」分支在翻转后显式 [record]（唯一代表玩家意图
 * 拆即切的位置）；身份门路径的登记保留给战役实时翻转（装配确认路径全程处于抑制窗口，其真实 variant 由 saveCurrentVariant 整体提交，不依赖镜像）。
 * 本脚本下一帧把模式位（模式 permaMod / next marker / 原版 automated / 免惩罚标签）镜像到
 * 真实 variant——此时临时 swap 已换回，写入的就是真实 variant。
 *
 * 边界：
 * - 镜像是**预提交写入**：refit 尚未提交时真实 variant 的模式位已更新。若玩家随后放弃编辑
 *   （未提交），模式位不会回滚——视作「拆即切」一旦触发即生效的有意语义（原版 refit 默认
 *   自动保存，放弃路径仅在选择不保留修改时出现）。
 * - 镜像只写模式位，不动切换器（真实 variant 上的切换器从未被拆，拆卸只发生在工作克隆上）；
 *   后续 refit 提交会用工作克隆整体覆盖真实 variant，二者模式位一致，无冲突。
 * - 自动装配全程处于 [beginAutofitSuppression] 抑制窗口：预览对克隆的翻转不登记，
 *   预览不污染真实状态；确认路径由 saveCurrentVariant 整体提交，无需镜像。
 * - 舰长清理不在此处（镜像以 stats=null 调用 [activateDualMode]）：清理由身份门路径
 *   与 [ASTDDualModeRefitListener] 的提交时兼容清理承担。
 * - drain 不触发成员 stats 重建：系统互换等派生状态随下一次常规 stats 刷新收敛，
 *   头像选择界面只读 permaMods（Misc.isAutomated），镜像后即正确。
 */
object ASTDDualModeMirror {

    private val log = Global.getLogger(ASTDDualModeMirror::class.java)

    /** 待镜像队列：成员 → 目标模式 permaMod id。IdentityHashMap 避免成员 equals/hashCode 语义干扰。 */
    private val pending = IdentityHashMap<FleetMemberAPI, String>()

    /**
     * 自动装配登记抑制窗口（计数支持模块递归装配的重入）：>0 时 [record] 直接丢弃。
     *
     * 动机：自动装配 strip 中途的「拆即切」翻转作用于装配克隆，预览路径不得污染真实 variant；
     * 确认路径的真实 variant 由 saveCurrentVariant 整体提交，无需镜像。因此装配全程的镜像登记
     * 一律抑制（见 [ASTDAutofitPlugin.doFit] 的 try/finally 窗口）。
     */
    private var autofitSuppression = 0

    /** 进入自动装配抑制窗口（与 [endAutofitSuppression] 配对，调用方须 try/finally）。 */
    fun beginAutofitSuppression() {
        autofitSuppression++
    }

    /** 退出自动装配抑制窗口。与 [beginAutofitSuppression] 不配对属于调用方 bug，记警告并忽略。 */
    fun endAutofitSuppression() {
        if (autofitSuppression <= 0) {
            log.warn("[ASTD] 双模式镜像：endAutofitSuppression 调用不配对（抑制计数已为 0），已忽略")
            return
        }
        autofitSuppression--
    }

    /** 登记一个待镜像模式；同成员重复登记时后者覆盖前者（连续翻多次只镜像最终模式）。 */
    fun record(member: FleetMemberAPI, modeId: String) {
        if (autofitSuppression > 0) return
        pending[member] = modeId
    }

    /** 本帧内是否已有该成员的待镜像记录（测试与诊断用）。 */
    fun isPending(member: FleetMemberAPI): Boolean = pending.containsKey(member)

    /**
     * 清空待镜像队列并复位抑制计数。onGameLoad 注册新脚本时调用：队列与计数同为进程内静态状态，
     * 不会随读档重置；残留条目的成员引用属于读档前的旧对象图，排空可避免对游离 variant 做无意义写入。
     */
    fun clear() {
        pending.clear()
        autofitSuppression = 0
    }

    /** 由镜像脚本每帧调用：把登记的模式位落到成员当前真实 variant 上。 */
    fun drain() {
        if (pending.isEmpty()) return
        // 先快照再清空：IdentityHashMap 迭代条目在 it.remove() 后即失效（再读 key 抛
        // "Entry was removed"）；快照式排空同时保证 drain 期间的新登记留到下一帧处理
        val batch = pending.map { it.key to it.value }
        pending.clear()
        for ((member, modeId) in batch) {
            val variant = member.variant ?: continue
            val config = ASTDDualModeRegistry.configForVariant(variant) ?: continue
            if (ASTDAutofitPlugin.activeDualModeId(variant, config) == modeId) continue
            // stats=null：不触发舰长清理与镜像再登记（镜像只写模式位，幂等收敛）
            variant.activateDualMode(config, modeId, null)
            log.info("[ASTD] 双模式镜像：${member.id} 真实 variant 模式位已镜像为 $modeId")
        }
    }
}

/**
 * 双模式镜像脚本：每帧排空 [ASTDDualModeMirror] 的待镜像队列。
 *
 * runWhilePaused=true：停靠空间站时战役暂停，但 refit 界面仍可编辑——镜像必须在暂停期也走帧，
 * 否则停靠状态下切模式后头像选择界面依旧陈旧。脚本为 transient（onGameLoad 注册，不入存档）；
 * 待镜像队列是进程内静态状态，注册新脚本前由插件显式 [ASTDDualModeMirror.clear]。
 */
class ASTDDualModeMirrorScript : EveryFrameScript {
    override fun isDone(): Boolean = false
    override fun runWhilePaused(): Boolean = true
    override fun advance(amount: Float) = ASTDDualModeMirror.drain()
}
