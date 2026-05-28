审查文件：contents/data/strings/strings.json, contents/data/strings/descriptions.csv, ss-csv/src/main/resources/i18n/zh-cn.properties
审查时间：2026-05-27 00:00
文本分类：ship system description / ship system status

R1  ☑ pass — 战术系统的具体数值留在效果文本和状态文本中，说明对象明确。
R2  ☑ pass — 新增与调整行直接说明系统效果，没有套话开头。
R3  ☑ pass — 文本使用舰船、护盾、装甲、武器等世界观内部术语。
R4  ☑ pass — 数值位于系统效果字段与状态 UI 中，本轮按机制说明需求保留。
R5  ☑ pass — 玩家可见文本来自 strings.json 与 ss-csv i18n，运行时代码未新增硬编码文案。
R6  ☑ pass — 等离子拱使用承压、防御语义；弧光耀斑保留弧光进攻语义。
R7  ☑ pass — 图鉴描述字段按 text1/text3/text5 分层，避免未知 text4 承载效果。
R8  ☑ pass — 未发现“不是…而是…”句式。
R9  ☑ pass — 未新增公式化过渡句。
R11 ☑ pass — 系统类型短词与既有进攻/防御/机动/支援口径一致。

违规项改写建议：
  无。

结论：☑ 全部通过（10/10）
