# 快速开始（开发/构建/部署）

本项目是一个 Starsector Mod 的 Gradle 模板工程，已包含：
- Java + Kotlin 混合编译（toolchain 17）
- 生产目录输出：`build/mod_production/`
- 打包 zip、部署到游戏目录、启动游戏等任务

## 环境准备

- JDK 17
- （可选）Python 3 + Pillow：用于生成调试占位贴图/数据（见 `03-debug-placeholders.md`）

## 最小工作流

1. 配置游戏目录：编辑 `gradle.properties`，设置：
   - `starsector.gameDir=/path/to/your/starsector`

2. 构建：运行 `./gradlew build`

构建成功后，你会看到：
- `build/mod_production/mod_info.json`
- `build/mod_production/jars/*.jar`
- `build/mod_production/data/**` 与 `build/mod_production/graphics/**`

## 部署与启动（可选）

如果你已配置了 `starsector.gameDir`：

- `./gradlew deployMod`：将 `build/mod_production/` 部署到游戏的 `mods/` 下
- `./gradlew launchGame`：使用 `launch-config.json` 的配置启动游戏

> 提示：如果你使用 IDEA 调试，`launch-config.json` 里会影响类路径与 JVM 参数。

## 常见问题

### 构建成功但游戏里没加载

检查 `build/mod_production/mod_info.json`：
- `id` 是否唯一
- `modPlugin` 是否是正确的全限定类名（例如：`cn.kasuminova.asteriadirectorate.AsteriaDirectoratePlugin`）

### 依赖/版本不匹配

- `mod.gameVersion` 必须与目标游戏版本匹配（例如 `0.98a`）。
- `mod.dependencies` 为空表示无依赖；如需依赖，确保依赖 mod 的 `id` 正确。
