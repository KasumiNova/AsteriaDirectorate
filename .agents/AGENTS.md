# 开发指令（Asteria Directorate）

> 本文件采用“轻量 XML-ish 元信息 + Markdown 正文”的混合格式：
> - 顶部元信息用于跨模型保持一致的关键约束与入口点
> - 细节说明使用 Markdown，便于人读、也便于模型按需提取

<copilot project="Asteria Directorate" type="Starsector Mod" gameVersion="0.98a" jdk="17" languages="Java,Kotlin">
  <skills entryPoint=".agents/skills/00-skill-index/SKILL.md" policy="follow-skills-read-selected-fully" />
  <constraints>
    <rule id="sscsv-build-only" />
    <rule id="boxutil-readonly" />
    <rule id="no-reflection" />
    <rule id="java17" />
    <rule id="avoid-monolith" />
  </constraints>
  <longTasks prefer="subAgent" templateFields="task,readOnlyScope,keywords,returnFormat,doNotDo" />
</copilot>

## 这是什么工程

- Starsector 0.98a 模组（Asteria Directorate），Java + Kotlin，toolchain=17。
- 构建逻辑主要在 `buildSrc/`：生产目录组装、打包、部署、启动、反编译依赖源码。

## 关键目录与数据流

- `contents/`：真实的模组静态资源源目录（`data/**`, `graphics/**` 等）。
- `build/mod_production/`：Gradle 产物：可直接加载的模组目录（不要手改 `build/**`）。
- `buildSrc/`：自定义 Gradle 插件。
- `dev-resources/sources/`：依赖源码镜像（由 `decompileSources` 生成，用于检索）。

## 常用工作流（Gradle）

- `./gradlew build`：构建（并 zip `mod_production` 到 `build/`）。
- `./gradlew modProduction`：只组装生产目录。
- `./gradlew deployMod`：部署到 `${starsector.gameDir}/mods/${mod.id}`。
- `./gradlew launchGame`：读取 `launch-config.json` 启动游戏（内存参数可能较激进）。
- `./gradlew decompileSources`：输出 `dev-resources/sources/` 并附加到 IDEA 索引。

## 代理工作约束（本仓库特有）

- **默认仅生成到 build**：处理 ss-csv 时优先运行 `./gradlew :ss-csv:generateSsCsv`，只写入 `build/generated/ss-csv/`；除非用户明确要求，否则不要执行 `writeSsCsvToContents` 覆盖 `contents/`。
- **BoxUtil 只读**：`/mods/BoxUtil` 为依赖模组源码参考目录，默认不要修改其代码/资源（除非用户明确要求在 BoxUtil 内修复）。
- **禁止反射**：不要为“兼容性探测”引入反射/动态 class lookup。
- **Java 版本**：Java 17；除非用户明确要求，否则不做降级兼容处理。
- **避免单文件巨型实现**：优先复用现有基类/工具类并按职责拆分。
- **严禁最小实现**：所有需求都必须完整实现，不要为了“先跑起来”而偷工减料。

## 规范优先（Skills）

- 实现/修改时必须优先遵循本仓库 skills。
- 由 AI 根据当前任务自行选择要读哪些 skill，并把被选中的 skill **全文读完**后再动手。
- skills 入口：`.agents/skills/00-skill-index/SKILL.md`

## 长任务（subAgent）

- 长任务优先让 subAgent 做“只读检索/收集信息”，主线程专注决策与改动。
- subAgent prompt 必须给足上下文并严格控范围，避免泛化成“完整分析项目”。

推荐字段（给 subAgent 的 prompt 里写清楚即可）：

- `task`
- `readOnlyScope`
- `keywords`
- `returnFormat`
- `doNotDo`

## ss-csv（核心约定）

- 安全生成到 `build/generated/ss-csv/`：`./gradlew :ss-csv:generateSsCsv`
- 覆盖写回 `contents/`（危险，仅在用户明确要求时）：`./gradlew :ss-csv:writeSsCsvToContents -PssCsvForce=true`
- schema header 真相来源：`tools/_schema_headers/*.header.csv`（生成器只读首行）。
- Catalog 扫描包：`cn.kasuminova.asteriadirectorate.sscsv.entries.catalog`

## 入口点与调试

- Mod 入口：`src/main/java/cn/kasuminova/asteriadirectorate/AsteriaDirectoratePlugin.java`
- devMode 新开档注入：`AsteriaTestCampaignBootstrap.runIfEnabled()`

## 武器弹体 VFX 管线

- `.proj` 的 `onFireEffect` 推荐统一指向：`ProjectileSpecOnFireDispatcher`
- Registry：`ProjectileVfxRegistry`
- 配置：`contents/data/config/astd_projectile_vfx.json`

## 迁移/改名最易漏点

- 改 `mod.id` / 包名后：同步更新 `gradle.properties`（`mod.*`），并检查 `contents/data/**` 中脚本类名引用。
