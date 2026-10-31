package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.WeaponAPI
import java.util.IdentityHashMap

// BeamWeaponRay 持有独立于 weapon.damage 的 Damage 对象，两者须按各自身份分别保存与恢复。
internal class GravityCollapseDamageSuppressor {

    private val weaponDamage = IdentityHashMap<WeaponAPI, Float>()
    private val beamDamage = IdentityHashMap<BeamAPI, Float>()

    fun suppress(weapon: WeaponAPI, beam: BeamAPI?) {
        weapon.damage?.let { d ->
            weaponDamage.putIfAbsent(weapon, d.damage)
            d.damage = 0f
        }
        val bd = beam?.damage ?: return
        beamDamage.putIfAbsent(beam, bd.damage)
        bd.damage = 0f
    }

    /** 武器被压制前的原始面板伤害；未压制时返回 null。 */
    fun originalDamageOf(weapon: WeaponAPI): Float? = weaponDamage[weapon]

    fun restore(weapon: WeaponAPI) {
        weaponDamage.remove(weapon)?.let { saved -> weapon.damage?.damage = saved }
        val it = beamDamage.entries.iterator()
        while (it.hasNext()) {
            val (beam, saved) = it.next()
            if (beam.weapon !== weapon) continue
            beam.damage?.damage = saved
            it.remove()
        }
    }
}
