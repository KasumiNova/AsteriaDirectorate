# 调试占位数据/贴图生成

旧版项目曾使用 `tools/gen_debug_placeholders.py` 生成“占位”内容，但目前该脚本已弃用。

现在推荐的调试链路是：

- **CSV 生成**：由 ss-csv 扫描 Kotlin catalog 并生成 CSV
  - 输出到：`build/generated/ss-csv/`
- **打包产物**：`modProduction` 生成可直接加载的 `build/mod_production/`
- **战役可见性验证**：devMode 新开档时自动注入测试市场/仓储（见 `AsteriaTestCampaignBootstrap`）

用途：
- 验证 CSV→脚本类→资源路径 是否正确
- 让你在实现真实机制之前，先把工程链路跑通

## 依赖

- 无额外 Python 依赖
- 使用本项目自带的 Gradle 任务即可

## 运行方式

1) 生成 CSV（输出到 `build/generated/ss-csv/`）：运行 `:ss-csv:generateSsCsv`

2) 打包可加载产物（输出到 `build/mod_production/`）：运行 `modProduction`

3) （可选）如果你确实希望让 ss-csv 直接覆盖 `contents/` 下的数据文件：
  - 使用 `:ss-csv:writeSsCsvToContents -PssCsvForce=true`
  - 注意该任务会覆盖 `contents/` 下对应 CSV（用于稳定可重复生成）

## 类名/包名变更提示

如果你迁移了包名/类名，请重点检查：

- `mod_info.json` 的 `modPlugin`
- `contents/data/**` 中引用脚本类名的字段（hullmod、system、weapon effect、AI 等）
- ss-csv 的 catalog 是否仍在生成正确的类全名
