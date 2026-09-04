package cn.kasuminova.astd.impl.buff

import cn.kasuminova.astd.api.buff.Buff
import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.api.buff.BuffLifetime
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI

/**
 * [BuffHost] 的 customData 实现：一艘船的全部 Buff 直接落在 `ship.customData` 上。
 *
 * 动机与载体（规格 00-共享基建 §1.2）：
 * - Ship 级：`ship.customData["astd_buff:ship:<buffId>"] = Buff`；
 * - Weapon 级：`WeaponAPI` 无 customData（jar 已核实），状态挂舰船侧复合键
 *   `ship.customData["astd_buff:weapon:<buffId>:<slotId>"] = WeaponBuffRecord`，
 *   记录登记时的 `weaponId` 用于换装检测（同槽位换武器后旧 Buff 视为失效，心跳回收）；
 * - 键级联在 ship 上：船变 hulk/移除时状态随实体生命周期自然终结，无需跨表清理。
 *
 * 本类自身也作为单个 customData 条目（[HOST_KEY]）挂在 ship 上，供 [BuffTickPlugin] 惰性发现：
 * 无 Buff 的船不入表，心跳遍历零成本。
 */
class BuffHostImpl(
    /** 宿主的 customData 表（即 `ship.customData` 本体）；Buff 读写直接落在该表上。 */
    private val data: MutableMap<String, Any?>,
) : BuffHost {

    /** Weapon 级 Buff 的登记记录：复合键值，含换装检测所需的登记时武器身份。 */
    internal class WeaponBuffRecord(
        /** 登记的 Buff 本体。 */
        val buff: Buff,
        /** 登记时的武器引用（供 [BuffHost.all] 返回与调试）。 */
        val weapon: WeaponAPI,
        /** 登记时的 `weapon.spec.weaponId`：换装检测基准。 */
        val weaponId: String,
        /** 登记时的 `weapon.slot.id`：复合键段。 */
        val slotId: String,
    )

    override fun find(id: String): Buff? = data[shipKey(id)] as? Buff

    override fun findByWeapon(id: String, weapon: WeaponAPI): Buff? {
        val record = data[weaponKey(id, slotIdOf(weapon))] as? WeaponBuffRecord ?: return null
        // 换装残留：登记武器与当前武器不符，视为不存在（由心跳负责回收并触发 onRemove）。
        if (record.weaponId != weaponIdOf(weapon)) return null
        return record.buff
    }

    override fun register(buff: Buff, weapon: WeaponAPI?) {
        if (!buff.id.startsWith(REQUIRED_ID_PREFIX)) {
            log.warn("Buff id 未以 $REQUIRED_ID_PREFIX 开头，将照常注册但请修正命名: id=${buff.id}")
        }

        if (weapon == null) {
            val key = shipKey(buff.id)
            val old = data[key] as? Buff
            if (old != null) {
                log.warn("Ship 级 Buff 重复注册，属程序错误，回收并覆盖旧实例: key=$key, old=${old.javaClass.name}, new=${buff.javaClass.name}")
                old.onRemove()
            }
            data[key] = buff
            return
        }

        val slotId = slotIdOf(weapon)
        val weaponId = weaponIdOf(weapon)
        val key = weaponKey(buff.id, slotId)
        val old = data[key] as? WeaponBuffRecord
        if (old != null) {
            if (old.weaponId != weaponId) {
                // 换装残留：正常流程，回收旧 Buff 后替换，不记 WARN。
                old.buff.onRemove()
            } else {
                log.warn("Weapon 级 Buff 重复注册，属程序错误，回收并覆盖旧实例: key=$key, old=${old.buff.javaClass.name}, new=${buff.javaClass.name}")
                old.buff.onRemove()
            }
        }
        data[key] = WeaponBuffRecord(buff, weapon, weaponId, slotId)
    }

    override fun remove(buff: Buff, weapon: WeaponAPI?) {
        val key = if (weapon == null) shipKey(buff.id) else weaponKey(buff.id, slotIdOf(weapon))
        val current = data[key] ?: run {
            log.warn("移除不存在的 Buff: key=$key, buff=${buff.javaClass.name}")
            return
        }
        val currentBuff = buffOf(current) ?: run {
            log.warn("Buff 键值类型异常: key=$key, valueType=${current.javaClass.name}")
            return
        }
        currentBuff.onRemove()
        data.remove(key)
    }

    override fun all(): List<Pair<Buff, WeaponAPI?>> =
        data.entries.mapNotNull { (key, value) ->
            when {
                key.startsWith(SHIP_PREFIX) -> (value as? Buff)?.let { it to null }
                key.startsWith(WEAPON_PREFIX) -> (value as? WeaponBuffRecord)?.let { it.buff to it.weapon }
                else -> null
            }
        }

    /**
     * 心跳：由 [BuffTickPlugin] 每帧对宿主船调用。
     * 回收规则——宿主 hulk 时回收全部 [BuffLifetime.HOST_BOUND]；Weapon 级条目在槽位换装/空槽时回收；
     * HOST_BOUND 且 [Buff.isHostValid] 为 false 时回收。存活条目透传 [Buff.advance]。
     * 遍历时基于快照，容忍 [BuffLifetime.SELF_MANAGED] 在 advance 内自行 [remove]。
     */
    internal fun tick(ship: ShipAPI, amount: Float) {
        val hostAlive = !ship.isHulk
        val snapshot = data.entries.filter { (key, _) -> key.startsWith(SHIP_PREFIX) || key.startsWith(WEAPON_PREFIX) }

        for ((key, value) in snapshot) {
            // SELF_MANAGED 可能已在本帧 advance 内自行移除/被覆盖，跳过已易主的键。
            if (data[key] !== value) continue

            val buff = buffOf(value) ?: continue
            val recycle = when {
                value is WeaponBuffRecord && !weaponMatches(ship, value) -> true
                buff.lifetime == BuffLifetime.HOST_BOUND && (!hostAlive || !buff.isHostValid()) -> true
                else -> false
            }
            if (recycle) {
                buff.onRemove()
                data.remove(key, value)
            } else {
                buff.advance(amount)
            }
        }
    }

    /** 换装检测：槽位当前武器的 weaponId 与登记时一致才视为有效；槽位空/武器被拆即失效。 */
    private fun weaponMatches(ship: ShipAPI, record: WeaponBuffRecord): Boolean {
        val current = ship.allWeapons.firstOrNull { it?.slot?.id == record.slotId } ?: return false
        return current.spec?.weaponId == record.weaponId
    }

    private fun buffOf(value: Any?): Buff? = when (value) {
        is Buff -> value
        is WeaponBuffRecord -> value.buff
        else -> null
    }

    private fun slotIdOf(weapon: WeaponAPI): String =
        weapon.slot?.id ?: throw IllegalArgumentException(
            "Weapon 级 Buff 要求武器带槽位（weapon.slot 为空）: buffWeapon=${weapon.spec?.weaponId}",
        )

    private fun weaponIdOf(weapon: WeaponAPI): String =
        weapon.spec?.weaponId ?: throw IllegalArgumentException(
            "Weapon 级 Buff 要求武器带 spec（weapon.spec 为空）: slot=${weapon.slot?.id}",
        )

    companion object {
        /** BuffHostImpl 自身在 ship.customData 上的登记键。 */
        const val HOST_KEY = "astd_buff:host"

        private const val SHIP_PREFIX = "astd_buff:ship:"
        private const val WEAPON_PREFIX = "astd_buff:weapon:"
        private const val REQUIRED_ID_PREFIX = "astd_"

        private val log = Global.getLogger(BuffHostImpl::class.java)

        private fun shipKey(buffId: String): String = "$SHIP_PREFIX$buffId"
        private fun weaponKey(buffId: String, slotId: String): String = "$WEAPON_PREFIX$buffId:$slotId"
    }
}
