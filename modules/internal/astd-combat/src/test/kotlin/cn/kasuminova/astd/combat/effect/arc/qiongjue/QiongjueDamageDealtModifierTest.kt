package cn.kasuminova.astd.combat.effect.arc.qiongjue

import cn.kasuminova.astd.api.buff.buffHost
import cn.kasuminova.astd.impl.buff.BuffInstall
import cn.kasuminova.astd.impl.buff.stubShip
import cn.kasuminova.astd.impl.buff.stubWeapon
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MutableStat
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QiongjueDamageDealtModifierTest {

    @BeforeTest
    fun installBuffBackend() {
        BuffInstall.install()
    }

    private fun hostWithStacks(
        stacks: Int,
        owner: Int
    ): Triple<com.fs.starfarer.api.combat.ShipAPI, com.fs.starfarer.api.combat.WeaponAPI, QiongjueCalcStacks> {
        val weapon = stubWeapon("WS 012", QiongjuePhaseRailgunDifficulty.WEAPON_ID)
        val ship = stubShip(weapons = listOf(weapon))
        `when`(ship.owner).thenReturn(owner)
        val engine = mock(CombatEngineAPI::class.java)
        val host = ship.buffHost()
        val buff = QiongjueCalcStacks(ship, engine, host)
        if (stacks > 0) buff.addStacks(stacks)
        host.register(buff)
        return Triple(ship, weapon, buff)
    }

    private fun projectileOf(ship: com.fs.starfarer.api.combat.ShipAPI, weapon: com.fs.starfarer.api.combat.WeaponAPI): DamagingProjectileAPI {
        val projectile = mock(DamagingProjectileAPI::class.java)
        `when`(projectile.weapon).thenReturn(weapon)
        `when`(projectile.source).thenReturn(ship)
        return projectile
    }

    private fun damageOf(base: Float): Pair<DamageAPI, MutableStat> {
        val stat = MutableStat(base)
        val damage = mock(DamageAPI::class.java)
        `when`(damage.modifier).thenReturn(stat)
        return damage to stat
    }

    @Test
    fun `玩家四层命中写入 v2 乘区`() {
        val (ship, weapon, _) = hostWithStacks(stacks = 4, owner = 0)
        val (damage, stat) = damageOf(600f)
        val result = QiongjueDamageDealtModifier().modifyDamageDealt(
            projectileOf(ship, weapon), null, damage, null, false,
        )
        assertEquals(QiongjueDamageDealtModifier.MOD_ID, result, "有层数穷距命中必须登记 stat 来源 id")
        assertEquals(600f * 1.20f, stat.modifiedValue, 0.5f, "4 层 × v2 5% → ×1.20")
    }

    @Test
    fun `敌版四层命中走 v1 乘区`() {
        val (ship, weapon, _) = hostWithStacks(stacks = 4, owner = 1)
        val (damage, stat) = damageOf(600f)
        DifficultyTuningImpl.installScaleForTests(1f)
        try {
            val result = QiongjueDamageDealtModifier().modifyDamageDealt(
                projectileOf(ship, weapon), null, damage, null, false,
            )
            assertEquals(QiongjueDamageDealtModifier.MOD_ID, result)
            assertEquals(600f * 1.10f, stat.modifiedValue, 0.5f, "4 层 × v1 2.5% → ×1.10")
        } finally {
            DifficultyTuningImpl.installScaleForTests(null)
        }
    }

    @Test
    fun `零层与无 Buff 放行不写乘区`() {
        val (ship, weapon, _) = hostWithStacks(stacks = 0, owner = 0)
        val (damage, stat) = damageOf(600f)
        assertNull(
            QiongjueDamageDealtModifier().modifyDamageDealt(projectileOf(ship, weapon), null, damage, null, false),
            "零层不得写伤害乘区",
        )
        assertEquals(600f, stat.modifiedValue, 0.001f)

        val lonelyWeapon = stubWeapon("WS 012", QiongjuePhaseRailgunDifficulty.WEAPON_ID)
        val lonely = stubShip(weapons = listOf(lonelyWeapon))
        `when`(lonely.owner).thenReturn(0)
        assertNull(
            QiongjueDamageDealtModifier().modifyDamageDealt(projectileOf(lonely, lonelyWeapon), null, damage, null, false),
            "无 Buff 不得写乘区",
        )
    }

    @Test
    fun `非穷距弹体与非弹体来源放行`() {
        val (ship, _, _) = hostWithStacks(stacks = 10, owner = 0)
        val (damage, stat) = damageOf(600f)
        val otherWeapon = stubWeapon("WS 001", "astd_starfall_echo")
        assertNull(
            QiongjueDamageDealtModifier().modifyDamageDealt(projectileOf(ship, otherWeapon), null, damage, null, false),
            "非穷距武器弹体不得写乘区",
        )
        assertNull(
            QiongjueDamageDealtModifier().modifyDamageDealt("not-a-projectile", null, damage, null, false),
            "非弹体来源不得写乘区",
        )
        assertEquals(600f, stat.modifiedValue, 0.001f)
    }

    @Test
    fun `双穷距共享同一份 Ship 级叠层`() {
        val w1 = stubWeapon("WS 012", QiongjuePhaseRailgunDifficulty.WEAPON_ID)
        val w2 = stubWeapon("WS 013", QiongjuePhaseRailgunDifficulty.WEAPON_ID)
        val ship = stubShip(weapons = listOf(w1, w2))
        `when`(ship.owner).thenReturn(0)
        val engine = mock(CombatEngineAPI::class.java)
        val host = ship.buffHost()
        val buff = QiongjueCalcStacks(ship, engine, host)
        buff.addStacks(10)
        host.register(buff)

        val (damage1, stat1) = damageOf(600f)
        val (damage2, stat2) = damageOf(600f)
        val listener = QiongjueDamageDealtModifier()
        listener.modifyDamageDealt(projectileOf(ship, w1), null, damage1, null, false)
        listener.modifyDamageDealt(projectileOf(ship, w2), null, damage2, null, false)
        assertEquals(600f * 1.5f, stat1.modifiedValue, 0.5f, "w1 满层 → ×1.5")
        assertEquals(600f * 1.5f, stat2.modifiedValue, 0.5f, "w2 共享同一 Buff 满层 → ×1.5")
    }
}
