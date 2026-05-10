package cn.kasuminova.astd.sscsv.entries.catalog.weapondata.psi

import cn.kasuminova.astd.sscsv.entries.WeaponDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.weapondata.weaponName
import cn.kasuminova.astd.sscsv.i18n.SsI18n

/** PSI 系武器（weapon_data.csv）。 */

object Wpn_astd_psi_omega : WeaponDataEntry() {
    override val id: String = "astd_psi_omega"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 50000

    override val range: Int = 1000

    // Beam：DPS 生效，damage/shot 需留空
    override val damagePerSecond: Int = 1000
    override val damagePerShot: Int = 0

    // 转向慢：用于压制持续照射
    override val turnRate: Int = 10

    override val ops: Int = 30

    // Beam 的伤害类型在 weapon_data.csv 的 type 列展示
    override val type: String = "FRAGMENTATION"

    // Beam：energy/sec 生效，energy/shot 需留空
    override val energyPerSecond: Int = 300
    override val energyPerShot: Int = 0

    // 前摇/冷却：均为 1s
    override val chargeup: Double = 1.0
    override val chargedown: Double = 1.0

    // Beam：必须给出足够大的 beam speed，否则光束可能无法正确延伸/结算。
    override val beamSpeed: Int = 10000

    override val tags: String = "astd_omega"
    override val groupTag: String = "astd"
    override val tech: String = "灵能链路"

    override val primaryRoleStr: String = SsI18n.t("weapon.$id.primaryRoleStr")
    override val customPrimary: String = SsI18n.t("weapon.$id.tooltip.customPrimary")
    override val customPrimaryHL: String = SsI18n.t("weapon.$id.tooltip.customPrimaryHL")

    override val number: Int = 9021
}
