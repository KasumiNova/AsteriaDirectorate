# Tooltip Style Editor

用于编辑和预览 ASTD 船插 tooltip 的内容、主题颜色、布局与 GLSL 背景，并导出 JSON 和 Kotlin 示例代码。

## 安装

```bash
cd tools/tooltip-style-editor
npm install
```

## 开发

```bash
npm run dev
```

Vite 会输出本地访问地址。当前预设保存在浏览器 localStorage 的 `astd-tooltip-style-editor-preset` 键中。

## 构建

```bash
npm run build
```

构建命令先执行 TypeScript 编译，再由 Vite 生成生产文件。

## 测试

```bash
npm test
npm run test:run
```

单独运行导出测试：

```bash
npm test -- src/export/gameTooltipExport.test.ts
```

## 编辑与导出

编辑器支持段落、章节标题、间隔和提示行，提供主题颜色、面板宽度、fragment shader 与 uniform 编辑，以及 WebGL 预览。

`src/export/gameTooltipExport.ts` 导出包含 `schemaVersion`、`kind`、`hullmod`、`theme`、`background` 和 `blocks` 的 JSON。

`src/export/kotlinTooltipExport.ts` 生成 `TooltipMakerAPI` 示例代码。背景着色器需要在游戏端通过 `ASTDShaderTooltipBackground` 接入。

## WebGL 与 GLSL 限制

预览使用 WebGL 和 GLSL ES 1.00 fragment shader：

- 声明精度，例如 `precision mediump float;`。
- 使用 `gl_FragCoord` 和 `gl_FragColor`。
- 不使用桌面 GLSL 特性、几何着色器或计算着色器。
- uniform 名称必须与编辑器提供的名称一致。
- 浏览器预览用于调整效果，游戏内效果以 Starsector 渲染结果为准。

## 与 projectile-vfx-preview 的边界

`tools/projectile-vfx-preview/` 是独立的弹体特效预览工具。两个工具共用浏览器预览的技术路线，但负责不同领域：本工具处理 tooltip 布局、文本样式与着色器面板背景。改动应限制在 `tools/tooltip-style-editor/` 内，除非有计划中的跨工具重构。
