# ss-csv descriptions 文案审查

日期：2026-05-11

范围：
- `ss-csv/src/main/resources/i18n/zh-cn.properties`
- `build/generated/ss-csv/data/strings/descriptions.csv`
- 本轮新增 `desc.astd_*` 条目
- 本轮修改的 `weapon.astd_gcp*.tooltip.customPrimary`、`weapon.astd_stasis_collapse_emitter.tooltip.customPrimary`、`weapon.astd_sgl8.tooltip.customPrimary`、`weapon.astd_tsm2.tooltip.customPrimary`、`weapon.astd_psi_omega.tooltip.customPrimary`

结果：全部通过。

## 初审发现

- R2：GCP 系列 tooltip 与 stasis collapse tooltip 的部分机制句过长。
- R9：若干 `…时` / `当…时` 结构需要改写。

## 已修正

- GCP 系列 tooltip 拆分长句，并保留 `{%s}` 占位符。
- Stasis collapse tooltip 拆分长句。
- SGL-8 / TSM-2 tooltip 将 `当目标防御网络…时` 改为顺序句。
- PSI-Ω tooltip 将 `命中护盾时` 改为 `护盾命中后`。
- DRV-11 notes 与 Stellar Jet emitter text2 删除公式化时间前置结构。
- descriptions 文本移除 GCP 的对照式否定结构与 Stellar Jet 的裸露数值。

## 复审结论

copy-review 复审结果：`0 fail`。

复审确认：
- R1 叙事化通过。
- R2 短句通过。
- R3 世界内视角通过。
- R4 描述层无裸露机制数字；tooltip 数值使用 `{%s}`。
- R8 无 `不是…而是…` / `并非…而是…` 结构。
- R9 公式化语法已修正。

备注：`contents/data/strings/descriptions.csv` 仍是旧生成物。本轮遵循 ss-csv 安全流程，仅生成到 `build/generated/ss-csv/`。
