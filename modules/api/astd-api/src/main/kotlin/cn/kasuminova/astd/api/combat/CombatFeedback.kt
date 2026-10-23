package cn.kasuminova.astd.api.combat

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 向玩家显示状态栏项目与浮字。
 *
 * 所有文本必须由调用方完成本地化。所有方法仅在战斗主线程调用，并直接修改战斗界面。
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
