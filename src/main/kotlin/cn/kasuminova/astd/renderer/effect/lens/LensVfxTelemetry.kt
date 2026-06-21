package cn.kasuminova.astd.renderer.effect.lens

import com.fs.starfarer.api.combat.CombatEngineAPI

/**
 * 引力透镜级 shader 视觉效果的「提交计数」遥测（Task 12 实机自动化用）。
 *
 * 动机：阶段二实机集成测试要验证「shader effect 被真实提交即证明视觉管线生效」——自动化只能验证
 * 提交计数，像素外观留人工。lens 的五个 shader effect（回声定影场 / 误差标记高光 / 深水标记高光 /
 * 幽灵信号波 / 渗透潮汐场）原本没有提交计数器，故新增本对象统计每个 effect 每帧成功 upsert 的次数。
 *
 * 方案选型（对照 Task 12 计划 A/B）：采用**方案 B**（customData 单调累加计数器），与既有范式
 * [cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfx] 及
 * [EchoFixationAfterimageRenderer.afterimageFrames] 完全一致。不采用方案 A
 * （[cn.kasuminova.astd.renderer.shader.runtime.CombatShaderRuntime.snapshotsForTests]）的原因：
 * 那是 `internal` 的测试专用快照 API、按 layer 而非按 effect-id 聚合、且只反映「当前帧活跃实例」而非
 * 「累计提交次数」；用它做实机断言既不符合其设计意图，也无法证明「提交曾经发生过」（活跃实例会随
 * staleAfter 退休归零，观测点很晚时可能读到 0）。单调累加计数器才是「提交即证据」的正确载体。
 *
 * 计数语义：仅在 submitFrame **返回非 null handle**（即真正向 shader runtime 提交了一帧）时 +1。
 * 因此计数 >0 严格等价于「该 effect 至少向渲染管线提交过一帧」，不会因 alpha 归零早退等空提交虚增。
 * 绝不在提交失败/早退路径计数——计数必须来自真实提交（Fail Fast，不伪造证据）。
 *
 * 键约定：与 ASTDArcProductionVfx 同风格，`TELEMETRY_PREFIX + key` 存入 `engine.customData`。
 * 外部（验证脚本经 diagnostics JSON）读取请走 [counter]，勿直读 customData 键——键名是实现细节。
 */
internal object LensVfxTelemetry {

    /** 回声定影场边界 shader（[EchoFixationFieldVisualEffect]）成功提交帧数。 */
    const val TELEMETRY_ECHO_FIXATION_FIELD_FRAMES = "echoFixationFieldVisualFrames"

    /** 误差标记高光 shader（[DriftMarkVisualEffect]）成功提交帧数。 */
    const val TELEMETRY_DRIFT_MARK_FRAMES = "driftMarkVisualFrames"

    /** 深水标记高光 shader（[DeepWaterMarkVisualEffect]）成功提交帧数。 */
    const val TELEMETRY_DEEP_WATER_MARK_FRAMES = "deepWaterMarkVisualFrames"

    /** 幽灵信号波 shader（[GhostSignalWaveEffect]，仅无人模式触发）成功提交帧数。 */
    const val TELEMETRY_GHOST_SIGNAL_WAVE_FRAMES = "ghostSignalWaveFrames"

    /** 渗透潮汐场 shader（[PermeatingTideFieldEffect]）成功提交帧数。 */
    const val TELEMETRY_TIDE_FIELD_FRAMES = "tideFieldVisualFrames"

    /** engine.customData 键前缀（与 ASTDArcProductionVfx 同风格，命名空间隔离 lens 遥测）。 */
    private const val TELEMETRY_PREFIX = "astd_lens_vfx:"

    /**
     * 提交计数 +1（engine.customData 单调累加）。
     * 调用方须在 submitFrame 返回非 null（真实提交）后才调用本方法，见类注释「计数语义」。
     */
    fun incrementCounter(engine: CombatEngineAPI, key: String, amount: Int = 1) {
        val current = engine.customData["$TELEMETRY_PREFIX$key"] as? Int ?: 0
        engine.customData["$TELEMETRY_PREFIX$key"] = current + amount
    }

    /** 读取某 effect 的累计提交帧数（供 Task 12 诊断 JSON 拼装与验证脚本断言 >0）。 */
    fun counter(engine: CombatEngineAPI, key: String): Int =
        engine.customData["$TELEMETRY_PREFIX$key"] as? Int ?: 0
}
