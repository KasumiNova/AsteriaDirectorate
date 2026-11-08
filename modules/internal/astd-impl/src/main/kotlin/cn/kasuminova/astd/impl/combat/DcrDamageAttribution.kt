package cn.kasuminova.astd.impl.combat

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import org.apache.log4j.Logger

/**
 * 把脚本结算的伤害归属到具体武器名，供 Detailed Combat Results 的战报统计使用。
 *
 * DCR 只从 `ShipAPI` 作伤害源的监听记录里取数据，脚本直调 `applyDamage` 无法被它关联到射弹，
 * 战报会把这些伤害记成「Unknown 伤害类型」。这里改用 DCR 的澄清接口补一条同源同目标的伤害记录，
 * 让战报按武器名归档，同时保留 `ShipAPI` 作伤害源，舰队级结构伤害统计不受影响。
 *
 * 仅在战斗主线程调用，且必须在对应的 `applyDamage` 之后、同一帧内调用。
 */
object DcrDamageAttribution {

    private const val MOD_ID = "DetailedCombatResults"

    private val log: Logger by lazy { Global.getLogger(DcrDamageAttribution::class.java) }

    @Volatile
    private var available: Boolean? = null

    fun isAvailable(): Boolean {
        available?.let { return it }
        val enabled = try {
            Global.getSettings().modManager.isModEnabled(MOD_ID)
        } catch (t: Throwable) {
            // 无头测试与设置未就绪的启动早期会走到这里；不缓存，避免把一次异常固化成永久关闭。
            log.error("[ASTD] Detailed Combat Results 检测失败", t)
            return false
        }
        available = enabled
        return enabled
    }

    /**
     * 为一次已发生的脚本伤害登记归属。
     *
     * [shipDamage] 与 [empDamage] 必须是该次 `applyDamage` 实际传入的数值：DCR 按数值把澄清记录
     * 与监听记录配对，数值与目标对不上时该条澄清被丢弃，战报退回「Unknown」。
     * [source] 为空或 [target] 不是舰船时 DCR 不会收录，直接跳过。
     */
    fun clarify(
        source: ShipAPI?,
        target: CombatEntityAPI?,
        damageType: DamageType,
        shipDamage: Float,
        empDamage: Float,
        weaponName: String?,
    ) {
        if (source == null || target !is ShipAPI || weaponName.isNullOrBlank()) return
        if (shipDamage <= 0f && empDamage <= 0f) return
        if (!isAvailable()) return
        DcrBridge.addClarification(shipDamage, empDamage, damageType, source, target, weaponName)
    }

    /** 隔离 DCR 类型引用：DCR 未启用时本对象不会被加载。 */
    private object DcrBridge {
        fun addClarification(
            shipDamage: Float,
            empDamage: Float,
            damageType: DamageType,
            source: ShipAPI,
            target: ShipAPI,
            weaponName: String,
        ) {
            data.scripts.DCRIntegration.addDamageClarification(
                shipDamage, empDamage, damageType, source, target, weaponName,
            )
        }
    }
}
