package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals

class GravityCollapseDamageSuppressorTest {

    private class DamageValue(var value: Float)

    private fun stubDamage(initial: Float): Pair<DamageAPI, DamageValue> {
        val holder = DamageValue(initial)
        val damage = mock(DamageAPI::class.java)
        `when`(damage.damage).thenAnswer { holder.value }
        doAnswer { holder.value = it.getArgument(0); null }.`when`(damage).damage = anyFloat()
        return damage to holder
    }

    @Test
    fun `充能期间清零 weapon 与 beam 的独立 Damage，恢复时各自回到原值`() {
        val (weaponDamage, weaponValue) = stubDamage(400f)
        val (beamDamage, beamValue) = stubDamage(320f)
        val weapon = mock(WeaponAPI::class.java)
        `when`(weapon.damage).thenReturn(weaponDamage)
        val beam = mock(BeamAPI::class.java)
        `when`(beam.damage).thenReturn(beamDamage)
        `when`(beam.weapon).thenReturn(weapon)

        val suppressor = GravityCollapseDamageSuppressor()
        suppressor.suppress(weapon, beam)
        suppressor.suppress(weapon, beam)
        assertEquals(0f, weaponValue.value)
        assertEquals(0f, beamValue.value)

        suppressor.restore(weapon)
        assertEquals(400f, weaponValue.value)
        assertEquals(320f, beamValue.value)
    }

    @Test
    fun `beam 在充能中途才出现时也能恢复，且只恢复属于该武器的 beam`() {
        val (weaponDamage, weaponValue) = stubDamage(400f)
        val (beamDamage, beamValue) = stubDamage(320f)
        val (otherBeamDamage, otherBeamValue) = stubDamage(150f)
        val weapon = mock(WeaponAPI::class.java)
        `when`(weapon.damage).thenReturn(weaponDamage)
        val otherWeapon = mock(WeaponAPI::class.java)
        val beam = mock(BeamAPI::class.java)
        `when`(beam.damage).thenReturn(beamDamage)
        `when`(beam.weapon).thenReturn(weapon)
        val otherBeam = mock(BeamAPI::class.java)
        `when`(otherBeam.damage).thenReturn(otherBeamDamage)
        `when`(otherBeam.weapon).thenReturn(otherWeapon)

        val suppressor = GravityCollapseDamageSuppressor()
        suppressor.suppress(weapon, null)
        suppressor.suppress(weapon, beam)
        suppressor.suppress(otherWeapon, otherBeam)

        suppressor.restore(weapon)
        assertEquals(400f, weaponValue.value)
        assertEquals(320f, beamValue.value)
        assertEquals(0f, otherBeamValue.value)

        suppressor.restore(otherWeapon)
        assertEquals(150f, otherBeamValue.value)
    }
}
