package cn.kasuminova.astd.sscsv.entries.catalog.weapondata.joint

import cn.kasuminova.astd.sscsv.entries.WeaponDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.weapondata.weaponName

/** 联制线（LH）武器数据。 */

/** 锻萼：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_lh_001_bloom : WeaponDataEntry() {
    override val id: String = "astd_lh_001_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-星坠"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9133
}

/** 飞星：整船 bloom 描边层（装配界面/战斗 decorative outline）。 */
object Wpn_astd_lh_002_bloom : WeaponDataEntry() {
    override val id: String = "astd_lh_002_bloom"
    override val name: String = weaponName(id)
    override val tier: Int = 5
    override val baseValue: Int = 0
    override val range: Int = 0
    override val turnRate: Int = 0
    override val type: String = "OTHER"
    override val tags: String = "no_drop, no_drop_salvage"
    override val tech: String = "菀星设计局-紫菀"
    override val noDpsInTooltip: Boolean = true
    override val number: Int = 9134
}
