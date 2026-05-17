# AOD-7 Projectile VFX Parity 纠偏报告

日期：2026-05-12

## 本轮反馈

- 游戏内表现：左侧巨大白色发光团，长框/投影明显，右侧弹头很小。
- 预览器表现：右侧弹头，向左延伸的细长蓝白光束，整体更细、更平滑。

## 已确认根因与修复

| Layer | 修复项 | 状态 |
|---|---|---|
| Trail | 本地坐标统一为 head `(0,0)`、tail 负 X；`renderFacing` 原值传给 BoxUtil；BoxUtil start 端写 tail 参数、end 端写 head 参数 | Pass |
| Trail | runtime 使用 preview `widthBase = max(startWidth * 0.075, 3.5)`，AOD-7 `80 -> 6`；tail width `4 -> 0.3` | Pass |
| Trail | 每帧按 `context.visibleLength` 更新 local nodes | Pass |
| Legacy TrailEntities | `localBeamNodes()` 改为负 X；`updateExportHandle()` 使用 facing 原值；start/end 语义与 renderer 对齐 | Pass |
| Glow | 使用负 X endpoints；line width = `widthBase * widthScale`，glow0 = `32.4`；使用 `context.renderFacing` | Pass |
| Glow | tail/head color 映射与 BoxUtil start/end 对齐 | Pass |
| Head/Beam | head 顶点保持 preview 负 X 坐标，补齐闭合 shape 顶点，运行时使用独立 shape layer 路径 | Pass |
| Mist | 使用 `context.visibleLength` 与共享 `widthBase` | Pass |
| Side Wisps | 使用共享 local path helper，随 `visibleLength` 更新 | Pass |
| Ribbon | 保持 history sampling 与共享 math 测试向量 | Pass |
| Debug | 新增 `contents/data/config/astd_projectile_vfx_debug.json`，支持 trail/head/glow/mist/sideWisps/ribbon/logLayoutOnce | Pass |

## 新增护栏

- Kotlin：`ASTDProjectileVfxCoordinateContractTest`
- Kotlin：`ASTDProjectileVfxWidthMappingTest`
- Kotlin：`ASTDProjectileVfxLayoutParityTest`
- Kotlin：`ASTDProjectileVfxDebugTest`
- TypeScript：`src/render/projectileVfxLayout.ts`
- TypeScript：`src/render/projectileVfxLayout.test.ts`

## 验证结果

```text
cd tools/projectile-vfx-preview
npm run test:run

Test Files  17 passed (17)
Tests       58 passed (58)
```

```text
cd tools/projectile-vfx-preview
npm run build

✓ built in 375ms
```

```text
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate
./gradlew test --tests 'cn.kasuminova.astd.renderer.projectile.*'

BUILD SUCCESSFUL
```

```text
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate
./gradlew build

Generated 51 file(s) with 185 entry/entries.
BUILD SUCCESSFUL
```

## 分层验收状态

| Matrix | 状态 | 说明 |
|---|---|---|
| Trail only | 自动测试 Pass，截图待采集 | 坐标、宽度、方向、visibleLength 已由测试覆盖 |
| Trail + Head | 自动测试 Pass，截图待采集 | head shape 顶点与 alpha 已覆盖 |
| Trail + Glow | 自动测试 Pass，截图待采集 | glow0 宽度 32.4，4 层参数已覆盖 |
| Trail + Mist | 自动测试 Pass，截图待采集 | deterministic samples 与 visibleLength 路径已覆盖 |
| Trail + Ribbon | 自动测试 Pass，截图待采集 | shared math vectors 已覆盖 |
| Trail + SideWisps | 自动测试 Pass，截图待采集 | 4 条 offset path 与 transform 已覆盖 |
| All layers | 自动测试 Pass，截图待采集 | 需游戏内人工截图确认整体亮度与平滑度 |
| Paused / Hit fade / Expire fade / High-speed | 自动测试 Pass，截图待采集 | lifecycle/fade context 已覆盖基础数学，视觉仍需截图 |

## 人工截图确认清单

采集路径建议：

- Preview：`docs/dev-docs/projectile-vfx-parity/captures/preview/<matrix-id>.png`
- Game：`docs/dev-docs/projectile-vfx-parity/captures/game/<matrix-id>.png`

优先采集：

1. `trail-only`
2. `trail-head`
3. `trail-glow`
4. `trail-mist`
5. `trail-ribbon`
6. `trail-side-wisps`
7. `all-layers`
8. `paused`
9. `hit-fade`
10. `expire-fade`
11. `high-speed`

通过标准：右侧弹头、尾迹向左延伸、左端收细暗化、右端弹头与束体同轴、无左侧巨大白团、无 432 级别宽度膨胀、无长框投影。
