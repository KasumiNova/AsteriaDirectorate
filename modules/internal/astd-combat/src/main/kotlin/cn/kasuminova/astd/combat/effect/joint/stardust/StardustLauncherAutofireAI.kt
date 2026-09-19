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

/**
 * 星尘发射器的自动开火 AI（`ModPlugin.pickWeaponAutofireAI` 覆盖钩子）。
 *
 * 动机：发射器内置于舰尾 arc 0 槽位（射界朝后），原版自动开火 AI 要求目标进入射界/射程
 * 才开火，AI 舰船永远不会把尾部对准目标，发射器形同虚设。光尘为全向自寻的 MOTE 弹体
 * （flocking 接敌，见 [StardustMoteAI]），不依赖射界，故本 AI **无条件开火**：
 * 弹药充足且本武器存活光尘未达上限 [StardustMoteTuning.MAX_ALIVE_MOTES_PER_WEAPON] 即持续发射。
 */
class StardustLauncherAutofireAI(
    private val weapon: WeaponAPI,
) : AutofireAIPlugin {

    private var shouldFire = false

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        shouldFire = weapon.ammo > 0 && countAliveMotes(engine.missiles) < StardustMoteTuning.MAX_ALIVE_MOTES_PER_WEAPON
    }

    /** 本武器已发射且仍在场的光尘数（上限管控口径：命过目标但未消亡的个体仍计入）。 */
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

    /** 瞄准占位：返回炮管当前指向的延长点（不改变炮口朝向，发射方向即舰尾朝向）。 */
    override fun getTarget(): Vector2f =
        MathUtils.getPointOnCircumference(weapon.location, TARGET_POINT_DISTANCE, weapon.currAngle)

    override fun getTargetShip(): ShipAPI? = null

    override fun getWeapon(): WeaponAPI = weapon

    override fun getTargetMissile(): MissileAPI? = null

    companion object {
        /** getTarget 占位点的延伸距离（su，仅语义占位，不参与任何弹道结算）。 */
        private const val TARGET_POINT_DISTANCE = 100f
    }
}

/** 星尘发射器自动开火 AI 的指派入口（与 [StardustMoteAiPicker] 同族，分别对应两个 ModPlugin 钩子）。 */
object StardustLauncherAutofireAiPicker {

    /** 武器 id 命中星尘发射器时返回专属自动开火 AI；否则返回 null 交回原版/其他模组。 */
    fun pick(weapon: WeaponAPI): PluginPick<AutofireAIPlugin>? {
        val weaponId = weapon.spec?.weaponId ?: return null
        if (weaponId != StardustMoteIds.WEAPON_ARC && weaponId != StardustMoteIds.WEAPON_LENS) return null
        return PluginPick(StardustLauncherAutofireAI(weapon), PickPriority.MOD_SPECIFIC)
    }
}
