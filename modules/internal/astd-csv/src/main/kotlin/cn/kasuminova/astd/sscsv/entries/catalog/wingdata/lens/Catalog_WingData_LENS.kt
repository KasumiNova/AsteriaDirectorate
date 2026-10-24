package cn.kasuminova.astd.sscsv.entries.catalog.wingdata.lens

import cn.kasuminova.astd.sscsv.entries.WingDataEntry
import cn.kasuminova.astd.sscsv.entries.catalog.wingdata.wingRoleDesc

object Wing_astd_conjugate_terminal_wing : WingDataEntry() {
    override val id: String = "astd_conjugate_terminal_wing"
    override val variant: String = "astd_conjugate_terminal"
    override val tags: String = "no_drop, no_sell, auto_fighter, restricted, hide_in_codex"
    override val tier: Int = 3
    override val fleetPts: Int = 6
    override val formation: String = "BOX"
    override val attackRunRange: Int = 500
    override val num: Int = 2
    override val role: String = "SUPPORT"
    override val roleDesc: String = wingRoleDesc(id)
    override val refit: Int = 8
    override val baseValue: Int = 6000
    override val number: Int = 9127
}

object Wing_astd_zw_103_wing : WingDataEntry() {
    override val id: String = "astd_zw_103_wing"
    override val variant: String = "astd_zw_103_fighter_Fighter"
    override val tags: String = "no_drop, no_sell, auto_fighter, restricted, hide_in_codex"
    override val tier: Int = 3
    override val fleetPts: Int = 6
    override val formation: String = "V"
    override val range: Int = 1000
    override val attackRunRange: Int = 500
    override val num: Int = 1
    override val role: String = "FIGHTER"
    override val roleDesc: String = wingRoleDesc(id)
    override val refit: Int = 15
    override val baseValue: Int = 6000
    override val number: Int = 9128
}

object Wing_astd_gemini_bomber_wing : WingDataEntry() {
    override val id: String = "astd_gemini_bomber_wing"
    override val variant: String = "astd_gemini_bomber_Bomber"
    override val tags: String = "auto_fighter, bomber5, bomber, astd_production"
    override val tier: Int = 2
    override val fleetPts: Int = 8
    override val opCost: Int = 20
    override val formation: String = "V"
    override val range: Int = 4000
    override val attackRunRange: Int = 2000
    override val attackPositionOffset: Int = 600
    override val num: Int = 1
    override val role: String = "BOMBER"
    override val roleDesc: String = wingRoleDesc(id)
    override val refit: Int = 20
    override val baseValue: Int = 12000
    override val number: Int = 9133
}

object Wing_astd_surge_fighter_wing : WingDataEntry() {
    override val id: String = "astd_surge_fighter_wing"
    override val variant: String = "astd_surge_fighter_Fighter"
    override val tags: String = "auto_fighter, fighter4, fighter, astd_production"
    override val tier: Int = 2
    override val fleetPts: Int = 5
    override val opCost: Int = 16
    override val formation: String = "V"
    override val range: Int = 4000
    override val attackRunRange: Int = 500
    override val num: Int = 2
    override val role: String = "FIGHTER"
    override val roleDesc: String = wingRoleDesc(id)
    override val refit: Int = 12
    override val baseValue: Int = 8000
    override val number: Int = 9134
}

object Wing_astd_gravwell_interceptor_wing : WingDataEntry() {
    override val id: String = "astd_gravwell_interceptor_wing"
    override val variant: String = "astd_gravwell_interceptor_Interceptor"
    override val tags: String = "auto_fighter, support3, fighter, astd_production"
    override val tier: Int = 2
    override val fleetPts: Int = 5
    override val opCost: Int = 15
    override val formation: String = "V"
    override val range: Int = 2000
    override val attackRunRange: Int = 200
    override val attackPositionOffset: Int = 600
    override val num: Int = 2
    override val role: String = "SUPPORT"
    override val roleDesc: String = wingRoleDesc(id)
    override val refit: Int = 12
    override val baseValue: Int = 7500
    override val number: Int = 9135
}
