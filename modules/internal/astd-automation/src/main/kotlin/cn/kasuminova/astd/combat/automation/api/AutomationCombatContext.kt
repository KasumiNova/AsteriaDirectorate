package cn.kasuminova.astd.combat.automation.api

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.apache.log4j.Logger

/**
 * 战斗自动化场景的运行上下文：主干（ASTDAutomationCombatPlugin）向场景处理器开放的全部协作面。
 *
 * 主干按场景注册的暂停策略推进墙钟 [elapsed]，再调用场景处理器的生命周期钩子；
 * 处理器经本接口读写共享进程状态（完成标记、失败原因、写出节流锚点），并触发证据写出。
 *
 * 实现由主干担当（一次战斗一个实例，所有状态均为战斗级生命周期，不做跨战斗复用）。
 */
interface AutomationCombatContext {

    /** 当前战斗引擎（init 注入后不变）。 */
    val engine: CombatEngineAPI

    /** 共享日志器：类目固定为枢纽类，保证 starsector.log 行格式与拆分前一致。 */
    val log: Logger

    /**
     * 战斗墙钟（秒）。主干在 advance 分发前按场景暂停策略累加；
     * 相位机/超时判定全部以此为准（含暂停期继续推进的探针场景）。
     */
    var elapsed: Float

    /** 首个失败原因，写入诊断 JSON 的 failureReason 字段；null 表示未失败。 */
    var failureReason: String?

    /** 场景是否已判定完成（截图捕获门控与重复上报去重依据）。 */
    val completed: Boolean

    /** 完成时刻（[elapsed] 口径），未完成为 -1。 */
    val completedAt: Float

    /** 判定场景完成：置位 completed 并以当前 [elapsed] 记录完成时刻。 */
    fun markCompleted()

    /** 遥测/诊断写出节流锚点（[elapsed] 口径），处理器写出前自判并回写。 */
    var lastWriteAt: Float

    /** 已写出的完成截图帧数（0..3），renderInUICoords 捕获阶段递增。 */
    var visualFramesWritten: Int

    /** 上一捕获帧的 [elapsed] 时刻，捕获帧间隔节流依据。 */
    var lastVisualFrameAt: Float

    /** 坠星残响取景舞台（xc_001 默认场景与 trail_pause_probe 探针共享；诊断 JSON 尾部亦读其兜底弹体状态）。 */
    val starfallEcho: StarfallEchoStageAccess

    /**
     * 写出诊断 JSON 日志行（断言快照）。
     *
     * [ship] 传 null 时按 xc_001 范式解析默认船（历史默认参数行为；
     * 现有调用点传 null 与解析结果一致，因为同帧同战斗下解析结果同样为 null）。
     */
    fun writeDiagnostics(state: String, ship: ShipAPI? = null)

    /**
     * 遥测写出钩子（SSOptimizer ASM 注入点入口，脚本侧为空实现）。
     *
     * 签名对齐注入目标方法的语义参数：state/ship/weapon；引擎由上下文提供。
     */
    fun writeTelemetry(state: String, ship: ShipAPI?, weapon: WeaponAPI?)
}
