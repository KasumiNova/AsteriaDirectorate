package cn.kasuminova.astd.combat.hullmods.arc

import com.fs.starfarer.api.combat.ShipAPI

/**
 * 虚数充能池状态（每舰一份，挂在 [ShipAPI.setCustomData]）。
 * 供虚数之翼船插、坠星残翼武器效果、星翼折跃系统三方共享读写。
 */
class ImaginaryCharge {

    var charge = 0f

    /** 交火共鸣 +3% 的最短触发间隔剩余秒数。 */
    var hitGainCooldown = 0f

    /** 星羽翼装弹幕的 0.3s 节拍剩余秒数。 */
    var barrageTimer = 0f

    /** 弹幕所用系统槽位对的轮换序号。 */
    var barrageAnchorIndex = 0

    /** 超翼过载剩余秒数（>0 表示窗口内）。 */
    var overdriveRemaining = 0f

    /** 最近被主炮命中的敌舰（星羽追踪回退目标）。 */
    var lastMainGunHit: ShipAPI? = null

    /** 透支折跃待命：系统充能为 0 时由船插虚拟补 1 发充能，激活即消耗 50% 虚数充能。 */
    var overdraftArmed = false

    /** 本次激活为透支折跃（系统 ACTIVE 边沿锁定，OUT 结算后清除）。 */
    var overdraftActive = false

    val inOverdrive: Boolean get() = overdriveRemaining > 0f

    fun add(amount: Float) {
        charge = (charge + amount).coerceIn(0f, ImaginaryWingsTuning.CHARGE_MAX)
    }

    fun consume(amount: Float) {
        charge = (charge - amount).coerceAtLeast(0f)
    }

    /** 交火共鸣 +3%，受最短触发间隔约束；返回是否实际入账。 */
    fun tryHitGain(amount: Float): Boolean {
        if (hitGainCooldown > 0f) return false
        hitGainCooldown = ImaginaryWingsTuning.HIT_GAIN_MIN_INTERVAL
        add(amount)
        return true
    }

    companion object {
        private const val KEY = "astd_imaginary_charge"

        // customData 首次写入必须调用 setCustomData；getCustomData 返回的空表不持久。
        fun of(ship: ShipAPI): ImaginaryCharge =
            ship.customData[KEY] as? ImaginaryCharge
                ?: ImaginaryCharge().also { ship.setCustomData(KEY, it) }

        fun peek(ship: ShipAPI): ImaginaryCharge? = ship.customData[KEY] as? ImaginaryCharge
    }
}
