# Asteria Directorate（阿斯忒里亚遗构局）

Starsector 模组开发项目模板（已预置 Asteria Directorate 示例内容；你可以按文档快速改名、迁移包名并生成调试用数据/贴图占位。）

## 项目结构

```
<project-root>/
├── src/                    # 源代码目录
│   ├── main/
│   │   ├── java/          # Java 源码
│   │   ├── kotlin/        # Kotlin 源码
│   │   └── resources/     # 资源文件
│   └── test/              # 测试代码
├── contents/              # Mod 静态资源（数据、图形等）
│   ├── data/
│   └── graphics/
├── libs/                  # 依赖库（Starsector API 等）
├── buildSrc/              # 自定义 Gradle 插件
│   ├── PLUGIN_ARCHITECTURE.md  # 插件架构文档
│   └── src/main/kotlin/
│       └── cn/kasuminova/starsector/gradle/
│           ├── StarsectorModPlugin.kt      # 主插件类
│           ├── ModMetadata.kt              # 元数据配置
│           ├── ModProductionTasks.kt       # 生产任务组
│           ├── ModPackagingTasks.kt        # 打包任务组
│           ├── ModDeploymentTasks.kt       # 部署与启动任务组
│           └── ModUtilityTasks.kt          # 工具任务组
├── dev-resources/        # 开发资源（参考代码、工具等）
├── build.gradle.kts       # 主构建脚本
├── gradle.properties      # Mod 元数据配置
├── launch-config.json     # 游戏启动配置
└── settings.gradle.kts    # 项目设置
```

## 快速开始

前置：建议使用 JDK 17（本项目 toolchain=17）。

1. 打开 `gradle.properties`，设置 `starsector.gameDir` 为你的游戏目录。
2. 运行 Gradle 构建（会生成 `build/mod_production/` 并打包 zip）。
3. （可选）运行部署/启动任务，将产物安装到游戏目录并启动游戏。

更多细节见：
- `docs/engineering/01-quickstart.md`
- `docs/engineering/02-renaming.md`
- `docs/engineering/03-debug-placeholders.md`
- `docs/engineering/04-launch-config.md`

## 构建任务

### 核心任务

- `./gradlew build` - 构建项目并自动打包 Mod（会自动执行 `zipModProduction`）
- `./gradlew jar` - 仅编译并打包 JAR
- `./gradlew clean` - 清理构建输出

### Mod 相关任务

- `./gradlew modProduction` - 生成 Mod 生产目录（`build/mod_production/`）
- `./gradlew zipModProduction` - 打包 Mod 为 ZIP 文件
- `./gradlew printModInfo` - 打印当前 Mod 配置信息
- `./gradlew deployMod` - 部署 Mod 到游戏目录（需配置 `starsector.gameDir`）
- `./gradlew launchGame` - 启动游戏（需配置 `starsector.gameDir`, 支持 IDEA 调试）

### 清理任务

- `./gradlew cleanDeploy` - 清理部署在游戏目录中的 Mod
- `./gradlew cleanModProduction` - 清理 Mod 生产目录

## 配置说明

所有 Mod 元数据在 `gradle.properties` 中配置：

```properties
mod.id=asteria_directorate
mod.name=Asteria Directorate
mod.author=作者名
mod.description=模组描述
mod.gameVersion=0.98a
mod.plugin=cn.kasuminova.asteriadirectorate.AsteriaDirectoratePlugin
mod.dependencies=依赖ID:依赖名称

# 必选：配置 Starsector 游戏根目录以启用自动部署和启动
starsector.gameDir=/path/to/your/starsector
```

注意：如果你希望 `mod_info.json` 里显示中文名称，建议优先在 `mod_info.json`（或构建链路的 JSON 生成环节）中处理；
直接把中文写进 `gradle.properties` 可能会在某些链路里出现转义/乱码表现（不同平台/编码环境差异较大）。

游戏启动的 JVM 参数和类路径在 `launch-config.json` 中配置，你可以根据自己的操作系统和需求进行修改。

## 开发流程

1. 修改 `gradle.properties` 配置 Mod 信息和游戏目录
2. 在 `src/main/kotlin` 或 `src/main/java` 编写代码
3. 在 `contents/` 目录放置数据文件和图形资源
4. 运行 `./gradlew build` 构建并打包
5. 生成的 ZIP 文件在 `build/` 目录

如果你需要一套“能进游戏跑起来”的占位数据/贴图（用于验证 CSV/.ship/.variant/.system 引用链路），见：
- `tools/README.md`

## 插件架构说明

本项目使用模块化的自定义 Gradle 插件架构，所有构建逻辑位于 `buildSrc/src/main/kotlin/cn/kasuminova/starsector/gradle/` 包中。

### 插件模块：

- **StarsectorModPlugin** - 主插件入口，负责协调
- **ModMetadata** - Mod 元数据配置封装
- **ModProductionTasks** - 生产目录管理任务
- **ModPackagingTasks** - 打包和发布任务
- **ModDeploymentTasks** - 部署与启动任务组
- **ModUtilityTasks** - 辅助工具任务

### 如何扩展：

如需添加新的构建任务，可以：
1. 在现有任务类中添加新方法（如 `ModUtilityTasks.kt`）
2. 或创建新的任务类并在 `StarsectorModPlugin.kt` 中注册

## 注意事项

- `build` 任务会自动执行 `zipModProduction`，无需单独运行
- 所有 Mod 任务逻辑都在 `buildSrc` 插件中，享有完整的 IDE 支持
- 插件使用域名包结构 `cn.kasuminova.starsector.gradle`
- `dev-resources/` 目录存放开发参考资料，不参与构建
