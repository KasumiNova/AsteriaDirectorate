# SecondInCommand（舰队副官）联动设计：ASTD 新副官天赋

> 纯调研+设计文档，不涉及代码改动。调研日期：2026-09-27。
> 对象版本：SecondInCommand 1.8.0（gameVersion 0.98a-RC5，作者 Lukas04）。

## 0. 调研来源与结论先行

- 本机已装模组：`/mnt/store/Games/Starsector098-linux/mods/SecondInCommand/`，**附带完整 `src/` 源码与全部数据文件**，是本次调研的主依据。
- `dev-resources/sources/second_in_command/`：同一模组的反编译 Java 副本，用于核对 Kotlin 源码未覆盖的类（如 `SCBaseAptitudePlugin`、`SCOfficer`、`SCAptitudeSection`）。
- GitHub 仓库未能定位（GitHub API 搜索与 `github.com/Lukas04/SecondInCommand` 均不可达），无官方集成文档；本文全部 API 结论从 1.8.0 源码反推。

核心结论：**SiC 的天赋/技能注册是纯数据驱动的**——它用 `getMergedSpreadsheetDataForMod` 合并所有启用模组在同路径下的 CSV。ASTD 只需在自己模组目录放置对应 CSV 行 + 提供插件类即可接入，不需要任何注册代码；SiC 缺失时这些 CSV 永远不会被读取，天然软依赖。

## 1. SecondInCommand 接入点清单

### 1.1 天赋/技能注册机制（数据驱动，零注册代码）

- `SCModPlugin.onApplicationLoad` 依次调用 `SCSpecStore.loadCategoriesFromCSV()` / `loadAptitudeSpecsFromCSV()` / `loadSkillSpecsFromCSV()`，三者均用 `Global.getSettings().getMergedSpreadsheetDataForMod("id", "data/config/secondInCommand/<文件>", "second_in_command")` 加载——merged 语义意味着**任何启用模组在同一路径放置的同名 CSV 行都会被合并进来**。
  来源：`mods/SecondInCommand/src/second_in_command/SCModPlugin.kt:50-63`、`src/second_in_command/specs/SCSpecStore.kt:35-134`。
- 插件类加载：`SCAptitudeSpec.getPlugin()` 用 `Global.getSettings().scriptClassLoader.loadClass(pluginPath).newInstance()`——scriptClassLoader 覆盖所有模组 jar，ASTD jar 内的 Kotlin 类可直接被加载，类路径写全限定名即可。
  来源：`src/second_in_command/specs/SCAptitudeSpec.kt:20-29`。
- **关键陷阱**：SiC 从 CSV 的 `fs_rowSource` 列用 `filterModPath` 反推 mods 下的**文件夹名**，再 `enabledModsCopy.find { it.dirName == modName }!!`（非空断言）。ASTD 安装文件夹名必须与 ModSpec 的 dirName 一致（当前为 `mods/ASTD`）；玩家若重命名文件夹，SiC 加载 ASTD 的行时会直接 NPE 崩溃。
  来源：`SCSpecStore.kt:93-94, 125, 139-146`。

### 1.2 数据文件格式

| 文件 | 列 | 说明 |
|---|---|---|
| `data/config/secondInCommand/SCAptitudes.csv` | id,name,categories,spawnWeight,color,tags,order,plugin | 天赋定义。tags 支持 `startingOption`（开局酒吧对话可选）、`always_show_in_codex`、`hide_in_codex`、`restricted` |
| `data/config/secondInCommand/SCSkills.csv` | id,name,iconPath,npcSpawnWeight,plugin | 技能定义。iconPath 经 `Global.getSettings().loadTextureCached` 加载，**可用 ASTD 自家 graphics 路径**；npcSpawnWeight 留空=NPC 永不选 |
| `data/config/secondInCommand/SCCategories.csv` | id,name,color | 天赋类别，仅用于互斥（同类别天赋在同一舰队/雇佣池互斥） |

来源：`mods/SecondInCommand/data/config/secondInCommand/` 三个 CSV 的表头注释（SCAptitudes.csv:1-9）、`SCSpecStore.kt:106-134`、互斥逻辑 `src/second_in_command/misc/NPCOfficerGenerator.kt:190-198, 215-223`。

另需 `data/world/factions/default_ranks.json` 加一条 `executive_officer_<天赋id>` 职位条目（天赋 CSV 注释明确要求；JSON 跨模组按键合并，ASTD 加自己的 key 互不干扰）。
来源：SCAptitudes.csv:4 注释、SiC 自带 `data/world/factions/default_ranks.json`。

### 1.3 API hook 点

天赋插件基类 `second_in_command.specs.SCBaseAptitudePlugin`
（来源：`dev-resources/sources/second_in_command/specs/SCBaseAptitudePlugin.java`，范例 `src/second_in_command/skills/automated/AptitudeAutomated.kt`）：

- `getOriginSkillId()`：基石技能 id（自动解锁的根技能，如无人战舰天赋的 `sc_automated_automated_ships`）。
- `createSections()`：用 `SCAptitudeSection(canChooseMultiple, requiredPreviousSkills, soundId)` 组织技能树段，`addSkill(skillId)` 逐个挂技能。第三参是 LunaLib UI 音效 id（如 `"technology1"/"technology3"/"technology5"`），技能点亮时播放。
- `getNPCFleetSpawnWeight(data, fleet)`：**抽象方法必须实现**，决定 NPC 舰队 roll 到该天赋的权重（`AptitudeAutomated` 的范式：舰队含无人舰给权重，否则 0；旗舰无人舰给 `Float.MAX_VALUE` 必中）。
- `getMarketSpawnweight(market)`：市场通讯目录雇佣池权重（默认=CSV spawnWeight，可按阵营/市场调权，见 `AptitudeAutomated.kt:60-66`；消费方 `ExecutiveOfficerCommAdder.kt:73`）。
- `getCryopodSpawnWeight(system)`：休眠舱/残骸打捞投放权重（消费方 `ExecutiveOfficerSalvageSpecialGenerator.kt:52-56`）。
- `guaranteePick(fleet)`：NPC 舰队保底必中钩子，默认 false。
- `addCodexDescription(tooltip)`：Codex 条目文案。

技能插件基类 `second_in_command.specs.SCBaseSkillPlugin`
（来源：`dev-resources/sources/second_in_command/specs/SCBaseSkillPlugin.java`，范式 `src/second_in_command/skills/technology/PhaseCoilTuning.kt`）：

- `applyEffectsBeforeShipCreation(data, stats, variant, hullSize, id)`：主力钩子，按 `variant` 判定舰船后往 `MutableShipStatsAPI` 挂 modifier（modifier id 用入参 `id`）。
- `applyEffectsAfterShipCreation` / `applyEffectsToFighterSpawnedByShip`：实体级与战机级钩子。
- `advance(data, amount)` / `advanceInCombat(data, ship, amount)`：每帧钩子（战斗逻辑由 SiC 的 CombatHandler 自动驱动，插件类只管实现）。
- `onActivation(data)` / `onDeactivation(data)`：技能启停钩子（挂/卸监听、脚本）。
- `getAffectsString()` / `addTooltip(data, tooltip)`：UI 文案。
- `getNPCSpawnWeight(fleet)`：NPC roll 单个技能的权重，可按舰队构成归零（`PhaseCoilTuning.kt:45-48`：舰队无相位舰则 0）。

程序化发放副官（剧情奖励用）：
`SCUtils.createRandomSCOfficer(aptitudeId[, faction, random])` 造官，`SCUtils.getPlayerData().addOfficerToFleet(officer)` 入队；`SCUtils.changeOfficerAptitude(fleet, officer, aptitudeId)` 可改天赋。
来源：`src/second_in_command/SCUtils.kt:122-141, 164-184`、用法 `NPCOfficerGenerator.kt:297-298`。

### 1.4 模组共存/缺失检测

- SiC 模组 id：`second_in_command`（`mods/SecondInCommand/mod_info.json:2`，与 `SCUtils.MOD_ID` 一致）。
- **数据+插件路径免检测**：SiC 未启用时 ASTD 的 SC CSV 不会被读取、插件类不会被加载，无需门控。
- **仅直调 SiC API 的代码需要门控**（剧情奖励副官）：`Global.getSettings().modManager.isModEnabled("second_in_command")`，且按 ASTD 既有 IndEvo 模式做软依赖隔离文件——全部 SiC 类引用集中在一个文件，入口先过 `isEnabled()` 缓存判定。
  参照：`modules/internal/astd-campaign/src/main/kotlin/cn/kasuminova/astd/campaign/world/IndEvoWorldExtras.kt:19-80`。
- 编译期：根 `build.gradle.kts` 的 SDG GAME_DIR 模式自动把已装模组 jar 挂到 compileOnly（`build.gradle.kts:56, 73-133`），SiC 已装于游戏目录即可直接编译引用，无需新增依赖声明。
- ASTD 的 mod_info.json **不得**把 second_in_command 写进 dependencies（保持软依赖）。
- SiC 自身与技能重制类模组互斥（QualityCaptains、ANewLevel 系列、TrulyAutomatedShips 等，`SCModPlugin.kt:65-100` 启动时抛异常）；ASTD 不改原版技能，不在互斥面内，也不应触发。

## 2. ASTD 新副官类型设计

### 2.1 定位

天赋名建议：**「引力相位工程院」**（id：`astd_sc_grav_phase`，可按文案规范再议）。
设定口吻：菀星设计局外派的技术军官——对照 SiC「无人战舰」天赋是"自动化舰队许可"，本天赋是"总局技术体系授权"：相位舰运用规范、引力武器火控条令、量产 AI 核心的协处理规程，三位一体，对应 85 号文档的 AI 核心分级体系与相位/引力两条装备主线。

- color：菀星紫（取 ASTD 主色系 RGBA）。
- categories：**建议留空**。挂 `sc_cat_automated` 会与 SiC「无人战舰」互斥，而 ASTD 量产 AI 核心舰队恰恰需要两者共存。
- tags：**建议不给 `startingOption`**（开局酒吧即出现菀星军官出戏），走市场雇佣+剧情发放；正常进 Codex。

### 2.2 技能构成

复用 vs 新增：天赋的 `createSections()` 直接列技能 id，`SCSpecStore` 全局索引，**ASTD 天赋技术上可以混入 SiC 自带技能**（如 `sc_technology_phase_coil_tuning`）。但 SiC 技能数值不贴合 ASTD 装备体系、且 SiC 更新可能改名，建议**主体全部新增**；如需降低首版工作量，至多混用 1-2 个 SiC 通用技能（待定项）。

建议技能树（10 个技能，三段式；名称均为工作名）：

- 基石技能（origin，自动解锁）「菀星制式校准」：装备 ASTD 系 hullmod/船体的舰船获得小幅泛用增益（峰值 CR、CR 恢复），对齐 AutomatedShips 的基石定位。
- Section 1（前置 0，可多选）：
  - 「相位线圈复调」：相位舰峰值时间、相位下机动（范式照 PhaseCoilTuning，判定改 ASTD 相位舰标签）。
  - 「引力井测地」：campaign 侧传感器/航行增益（引力透镜阵列设定的民用面）。
- Section 2（前置 2，可多选）：
  - 「重力透镜火控」：ASTD 引力武器射程/伤害/转向。
  - 「相位共振闪回」：相位舰冷却/闪回窗口强化。
  - 「协处理阵列」：AI 核心军官相关增益（自动化点数折扣或 AI 军官 CR），呼应量产核心体系。
- Section 3（前置 4，单选一）：
  - 「奇点统筹」：引力武器旗舰级舰队增益（capstone）。
  - 「值班长人格矩阵」：副官本身即特殊核心人格化（联动 85 文档 §4.4「值班长」），提供自动化舰队向 capstone。

技能图标 40×40（SiC UI 渲染尺寸，见 `SCUtils.kt:248`），美术按 `docs/design/art` 规范排期。

### 2.3 获取途径建议

1. **通讯目录雇佣**：`getMarketSpawnweight` 对菀星剧情空间站市场大幅加成、其他市场基础权重（范式 `AptitudeAutomated.getMarketSpawnweight` 按阵营调权）。ASTD 目前无独立阵营，判定走市场/实体 id。
2. **剧情固定发放**：主线/支线节点奖励一名固定天赋副官（astd-campaign 剧情代码经隔离对象调 `SCUtils.createRandomSCOfficer` + `addOfficerToFleet`，需 `isModEnabled` 门控）。
3. **休眠舱打捞**：`getCryopodSpawnWeight` 给低权重，让废墟打捞偶发遇见（「冬眠两百年的总局技术员」契合设定）。
4. **NPC 携带**：`getNPCFleetSpawnWeight` 检查舰队是否含 ASTD 船体（hull id 前缀/tag），让使用 ASTD 装备的舰队自然携带该天赋；技能级再用 `getNPCSpawnWeight` 按舰队构成过滤。

## 3. 实现步骤拆分

1. **数据文件**（根工程 contents 装配侧，不经 astd-csv 生成器——ss-csv 体系只管 ASTD 自有 CSV，SC 文件是第三方格式手写；落地前确认 `contents/.ss-csv-manifest` 不接管该路径）：
   - `contents/data/config/secondInCommand/SCAptitudes.csv`：天赋一行；
   - `contents/data/config/secondInCommand/SCSkills.csv`：技能约 10 行；
   - `contents/data/world/factions/default_ranks.json`：`executive_officer_astd_sc_grav_phase` 职位条目；
   - `contents/graphics/secondInCommand/astd/`：技能图标。
2. **代码 hook**（modules/internal/astd-campaign，新包 `cn.kasuminova.astd.campaign.integration.sic`）：
   - 天赋插件类（继承 `SCBaseAptitudePlugin`）+ 技能插件类群（继承 `SCBaseSkillPlugin`）；
   - 技能纯 stats 修改即可全部落此包；需复用 astd-combat 既有 Tuning 常量时允许该包引用 astd-combat（模块间依赖是否已打通待确认，未打通则数值先内联并标注来源）；
   - astd-combat / astd-csv / astd-render 无需改动。
3. **兼容检测**（astd-campaign 同包）：
   - 新建 `SicIntegration` 隔离对象（仿 `IndEvoWorldExtras`）：`isEnabled()` 缓存 `isModEnabled("second_in_command")`，剧情奖励副官的全部调用点经它门控；插件类本体不门控（SiC 缺失时不会被加载）；
   - 确认根工程 mod_info.json 不含 SiC 依赖声明。
4. **测试**：
   - 构建后核验装配产物（jar + contents 目录）含 CSV、default_ranks.json 条目与插件类；
   - 游戏内：新档菀星空间站通讯目录出现天赋 → 雇佣 → 技能树可点、效果生效；LunaDebug 的 AddAllOfficers snippet / 控制台 ListAptitudes 辅助验证；与装备 ASTD 船体的 NPC 舰队交战观察对方是否携带；
   - 回归：无 SiC 环境启动 + 读档正常（验证软依赖）；有 SiC 无 ASTD、双装新档三组合各跑一遍；
   - 单元测试只写纯逻辑（如按舰队构成算权重的函数），按项目规范不做源码 contain 测试。

## 4. 风险与待定项

- **API 无官方承诺**：SiC 无公开集成文档（GitHub 未定位），以上接口反推自 1.8.0 源码；SiC 升级可能改 CSV 格式或基类签名。文档锁定对接版本 1.8.0，SiC 更新后需回归。
- **文件夹名敏感**：`SCSpecStore.filterModPath` + `!!` 断言意味着玩家重命名 ASTD 安装文件夹会崩溃（SiC 侧缺陷，非个例——所有第三方天赋同病），需在发布说明注明"不要重命名模组文件夹"。
- **卸载 SiC 的存档**：SCOfficer/SCData 存玩家 memory 并随存档序列化，卸载 SiC 本身即坏档，与 ASTD 无关，但文案上别承诺"可随时卸载"。
- 待定项：
  1. 天赋是否给 `startingOption`（开局可选）；
  2. 是否混入 1-2 个 SiC 自带技能降低首版工作量；
  3. 技能数值与 Section 音效 id（沿用 `technology1/3/5` 还是换原版 UI 音效）；
  4. 「值班长人格矩阵」与 85 文档特殊核心体系的联动深度（是否要求玩家持有对应特殊核心才解锁）；
  5. astd-campaign → astd-combat 的模块依赖现状（决定技能数值能否复用 Tuning 常量）。
