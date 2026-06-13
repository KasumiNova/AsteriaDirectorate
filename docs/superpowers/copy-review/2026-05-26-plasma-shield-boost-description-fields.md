审查文件：ss-csv/src/main/resources/i18n/zh-cn.properties; contents/data/strings/descriptions.csv; contents/data/strings/strings.json
审查时间：2026-05-26 23:30
文本分类：舰船系统描述 / 系统状态文本

R1  pass — text1/text3 保持简短描述，具体数值集中在 text5 与系统状态 UI。
R2  pass — 首句直接说明装甲护盾增压效果，无套话开头。
R3  pass — 使用装甲护盾、离子化反冲蓄能器等世界内术语。
R4  pass with exception — 本轮按验收要求在系统效果与状态文本中显示具体数值。
R5  pass — 玩家可见文本仍在 ss-csv i18n 与 strings.json 中，逻辑代码只引用 key 和变量。
R6  pass — 术语与 plasma_arch / 弧光路线一致。
R7  pass — 装配界面首行与效果文本分离，避免顶部堆叠。
R8  pass — 未出现“不是……而是……”句式。
R9  pass — 未新增公式化过渡句。
R11 pass — 状态文本两行显示，降低图标占用并保留完整机制说明。

违规项改写建议：
  无。

结论：通过。
