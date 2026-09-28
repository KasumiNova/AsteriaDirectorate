package cn.kasuminova.astd.combat.automation.api

import cn.kasuminova.astd.renderer.projectile.driver.ProjectileVfxTelemetrySnapshot

/**
 * 单个战斗自动化场景的处理器接口（注册式扩展点）。
 *
 * 新武器/系统接入烟测：新增一个实现类并在
 * [AutomationScenarioRegistry] 实现中登记一行即可，枢纽主干无需改动。
 *
 * 生命周期（每战斗一次，处理器实例随插件实例创建）：
 * 1. [isEnabled] 系统属性门控命中后由注册表选为活跃场景；
 * 2. [init] 在引擎 init 阶段调用（渲染器未就绪，reserves 部署一律推迟到 [advance]）；
 * 3. [advance] 每帧调用，主干已按 [pausePolicy] 推进 [AutomationCombatContext.elapsed]；
 * 4. [renderCapture] 在 renderInUICoords 阶段调用（截图只能在渲染帧取 framebuffer）；
 * 5. [appendDiagnostics] 在每次诊断 JSON 写出时调用，追加本场景证据字段。
 */
interface AutomationScenario {

    /** 场景 ID（系统属性 ssoptimizer.automation.scenario 与 scenarios.json 登记口径）。 */
    val scenarioId: String

    /** advance 分发前的暂停策略（历史分支语义，逐场景固定）。 */
    val pausePolicy: PausePolicy

    /** 场景启用判定：automation 总开关且场景属性等于 [scenarioId]。 */
    fun isEnabled(): Boolean

    /**
     * 引擎 init 钩子：关战斗结束、锁相机、必要时开 devMode，并写出 CombatReady 首帧证据。
     * 不得在此时部署 reserves（渲染器未就绪，spawnFleetMember 会触发原版渲染 NPE）。
     */
    fun init(ctx: AutomationCombatContext)

    /** 每帧推进：部署钉位、相位机、断言与节流写出。 */
    fun advance(ctx: AutomationCombatContext, amount: Float)

    /**
     * 完成帧截图捕获（renderInUICoords 阶段）。
     *
     * 默认实现走坠星残响范式捕获（[AutomationCombatContext.starfallEcho]），
     * 与拆分前「无专属捕获分支的场景落入默认块」的行为一致；有专属取景的场景覆写本方法。
     */
    fun renderCapture(ctx: AutomationCombatContext) {
        ctx.starfallEcho.renderDefaultCapture(ctx)
    }

    /**
     * 断言快照：向诊断 JSON 追加本场景证据字段（不含头尾公共字段，键名/口径一律不动）。
     *
     * @param json 诊断 JSON 构造器（已写入公共头字段，追加后由主干补尾部字段收尾）
     * @param vfxTelemetry 弹体 VFX 驱动遥测快照（runtimeTrackedCount 等公共读数）
     */
    fun appendDiagnostics(ctx: AutomationCombatContext, json: StringBuilder, vfxTelemetry: ProjectileVfxTelemetrySnapshot)
}

/** advance 分发前的暂停处理策略（拆分前三条分支的忠实建模）。 */
enum class PausePolicy {
    /** 常规场景：强制解除暂停后推进墙钟。 */
    FORCE_UNPAUSE,

    /** 暂停对照语义场景（grg/tpp）：不动暂停标志，墙钟照常推进（暂停期 advance 仍按真实 amount 调用）。 */
    KEEP_PAUSED,

    /** xc_001 默认分支：暂停时直接跳过本帧（不累加墙钟）。 */
    SKIP_WHEN_PAUSED,
}
