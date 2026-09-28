package cn.kasuminova.astd.combat.automation.api

/**
 * 战斗自动化场景注册表：枢纽主干与场景处理器之间的唯一接线点。
 *
 * 场景谓词互斥（场景系统属性单值），[active] 语义为「首个启用的场景，否则默认场景」。
 * 实现位于内部包，每战斗实例化一次（处理器持有战斗级相位机状态，不得跨战斗复用）。
 */
interface AutomationScenarioRegistry {

    /** 全部已登记场景处理器（含默认场景），顺序与拆分前 if-else 链一致。 */
    val handlers: List<AutomationScenario>

    /** 默认场景处理器（xc_001 坠星残响；无任何场景启用时仍由它驱动 advance/render 舞台）。 */
    val defaultHandler: AutomationScenario

    /** 活跃场景：首个 [AutomationScenario.isEnabled] 为 true 的处理器，否则 [defaultHandler]。 */
    fun active(): AutomationScenario = handlers.firstOrNull { it.isEnabled() } ?: defaultHandler

    /** 诊断写出门卫：任一场景启用才落盘（拆分前 writeDiagnostics 的全谓词守卫）。 */
    fun anyEnabled(): Boolean = handlers.any { it.isEnabled() }
}
