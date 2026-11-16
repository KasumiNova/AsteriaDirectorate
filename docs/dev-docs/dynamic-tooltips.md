# 武器与舰船系统描述变量注入

## 实现规则

数值注入必须保留描述内容、段落、标点、空格和高亮标记，只替换原有数字位置。禁止在刷新时追加强度标题、敌方对照或机制解释。

原文中没有明确数字的位置保留原文。原数字与战斗计算没有可靠对应关系时保留其字面值，禁止为了构造变量而更改其含义或另造换算公式。

## 文本来源

默认强度为 2.0，描述正文与高亮参数由以下本地化文本统一生成。

- 静态内容保留在 [zh-cn.properties](../../modules/internal/astd-csv/src/main/resources/i18n/zh-cn.properties)。
- 已登记武器的原文与参数模板位于 [tooltip-weapons.zh-cn.properties](../../modules/internal/astd-csv/src/main/resources/i18n/tooltip-weapons.zh-cn.properties)。
- 已登记系统的原文与参数模板位于 [tooltip-systems.zh-cn.properties](../../modules/internal/astd-csv/src/main/resources/i18n/tooltip-systems.zh-cn.properties)。
- 同一轮生成将默认参数展开到 CSV，并将未展开模板输出至 `data/strings/tooltip_strings.json`。
- 默认强度 2.0 下，运行时生成说明必须与默认参数展开的 CSV 逐字一致。

武器正文保留 `{%s}`；只替换原有 HL 槽中的数值，保留参数之间的空格与分隔符。系统只将原数字替换为 `%name%`，保留原来的 `{{...}}` 标记和真实换行。原空 `text4` 继续为空。

参数只声明名称与默认值。运行时使用模板中的原有文字说明参数含义。

## 难度设置提示

提示统一使用 `{{难度系数}} 武器/船插/系统: 可在模组 {{菀星设计总局}} 的 Lunalib 设置中调整相关机制数值。`，按条目类别选择名称，并高亮难度系数与模组名称。

武器提示放在 `customAncillary`，通过 `customAncillaryHL` 提供两个高亮值；`customPrimary` 只保留机制正文。系统提示只放在 `text5`，使用 `{{...}}` 高亮；`text1` 和 `text3` 只保留简介。船插使用共用本地化键 `ui.hullmod.export.difficulty_note` 的内联高亮标记。

## 运行时

`DifficultyDescriptions` 在应用加载、读档和 LunaLib 设置应用完成后更新共享描述。文本按当前我方强度计算，不添加敌方数值说明。更新从原始模板生成，禁止在上一次结果上追加内容。

武器通过 `WeaponSpecAPI` 的 `setCustomPrimary/Ancillary` 及对应 HL setter 更新。舰船系统通过已有 `Description` 的公开 setter 更新。已经打开的面板通过重新构造读取新文本。

## 覆盖范围

武器登记 24 个具有数值变量的 ID，保留 64 个原数值参数。原本没有说明的 GCP 战机型和双子星战机型保持为空。星尘描述保留在静态文本表中；七星原文只有折跃次数，保留该参数，不增加难度机制说明。

系统登记 8 个具有原数值位置的 ID，保留 27 个参数：辐能链接、压制模式、贯虹突进、战机引力联结、引力裂隙、引力磁暴、引力时流干涉、引力空间复制。星翼折跃和三个联合系统的原描述按静态文本保留，避免向原本只有概述的段落补写数值。

## 验证

- 模板与数值格式算法测试。
- 生成 CSV 与修改前原始说明逐字比对。
- 游戏内默认基准、7 组强度切换及重复刷新验证。
- LunaLib 实际设置通知验证。
- 原生文字排版及截图检查。
- 正式依赖检查与发行部署，正式产物排除开发验收场景。

生成与测试命令：

```bash
./gradlew :astd-csv:test :astd-combat:test --tests 'cn.kasuminova.astd.combat.description.*' :astd-csv:generateSsCsv
```

仅生成到 `build/generated/ss-csv/`，禁止写回静态 contents。
