# Projectile VFX Preview Layer Matrix — 2026-05-12

## 自动化静态 artifact

- 矩阵定义：`docs/dev-docs/projectile-vfx-parity/2026-05-12-preview-layer-matrix.static.json`
- 预览截图目标目录：`docs/dev-docs/projectile-vfx-parity/captures/preview/`
- 游戏内截图目标目录：`docs/dev-docs/projectile-vfx-parity/captures/game/`

该 JSON 记录 12 个验收场景、layer visibility、采样时间、预期保存路径，可作为自动截图脚本或人工采集的核验输入。

Preview 工具当前可通过 UI layer toggles 生成下列矩阵截图：

1. Trail only
2. Trail + Head
3. Trail + Glow
4. Trail + Mist
5. Trail + Ribbon
6. Trail + SideWisps
7. All layers
8. Paused
9. Hit fade
10. Expire fade
11. High-speed movement
12. Curved movement preview parity sample

可用入口：`tools/projectile-vfx-preview` → `npm run build` / `npm run dev` → `Screenshot PNG`。

建议采集命令：

1. `cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview`
2. `npm run build`
3. `npm run dev`
4. 在预览 UI 中按 `2026-05-12-preview-layer-matrix.static.json` 切换 layer 与时间点，点击 `Screenshot PNG`。
5. 将文件保存到 `docs/dev-docs/projectile-vfx-parity/captures/preview/<matrix-id>.png`。

自动化说明：当前项目没有 Playwright/浏览器截图依赖，Vitest/jsdom 对 `HTMLCanvasElement.getContext()` 输出 `Not implemented`，因此本次生成文本验收矩阵与可操作步骤，未伪造游戏内截图。

游戏内截图缺口：需要人工启动 `./gradlew launchGame` 后在战斗内按同一 layer 矩阵采集。当前自动化验证覆盖 runtime 数据路径、颜色采样、生命周期 fade 和 build/test。

建议游戏内采集命令：

1. `cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate`
2. `./gradlew build`
3. `./gradlew launchGame`
4. 在战斗内触发 AOD-7 `astd_aod7_shot`，按矩阵顺序采集。
5. 将文件保存到 `docs/dev-docs/projectile-vfx-parity/captures/game/<matrix-id>.png`。
