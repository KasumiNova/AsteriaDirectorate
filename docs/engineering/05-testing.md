# 测试流程（构建 / 部署 / 弹体 VFX）

本文档用于在本仓库内快速验证：
- `ss-csv` 生成的 CSV 是否正确落盘
- mod 的生产目录是否可部署
- `ProjectileSpecOnFireDispatcher` 与 `ProjectileVfxRegistry` 的弹体 VFX 是否按 `projectileSpecId` 生效

## 1) 一键启动（推荐）

本项目提供 `launchGame` 任务：会先构建并部署到游戏目录，再启动 Starsector。

前置：
- `gradle.properties` 中 `starsector.gameDir` 指向游戏根目录（例如 `/.../Starsector098-linux`）。

执行：
- `./gradlew launchGame`

## 1.1) 自动烟测（推荐）

本项目提供与 SSOptimizer 类似的启动烟测脚本，会优先使用游戏目录中的 `launch_injected_ss.sh`，因此可覆盖 SSOptimizer Java Agent 注入路径。

执行启动器路径烟测：
- `./gradlew smokeTestLauncher`

执行自动进入游戏路径烟测：
- `./gradlew smokeTestGame`

脚本会检查 `starsector.log` 与进程输出中的启动期致命错误，包括本模组 `astd_*` 数据缺失、Asteria 类加载错误、JVM 崩溃标记与 Starsector `CombatMain` 致命异常。其它启用模组在加载阶段输出的非致命缺失规格日志会保留在日志中，烟测判定聚焦本模组与客户端崩溃。

独立运行脚本：
- `bash tools/smoke_test_game_launch.sh /mnt/windows_data/Games/Starsector098-linux 10 launcher`
- `bash tools/smoke_test_game_launch.sh /mnt/windows_data/Games/Starsector098-linux 20 game`

## 2) 仅部署（不启动）

如果你习惯手动启动游戏或用 Steam/脚本启动：

- `./gradlew deployMod`

它会将 `build/mod_production` 覆盖部署到：
- `${starsector.gameDir}/mods/${mod.id}`

> 注意：实际加载到游戏里的 mod 目录是 `${mod.id}`（默认 `asteria_directorate`），
> 而不是仓库文件夹名 `StarsectorModDemo`。

另外还有一个 `deployToStarsector` 任务，会把 production 目录复制到 `starsector.modsDir` 下。
- 需要在 `gradle.properties` 中设置 `starsector.modsDir`。

## 3) ss-csv：生成与覆盖 contents/data

### 只生成到 build 目录（安全）

- `./gradlew :ss-csv:generateSsCsv`

输出位置：
- `build/generated/ss-csv/`

### 直接覆盖写入 contents（危险但方便）

- `./gradlew :ss-csv:writeSsCsvToContents -PssCsvForce=true`

这会覆盖：
- `contents/data/weapons/weapon_data.csv`

建议：
- 修改 `ss-csv/src/main/kotlin/.../Catalog_WeaponData_*.kt` 后再运行该任务
- 覆盖前先提交/备份，方便回滚

## 4) 弹体 VFX 验证清单

### 4.1 确认 dispatcher 生效

开启游戏 devMode（Starsector 的全局 devMode），进入任意战斗后：
- 第一次发射带 `ProjectileSpecOnFireDispatcher` 的武器时，应该只出现一次浮字：`[ASTD] VFX onFire OK`
- 同时 `starsector.log` 应看到：`ProjectileSpecOnFireDispatcher.onFire invoked`

### 4.2 确认 preset 映射生效

映射文件：
- `data/config/astd_projectile_vfx.json`

你可以通过修改其中的 `preset` 来快速切换弹体表现（无需改 `.proj`）。

### 4.3 关键视觉检查点

- 弹体是否可见（透明贴图 `BUtil_NONE.png` 的弹体必须依赖代码渲染）
- 不同武器之间是否“辨识度足够”（颜色、尾迹长度、粒子密度至少有一项明显差异）
- 触发超射程淡出后：
  - 弹体不会出现“末帧变亮/变不透明”的闪烁
  - `ParticleSprayStyle.emitWhileFading=false` 的弹体在 `projectile.isFading` 后不再继续刷粒子

## 5) 常见问题

### 5.1 配置文件写错导致 preset 不生效

`ProjectileVfxRegistry` 会在配置不存在/格式错误时回退到默认映射。
建议：
- 修改 `data/config/astd_projectile_vfx.json` 后先检查 JSON 合法性
- 进游戏看 `starsector.log` 是否有 `unknown preset` 的 warn

### 5.2 部署失败/覆盖不生效

- 确认游戏已退出（jar 被占用会导致覆盖失败）
- 如果你在 NTFS 挂载盘上开发，某些删除操作可能失败；本项目的 `deployMod` 已尽量使用覆盖式部署减少硬失败
