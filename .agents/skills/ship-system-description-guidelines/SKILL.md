---
name: "ship-system-description-guidelines"
description: "Use when editing Starsector SHIP_SYSTEM rows in descriptions.csv or ss-csv description i18n, especially text1/text2/text3/text4/text5 field placement, multiline effect text, and refit/codex tooltip behavior."
---

# 舰船系统描述文本规范

## 适用范围

适用于 `data/strings/descriptions.csv` 中 `type=SHIP_SYSTEM` 的条目，以及对应的 `ss-csv/src/main/resources/i18n/*.properties`：

- `desc.<system_id>.text1`
- `desc.<system_id>.text2`
- `desc.<system_id>.text3`
- `desc.<system_id>.text4`
- `desc.<system_id>.text5`

## 字段语义

- `text1` 为图鉴界面的首行描述文本。
- `text2` 为类型短词，例如 `攻击`、`防御`、`机动`、`支援`。
- `text3` 为装配界面的舰船信息文本，通常需要和 text1 一致。
- `text4` 暂时作用不明；没有确认用途前保持为空，避免文本显示在未知位置。
- `text5` 为效果文本，用于说明系统实际作用。

关键词：text1 为图鉴界面的首行描述文本；text2 为类型短词；text3 为装配界面的舰船信息文本；text4 暂时作用不明；text5 为效果文本；text1 / text5 支持原生换行。

## 换行规则

- `text1 / text5 支持原生换行`。
- 在 ss-csv properties 中写 `\n`，生成后会进入 CSV 单元格内真实换行。
- 不要在手写 CSV 中写字面量 `\n`。
- 包含换行的 CSV 字段必须由生成器处理，避免手工列错位。

## 编写规则

- 需要在图鉴和装配界面都显示同一首行描述时，将 `text1` 复制到 `text3`。
- 多段系统效果合并到 `text5`，用原生换行拆分。
- 数值高亮沿用原版 `{{...}}}` 标记；百分号优先使用全角 `％`，避免底层格式化路径误读。
- `text2` 必须是短词，不写短句。
- 不确定 `text4` 的显示位置前，不把效果文本放入 `text4`。

## ss-csv 流程

- 不手改 `contents/data/strings/descriptions.csv`。
- 修改 `ss-csv/src/main/resources/i18n/<locale>.properties` 后运行：

```bash
./gradlew :ss-csv:generateSsCsv :ss-csv:writeSsCsvToContents -PssCsvForce=true
```

## 验收检查

- `descriptions.csv` 中目标系统的 `text3 == text1`，除非有明确不同文案需求。
- `text4` 为空，除非已通过实机确认用途。
- `text5` 包含完整效果说明；多段效果使用真实换行。
- 装配界面舰船信息区能看到 `text3`。
- 系统条目 Tooltip 能看到 `text1`、`text2` 与 `text5`。
