package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.fleet.FleetMemberAPI
import java.util.IdentityHashMap

object ASTDDualModeMirror {

    private val log = Global.getLogger(ASTDDualModeMirror::class.java)

    private val pending = IdentityHashMap<FleetMemberAPI, String>()

    private var autofitSuppression = 0

    fun beginAutofitSuppression() {
        autofitSuppression++
    }

    fun endAutofitSuppression() {
        if (autofitSuppression <= 0) {
            log.warn("[ASTD] 双模式镜像：endAutofitSuppression 调用不配对（抑制计数已为 0），已忽略")
            return
        }
        autofitSuppression--
    }

    fun record(member: FleetMemberAPI, modeId: String) {
        if (autofitSuppression > 0) return
        pending[member] = modeId
    }

    fun isPending(member: FleetMemberAPI): Boolean = pending.containsKey(member)

    fun clear() {
        pending.clear()
        autofitSuppression = 0
    }

    fun drain() {
        if (pending.isEmpty()) return
        // IdentityHashMap 迭代条目删除后不可再读，必须先快照再清空。
        val batch = pending.map { it.key to it.value }
        pending.clear()
        for ((member, modeId) in batch) {
            val variant = member.variant ?: continue
            val config = ASTDDualModeRegistry.configForVariant(variant) ?: continue
            if (ASTDAutofitPlugin.activeDualModeId(variant, config) == modeId) continue
            variant.activateDualMode(config, modeId, null)
            log.info("[ASTD] 双模式镜像：${member.id} 真实 variant 模式位已镜像为 $modeId")
        }
    }
}

class ASTDDualModeMirrorScript : EveryFrameScript {
    override fun isDone(): Boolean = false
    override fun runWhilePaused(): Boolean = true
    override fun advance(amount: Float) = ASTDDualModeMirror.drain()
}
