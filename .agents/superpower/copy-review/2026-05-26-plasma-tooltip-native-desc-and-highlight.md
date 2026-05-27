审查文件：ss-csv/src/main/resources/i18n/zh-cn.properties; contents/data/hullmods/hull_mods.csv; src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcProductionTooltipContracts.kt; src/main/kotlin/cn/kasuminova/astd/combat/hullmods/base/ASTDHullModTooltipRenderer.kt
审查时间：2026-05-26 23:00
文本分类：hullmod tooltip

R1  pass — 本轮移除 CSV 原生 desc，避免游戏自动描述与自定义 tooltip 重复。
R2  pass — 未新增正文句式，仅恢复导出结构中的高亮表现。
R3  pass — 保留等离子装甲护盾、离子化反冲蓄能器等世界内术语。
R4  pass with exception — 高亮对象包含机制数值，属于 tooltip UI 层数值展示。
R5  pass — 玩家可见正文仍来自 strings.json / ss-csv i18n，运行时代码只保存 key 与颜色元数据。
R6  pass — 颜色元数据恢复工具导出的橙色势力标记与黄色机制高亮。
R7  pass — 移除顶部重复文本后，tooltip 信息层级更清晰。
R8  pass — 未引入“不是……而是……”句式。
R9  pass — 未引入公式化过渡句。
R11 pass — 两个 plasma_arch 独特 hullmod 的技术说明口径一致。

违规项改写建议：
  无。

结论：通过。
