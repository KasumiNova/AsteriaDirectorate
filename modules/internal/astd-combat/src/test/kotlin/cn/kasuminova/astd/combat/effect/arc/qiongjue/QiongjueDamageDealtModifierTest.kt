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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * “穷距”逐命中伤害乘区通道（QiongjueDamageDealtModifier）逻辑验证（全部调用真实逻辑）。
 *
 * 背景：同舰同 spec 武器共享 `weapon.damage.modifier` 底层 stat（05 烟测实证），
 * 伤害乘区必须走逐命中 DamageAPI——本类验证该通道的过滤矩阵与乘区数值。
 */
class QiongjueDamageDealtModifierTest {

    @BeforeTest
    fun installBuffBackend() {
        // buffHost() 扩展走 api 侧 BuffBackends 桥：测试环境装真后端（引擎为 null 时后端跳过心跳登记）。
        BuffInstall.install()
    }

    /** 带真实叠层 Buff 的宿主：stub 船 + 真 BuffHost 登记真 QiongjueCalcStacks（不 advance，仅读层数）。 */
    private fun hostWithStacks(stacks: Int, owner: Int): Triple<com.fs.starfarer.api.combat.ShipAPI, com.fs.starfarer.api.combat.WeaponAPI, QiongjueCalcStacks> {
        val weapon = stubWeapon("WS 012", QiongjuePhaseRailgunDifficulty.WEAPON_ID)
        val ship = stubShip(weapons = listOf(weapon))
        `when`(ship.owner).thenReturn(owner)
        val engine = mock(CombatEngineAPI::class.java)
        val buff = QiongjueCalcStacks(ship, weapon, engine)
        if (stacks > 0) buff.addStacks(stacks)
        ship.buffHost().register(buff, weapon)
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
        assertEquals(600f * 1.25f, stat.modifiedValue, 0.5f, "4 层 × v2 6.25% → ×1.25")
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
            assertEquals(600f * 1.2f, stat.modifiedValue, 0.5f, "4 层 × v1 5% → ×1.2")
        } finally {
            DifficultyTuningImpl.installScaleForTests(null)
        }
    }

    @Test
    fun `零层与无 Buff 放行不写乘区`() {
        val (ship, weapon, _) = hostWithStacks(stacks = 0, owner = 0)
        val (damage, stat) = damageOf(600f)
        // 零层：Buff 存在但 stacks=0 → null。
        assertNull(
            QiongjueDamageDealtModifier().modifyDamageDealt(projectileOf(ship, weapon), null, damage, null, false),
            "零层不得写伤害乘区",
        )
        assertEquals(600f, stat.modifiedValue, 0.001f)

        // 无 Buff：另一艘船未登记 → null。
        val lonelyWeapon = stubWeapon("WS 012", QiongjuePhaseRailgunDifficulty.WEAPON_ID)
        val lonely = stubShip(weapons = listOf(lonelyWeapon))
        `when`(lonely.owner).thenReturn(0)
        assertNull(
            QiongjueDamageDealtModifier().modifyDamageDealt(projectileOf(lonely, lonelyWeapon), null, damage, null, false),
            "无 Buff 不得写伤害乘区",
        )
    }

    @Test
    fun `非穷距弹体与非弹体来源放行`() {
        val (ship, _, _) = hostWithStacks(stacks = 10, owner = 0)
        val (damage, stat) = damageOf(600f)
        // 其他武器 id。
        val otherWeapon = stubWeapon("WS 001", "astd_aod7")
        `when`(ship.allWeapons).thenReturn(listOf(otherWeapon))
        assertNull(
            QiongjueDamageDealtModifier().modifyDamageDealt(projectileOf(ship, otherWeapon), null, damage, null, false),
            "非穷距武器弹体不得写乘区",
        )
        // param 非弹体（beam 等）。
        assertNull(
            QiongjueDamageDealtModifier().modifyDamageDealt("not-a-projectile", null, damage, null, false),
            "非弹体来源不得写乘区",
        )
        assertEquals(600f, stat.modifiedValue, 0.001f)
    }

    @Test
    fun `双穷距逐命中 DamageAPI 天然隔离`() {
        // 机制级回归：同舰双穷距共享 weapon.damage.modifier stat（烟测实证），逐命中通道下
        // 各发弹体 DamageAPI 独立——w1 满层写入不影响 w2 零层放行。
        val w1 = stubWeapon("WS 012", QiongjuePhaseRailgunDifficulty.WEAPON_ID)
        val w2 = stubWeapon("WS 013", QiongjuePhaseRailgunDifficulty.WEAPON_ID)
        val ship = stubShip(weapons = listOf(w1, w2))
        `when`(ship.owner).thenReturn(0)
        val engine = mock(CombatEngineAPI::class.java)
        val buff1 = QiongjueCalcStacks(ship, w1, engine)
        buff1.addStacks(10)
        ship.buffHost().register(buff1, w1)
        ship.buffHost().register(QiongjueCalcStacks(ship, w2, engine), w2)

        val (damage1, stat1) = damageOf(600f)
        val (damage2, stat2) = damageOf(600f)
        val listener = QiongjueDamageDealtModifier()
        listener.modifyDamageDealt(projectileOf(ship, w1), null, damage1, null, false)
        listener.modifyDamageDealt(projectileOf(ship, w2), null, damage2, null, false)
        assertEquals(600f * 1.625f, stat1.modifiedValue, 0.5f, "w1 满层 → ×1.625")
        assertEquals(600f, stat2.modifiedValue, 0.001f, "w2 零层不受 w1 污染（共享 stat 场景下会被 ×1.625）")
        assertTrue(stat1 !== stat2, "逐命中 DamageAPI 实例独立（测试结构前提）")
    }
}
