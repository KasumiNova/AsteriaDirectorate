package cn.kasuminova.astd.api.render

import com.fs.starfarer.api.combat.CombatEngineLayers

/**
 * 绘制层直接复用原版 [CombatEngineLayers]（设计 D3）。
 *
 * 动机：层的含义、取值与先后完全对齐游戏本体，免维护第二份枚举映射，也天然对齐 BoxUtil 的 setLayer
 * 与自定义 GL 插件的 active-layer 过滤。绘制序按枚举 ordinal 升序（低层先画、高层在上）。
 */
typealias RenderLayer = CombatEngineLayers
