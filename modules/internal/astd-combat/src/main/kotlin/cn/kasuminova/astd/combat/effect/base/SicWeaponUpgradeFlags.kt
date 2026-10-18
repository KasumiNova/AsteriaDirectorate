package cn.kasuminova.astd.combat.effect.base

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * SIC 技能「武备升级」（astd-campaign `cn.kasuminova.astd.campaign.integration.sic.ArmamentUpgradeSkill`）
 * 写入的舰船级激活标记（docs/design/skills/10-sic-skills.md 阶段二）。
 *
 * 契约：该键由 astd-campaign 的 SIC 技能在 applyEffectsAfterShipCreation 中
 * `ship.setCustomData(ARMAMENT_UPGRADE_KEY, true)` 写入（技能生效即全舰队舰船打标）；
 * astd-combat 各武器特效只读本标记做分支，本对象不含任何 SIC/campaign 类引用，
 * astd-combat 不反向依赖 astd-campaign。
 *
 * 弹体链路统一从来源舰取标记（`projectile.source` / `beam.weapon.ship` 等）；
 * 经 FX 无人机中转的链路（双子星 DEM payload 光束的 beam.source 是 DEMScript 新建实体，
 * 弹头 customData 不传递到 drone）退化为 [isArmamentUpgradeActiveForOwner] 按归属方判定——
 * 技能对舰队全部舰船打标，同 owner 内任一舰船带标记即视为激活。
 */
object SicWeaponUpgradeFlags {

    /** 舰船 customData 键：存在即「武备升级」激活（值恒为 true，存在性判定）。 */
    const val ARMAMENT_UPGRADE_KEY = "astd_sic_armament_upgrade"

    /** 舰船级激活判定：技能打标即 true；null 舰（游离弹等）按未激活处理。 */
    fun isArmamentUpgradeActive(ship: ShipAPI?): Boolean =
        ship?.customData?.containsKey(ARMAMENT_UPGRADE_KEY) == true

    /**
     * 归属方级激活判定（FX 无人机中转链路用）：同 [owner] 的任一在场舰船带标记即激活。
     * 技能语义为「舰队中所有舰船」，归属方粒度与舰船粒度等价。
     */
    fun isArmamentUpgradeActiveForOwner(engine: CombatEngineAPI, owner: Int): Boolean =
        engine.ships.any { it.owner == owner && isArmamentUpgradeActive(it) }
}
