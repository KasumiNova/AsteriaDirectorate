# ASTD 调试 Agent 与 subAgent 验收实施文档

日期：2026-05-17

## 目标

落地一个 ASTD 仓库内的调试专用 Agent 文件，使后续调试流程能强制进入 subAgent 验收闭环。该 Agent 需要指导其他 Agent：修复完成后必须调用独立验证 subAgent，验证 subAgent 以只读为默认边界，返回 `PASS` / `FAIL` 证据。

## 目标文件

```text
.agents/agents/astd-debug-verifier.agent.md
```

## 文件格式要求

frontmatter：

```yaml
---
name: ASTD Debug Verifier
description: "Use when: debugging ASTD bugs, VFX parity issues, preview/runtime mismatch, automation failures, simulation failures, or when a subAgent must verify code results."
tools: [read, search, execute, agent, todo]
model: GPT-5.5 (copilot)
---
```

如 VS Code 不接受指定模型名，实现 subAgent 可移除 `model` 字段，并在验收报告中说明。

## 正文必须包含

### 1. 角色

- 主调试 Agent：复现、定位、修复、自检、调用验证 subAgent。
- 验证 subAgent：只读审查、运行验证、输出证据。

### 2. 强制流程

1. Reproduce
2. Diagnose
3. Fix
4. Self-check
5. Delegate verification
6. Decide

### 3. 验收循环限制

- 最多 3 轮。
- 每轮必须新增证据、修复明确失败点或排除一个假设。
- 第 3 轮仍失败时停止继续改动并报告用户。

### 4. subAgent Prompt 模板

必须包含字段：

- `task`
- `changedScope`
- `readOnlyScope`
- `verificationCommands`
- `riskKeywords`
- `doNotDo`
- `returnFormat`

### 5. 验证 subAgent 输出格式

必须包含字段：

- `verdict`
- `scopeReviewed`
- `commandsRun`
- `evidence`
- `coverageGaps`
- `blockingIssues`
- `recommendedNextStep`

### 6. ASTD 专用约束

必须包含：

- ss-csv 默认只生成到 `build/generated/ss-csv/`。
- BoxUtil 只读。
- 禁止反射式兼容探测。
- Java 17。
- VFX 优先使用 BoxUtil 与 ASTD 弹体 VFX 管线。
- 玩家可见文本修改后调用 copy-review。

### 7. 首版游戏内自动化验收标准

必须包含本次任务的标准：

- 能看到 `arc_flare` 舰船位于模拟战或等价自动化战斗场景中。
- 能操控 `aod7` 开火。
- 能看到 `aod7` 弹体特效。
- 验收结果包含日志、截图或 telemetry 证据。

## 验收

实现后执行：

```bash
grep -n "ASTD Debug Verifier\|subAgent\|PASS\|FAIL\|arc_flare\|aod7" .agents/agents/astd-debug-verifier.agent.md
```

并检查：

- YAML frontmatter 位于文件开头。
- `description` 含 Use when 与触发关键词。
- `tools` 覆盖 read/search/execute/agent/todo。
- 正文明确禁止验证 subAgent 修改文件。
- 正文明确完整验收标准。

## subAgent 实施要求

实现 subAgent 只需要落地 Agent 文件。禁止在本阶段修改 ASTD 业务源码。完成后输出：

- changed files
- frontmatter 摘要
- grep 验收结果
- 任何兼容性调整
