# 通用游戏特效预览工具设计

日期：2026-05-28

## 目标

创建 `tools/game-vfx-preview/`，作为通用游戏特效效果预览工作台。第一版内置一个缓慢旋转的蓝色辉星 preset，但项目结构必须支持继续扩展其他游戏 VFX，而不是绑定单个特效。

## 约束

- UI 使用 Material Design 3 组件库，默认选型为 `@material/web`。
- 严禁手写非必要组件样式；CSS 只用于布局、viewport/canvas 尺寸、MD3 token 和少量间距 glue。
- 渲染优先 WebGL，核心效果不得用 Canvas 2D 线条/笔刷语义实现。
- 项目约定和开发规范写入 `.agents/skills/game-vfx-preview-guidelines/SKILL.md`。

## 交付形态

- 技术栈：Vite + React + TypeScript + `@material/web`。
- 渲染：WebGL full-screen shader pass。
- 目录：`tools/game-vfx-preview/`。
- 首个效果：`rotating-blue-starburst`。

## 界面结构

- 顶部：MD3 top app bar 风格标题区和当前 effect 状态。
- 中央：WebGL preview canvas。
- 右侧：MD3 controls，包括 effect 选择、播放开关、时间/速度/强度/光刺数量等参数。
- 侧栏：JSON preset 预览，便于后续导出和迁移到 runtime。

## 数据流

1. `EffectPreset` 定义 effect id、名称、参数和 shader key。
2. React UI 修改参数。
3. 参数转换为 WebGL uniforms。
4. WebGL renderer 绘制 full-screen pass。
5. JSON preview 从同一个 preset state 序列化。

## 验证标准

- `npm run test:run` 通过。
- `npm run build` 通过。
- dev server 中能看到非空 WebGL 画面。
- 蓝色辉星缓慢旋转，中心高亮、周围有柔和蓝色 glow 与多方向光刺。
- UI 控件来自 MD3 组件库，未手写替代控件样式。
