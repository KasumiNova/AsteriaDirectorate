package cn.kasuminova.astd.sscsv.entries.catalog.weapondata.psi

import cn.kasuminova.astd.sscsv.entries.WeaponDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.weapondata.weaponName
import cn.kasuminova.astd.sscsv.i18n.SsI18n

object Wpn_astd_psi_omega : WeaponDataEntry() {
    override val id: String = "astd_psi_omega"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 50000

    override val range: Int = 1000

    override val damagePerSecond: Int = 1000
    override val damagePerShot: Int = 0

    override val turnRate: Int = 10

    override val ops: Int = 30

    override val type: String = "FRAGMENTATION"

    override val energyPerSecond: Int = 300
    override val energyPerShot: Int = 0

    override val chargeup: Double = 1.0
    override val chargedown: Double = 1.0

    override val beamSpeed: Int = 10000

    override val tags: String = "energy20, beam18, LR, restricted, codex_unlockable, astd_omega"
    override val groupTag: String = "astd"
    override val tech: String = "灵能链路"

    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")

    override val number: Int = 9021
}
