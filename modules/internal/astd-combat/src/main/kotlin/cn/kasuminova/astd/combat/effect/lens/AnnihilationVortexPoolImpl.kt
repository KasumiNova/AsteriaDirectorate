package cn.kasuminova.astd.combat.effect.lens

import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.api.combat.AnnihilationVortexPool
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.WeaponAPI

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

    private var consumed = false

    private var removed = false

    private val zeroDamageLoggedTypes = HashSet<DamageType>()

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
