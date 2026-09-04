package cn.kasuminova.astd.api.combat

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * HUD / 浮字反馈通道：机制可视化铁律的统一落点（规格 00-共享基建 §4.2）。
 *
 * 动机：任何「叠层/自适应增伤/硬辐抬升/射程加成」数值变化必须在同帧至少触发一个玩家可见通道，
 * 缺反馈的机制视为未完工；把引擎的三个已核实入口（状态栏/伤害浮字/自定义浮字）收敛为一处，
 * 由实现统一做 0 值/空串防线与日志，调用方只负责业务语义与 i18n 文本。
 *
 * 命中特效（`spawnEmpArcVisual` / RenderEntity 管线）不在本通道内，由各自特效面承担。
 * 仅对 AI 生效的数值（玩家不可见）豁免 HUD，但保留命中粒子。
 *
 * 所有文本参数（title/desc/text）由调用方完成 i18n 解析后传入，本接口不做字符串表查找。
 */
interface CombatFeedback {
    /**
     * 左侧状态栏：为玩家船维持一个状态项（需每帧调用刷新）。
     * [key] 状态项唯一键；[icon] 图标贴图路径；[title]/[desc] 已 i18n 的标题与描述；
     * [negative] 为 true 时按负面效果样式显示（Buff 本身不分正负，正负语义在此表达）。
     */
    fun maintainPlayerStatus(
        engine: CombatEngineAPI,
        key: Any,
        icon: String,
        title: String,
        desc: String,
        negative: Boolean,
    )

    /**
     * 伤害浮字：在 [point] 处为 [target] 飘出 [amount] 数值（[source] 为伤害来源，可空）。
     * [amount] <= 0 或 NaN 属调用方错误：记 WARN 并跳过，禁止飘出无意义数值。
     */
    fun floatingDamage(
        engine: CombatEngineAPI,
        point: Vector2f,
        amount: Float,
        color: Color,
        target: CombatEntityAPI,
        source: CombatEntityAPI?,
    )

    /**
     * 自定义浮字：在 [point] 处飘出任意 [text]（已 i18n），[anchor] 为跟随实体（可空），
     * [flashFrequency]/[flashDuration] 控制闪烁。
     * [text] 空白属调用方错误：记 WARN 并跳过。
     */
    fun floatingText(
        engine: CombatEngineAPI,
        point: Vector2f,
        text: String,
        size: Float,
        color: Color,
        anchor: CombatEntityAPI?,
        flashFrequency: Float,
        flashDuration: Float,
    )
}
