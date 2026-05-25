审查文件：contents/data/strings/strings.json; contents/data/strings/descriptions.csv; ss-csv/src/main/resources/i18n/zh-cn.properties
审查时间：2026-05-25
文本分类：hullmod tooltip / 舰船系统描述 / hullmod 描述

R1  pass — tooltip 数值已集中在表格 value；summary / note 保持叙事或短备注。
R2  pass — 新增与修改句子长度可控，未使用套话开头。
R3  pass — 未引入玩家、游戏内、脚本等第四面墙表达。
R4  pass — descriptions.csv 与 ss-csv 叙事描述无裸数值；tooltip 表格 value 作为 UI 数值说明保留具体数值。
R5  pass — 运行时文本来自 strings.json；ss-csv 文本来自 zh-cn.properties；Kotlin tooltip contract 仅引用 i18n key。
R6  pass — 链路、共振、电弧、时流、火线等词汇与弧光路线一致。
R7  pass — plasma / ionized 的恢复、限制、击穿概率已拆入表格；note 信息密度降低。
R8  pass — 未新增禁用的“不是…而是…”结构。
R9  pass — 条件机制已尽量改为表格标签/短值，如“同网络连接：效果+50%”“低基础射程补偿：<600，最高+150”。
R10 pass — 非对话文本，不适用角色声音专项。
R11 pass — 情感锚点稳定，均围绕弧光链路、承压护层、追猎节奏。
R12 pass — 舰船系统状态文本未在本轮变更。

违规项改写建议：
  无。

结论：全部通过。
