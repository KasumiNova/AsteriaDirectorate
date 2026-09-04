package cn.kasuminova.astd.combat.effect.lens

import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.api.combat.AnnihilationVortexPool
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.WeaponAPI

/**
 * [AnnihilationVortexPool] 实现：吞噬池记账（规格 04 §2.2）。
 *
 * 职责：类型转换比表、软上限分段折算（阈值内全额、超出部分 ×[AnnihilationVortexDifficulty.EXCESS_RATIO]）、
 * 宿主失效自回收（advance 内判定 + [BuffHost.remove] + INFO 日志）。
 *
 * 生命周期（SELF_MANAGED）：正常路径由 BeamEffect 在停火坍缩后 [markConsumed] 并移除；
 * 宿主失效（船 hulk/换装/武器被拆）时由自身 advance 或 BuffHost 心跳回收，
 * 两条回收路径在 [onRemove] 汇合，未消费的池记一条 INFO「丢弃 X」——宿主死亡涡旋哑火，不触发坍缩。
 */
class AnnihilationVortexPoolImpl(
    threshold: Float,
    private val host: BuffHost,
    private val weapon: WeaponAPI,
) : AnnihilationVortexPool {

    override val id: String = AnnihilationVortexBeamEffect.POOL_BUFF_ID

    override val lifetime: BuffLifetime = BuffLifetime.SELF_MANAGED

    override val threshold: Float

    override var convertedTotal: Float = 0f
        private set

    override var absorbedCount: Int = 0
        private set

    /** 正常消费标记：停火坍缩后 BeamEffect 置位再移除，[onRemove] 据此区分「消费完毕」与「宿主失效丢弃」。 */
    private var consumed = false

    /** 回收幂等闸：advance 自移除与心跳回收只走一次。 */
    private var removed = false

    /** 0 伤害弹体 INFO 节流：每弹种每池只记一条（规格 04 §2.4「同弹种同帧只记一条」的更严实现）。 */
    private val zeroDamageLoggedTypes = HashSet<DamageType>()

    /** 未登记伤害类型 WARN 节流：每类型每池一条。 */
    private val unknownTypeWarnedTypes = HashSet<DamageType>()

    init {
        if (threshold <= 0f) {
            log.warn("[ASTD] 湮灭涡旋吞噬池 threshold 非正（$threshold），clamp 到 1（软上限退化为全程 ${AnnihilationVortexDifficulty.EXCESS_RATIO} 折算）: weapon=${weapon.spec?.weaponId}")
            this.threshold = 1f
        } else {
            this.threshold = threshold
        }
    }

    override fun addAbsorbed(type: DamageType, baseDamage: Float): Float {
        val ratio = AnnihilationVortexDifficulty.CONVERSION[type] ?: run {
            if (unknownTypeWarnedTypes.add(type)) {
                log.warn("[ASTD] 湮灭涡旋吸收到未登记伤害类型 $type，按转换比 1.0 计入: weapon=${weapon.spec?.weaponId}")
            }
            1f
        }
        val converted = baseDamage.coerceAtLeast(0f) * ratio
        if (baseDamage <= 0f && zeroDamageLoggedTypes.add(type)) {
            log.info("[ASTD] 湮灭涡旋吸收 0 面板伤害弹体（type=$type），照常移除、入池 0、计数 +1: weapon=${weapon.spec?.weaponId}")
        }

        val room = (threshold - convertedTotal).coerceAtLeast(0f)
        val inRoom = minOf(converted, room)
        val effective = inRoom + (converted - inRoom) * AnnihilationVortexDifficulty.EXCESS_RATIO
        convertedTotal += effective
        absorbedCount++
        return effective
    }

    /** 标记本池已被停火坍缩正常消费（消费后移除不记「宿主失效丢弃」INFO）。 */
    fun markConsumed() {
        consumed = true
    }

    override fun isHostValid(): Boolean {
        val ship = weapon.ship ?: return false
        return !ship.isHulk && ship.isAlive
    }

    override fun advance(amount: Float) {
        if (removed) return
        if (isHostValid()) return
        removed = true
        host.remove(this, weapon)
    }

    override fun onRemove() {
        if (consumed) return
        log.info("[ASTD] 涡旋吞噬池随宿主失效丢弃 $convertedTotal（吸收 $absorbedCount 发），不触发坍缩: weapon=${weapon.spec?.weaponId}")
        Global.getCombatEngine()?.let { AnnihilationVortexBeamEffect.telemetryInc(it, AnnihilationVortexBeamEffect.TELEMETRY_POOL_RECYCLED) }
    }

    private companion object {
        private val log = Global.getLogger(AnnihilationVortexPoolImpl::class.java)
    }
}
