审查文件：ss-csv/src/main/resources/i18n/zh-cn.properties, contents/data/strings/strings.json, tools/exports/hullmod_tooltip_等离子装甲护盾.json, tools/exports/hullmod_tooltip_离子化反冲蓄能器.json
审查时间：2026-05-26 00:00
文本分类：ship system / hullmod tooltip

R1  ☑ pass — 系统描述保留效果文本职责，hullmod tooltip 表格继续承担具体数值说明。
R2  ☑ pass — 新增系统效果行句式直接，没有套话开头。
R3  ☑ pass — 文本使用舰船、护盾、装甲、辐能与电弧等世界观内部术语。
R4  ☑ pass — 数值位于系统效果和 tooltip 数值说明中，符合本轮机制同步要求。
R5  ☑ pass — 玩家可见文本仍由 ss-csv i18n、strings.json 与导出 JSON 驱动，未在 Kotlin 逻辑中硬编码中文。
R6  ☑ pass — 术语继续贴合弧光/等离子路线。
R7  ☑ pass — 大额护盾单次伤害、装甲计算值翻倍与反冲蓄能器翻倍拆成独立行。
R8  ☑ pass — 未发现“不是…而是…”类句式。
R9  ☑ pass — 未新增“通过…来实现…”等公式化过渡句。
R11 ☑ pass — 机制说明口径与等离子拱级现有 tooltip 风格一致。

违规项改写建议：
  无。

结论：☑ 全部通过（10/10）
