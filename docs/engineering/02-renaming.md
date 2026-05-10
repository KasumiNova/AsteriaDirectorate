# 改名与迁移清单（模板复用）

本模板预置了一个示例势力/模组名（Asteria Directorate）与示例包名（`cn.kasuminova.asteriadirectorate`）。
如果你要把它变成“你自己的模组”，建议按下面顺序改。

## 1) 改 mod 元数据（必须）

编辑 `gradle.properties`：

- `mod.id`：建议全小写 + 下划线（例如 `my_mod`）
  - 会出现在 `mod_info.json` 的 `id`
  - 一旦发布并产生存档依赖后不建议频繁改动
- `mod.name`：建议先用 ASCII（避免 properties→JSON 链路在部分环境出现转义/乱码）
- `mod.author` / `mod.description`
- `mod.gameVersion`：例如 `0.98a`

构建后在 `build/mod_production/mod_info.json` 验证：
- `id`、`name`、`gameVersion` 是否符合预期

## 2) 改插件入口（必须）

- 插件类位于：`src/main/java/.../AsteriaDirectoratePlugin.java`
- `gradle.properties` 的 `mod.plugin` 必须等于插件类的全限定名

如果你更名/移动了插件类，除了更新 `mod.plugin`，也建议全工程搜索以下字段：
- `modPlugin`
- `mod.plugin`

## 3) 改包名与脚本引用（按需）

如果你改了 Java/Kotlin 包名或类名，需要同步更新数据文件里的脚本引用：

- `contents/data/hullmods/hull_mods.csv` 的 `script` 列
- `contents/data/shipsystems/*.system` 的 `statsScript`

并确保构建产物里也对应更新：
- `build/mod_production/data/hullmods/hull_mods.csv`
- `build/mod_production/data/shipsystems/*.system`

## 4) 调试数据 ID（可选）

本模板数据侧使用 `astd_` 前缀作为“调试数据 ID”，它和 Java 包名没有绑定关系。
你可以：
- 保持不动（最快）
- 或统一改成你自己的前缀（更整洁，但需要同步更新 CSV/ship/variant/system/weapon 等引用）

## 5) 最终核对

- `./gradlew build` 应该成功
- `build/mod_production/mod_info.json` 的 `modPlugin` 能加载到对应类
- 游戏里能看到 mod 条目，并且不会在加载阶段报 Missing class / script not found
