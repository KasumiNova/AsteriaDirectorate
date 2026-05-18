# 自动化 VFX 截图稳定性设计

日期：2026-05-17

## 目标

- 游戏自动化窗口使用 `2560x1440`。
- ASTD 自动化 mission 中我方 `arc_flare` 与敌舰保持足够距离，避免交战影响截图。
- 自动化期间镜头锁定到固定位置和缩放，避免鼠标位置影响截图。
- SSOptimizer 输出多帧低损失压缩截图，供模型视觉确认弹体特效。
- telemetry 继续验证 `astd_aod7_shot` 与 `aod7_shot` 已触发。

## 方案

1. SSOptimizer 烟测脚本在 `automation` 模式默认使用 `2560x1440` 分辨率。
2. ASTD mission 扩大地图；ASTD 自动化插件在初始化和每帧固定我方/敌方位置、朝向、速度与 AI 状态。
3. ASTD 自动化插件每帧设置 combat viewport 的 center 与 view multiplier，形成固定取景。
4. SSOptimizer `AutomationScreenshotHelper` 在 `Completed` 后采集多帧 JPG，并在 telemetry 中写入主截图与 `screenshotFrames` 列表。
5. 验收流程保留 JSON verifier，再读取 JPG 多帧进行模型视觉确认。

## 风险与约束

- ASTD 脚本侧仍受 Starsector 沙盒约束，截图与文件写入继续由 SSOptimizer helper 承担。
- ASTD combat plugin 为 Janino 加载，SSOptimizer 对该类继续使用 ASM 注入。
- JPG 质量采用低损失设置，目标是控制模型 API 输入大小并保留 VFX 视觉细节。
