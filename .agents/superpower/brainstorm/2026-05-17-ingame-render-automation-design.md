# 游戏内自动化渲染测试最终实现设计

日期：2026-05-17

## 目标

建立一套由 SSOptimizer 主体驱动的端到端自动化渲染测试框架，用于验证 ASTD 弹体 VFX 在游戏内运行时、Web Preview 与 golden 截图之间的一致性。框架覆盖游戏启动、战役进入、装配界面预览、战斗模拟、开火控制、截图采集、runtime telemetry、图像对比、热重载与动态执行。

## 总体架构

采用 `Agent 全托管` 架构：SSOptimizer 作为自动化主控层，ASTD 作为被测模组。SSOptimizer 负责启动、注入、场景推进、UI 控制、战斗控制、截图采集、telemetry 导出、热重载与报告生成。ASTD 保持被测对象定位，仅提供必要的 dev-only 测试表面，例如 VFX preset hash、运行时节点快照和 renderer telemetry。

核心组件：

- **SSOptimizer Automation Agent**：游戏启动、javaagent 注入、Mixin/ASM hook、场景推进、动态命令执行、截图与 telemetry。
- **ASTD Test Surface**：dev-only telemetry provider、VFX preset hash、运行时 entity 状态导出。
- **External Runner**：批量执行测试矩阵、读取截图与 telemetry、执行图像/数据对比、生成报告。

端到端路径：启动游戏 → 进入战役 → 打开装配界面 → 进入战斗模拟 → 控制舰船开火 → 捕获截图与内部数据 → 对比 preview/golden/runtime telemetry → 输出报告。

## 启动与场景编排

SSOptimizer 提供 `automation` profile。启动脚本注入 javaagent 并加载自动化配置。Agent 在游戏主循环稳定后接管流程：等待标题界面完成、创建或载入 dev 存档、进入战役层、定位目标舰船/武器、打开装配界面并进入战斗模拟。

场景编排采用状态机：

1. `LaunchReady`：确认 JVM、LWJGL、Starsector 主类、模组列表就绪。
2. `CampaignReady`：确认 campaign engine 和 player fleet 可访问。
3. `RefitReady`：打开指定舰船装配界面，设置武器组和目标装配。
4. `SimulationReady`：进入模拟战斗，生成测试目标、锁定相机、冻结干扰变量。
5. `CaptureReady`：按脚本控制开火、采样 telemetry、截图。

每个状态输出超时信息、失败原因、截图和内部快照，用于定位卡死点。

## 战斗控制与截图采集

战斗模拟由 Agent hook `CombatEngineAPI` 和输入层完成。Agent 创建固定测试环境：固定地图、固定相机、固定舰船朝向、固定目标距离、固定武器装配、固定时间倍率。测试脚本按帧执行：等待冷却、触发开火、记录 projectile id、跟踪弹体生命周期，并在关键帧采集截图。

截图分三类：

- **Frame capture**：整屏截图，用于肉眼回放和报告。
- **ROI capture**：围绕弹体 bounding box 裁剪，用于图像对比。
- **Debug overlay capture**：叠加弹体轴线、head/tail、节点、宽度、颜色采样点。

采样点由 runtime telemetry 提供。截图命名包含 preset、武器、帧号、弹体 id、配置 hash。External Runner 读取同名 JSON，完成截图与数据绑定。

## Telemetry 与对比算法

每次捕获生成一组绑定产物：截图 PNG、runtime telemetry JSON、preview expected JSON、对比报告 JSON。telemetry 记录 projectile spec、preset hash、trail/head/glow/mist/ribbon 的关键节点、颜色、alpha、宽度、可见长度、坐标变换和生命周期时间。

对比采用三层校验：

1. **数据一致性**：runtime layout 与 preview layout 对比，容差按像素/浮点分别配置。
2. **图像一致性**：ROI 内做结构相似度、边缘轮廓、主色分布、alpha 覆盖面积对比。
3. **语义一致性**：检查 head 在弹体头部、tail 沿负 X、ribbon 跟随 trail、glow 包围主体、生命周期淡入淡出顺序正确。

失败报告保存红框标注图、差异热力图、关键 telemetry diff，并给出失败层级。

## 热重载与动态执行

热重载由 SSOptimizer Agent 提供两层能力。

第一层是脚本与配置热加载：External Runner 写入命令或 JSON，Agent 在安全帧边界读取并应用，支持切换 preset、重新生成测试矩阵、重置战斗、重新捕获截图。

第二层是方法体热重载：Agent 使用 `Instrumentation.redefineClasses` 替换已加载类的方法体，用于快速迭代 renderer、layout、automation driver。约束为保持类名、字段和方法签名稳定。热重载成功后写入 reload id，后续截图和 telemetry 绑定该 reload id。

动态执行采用受限命令集：场景跳转、舰船控制、武器触发、相机控制、截图、telemetry dump、VFX debug 开关。每条命令返回状态、耗时、错误栈和当前游戏阶段。

## SSOptimizer 模块结构

在 SSOptimizer 内新增自动化领域模块，保持 patch 与业务逻辑分离：

- `bootstrap/automation`：解析 profile、启动 JSON-RPC/file queue、注册 agent 服务。
- `mixin/automation`：只放 Starsector/LWJGL/CombatEngine/Refit UI hook。
- `asm/automation`：处理 Mixin 覆盖不到的输入、截图或私有调用点。
- `common/automation`：状态机、命令协议、场景 driver、telemetry、截图、报告落盘。
- `common/hotreload`：类重定义、脚本/配置热加载、reload id 管理。
- `common/renderprobe`：OpenGL readPixels、ROI 裁剪、debug overlay、帧同步。

ASTD 侧只保留测试表面：dev-only telemetry exporter、VFX preset hash、运行时节点快照。Agent 通过 Mixin/API 读取它们。

## 测试与验收

验收分四级：

1. **单元测试**：SSOptimizer 命令协议、状态机、telemetry schema、截图命名、hot reload 约束；ASTD telemetry exporter 和 VFX hash。
2. **注入测试**：Mixin/ASM hook 命中目标类，关键 accessor 可用，游戏版本签名匹配。
3. **本地烟测**：自动启动游戏、进入战役、打开装配、进入模拟战斗、触发 AOD-7 开火、生成截图和 telemetry。
4. **视觉回归**：同一 preset 的 preview expected、runtime telemetry、ROI 图像通过阈值；失败输出 diff 包。

首个 golden 用 AOD-7 建立：固定分辨率、固定缩放、固定舰船、固定武器槽、固定帧序列。后续扩展多武器、多分辨率、多时间倍率矩阵。

## 首版推荐边界

首版交付聚焦 AOD-7：完成 SSOptimizer automation profile、状态机、战斗模拟开火、ROI 截图、runtime telemetry、preview expected 对比、报告落盘、脚本/配置热加载和方法体热重载。装配界面自动化保留为首版路径的一部分，先验证打开与截图，再扩展到交互式装配修改。
