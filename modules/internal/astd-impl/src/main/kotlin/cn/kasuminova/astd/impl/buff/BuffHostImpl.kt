package cn.kasuminova.astd.impl.buff

import cn.kasuminova.astd.api.buff.Buff
import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.impl.buff.BuffHostImpl.Companion.HOST_KEY
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI

class BuffHostImpl(
    private val data: MutableMap<String, Any?>,
) : BuffHost {

    internal class WeaponBuffRecord(
        val buff: Buff,
        val weapon: WeaponAPI,
        val weaponId: String,
        val slotId: String,
    )

    override fun find(id: String): Buff? = data[shipKey(id)] as? Buff

    override fun findByWeapon(id: String, weapon: WeaponAPI): Buff? {
        val record = data[weaponKey(id, slotIdOf(weapon))] as? WeaponBuffRecord ?: return null
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

    internal fun tick(ship: ShipAPI, amount: Float) {
        val hostAlive = !ship.isHulk
        val snapshot = data.entries.filter { (key, _) -> key.startsWith(SHIP_PREFIX) || key.startsWith(WEAPON_PREFIX) }

        for ((key, value) in snapshot) {
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
        const val HOST_KEY = "astd_buff:host"

        private const val SHIP_PREFIX = "astd_buff:ship:"
        private const val WEAPON_PREFIX = "astd_buff:weapon:"
        private const val REQUIRED_ID_PREFIX = "astd_"

        private val log = Global.getLogger(BuffHostImpl::class.java)

        private fun shipKey(buffId: String): String = "$SHIP_PREFIX$buffId"
        private fun weaponKey(buffId: String, slotId: String): String = "$WEAPON_PREFIX$buffId:$slotId"
    }
}
