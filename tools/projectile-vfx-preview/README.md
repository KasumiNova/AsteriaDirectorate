# ASTD Projectile VFX Preview

独立网页单页工具，用于在游戏外预览 ASTD 弹体拖尾、调参和导出 BoxUtil 风格 JSON。

## 安装

```bash
cd tools/projectile-vfx-preview
npm install
```

## 开发

```bash
npm run dev
```

## 构建

```bash
npm run build
```

## 测试

```bash
npm run test:run
```

## AOD-7 确定性验收截帧

预览侧的 AOD-7 parity reference 由 `src/ui/capture.ts` 的 `DEFAULT_AOD7_PARITY_CAPTURE_SPEC` 固定：

- preset id: `aod7_shot`
- size: `1280x720`
- elapsed: `0.42s`
- layers: trail/head/glow/mist/sideWisps/ribbon 全开
- output: `docs/dev-docs/projectile-vfx-parity/captures/preview/aod7-all-layers-reference.png`

当前实现提供浏览器端 deterministic capture API，返回 PNG data URL 与上述 metadata。仓库尚未引入直接 Node canvas/Playwright 依赖，因此真实 PNG 落盘需要通过预览页面或后续 CLI runner 调用该 API。

## 输入格式

- 顶层为 JSON object。
- `trailEntities`、`spriteEntities`、`segmentEntities`、`timeline`、`previewCamera`、`simulation` 对应内部预览模型。
- `TrailEntityConfig` 重点字段：`nodes`、`startColor`、`endColor`、`startEmissive`、`endEmissive`、`startWidth`、`endWidth`、`texturePixels`、`textureSpeed`、`uvOffset`、`fillStartAlpha`、`fillEndAlpha`、`fillStartFactor`、`fillEndFactor`、`jitterPower`、`flick`、`syncFlick`、`stripLineMode`、`flowWhenPaused`、`flickWhenPaused`、`flickMixValue`、`flickerSyncCode`、`blendMode`。

## BoxUtil API 字段映射

- `TrailEntityConfig` → BoxUtil `TrailEntity` 预览语义。
- `SpriteEntityConfig` → BoxUtil `SpriteEntity` 预览语义。
- `SegmentEntityConfig` → BoxUtil `SegmentEntity` 预览语义。
- `TimelineConfig` → 预览时间轴。
- `PreviewCameraConfig` → 画布观察相机。
- `SimulationConfig` → 预览播放与循环策略。

## 导出与 ASTD 回填

1. 在右侧配置面板粘贴 JSON。
2. 点击 Apply 校验并更新预览。
3. 调整 TrailEntity 参数并观察画面变化。
4. 点击 Export 生成格式化 JSON。
5. 将导出的 JSON 回填到 ASTD preset 或相关配置文件。

## 例子

可直接载入 `examples/basic-trail.json` 观察默认拖尾。
