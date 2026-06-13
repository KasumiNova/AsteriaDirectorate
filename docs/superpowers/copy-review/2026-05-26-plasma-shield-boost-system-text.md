审查文件：ss-csv/src/main/resources/i18n/zh-cn.properties; contents/data/strings/strings.json; contents/data/strings/descriptions.csv

文本分类：舰船系统名称 / 舰船系统描述 / 系统状态文本

R1  pass — 系统描述直接说明承伤能力与代价，符合本次用户要求的机制说明口径。
R2  pass — 句子短，动词明确，无套话开头。
R3  pass — 使用装甲护盾、离子化反冲蓄能器等世界内术语。
R4  pass with exception — 本次用户明确要求数值文本与状态位显示百分比范围；descriptions.csv 数值使用原版 `{{...}}}` 高亮标记。
R5  pass — 运行时状态文本走 strings.json；系统名称与顶部描述走 ss-csv i18n。
R6  pass — 术语保持弧光/等离子拱路线一致。
R8  pass — 未使用“不是……而是……”句式。
R9  pass — 无“通过……来实现……”等公式化结构。

结论：通过。此轮为明确机制说明型文本，数值暴露来自当前验收要求，不按通常的叙事化状态文本限制处理。
