# 正电子冲击波 实现规格 v1（待评审）

> 依据：
> - 设计案定稿：`docs/design/weapons/blue/20-production.md`「正电子冲击波」（已定案 v1.0，2026-07-29 裁定）
> - 全局约定：`docs/design/weapons/90-首批实装计划.md` v6 §6 与全局约定
> - 共享基建：`docs/design/weapons/impl/00-共享基建.md` §2（ConeImpactHandler）、§3（合并协议）、§4.2/§4.3（反馈通道/特效检查表）
>
> 状态：规划文档，不改动 `src/` 与 `ss-csv/`。
> API 核查时间：2026-07-29，对照 `starfarer.api.jar`（0.98）与现有 `src/` / `ss-csv/` 代码逐条核实。
> id 说明：游戏内 id 提案为 `astd_positron_shockwave`（首批计划 ID 表，实现前最终确认）；本文按提案书写，若改名全文键名同步替换。

---

## 0. 与首批计划 §6 的两处实现层修正（必须先读）

1. **「collisionRadius = 0」的真实落地形态**：`specClass = projectile` 的 `.proj` 模型**没有 collisionRadius 字段**（ss-csv `ProjectileProjSpec` 已核实，collisionRadius 仅存在于 `ProjMissileSpec`）。无触碰体积的正确实现是 **`.proj` 的 `collisionClass = "NONE"`**（`CollisionClass.NONE` 已核实存在于 jar；`collisionClassByFighter` 置空不写）。烟测必须验证「射弹穿过舰船/战机不触发碰撞与引爆」。
2. **VFX 挂载分工**：`.proj` 的 `onFireEffect` 挂 `PositronShockwaveOnFireEffect`（引信脚本注册，**不再是** `ProjectileSpecOnFireDispatcher`）；弹体 VFX 追踪改由 `.wpn` 的 `onFireEffect = cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher` 承担（`.wpn` 与 `.proj` 的 onFireEffect 均会逐弹触发，dispatcher 自带去重，此分工已对照 `ProjectileSpecOnFireDispatcher` 源码确认可行）。

---

## 1. 数据面

### 1.1 ss-csv catalog 条目（`Catalog_WeaponData_ARC.kt` 末尾追加）

`object Wpn_astd_positron_shockwave : WeaponDataEntry(), SsProjProjectileOutputs`，逐列取值（未列出的列用基类默认）：

| 字段 | 值 | 依据/备注 |
|---|---|---|
| `id` | `astd_positron_shockwave` | 提案 id |
| `name` | `weaponName(id)` | 走 i18n |
| `tier` | `1` | 量产小件（提案，对照 GCP12 T3 / spc3 T3） |
| `rarity` | `1`（默认） | 量产 |
| `baseValue` | `2500` | 提案（小型 PD 量产件量级，目检经济后可调） |
| `range` | `600` | 设计案面板 |
| `damagePerSecond` | `133` | 200 ÷ 1.5s 折算 tooltip 统计 |
| `damagePerShot` | `200` | 设计案面板（破片） |
| `emp` / `impact` | `0` | 无 |
| `turnRate` | `45` | 提案（PD 快速跟踪档位，目检调整） |
| `ops` | `6` | 设计案面板 |
| `ammo` / `ammoPerSec` / `reloadSize` | 默认（空列） | 无限弹药 |
| `type` | `FRAGMENTATION` | 设计案：200 破片 |
| `energyPerShot` | `100` | 设计案面板 |
| `energyPerSecond` | `67` | 100 ÷ 1.5s 折算 |
| `chargeup` | `0.0` | 无蓄力 |
| `chargedown` | `1.5` | 发射间隔 1.5s（非 Beam 用 chargedown 描述射速，避免 tooltip 除 0） |
| `burstSize` / `burstDelay` | `1` / `0.0` | 单发 |
| `projSpeed` | `900` | 提案（设计案未给弹速；600su ÷ 900 ≈ 0.667s 飞行，目检调整） |
| `flightTime` | `0.667` | = range ÷ projSpeed，弹体原版寿命恰覆盖射程；引信自爆先于此触发 |
| `projHitpoints` | `0`（默认） | 弹道弹体本就不可被拦截，设计「不吃拦截」由 spawnType=BALLISTIC + collisionClass=NONE 共同保证 |
| `aiHints` | `setOf(AiHint.PD)` | 设计案：点防御、优先攻击导弹（`PD` 枚举已在 ss-csv `AiHint` 存在） |
| `tags` | `astd_production` | 量产线 |
| `groupTag` | `astd` | 同线惯例 |
| `tech` | `弧光阵列` | ARC 线 |
| `primaryRoleStr` | `SsI18n.t("weapon.$id.primaryRoleStr")` | 点防御 |
| `customPrimary` / `customPrimaryHL` | `SsI18n.t("weapon.$id.tooltip.customPrimary")` / 同 HL | 对照 gcp12 接线方式 |
| `noDpsInTooltip` | `false`（默认） | 正常显示 DPS |
| `number` | **`9215`** | 合并协议预分配段（正电子 9215） |

**`.proj` 生成（`projSpec`）**：**不走** `ProjectileProjSpec.standard()`（其硬编码 `collisionClass = "PROJECTILE_FF"`），在条目内直接写字面量（避免触碰共享的 `ProjProjectileSpec.kt`，其他九组也在并行改 ss-csv）：

```kotlin
override val projSpec: ProjectileProjSpec = ProjectileProjSpec(
    id = "astd_positron_shockwave_shot",
    spawnType = ProjectileSpawnType.BALLISTIC,
    onFireEffect = "cn.kasuminova.astd.combat.effect.arc.PositronShockwaveOnFireEffect",
    onHitEffect = null,                       // 无触碰体积，无 onHit 路径
    collisionClass = "NONE",                  // 无触碰体积的真实实现（§0-1）
    collisionClassByFighter = null,
    // 原版弹体视觉隐藏四件套（照 Wpn_astd_aod7.projSpec 样板）
    length = 2.0, width = 2.0, fadeTime = 0.2,
    fringeColor = Rgba(140, 200, 255, 0),
    coreColor = Rgba(240, 248, 255, 0),
    textureScrollSpeed = 0.0, pixelsPerTexel = 1.0,
    bulletSprite = "graphics/textures/BUtil_NONE.png",
)
```

### 1.2 `.wpn` JSON 骨架（`contents/data/weapons/astd_positron_shockwave.wpn`，手写）

照 `astd_spc3.wpn` 样板，插件挂载点写全：

```json
{
    "id": "astd_positron_shockwave",
    "specClass": "projectile",
    "type": "ENERGY",
    "size": "SMALL",
    "displayArcRadius": 600,
    "turretSprite": "graphics/textures/BUtil_NONE.png",
    "turretGunSprite": "graphics/textures/BUtil_NONE.png",
    "hardpointSprite": "graphics/textures/BUtil_NONE.png",
    "hardpointGunSprite": "graphics/textures/BUtil_NONE.png",
    "visualRecoil": 0.0,
    "turretOffsets": [0, 0],
    "turretAngleOffsets": [0],
    "hardpointOffsets": [0, 0],
    "hardpointAngleOffsets": [0],
    "barrelMode": "LINKED",
    "animationType": "MUZZLE_FLASH",
    "projectileSpecId": "astd_positron_shockwave_shot",
    "onFireEffect": "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
    "everyFrameEffect": "cn.kasuminova.astd.combat.effect.generic.CombatVfxBootstrapEveryFrameEffect",
    "fireSoundTwo": "flak_fire"
}
```

- `onFireEffect`（`.wpn` 侧）＝弹体 VFX 追踪（§0-2 分工）；`fireSoundTwo = "flak_fire"` 已在原版 `sounds.json` 核实存在。
- `CombatVfxBootstrapEveryFrameEffect` 已核实存在（spc3 同款挂载）。

### 1.3 i18n 键清单（`ss-csv/src/main/resources/i18n/zh-cn.properties` 文件末尾集中追加）

| 键 | 值 | 来源 |
|---|---|---|
| `weapon.astd_positron_shockwave.name` | `正电子冲击波` | 设计案定名 |
| `weapon.astd_positron_shockwave.tooltip.customPrimary` | `射弹在接近导弹或战机时自动引爆，沿飞行方向产生锥状冲击，对范围内所有目标造成破片伤害。效果受到难度系数影响。` | 设计案「玩家可见机制文本」裁定原文 |
| `weapon.astd_positron_shockwave.tooltip.customPrimaryHL` | （空字符串） | 裁定 tip 无 `{%s}` 占位，HL 置空保持键位齐全 |
| `weapon.astd_positron_shockwave.primaryRoleStr` | `点防御` | 提案（原版 PD 角色词惯例） |
| `desc.astd_positron_shockwave.text1` | `弧光科研部的点防御近炸弹。射弹附近存在目标时，会引爆内部的正电子装药，将锥形破片雨泼向来袭的导弹与战机。对敌方的蜂群式导弹效果极佳。` | 设计案「文案」用户优化后裁定原文 |
| `desc.astd_positron_shockwave.text2~text5`、`desc.astd_positron_shockwave.notes` | **不添加** | 设计案只裁定一段描述；`LocalizedDescription` 的 `desc()` 带空串 fallback，缺键输出空列，无需占位 |

### 1.4 `Catalog_Descriptions.kt` 条目

WEAPON 分组尾部（`Desc_astd_psi_omega` 之后）追加一行：

```kotlin
object Desc_astd_positron_shockwave : LocalizedDescription("astd_positron_shockwave", "WEAPON")
```

### 1.5 `contents/data/campaign/special_items.csv` 条目（文件末尾追加）

单件蓝图（量产件 P2 口径；`WeaponBlueprintItemPlugin` + params=武器 id 为模组单件蓝图标准形态，对照原版 `weapon_bp` 行格式）：

```csv
正电子冲击波蓝图,astd_positron_shockwave_bp,"single_bp, astd",弧光阵列,,2500,1000,1,,graphics/icons/cargo/blueprint_weapons.png,ui_chip_pickup,ui_weapon_bp_drop,com.fs.starfarer.api.campaign.impl.items.WeaponBlueprintItemPlugin,astd_positron_shockwave,使重工业设施能够制造出该蓝图所描述的武器。,
```

- `order` 列留空（合并协议：收口人统一编号）；列对齐由收口人统一跑。
- dev 仓储链路：`AsteriaTestCampaignBootstrap` 对 `allSpecialItemSpecs` 经 `isDevStorageSpecialItem` 过滤后自动投放，无需改代码；武器本体也经 `isDevStorageWeapon` 自动入仓储（tags 无拒绝项）。

---

## 2. 代码面

包位置：`src/main/kotlin/cn/kasuminova/astd/combat/effect/arc/`（ARC 线机制包，对照首批计划全局约定第 6 条）。

### 2.1 类清单表

| 类名 | 接口/实现 | 职责 | 挂载点 | 文件路径 |
|---|---|---|---|---|
| `PositronShockwaveOnFireEffect` | `OnFireEffectPlugin` 实现 | 发射时一次性结算难度三锚点（玩家固定 v2），为每发弹体注册引信脚本 | `.proj` 的 `onFireEffect` | `combat/effect/arc/PositronShockwaveOnFireEffect.kt` |
| `PositronShockwaveFuseScript` | `BaseEveryFrameCombatPlugin` 子类 | 弹体生命周期状态机：每帧近炸检测（锥内敌方导弹/战机）→ 引爆；抵达最大射程 → 无条件引爆；引爆编排（结算 + VFX + 反馈 + 移除弹体） | 由 OnFireEffect `engine.addPlugin` 注册 | `combat/effect/arc/PositronShockwaveFuseScript.kt` |
| `PositronShockwaveDifficulty` | `object` 数值登记 | 三锚点 `ScalingEntry` 声明 + `resolve(source)`（玩家固定 v2）；内部纯函数 `reachedMaxRange` / `isFuseTarget` 供引信与单测共用 | 被 OnFireEffect / FuseScript 调用 | `combat/effect/arc/PositronShockwaveDifficulty.kt` |

**不新建的类**（反薄适配层，逐项说明）：
- ~~`PositronShockwaveConeHandler`~~：结算直接调基建件 `ConeImpactHandler.resolve(engine, spec)`（object 无状态结算器），无本武器增量逻辑，不包一层。
- ~~`PositronShockwaveVfx`~~：弹体 VFX 走 `ProjectileVfxSpecs` 登记（§3）；引爆锥面 VFX 走基建锥面组件；引爆音效/小闪光/浮字合计 ≤10 行，直接写在 FuseScript 的 `detonate()` 内。
- **不消费 Buff API**：本武器无叠层/无跨帧数值状态（引信状态随弹体生命周期，由脚本自身持有），明确不走 `StackableBuff`。
- **不消费 CombatRandom**：结算无任何随机成分。

**基建依赖件（只消费、不修改签名）**：
- `ConeImpactHandler.resolve(engine, ConeImpactSpec)` —— 基建 PR 先合 main（`impl/combat/ConeImpactHandler.kt`）。
- 锥面 VFX 组件（基建 §2.2-5「顶点闪光 + 沿中轴扩散的冲击锥」，参数化锥角/长度/调色）—— 导出签名以基建 PR 为准，本文记为 `ConeImpactVfx.spawn(engine, origin, direction, halfAngleDeg, range, color)` 形态调用。

### 2.2 核心逻辑伪代码

**难度数值登记**（`PositronShockwaveDifficulty`，对照设计案三锚点）：

```kotlin
val CONE_ANGLE = ScalingEntry(45f, 56.25f, 90f)        // 锥角（面板全角，非半角）
val CONE_RANGE = ScalingEntry(200f, 250f, 400f)        // 锥长 = 近炸距离（同一参数，裁定）
val DAMAGE_MULT = ScalingEntry(1f, 1.25f, 2f)          // 面板 200 破片的倍率

data class Resolved(val halfAngleDeg: Float, val range: Float, val damage: Float)

/** 玩家（owner == 0）固定 v2；敌方/友军 AI 走 DifficultyTuningImpl.value。对照 ASTDVirtualParticleLatticeWebHullMod:252 既有口径。 */
fun resolve(source: ShipAPI?): Resolved {
    fun pick(e: ScalingEntry) = if (source?.owner == 0) e.v2 else DifficultyTuningImpl.value(e)
    return Resolved(pick(CONE_ANGLE) / 2f, pick(CONE_RANGE), 200f * pick(DAMAGE_MULT))
}
```

**状态机**（FuseScript，两状态 `FLYING → DONE`，无定时器族）：

```
onFire(projectile, weapon, engine):                      // PositronShockwaveOnFireEffect
    if (engine.isPaused) return
    source = weapon.ship
    spec = PositronShockwaveDifficulty.resolve(source)   // 难度取值调用点：发射时一次性锁定
    engine.addPlugin(PositronShockwaveFuseScript(projectile, source, spec))

advance(amount):                                          // FuseScript，engine.isPaused 时引擎不回调仍显式防线
    if (done) return
    if (!engine.isEntityInPlay(projectile) || projectile.isFading) { done = true; return }
    loc = projectile.location
    vel = projectile.velocity
    if (vel.lengthSquared() < 1e-3f) { logOnceWarn("弹体速度近零，本帧跳过引信判定"); return }  // 0 值防线 §2.5

    // 条件 1（优先）：近炸——锥状攻击范围内存在敌方导弹/战机
    dir = vel.normalise()
    detonate = CombatUtils.getEntitiesWithinRange(loc, spec.range).any { e ->
        PositronShockwaveDifficulty.isFuseTarget(e, owner = projectile.owner)   // 严格只导弹/战机/无人机，剔除同方与 hulk
            && coneAngleDeg(loc, dir, e.location, e.collisionRadius) <= spec.halfAngleDeg
    }

    // 条件 2：抵达最大射程——无条件自爆（裁定：不会静默消散）
    if (!detonate) detonate = PositronShockwaveDifficulty.reachedMaxRange(
        projectile.elapsed, projectile.moveSpeed, range = 600f /* weapon spec 面板读取 */)

    if (detonate) detonateAndFinish(engine, loc, dir)

detonateAndFinish(engine, loc, dir):                      // 结算顺序：几何结算 → VFX → 反馈 → 移除
    targets = ConeImpactHandler.resolve(engine, ConeImpactSpec(
        origin = loc, direction = dir,
        halfAngleDeg = spec.halfAngleDeg, range = spec.range,
        damage = spec.damage, damageType = DamageType.FRAGMENTATION, empDamage = 0f,
        source = sourceShip, owner = sourceShip.owner,
        filter = { e -> e.owner != sourceShip.owner },   // 结算波及全部敌对目标（含舰船，裁定「自爆波及」）
        hitShips = true, hitFighters = true, hitMissiles = true,
    ))
    ConeImpactVfx.spawn(engine, loc, dir, spec.halfAngleDeg, spec.range, POSITRON_BLUE)  // 蓝色调缩小版
    engine.spawnExplosion(loc, ZERO, Color(140, 200, 255, 90), spec.range * 0.25f, 0.15f)
    Global.getSoundPlayer().playSound("explosion_flak", 1f, 0.9f, loc, ZERO)             // 音源已核实存在
    if (sourceShip?.owner == 0 && targets.isNotEmpty())                                  // 机制可视化铁律：引爆计数浮字
        engine.addFloatingText(loc, "近炸命中 ×${targets.size}", 16f, Color(180, 220, 255), sourceShip, 0f, 0f)
    engine.removeEntity(projectile)
    done = true
```

**结算顺序约定**：先 `ConeImpactHandler.resolve` 拿命中清单（`applyDamage` 不触发 onHitEffect，无回环风险，基建 §2.2-4 已核实），再画 VFX/浮字，最后 `removeEntity(projectile)`（先结算后移除，保证引爆帧弹体仍是合法伤害来源上下文）。

**难度取值调用点**：仅在 `onFire` 一次性 `resolve` 并随脚本持有——同一发弹体的锥角/锥长/伤害在其生命周期内恒定，不受战斗中调整 LunaLib 设置影响；下一发重新取值（与全局口径一致）。

### 2.3 玩家可见反馈（对照实现注意事项 2）

| 机制 | 反馈通道 | 落点 |
|---|---|---|
| 弹体存在 | 弹体 VFX：小型白蓝箭弹短拖尾 | `ProjectileVfxSpecs` 登记（§3） |
| 锥状引爆（范围/伤害） | 锥面冲击 VFX（蓝色调、约贯星 50% 规模）+ 小型蓝闪 `spawnExplosion` + `explosion_flak` 音效 | FuseScript.detonate |
| 命中结算 | 原版伤害浮字（`applyDamage` 末参 `showFloaty = true`） | ConeImpactHandler 内部（基建既有） |
| 引爆成效（玩家侧） | 自定义浮字「近炸命中 ×n」（仅 `source.owner == 0` 且命中数 >0） | FuseScript.detonate，对照基建 §4.2「正电子引爆计数」 |
| HUD | **不配置** | 无常驻数值状态（层数/倍率均不随时间变化；难度数值对玩家恒定 v2），不触发「有机制无反馈」 |

### 2.4 0 值与边界处理（对照实现注意事项 3）

| 场景 | 行为 |
|---|---|
| 弹体速度近零（生成首帧/外部减速） | 本帧跳过引信判定并 WARN（每弹体一次）；**不自爆、不静默**——方向矢量无意义时禁止产出错误锥形（与基建 §2.4-5「direction 非单位矢量 WARN + 归一化」同族防线） |
| `moveSpeed <= 0`（配置错误） | `reachedMaxRange` 返回 true（立即按当前位置引爆）并记 ERROR：宁可原地自爆也不允许「静默消散」违背裁定 |
| `elapsed * speed` 恰等于 range | 判定为已达（`>=`），边界含等号 |
| 近炸粗筛为空 / 锥内无有效目标 | 不引爆，弹体继续飞行（正常路径，无日志噪音） |
| 难度锚点 | 三项锚点 v1/v2/v5 全为正数，resolve 无 0 值路径；`source == null`（罕见无主弹体）按敌方口径取值并 WARN 一次 |
| 顶点重叠（目标与引爆点 dist≈0） | 由基建 ConeImpactHandler 直接纳入（基建 §2.2-3 已定），本武器不重复处理 |
| 弹体在飞行中被移除（战斗结束/异常） | `!isEntityInPlay → done = true` 静默回收（非引爆路径，不产 VFX） |

### 2.5 每帧成本说明

近炸检测每发弹体每帧一次 `CombatUtils.getEntitiesWithinRange(loc, spec.range)`（LazyLib 空间网格粗筛，v2 半径 250su）；1.5s 发射间隔下单舰在场弹体 ≤2，对照基建 §2.3 表格结论「可控」。角度精筛只发生在粗筛候选上（通常 <5 个导弹/战机实体）。

---

## 3. 特效面

### 3.1 弹体 VFX 登记（`ProjectileVfxSpecs.kt`）

- `builders` map **字面量末尾**追加条目：
  `"astd_positron_shockwave_shot" to { simpleProjectileVfx("astd_positron_shockwave_shot", positronWhiteBlue(), width = 5f, length = 90f) }`
- 调色板：冷蓝白系。按合并协议「新调色板只允许收口人添加共享函数」，**分支内内联字面量**私有函数（追加在调色板函数 `violet()` 之前、构建函数区之后）：
  `private fun positronWhiteBlue() = VfxPalette(ASTDColor(0.62f, 0.82f, 1f, 0.85f), TEX_SMOOTH, TEX_ZAPPY)`（提案值，目检微调；小型 PD 弹体克制处理，width 5 / length 90 短拖尾，不抢主炮视觉——设计案特效节要求）。
- 主色依据：全局约定「电驱/穷距/正电子用白色弹体与明亮拖尾；正电子锥状冲击 VFX 用蓝色调缩小版」→ 弹体白蓝、引爆蓝调。

### 3.2 `contents/data/config/smd_projectile_vfx.json`

`entries` 数组末尾追加：

```json
{
  "projectileSpecId": "astd_positron_shockwave_shot",
  "preset": "positron_shockwave_shot"
}
```

### 3.3 引爆 VFX

走基建锥面组件（三案共用，本武器为首次落地调用方）：`ConeImpactVfx.spawn(engine, origin, direction, halfAngleDeg = spec.halfAngleDeg, range = spec.range, color = 蓝色调)`，规模随 spec.range 参数化自然约为贯星（300~600su）的 50%。叠加 §2.2 的小型 `spawnExplosion` 蓝闪与音效。锥面组件实现本身属基建 PR，本武器分支内**不实现**锥面渲染，只传参调用。

---

## 4. 测试面

### 4.1 单元测试用例清单（`src/test/kotlin/cn/kasuminova/astd/combat/effect/arc/`）

测试桩惯例：Starsector API 接口用 `java.lang.reflect.Proxy` 动态代理 stub（照 `ProjectileSpecOnFireDispatcherRuntimeTest` 既有模式）；难度系数注入用 `DifficultyTuningImpl.installScaleForTests(scale)`，每个用例结束后传 `null` 清理。**禁止纯源码 contain 测试**，全部调用真实逻辑断言返回值/行为。

`PositronShockwaveDifficultyTest`：

1. `玩家来源固定 v2，与难度档无关`——owner=0 的 ShipAPI stub + `installScaleForTests(5f)`：调用 `resolve(source)`，断言 `halfAngleDeg == 28.125f`、`range == 250f`、`damage == 250f`（浮点容差 1e-3）。
2. `敌方迟暮档取 v1`——owner=1 stub + `installScaleForTests(1f)`：`halfAngleDeg == 22.5f`、`range == 200f`、`damage == 200f`。
3. `敌方破晓档取 v5`——owner=1 + `installScaleForTests(5f)`：`halfAngleDeg == 45f`、`range == 400f`、`damage == 400f`。
4. `无主弹体按敌方口径取值并 WARN`——`resolve(null)` + `installScaleForTests(2f)`：断言取 v2 等值且只输出一条 WARN（捕获 logger 断言，对照基建 §1.4-4 模式）。

`PositronShockwaveFuseTest`（判定纯函数，由 `PositronShockwaveDifficulty` 内部函数承载，真实调用）：

5. `reachedMaxRange 恰达射程边界含等号`——`elapsed=0.6667f, speed=900f, range=600f` → true；`elapsed=0.6666f` → false。
6. `reachedMaxRange 弹速为 0 立即引爆并报 ERROR`——`speed=0f` → true + 捕获 logger 断言 ERROR 一条（0 值防线 §2.4）。
7. `isFuseTarget 目标类型矩阵`——Proxy stub 逐个构造：敌对 `MissileAPI` → true；敌对 `ShipAPI`(`isFighter=true`) → true；敌对 `ShipAPI`(`isDrone=true`) → true；敌对普通舰船（isFighter/isDrone 均 false）→ **false**（裁定：舰船不触发近炸）；同方 `MissileAPI`（owner 相同）→ false；敌对 hulk 战机（`isHulk=true`）→ false。六宫格逐条断言。

**不在单测面的部分**（写明边界，防止误补）：FuseScript.advance 整链依赖 LazyLib `CombatUtils.getEntitiesWithinRange`（静态走 `Global.getCombatEngine()`，单测环境无引擎），几何锥筛正确性由基建 ConeImpactHandler 单测（基建 §2.4 清单 1~4）覆盖；整链行为由烟测兜底。

### 4.2 烟测检查点（`deployMod` + `launchSmokeTestGame`）

1. dev 仓储出现「正电子冲击波蓝图」与武器本体；可学习蓝图、可装配小型能量槽。
2. **无触碰体积**：向敌舰齐射，射弹穿过舰船/战机不触发碰撞、不提前引爆（§0-1 验证点）。
3. **近炸引爆**：敌导弹群来袭时弹体在导弹/战机进入锥面即引爆，集群被成片清除；观察伤害数字 = 250（v2）。
4. **最大射程无条件自爆**：向空域发射，弹体抵达 600su 时自爆（有锥面 VFX 与音效），无静默消散。
5. **舰船蹭波及**：敌舰恰在锥面边缘时吃到破片伤害，但舰船**不触发**近炸（只对空发射到射程自爆才波及）。
6. 引爆浮字「近炸命中 ×n」仅玩家侧出现；弹体白蓝短拖尾、引爆蓝色调锥面，目检不抢主炮视觉。
7. PD 行为：AI 装配后优先攻击导弹（hints=PD 生效）。
8. automation 到达终态即退出，不干等超时（烟测后必关游戏）。

---

## 5. 并行实装注意

### 5.1 本武器触碰的共享文件清单（按合并协议标注）

| 共享文件 | 本分支动作 | 键名空间/追加位置 |
|---|---|---|
| `ss-csv/src/main/resources/i18n/zh-cn.properties` | 追加 §1.3 全部键 | `weapon.astd_positron_shockwave.*` / `desc.astd_positron_shockwave.*`，集中插文件末尾，不动其他武器键 |
| `ss-csv/.../strings/Catalog_Descriptions.kt` | 追加 §1.4 一行 | WEAPON 分组尾部（`Desc_astd_psi_omega` 之后） |
| `ss-csv/.../weapondata/arc/Catalog_WeaponData_ARC.kt` | 追加 `Wpn_astd_positron_shockwave` object | 文件末尾；`number = 9215`（预分配段，不与他人撞号） |
| `contents/data/campaign/special_items.csv` | 追加 §1.5 一行 | 文件末尾；`order` 留空 |
| `src/.../projectile/driver/ProjectileVfxSpecs.kt` | builders map 末尾加一条目 + `positronWhiteBlue()` 私有函数 | 调色板内联字面量（共享 `coldBlueWhite` 调色板只允许收口人沉淀）；函数追加在调色板函数区之前 |
| `contents/data/config/smd_projectile_vfx.json` | entries 末尾追加一个对象 | 收口人按 projectileSpecId 字典序重排 |

**明确不触碰**：`ProjProjectileSpec.kt`（`standard()` 不加参数，§1.1 已绕开）、`ss-csv` 其他输出模型、Buff API 全部文件、`CombatRandom.kt`。

### 5.2 对共享基建的依赖项

| 依赖 | 形态 | 阻塞性 |
|---|---|---|
| `ConeImpactHandler` + `ConeImpactSpec`（基建 §2） | 只调用 `resolve`，不修改签名 | **硬阻塞**：基建 PR 未合 main 前本分支只能写数据面/i18n/难度登记与纯函数单测 |
| 锥面 VFX 组件（基建 §2.2-5，随 ConeImpactHandler 同 PR 落地） | 只传参调用 | 硬阻塞（VFX 部分；可先用 `spawnExplosion` 原型过渡跑烟测，收口前接回锥面组件） |
| `ProjectileVfxSpecs` / dispatcher / `CombatVfxBootstrapEveryFrameEffect` | 已就绪 | 无 |
| `DifficultyTuningImpl` / `ScalingEntry` | 已就绪（`installScaleForTests` 供单测注入） | 无 |
| Buff API / CombatRandom | **不消费**（§2.1 说明） | 无 |

### 5.3 预估实现顺序内的位置

首批 §12 第 6 位（穷距之后、七星之前）：本武器是 `ConeImpactHandler` 的最小可行落地场景，验证「近炸检测 + 锥状结算 + 锥面 VFX」接口后贯星（第 9 位）直接复用。分支内建议顺序：数据面（ss-csv + i18n + .wpn + special_items）→ 难度登记 + 纯函数单测 →（基建合 main 后）引信脚本 + 引爆编排 → VFX 登记 → 烟测。

---

## 6. 验收要点（主代理逐项核对）

**数据面**
- [ ] `Wpn_astd_positron_shockwave` 各列与 §1.1 一致；`number = 9215`；`aiHints` 仅 `PD`
- [ ] `projSpec` 为字面量构造，`collisionClass = "NONE"`、`onHitEffect = null`、隐藏四件套齐全；未改 `ProjProjectileSpec.kt`
- [ ] `./gradlew :ss-csv:generateSsCsv` 产物中 `weapon_data.csv` 行、`.proj` 内容正确；`copyContents` 叠加生效
- [ ] `.wpn` 三个插件挂载点（dispatcher / bootstrap / projectileSpecId）与 §1.2 一致
- [ ] i18n 五键齐全且文案与设计案裁定原文逐字一致；`text2~5/notes` 无占位键
- [ ] `Desc_astd_positron_shockwave` 在 WEAPON 分组尾部
- [ ] `special_items.csv` 蓝图行 params = `astd_positron_shockwave`、`order` 留空

**代码面**
- [ ] 三个新类均在 `combat/effect/arc/`，无 ConeHandler/Vfx 薄适配层；无 XxxService/Manager 命名；无反射
- [ ] 难度取值只在 `onFire` 一次性 resolve；玩家 owner==0 固定 v2（对照既有 `ASTDVirtualParticleLatticeWebHullMod:252` 口径）
- [ ] 结算顺序 = 结算 → VFX/浮字 → `removeEntity`；`showFloaty = true`
- [ ] 近炸 filter 严格只导弹/战机/无人机且剔除同方与 hulk；舰船不触发近炸
- [ ] 0 值防线三条（速度近零 WARN 跳帧 / moveSpeed=0 ERROR 立即引爆 / 射程边界含等号）均有日志、无空 catch
- [ ] 玩家侧引爆浮字仅在 `owner==0 && targets.isNotEmpty()` 时触发；无 HUD（无常驻状态，合规）

**特效面**
- [ ] `ProjectileVfxSpecs` 条目在 map 末尾、调色板为分支内内联字面量（未加共享调色板函数）
- [ ] `smd_projectile_vfx.json` 条目追加在数组末尾
- [ ] 引爆锥面走基建组件调用，本分支未自实现锥面渲染

**测试面**
- [ ] 7 条单测全部真实调用逻辑（无源码 contain）；难度注入用例结束后 `installScaleForTests(null)` 清理
- [ ] 烟测 8 个检查点全过；尤其「穿舰不爆」「600su 空射自爆」「成片清除导弹群」三条裁定行为

**目检**
- [ ] 白蓝短拖尾克制不抢主炮；锥面蓝色调规模约为贯星 50%；引爆音效与闪光同步
- [ ] 高耗散/高结构目标下破片伤害数值读感符合「单发孱弱、价值在成片」的设计性格
