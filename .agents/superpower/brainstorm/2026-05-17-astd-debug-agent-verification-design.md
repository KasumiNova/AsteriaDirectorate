# ASTD 调试专用 Agent 与 subAgent 验收设计

日期：2026-05-17

## 目标

设计一个 ASTD 仓库内可落地的调试专用 Agent 模板，用于 bug 修复、VFX 调试、preview/runtime mismatch、生成物修复、游戏内自动化调试等场景。核心目标是将“修复”和“验收”拆成两个职责：主调试 Agent 负责复现、定位、修复；验证 subAgent 负责独立验收代码结果，并返回可引用的证据。

## 推荐文件

建议新增 Agent 文件：

`Asteria_Directorate/.agents/agents/astd-debug-verifier.agent.md`

该文件使用 YAML frontmatter 定义名称、描述和工具权限。描述明确触发场景：bug、VFX parity、preview/runtime mismatch、测试失败、自动化渲染异常、游戏启动或战斗模拟失败。

## 角色划分

### 主调试 Agent

职责：

- 收集用户现象、日志、截图、失败测试或浏览器状态。
- 读取相关代码、配置、生成物和测试，形成根因假设。
- 按职责边界修复代码。
- 运行直接相关测试和构建。
- 调用验证 subAgent 做独立验收。
- 根据验证结果继续修复或交付。

权限：

- 可以读写 ASTD 源码、测试、文档和配置。
- 可以运行 Gradle、npm、preview、浏览器验证和 smoke test。
- 可以调用 subAgent。
- Git 操作由主调试 Agent 负责。

### 验证 subAgent

职责：

- 独立审查主 Agent 的改动范围。
- 运行或复核验证命令。
- 检查测试覆盖、生成物一致性、截图报告和 telemetry。
- 输出 `PASS` / `FAIL` 结论与证据。
- 发现问题时给出最小可复现路径。

权限与边界：

- 默认只读代码。
- 可以运行测试、构建、截图采集和报告读取。
- 禁止主动修改文件。
- 禁止写回 `contents/`。
- 禁止扩大任务范围。
- Git 状态只做报告。

## 强制调试流程

1. **复现**：收集现象、日志、截图、失败测试或浏览器状态。
2. **定位**：读取相关代码、配置、生成物和测试，形成根因假设。
3. **修复**：在最小职责边界内改动代码，遵循 ASTD skills 与 ss-csv 生成约束。
4. **自检**：主 Agent 运行直接相关测试/构建，记录命令和结果。
5. **独立验收**：调用验证 subAgent，传入任务、改动范围、验证命令、风险点和禁止改动要求。
6. **决策**：验证 subAgent 返回 `PASS` 才能交付；返回 `FAIL` 时主 Agent 继续修复并再次验收。

独立验收最多循环 3 轮。每轮必须缩小问题：新增一个证据、修复一个明确失败点或排除一个假设。第 3 轮仍失败时，主 Agent 停止继续改动，提交当前证据、失败原因和建议下一步，由用户决策。

## subAgent Prompt 模板

验证 subAgent 的 prompt 固定包含以下字段：

```text
task:
  本轮要验收的具体 bug 或功能结果。

changedScope:
  主 Agent 修改过的文件/模块。

readOnlyScope:
  允许审查的代码、测试、文档、生成物范围。

verificationCommands:
  建议运行的测试、构建、preview、烟测命令。

riskKeywords:
  重点搜索词，例如 VFX preset、layout parity、hardcoded text、generated output。

doNotDo:
  - 禁止改代码。
  - 禁止写回 contents/。
  - 禁止扩大任务范围。

returnFormat:
  - verdict: PASS 或 FAIL
  - scopeReviewed
  - commandsRun
  - evidence
  - coverageGaps
  - blockingIssues
  - recommendedNextStep
```

subAgent 的结论必须基于证据。它可以补跑命令、读取 diff、检查测试覆盖、检查截图报告；发现问题时输出最小可复现路径。

## 验收输出格式

验证 subAgent 必须返回结构化结果：

- `verdict`：`PASS` 或 `FAIL`。
- `scopeReviewed`：审查的文件、测试、报告或截图。
- `commandsRun`：命令、工作目录、退出码。
- `evidence`：通过或失败的关键证据，包含断言、截图路径、报告路径、diff 重点。
- `coverageGaps`：尚未覆盖的风险。
- `blockingIssues`：阻塞交付的问题。
- `recommendedNextStep`：下一步只给一个具体动作。

主 Agent 的交付标准：

- `verdict=PASS`。
- 相关命令退出码为 0。
- `blockingIssues` 为空。
- 存在 `coverageGaps` 时，最终回复必须明示影响范围。
- 视觉类任务必须附带截图或图像对比报告路径。

## 与 ASTD 约束的关系

调试 Agent 必须遵循 ASTD 仓库约束：

- ss-csv 默认只生成到 `build/generated/ss-csv/`。
- BoxUtil 作为只读依赖参考。
- 禁止反射式兼容探测。
- Java 17。
- 避免单文件巨型实现。
- 玩家可见文本修改后需要文案审查。
- VFX 修改优先复用项目 VFX 管线与 BoxUtil 高性能实现。

## 视觉/VFX 调试专用验收

VFX、preview、runtime parity、截图回归类任务的验证 subAgent 需要额外检查：

- Preview 参数面板是否实际影响渲染输出。
- Runtime layout 与 preview layout 是否使用同一语义。
- head/tail 坐标、宽度、颜色、alpha、生命周期是否可由 telemetry 解释。
- 截图、ROI、debug overlay 或图像对比报告是否存在。
- Kotlin 与 TypeScript 测试是否覆盖同一几何/颜色/生命周期规则。

## 推荐最终交付格式

主调试 Agent 最终回复包含：

- 修复范围。
- 主 Agent 自检命令与结果。
- 验证 subAgent verdict。
- 验证证据路径或命令。
- 剩余 coverage gaps。
- 一个明确的后续动作。
