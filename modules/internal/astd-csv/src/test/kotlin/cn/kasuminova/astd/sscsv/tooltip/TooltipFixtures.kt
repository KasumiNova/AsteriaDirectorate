package cn.kasuminova.astd.sscsv.tooltip

import cn.kasuminova.astd.sscsv.entries.DescriptionEntry
import cn.kasuminova.astd.sscsv.entries.ShipSystemEntry
import cn.kasuminova.astd.sscsv.entries.WeaponDataEntry

internal const val TEST_WEAPON_ID = "astd_starfall_wing"
internal const val TEST_SYSTEM_ID = "astd_grav_rift_generator"

internal fun weaponStrings(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
    put("dynamic.weapons", TEST_WEAPON_ID)
    put(
        "weapon.$TEST_WEAPON_ID.tooltip.customPrimary",
        "命中时额外附加目标最大船体值 {%s} 的高爆伤害。",
    )
    put("weapon.$TEST_WEAPON_ID.tooltip.customPrimaryHL", "%redHullDamage%")
    put("tooltip.weapon.$TEST_WEAPON_ID.default.redHullDamage", "2%")
    putAll(extra)
}

internal fun systemStrings(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
    put("dynamic.systems", TEST_SYSTEM_ID)
    put("desc.$TEST_SYSTEM_ID.text1", "启动后获得 {{%damageReduction%}} 固定减伤。")
    put("desc.$TEST_SYSTEM_ID.text2", "防御")
    put("desc.$TEST_SYSTEM_ID.text3", "装配：获得 {{%damageReduction%}} 固定减伤。")
    put("desc.$TEST_SYSTEM_ID.text4", "固定减伤 {{%damageReduction%}}")
    put("desc.$TEST_SYSTEM_ID.text5", "系统持续期间获得 {{%damageReduction%}} 固定减伤。")
    put("tooltip.system.$TEST_SYSTEM_ID.default.damageReduction", "40%")
    putAll(extra)
}

internal object TestWeaponEntry : WeaponDataEntry() {
    override val name: String = "坠星残翼"
    override val id: String = TEST_WEAPON_ID
}

internal object TestSystemEntry : ShipSystemEntry() {
    override val name: String = "引力裂隙发生器"
    override val id: String = TEST_SYSTEM_ID
}

internal object TestSystemDescription : DescriptionEntry() {
    override val id: String = TEST_SYSTEM_ID
    override val type: String = "SHIP_SYSTEM"
}
