---
name: "texture-loading-guidelines"
description: "贴图加载规范：代码侧渲染的自定义贴图必须 loadTexture 预加载；SpriteAPI 为共享缓存实例须逐帧重置状态；附排查清单。"
---

# Skill：贴图加载与 SpriteAPI 使用规范

## 背景（反复踩过的坑）

在 SSOptimizer 等延迟加载环境下，Starsector 只会把**被数据文件引用过**的贴图上传 GL
（`.wpn`/`.ship`/`sprites.json`/hullmod 图标等）。代码里直接 `Global.getSettings().getSprite(path)`
拿到的是 `textureID=0` 的空壳实例：

- **不报错、不抛异常**，attach/加载日志一切正常；
- 渲染循环照常执行，但屏幕上什么都画不出来。

已踩案例：制式核心军官头像黑壳、武器常驻发光贴图完全不渲染（战斗内逐帧渲染仍不可见）。

## 强制规则

1) **代码侧渲染的自定义贴图，必须在 `AsteriaDirectoratePlugin.onApplicationLoad()` 预加载**
   - 调 `Global.getSettings().loadTexture(path)` 强制上传 GL。
   - 集中放在对应管理器的 `preloadTextures()` / `preloadPortraits()` 方法里，由 ModPlugin 调用。
   - 现有先例：`StandardCores.preloadPortraits()`、`WeaponGlowLayer.preloadTextures()`。
   - 判断标准：只要贴图路径**没有出现在任何数据文件中**（纯代码按约定路径派生的都算），就必须预加载。

2) **`getSprite()` 返回全局共享缓存实例，禁止跨帧持有其可变状态**
   - 混合模式（`setAdditiveBlend`）、颜色、alpha、尺寸都可能被其他渲染方随时改写。
   - 渲染循环中必须**逐帧重取** `getSprite(path)` 并重置全部状态后再画：
     `setAdditiveBlend()` → `color` → `alphaMult` → `setSize()` → `angle` → `renderAtCenter()`。
   - attach 阶段只缓存路径与原始宽高，不要缓存"已设置好状态"的 SpriteAPI。
   - 参考实现：`WeaponGlowLayer`（renderer/effect/system）、`ASTDAfterimageEffect`。

3) **预加载失败必须有日志**
   - `loadTexture` 包 try/catch 并 `log.warn` 打出路径，禁止空 catch（全局规范）。

## 排查清单（贴图"完全不渲染"时按序检查）

1. 贴图是否被任何数据文件引用？没有 → 检查是否 `loadTexture` 预加载（90% 是这个）。
2. 渲染插件是否真的被安装/执行：装 `active` / `first render, attachments=N` 之类一次性日志。
3. attach/匹配逻辑是否命中：attach 时打 weapon id / slot / path 日志。
4. 共享 Sprite 状态是否被改写：改为逐帧重取 + 全量重置后再画。
5. 贴图内容本身：用脚本统计发光像素数与峰值 alpha，排除"渲染正常但贴图太暗"。
6. 图层与混合：确认渲染层（如 `ABOVE_SHIPS_AND_MISSILES_LAYER`）与加法混合是否符合预期。

## 装配界面（refit）不发光的机制说明

- 原版 `turretGlowSprite`/`hardpointGlowSprite` 的 alpha 由开火/充能驱动（glowAmount、chargeProgress），
  待机时恒为 0，因此**装配界面、图鉴里所有武器 glow 都不显示**——这是原版一致行为，不是 bug。
- 装配界面的舰船展示走真实 Ship 渲染管线（`ShipDisplayPanel` 调 `ship.render`），
  但没有公开 API 可注入额外渲染层；按仓库规范禁止用反射挂 CoreUI 面板。
- 如需装配界面也可见发光，唯一规范内方案是**把常亮发光烘进底图**（普通混合），
  代价是失去战斗内加法辉光的观感加成，且需与战斗侧 additive 层统筹强度避免过曝。
