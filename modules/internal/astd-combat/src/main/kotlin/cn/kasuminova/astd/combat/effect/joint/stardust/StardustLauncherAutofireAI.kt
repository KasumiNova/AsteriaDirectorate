package cn.kasuminova.astd.combat.effect.joint.stardust

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.PluginPick
import com.fs.starfarer.api.campaign.CampaignPlugin.PickPriority
import com.fs.starfarer.api.combat.AutofireAIPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f

// 原版自动开火 AI 要求目标进入射界/射程才开火，舰尾 arc 0 槽位的发射器因此从不开火，本 AI 无条件开火。
// 备弹达到（满值 - REGEN_AMMO_HEADROOM）时无视存活光尘上限继续发射，让弹药自恢复持续转化为光尘而非溢出浪费。
class StardustLauncherAutofireAI(
    private val weapon: WeaponAPI,
) : AutofireAIPlugin {

    private var shouldFire = false

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        shouldFire = shouldFire(
            ammo = weapon.ammo,
            maxAmmo = weapon.maxAmmo,
            aliveMotes = countAliveMotes(engine.missiles),
        )
    }

    private fun countAliveMotes(missiles: List<MissileAPI>): Int {
        var count = 0
        for (missile in missiles) {
            if (missile.weapon !== weapon) continue
            val specId = missile.projectileSpecId ?: continue
            if (!StardustMoteIds.isStardustProj(specId)) continue
            if (missile.isExpired) continue
            count++
        }
        return count
    }

    override fun shouldFire(): Boolean = shouldFire

    override fun forceOff() {
        shouldFire = false
    }

    override fun getTarget(): Vector2f =
        MathUtils.getPointOnCircumference(weapon.location, TARGET_POINT_DISTANCE, weapon.currAngle)

    override fun getTargetShip(): ShipAPI? = null

    override fun getWeapon(): WeaponAPI = weapon

    override fun getTargetMissile(): MissileAPI? = null

    companion object {
        private const val TARGET_POINT_DISTANCE = 100f

        internal fun shouldFire(ammo: Int, maxAmmo: Int, aliveMotes: Int): Boolean {
            if (ammo <= 0) return false
            if (aliveMotes < StardustMoteTuning.MAX_ALIVE_MOTES_PER_WEAPON) return true
            return ammo >= (maxAmmo - StardustMoteTuning.REGEN_AMMO_HEADROOM).coerceAtLeast(1)
        }
    }
}

object StardustLauncherAutofireAiPicker {

    fun pick(weapon: WeaponAPI): PluginPick<AutofireAIPlugin>? {
        val weaponId = weapon.spec?.weaponId ?: return null
        if (weaponId != StardustMoteIds.WEAPON_ARC && weaponId != StardustMoteIds.WEAPON_LENS) return null
        return PluginPick(StardustLauncherAutofireAI(weapon), PickPriority.MOD_SPECIFIC)
    }
}
