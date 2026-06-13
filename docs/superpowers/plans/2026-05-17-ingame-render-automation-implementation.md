# 游戏内自动化渲染测试实施文档

日期：2026-05-17

## 目标验收

首版必须能由自动化流程完成以下可观察结果：

1. 游戏启动并进入可用战役或测试流程。
2. `arc_flare` 舰船位于模拟战中。
3. `aod7` 能被触发开火。
4. 画面中能看到 `aod7` 弹体特效。
5. 验收产物包含日志、截图或 telemetry 之一，能证明上述结果。

## 总体策略

采用 `SSOptimizer 主体驱动，ASTD 提供测试表面`。实现顺序以可见结果优先：先完成最小自动化场景与证据落盘，再扩展完整 JSON-RPC、图像对比与热重载。

首版允许使用混合路径：

- SSOptimizer：负责启动 profile、注入、自动化 driver、截图/日志/telemetry 验收。
- ASTD：提供 dev-only scenario surface，让 `arc_flare + aod7` 可以稳定进入战斗并触发 VFX。

## 仓库约束

- ASTD 代码根包使用 `cn.kasuminova.astd`。
- ASTD 新增类名前缀使用 `ASTD`。
- BoxUtil 工作区只读。
- ss-csv 默认只生成到 `build/generated/ss-csv/`。
- VFX 走现有弹体 VFX 管线与 BoxUtil 封装。
- SSOptimizer 的 `asm/` 与 `mixin/` 只放注入代码和 Hook，业务逻辑放 `common/`。
- SSOptimizer app 模块新增 Starsector 混淆类引用时，遵守 mapping 模块规范。

## 阶段 1：调试 Agent 模板落地

### 文件

- 新增：`.agents/agents/astd-debug-verifier.agent.md`

### 要求

- frontmatter 包含 `name`、`description`、`tools`、`model`。
- 描述包含触发关键词：debug、VFX parity、preview/runtime mismatch、automation、simulation、subAgent verification。
- 正文定义主调试 Agent 如何调用验证 subAgent。
- 正文包含固定 subAgent prompt 模板和验收输出格式。
- 明确验证 subAgent 默认只读，禁止改代码、禁止写回 `contents/`、禁止扩大任务。
- 明确最多 3 轮验收循环。

### 验收

- 文件位于 `.agents/agents/astd-debug-verifier.agent.md`。
- YAML frontmatter 可解析。
- 包含 `PASS` / `FAIL` 输出格式。
- 包含本次首版游戏内 VFX 验收标准。

## 阶段 2：ASTD 测试表面

### 推荐文件

- `src/main/kotlin/cn/kasuminova/astd/internal/debug/ASTDInGameAutomationScenario.kt`
- `src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt`
- `contents/data/config/astd_automation_scenarios.json`

### 最小职责

- 提供 `arc_flare_aod7_basic` 场景配置。
- 确认 `astd_arc_flare` / `astd_arc_flare_Standard` 与 `astd_aod7` / `astd_aod7_shot` 可解析。
- 在 devMode 或系统属性启用时，为自动化提供可查询状态：场景 id、舰船 id、武器 id、弹体 id、VFX preset id。
- 战斗中记录 telemetry：是否存在 arc_flare、是否触发 aod7、是否出现 `astd_aod7_shot` projectile、是否安装 VFX runtime plugin。

### 首版建议

优先利用现有 `AsteriaTestCampaignBootstrap` 注入仓储，保持战役路径可用。若模拟战 UI 自动化成本过高，允许新增 dev-only combat plugin 或 mission/test scenario 作为中间跳板，但验收产物必须显示 `arc_flare` 在战斗中并触发 `aod7`。

## 阶段 3：SSOptimizer 自动化主控

### 推荐模块

```text
github.kasuminova.ssoptimizer.common.automation
  AutomationConfig
  AutomationStateMachine
  AutomationScenarioRunner
  AutomationTelemetryWriter
  AutomationScreenshotWriter

github.kasuminova.ssoptimizer.mixin.automation
  仅放 Mixin hook/accessor

github.kasuminova.ssoptimizer.asm.automation
  仅放 Mixin 无法覆盖的 hook
```

### 最小职责

- 读取系统属性：
  - `ssoptimizer.automation.enabled=true`
  - `ssoptimizer.automation.scenario=arc_flare_aod7_basic`
  - `ssoptimizer.automation.outputDir=<path>`
- 自动化状态机至少包含：`LaunchReady`、`CampaignReady`、`CombatReady`、`FireObserved`、`VfxObserved`、`Completed`、`Failed`。
- 在日志中输出统一 tag：`[SSO-Automation]`。
- 将 telemetry 写入 outputDir，至少包含：scenario、state、shipId、weaponId、projectileSpecId、projectileObserved、vfxObserved、screenshotPath、failureReason。

### 首版触发策略

优先选择稳定路径：

1. 启动游戏并确认 ASTD 加载。
2. 进入 dev/test 场景。
3. 进入战斗或模拟战。
4. 控制/触发 `aod7` 开火。
5. 在弹体出现后截图并写 telemetry。

UI 自动化可通过 SSOptimizer 输入注入、已有 smoke test 输入宏、或直接通过游戏 API 状态推进实现。首版以稳定可验收为优先。

## 阶段 4：验证脚本与报告

### 推荐文件

- SSOptimizer：`tools/smoke_test_game_launch.sh` 增加 `automation` 模式，或新增 `tools/smoke_test_automation.sh`。
- ASTD：`tools/verify_ingame_vfx_automation.py` 用于读取 telemetry 和截图。

### 验收规则

脚本必须检查：

- 游戏日志包含 `[SSOptimizer] Agent loaded` 或等价 agent 加载证据。
- 游戏日志包含 `[ASTD] Asteria Directorate loaded`。
- 自动化 telemetry 存在。
- telemetry 中 `scenario=arc_flare_aod7_basic`。
- telemetry 中 `shipId=astd_arc_flare` 或 variant `astd_arc_flare_Standard`。
- telemetry 中 `weaponId=astd_aod7`。
- telemetry 中 `projectileSpecId=astd_aod7_shot`。
- telemetry 中 `projectileObserved=true`。
- telemetry 中 `vfxObserved=true` 或截图存在且帧号位于开火后。

## 阶段 5：完整验收

### 必跑命令

ASTD：

```bash
./gradlew build
```

SSOptimizer：

```bash
./gradlew test
```

游戏自动化烟测：

```bash
./tools/smoke_test_game_launch.sh /mnt/windows_data/Games/Starsector098-linux 90 automation
```

如果 SSOptimizer 脚本路径或参数不同，实现 subAgent 需要以仓库实际结构调整，并在验收报告中写明实际命令。

### 完成标准

- 构建和单元测试通过。
- 自动化烟测生成 telemetry 或报告。
- 报告显示 `arc_flare` 在模拟战或等价战斗场景中。
- 报告显示 `aod7` 开火。
- 报告显示 `astd_aod7_shot` 弹体被观察到。
- 报告显示 VFX 被触发或截图可见。

## subAgent 实施要求

实现 subAgent 必须：

- 先读取本实施文档和两个设计文档。
- 修改 ASTD 与 SSOptimizer 时分别遵守各自仓库规范。
- 每个阶段完成后运行相关测试。
- 输出 changed files、commands run、evidence、known gaps。
- 遇到游戏烟测需要人工窗口交互时，改用可记录的脚本/日志证据推进，并说明阻塞点。
