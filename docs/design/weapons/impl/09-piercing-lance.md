# 贯星之矛 逐件实现规格 v1（待评审）

> 依据：`90-首批实装计划.md` v6 全局约定 + §9；设计案定稿 `blue/30-superlative.md`「贯星之矛」（v1.0，2026-07-28 裁定）；共享基建 `impl/00-共享基建.md` §2（ConeImpactHandler）/§3（合并协议）/§4.2（HUD/浮字通道）。
> 状态：规划文档，不改动 `src/` 与 `ss-csv/`。
> API 核查时间：2026-07-29，对照 `starfarer.api.jar`、vanilla `data/weapons/`（cryoblaster/disintegrator/tachyon_lance 等）与本仓库现有代码逐条核实。
> 游戏内 id：`astd_piercing_lance`（提案，实现前最终确认；本文按此 id 书写全部键位）。

---

## 0. 已核实的落点事实（本规格的-exclusive 增量）

以下结论在本次核查中新证实，直接消除计划 §13 风险 13 的一半：

| 事实 | 证据 |
|---|---|
| **HYBRID 挂载的正确实现是 `.wpn` 中 `"type":"ENERGY"` + `"mountTypeOverride":"HYBRID"`**：属性/技能/船插结算按 `type`（ENERGY）生效，装配兼容性由 `mountTypeOverride` 放宽到实弹/能量槽。设计意图「视作能量武器结算」**无需任何 hullmod/tag 强制**，vanilla cryoblaster / disintegrator / minipulser / vpdriver 全部此形态 | vanilla `data/weapons/cryoblaster.wpn` 等 5 件 |
| `weapon_data.csv` 的 `type` 列是 **DamageType**（KINETIC/ENERGY/…），不是挂载类型；挂载类型在 `.wpn` | vanilla `weapon_data.csv` header + `WeaponDataEntry` 注释 |
| 原版充能武器（chargeup>0）自带装配界面/战斗中充能条表现，2s 充能窗口**不需要自写 HUD** | vanilla 机制（tachyon_lance 等） |
| 玩家固定 v2 取值样板已存在：`if (ship.owner == 0) ENTRY.v2 else DifficultyTuningImpl.value(ENTRY)` | `ASTDVirtualParticleLatticeWebHullMod.kt:252` |
| `DifficultyTuningImpl`（object，`cn.kasuminova.astd.impl.difficulty`）有 `testOverride` 测试钩子，单测可直接注入 k_s | 已读源码 |
| `engine.applyDamage(entity, point, damage, damageType, empAmount, false, false, source, true)` 一次调用同时结算主伤害 + EMP | `GravityCollapseOnHitHandler.kt:153` |
| `CombatUtils.getEntitiesWithinRange(point, radius)`（LazyLib 空间网格粗筛）已在 `GravityCollapseOnHitHandler.kt:112` 实战使用 | 已读源码 |
| 命中矢量样板：`projectile.velocity` 取方向（`HighFluxShieldPressureOnHitEffect.kt:178`）；弹体伤害 NaN 防线样板：`sanitizePanelDamage`（同文件 :157） | 已读源码 |
| `.proj` 隐藏四件套（length/width=2、双色 alpha=0、`BUtil_NONE.png`、`fadeTime=0.2`）照 `astd_aod7_shot.proj` 样板 | `Wpn_astd_aod7.projSpec` |
| `ConeImpactSpec` 已含 `empDamage` 字段与命中本体豁免 filter 语义，贯星「FRAGMENTATION + 同锚 EMP」一发调用即可 | 共享基建 §2.2 |

**本武器不依赖 Buff API**（无叠层/无持续状态，OnHit 一次性结算）；依赖 `ConeImpactHandler`（基建件，先于武器组落地 main；正电子为首个业务调用方）。

---

## 1. 数据面

### 1.1 ss-csv catalog 条目

文件：`ss-csv/src/main/kotlin/cn/kasuminova/astd/sscsv/entries/catalog/weapondata/arc/Catalog_WeaponData_ARC.kt`，**末尾追加** `object Wpn_astd_piercing_lance : WeaponDataEntry(), SsProjProjectileOutputs`（收口人按 number 升序归位）。逐列值：

| 列（字段） | 值 | 说明 |
|---|---|---|
| `id` | `"astd_piercing_lance"` | 提案 id |
| `name` | `weaponName(id)` | 走 i18n |
| `tier` | `3` | 稀有掉落大槽 |
| `rarity` | 默认 `1` | 获取走赏金掉落（P6），不走 rarity 列 |
| `baseValue` | `60000` | **提案值待评审**（aod7 T3 为 50000，贯星 30 OP 大槽稀有件上探） |
| `range` | `1000` | 定案 |
| `damage/second` | `357` | 2500/7s 循环折算（注释写明口径，仅 tooltip 统计用） |
| `damage/shot` | `2500` | 定案 |
| `emp` | `0` | EMP 是锥状冲击机制产物，不上原生面板列 |
| `impact` | `0` | |
| `turn rate` | `30` | 大槽常值（aod7 同）；设计案未提慢转向 |
| `OPs` | `30` | 定案 |
| `ammo` / `ammo/sec` / `reload size` | `0`（列空） | 无弹匣 |
| `type` | `"ENERGY"` | **DamageType 列**（§0 已核实），与「视作能量武器」口径一致 |
| `energy/shot` | `3000` | 定案 |
| `energy/second` | `429` | 3000/7s 循环折算 |
| `chargeup` | `2.0` | 定案（充能 2s） |
| `chargedown` | `5.0` | 定案（冷却 5s） |
| `burst size` / `burst delay` | `1` / `0.0` | 单发；非 beam 必须填 chargedown 系描述避免 tooltip 除 0（aod7 注释同款） |
| `min/max spread` / `spread/shot` / `spread decay/sec` | 全 `0.0` | 完美精度走 spec 面板 |
| `proj speed` | `3000` | **提案值待目检**：「极快」，1000su 射程约 0.33s 飞行（aod7 为 2400） |
| `hints` | 不设 | 提案：先按大槽能量武器默认 AI 行为；目检若开火纪律差再评 `STRIKE` |
| `tags` | `"no_drop, no_drop_salvage"` | P6 前测试口径；P6 后改赏金掉落（计划 §14） |
| `groupTag` | `"astd"` | |
| `tech/manufacturer` | `"弧光阵列"` | |
| `primaryRoleStr` | `SsI18n.t("weapon.$id.primaryRoleStr")` | 提案文案「爆发攻坚」，**待评审** |
| `customPrimary` / `customPrimaryHL` | `SsI18n.t(...)` | 照 LENS 线条目样板（`Catalog_WeaponData_LENS.kt:71`） |
| `number` | `9219` | 合并协议预分配段位（贯星 9219） |

`.proj` 输出（`SsProjProjectileOutputs` 混入，生成 `data/weapons/proj/astd_piercing_lance_shot.proj`）：

```kotlin
override val projSpec: ProjectileProjSpec = ProjectileProjSpec(
    id = "astd_piercing_lance_shot",
    spawnType = ProjectileSpawnType.BALLISTIC,
    onFireEffect = "cn.kasuminova.astd.combat.effect.generic.ProjectileSpecOnFireDispatcher",
    onHitEffect = "cn.kasuminova.astd.combat.effect.arc.piercinglance.PiercingLanceOnHitEffect",
    collisionClass = "PROJECTILE_FF",
    collisionClassByFighter = "PROJECTILE_FIGHTER",
    // 原版弹体视觉隐藏四件套（照 aod7 样板）：弹体观感全交 ProjectileVfxSpecs。
    length = 2.0, width = 2.0,
    fadeTime = 0.2,   // 超射程滑行窗口，拖尾跟随淡出
    fringeColor = Rgba(120, 200, 255, 0),
    coreColor = Rgba(220, 245, 255, 0),
    textureScrollSpeed = 0.0,
    pixelsPerTexel = 1.0,
    bulletSprite = "graphics/textures/BUtil_NONE.png",
)
```

### 1.2 `.wpn` JSON 骨架（手写，不生成）

文件：`contents/data/weapons/astd_piercing_lance.wpn`。**HYBRID 实现是本次核查的关键结论**（§0）：

```json
{
    "id": "astd_piercing_lance",
    "specClass": "projectile",
    "type": "ENERGY",
    "mountTypeOverride": "HYBRID",
    "size": "LARGE",
    "displayArcRadius": 1000,
    "turretSprite": "graphics/fx/empty.png",
    "turretGunSprite": "graphics/fx/empty.png",
    "hardpointSprite": "graphics/fx/empty.png",
    "hardpointGunSprite": "graphics/fx/empty.png",
    "visualRecoil": 6.0,
    "turretOffsets": [0, 0],
    "turretAngleOffsets": [0],
    "hardpointOffsets": [0, 0],
    "hardpointAngleOffsets": [0],
    "barrelMode": "LINKED",
    "animationType": "MUZZLE_FLASH",
    "projectileSpecId": "astd_piercing_lance_shot",
    "everyFrameEffect": "cn.kasuminova.astd.combat.effect.generic.CombatVfxBootstrapEveryFrameEffect",
    "fireSoundTwo": "tachyon_lance_fire"
}
```

插件挂载点核对：

- `everyFrameEffect = CombatVfxBootstrapEveryFrameEffect`：把扫描式 VFX dispatcher 装进引擎（aod7 同款，防 onFire 未触发时透明弹体完全不可见）。
- `onFireEffect`（在 `.proj` 侧）= `ProjectileSpecOnFireDispatcher`：弹体 VFX 接入。
- `onHitEffect`（在 `.proj` 侧）= `PiercingLanceOnHitEffect`：锥状冲击结算。
- `"type":"ENERGY" + "mountTypeOverride":"HYBRID"`：能量结算 + 实弹/能量槽皆可装配（vanilla cryoblaster 同款）。
- `turretSprite/hardpointSprite` 暂用 `graphics/fx/empty.png`（aod7 同款处理）——**TODO：武器贴图美术资产待补**，本规格不含美术产出。
- `fireSoundTwo = tachyon_lance_fire`：**提案待目检**（充能重矛听感对标原版聚能矛）。

### 1.3 i18n 键清单

文件：`ss-csv/src/main/resources/i18n/zh-cn.properties`，全部键**集中追加在文件末尾**（合并协议 §3），键名空间 `weapon.astd_piercing_lance.*` / `desc.astd_piercing_lance.*`：

```properties
weapon.astd_piercing_lance.name=贯星之矛
weapon.astd_piercing_lance.tooltip.customPrimary=命中时沿射弹矢量产生锥状冲击，对范围内所有目标额外造成破片伤害与 EMP。效果受到难度系数影响。
weapon.astd_piercing_lance.tooltip.customPrimaryHL=
weapon.astd_piercing_lance.primaryRoleStr=爆发攻坚
desc.astd_piercing_lance.text1=阿斯忒里亚弧光科研部为正面决胜打造的重型投射平台。两秒的充能过程将骇人的能量压缩进一枚高密度弹体，出膛后在毫秒之间贯穿战场——命中随后产生的冲击波能够吞没其中的一切。
```

- `name` / `tooltip.customPrimary` / `desc.text1` 为设计案定稿原文（2026-07-28 裁定），一字不动。
- `customPrimary` 无 `{%s}` 占位符（难度数值隐性缩放，tip 不写死数值），故 `customPrimaryHL` 为空串。
- `primaryRoleStr`「爆发攻坚」为提案，待评审（aod7 用「爆发打击」，需错位）。
- `desc.text2~text5` / `desc.notes` 设计案未提供——不建键，`LocalizedDescription` 走 fallback 空串；若评审要求补 notes，在 PR 中单独追加。

`Catalog_Descriptions.kt` 追加（WEAPON 分组尾部、`Desc_astd_psi_omega` 之后）：

```kotlin
object Desc_astd_piercing_lance : LocalizedDescription("astd_piercing_lance", "WEAPON")
```

### 1.4 special_items.csv

**不改动**。贯星之矛为稀有掉落件：P6 前 `no_drop, no_drop_salvage` 仅 dev 仓储/控制台测试，P6 后接 T3~T4 支线赏金掉落（计划 §14）。合并协议中 special_items.csv 的追加约定仅适用量产件与单件蓝图，本武器不在其列。

---

## 2. 代码面

### 2.1 类清单

| 类名 | 接口/实现 | 职责 | 挂载点 | 文件路径 |
|---|---|---|---|---|
| `PiercingLanceOnHitEffect` | 实现（`OnHitEffectPlugin`，引擎回调要求具体类实例化） | 薄适配：命中回调入口，校验命中上下文后调用 `PiercingLanceConeStrike` | `.proj` 的 `onHitEffect` | `src/main/kotlin/cn/kasuminova/astd/combat/effect/arc/piercinglance/PiercingLanceOnHitEffect.kt` |
| `PiercingLanceConeStrike` | object 逻辑层（对齐基建 `ConeImpactHandler` object 形态；仅本武器一处调用，按反过度设计规范不立接口） | 组装 `ConeImpactSpec`（命中矢量/难度取值/本体豁免 filter）→ 调 `ConeImpactHandler.resolve` → 逐目标浮字 → 触发 VFX | 被 `OnHitEffect` 调用 | 同包 `PiercingLanceConeStrike.kt` |
| `PiercingLanceDifficulty` | object 数值登记 | 三锚点常量（`CONE_ARC` / `CONE_RANGE` / `CONE_DAMAGE`）+ `valueFor(source: ShipAPI?)` 玩家 pin v2 入口 | 被 `ConeStrike` 使用 | 同包 `PiercingLanceDifficulty.kt` |
| `PiercingLanceVfx` | object 渲染触发层 | 命中顶点大闪光 + 大光柱（RenderEntity + bloom 管线）+ 调共享锥面组件画锥状冲击 | 被 `ConeStrike` 调用 | 同包 `PiercingLanceVfx.kt` |

注释要求（铁律）：`ConeStrike` 类头写清「贯星之矛锥状冲击结算的组装与触发层」+ 动机「隔离 OnHitEffect 引擎回调与可测纯逻辑」；`buildConeSpec` 等公开成员逐个写明作用与单位。

### 2.2 核心逻辑伪代码（无状态机，OnHit 一次性结算）

```kotlin
// PiercingLanceOnHitEffect.onHit(projectile, target, point, shieldHit, damageResult, engine)
fun onHit(...) {
    if (engine.isPaused) return
    if (target is ShipAPI && (target.isHulk || target.isPhased)) 仍结算   // 锥面波及其他目标不因本体状态豁免；仅本体被 filter 豁免
    val hitPoint = point ?: projectile.location ?: return                  // 陨石等 point 为 null 的防线（样板已核实）

    val spec = PiercingLanceConeStrike.buildConeSpec(projectile, target, hitPoint) ?: return  // null = 已记 WARN 的放弃路径
    PiercingLanceConeStrike.resolve(engine, spec)
}

// PiercingLanceConeStrike
const val PANEL_DAMAGE = 2500f   // 设计口径「面板 x%」，不取 projectile.damageAmount（不吃目标侧易伤、不被 NaN 污染）

fun buildConeSpec(projectile, directTarget, hitPoint): ConeImpactSpec? {
    // 1) 命中矢量：弹体速度方向 → 零向量 fallback source→target 方向 → 仍不可得记 WARN 返回 null（0 值防线，见 §2.4）
    val direction = 命中矢量归一化(projectile, directTarget) ?: return null
    // 2) 难度取值调用点：source.owner == 0 时三项全部强制 v2，否则 DifficultyTuningImpl.value(entry)
    val source = projectile.weapon?.ship  // null 时按敌版取值并记 DEBUG（来源船已毁的极端帧）
    val arcDeg   = PiercingLanceDifficulty.valueFor(source, CONE_ARC)     // 40 / 50 / 80
    val range    = PiercingLanceDifficulty.valueFor(source, CONE_RANGE)   // 300 / 375 / 600
    val dmgMult  = PiercingLanceDifficulty.valueFor(source, CONE_DAMAGE)  // 1.00 / 1.25 / 2.00
    // 3) 组装（EMP 与破片同锚，2026-07-28 裁定）
    return ConeImpactSpec(
        origin = hitPoint, direction = direction,
        halfAngleDeg = arcDeg / 2f, range = range,
        damage = PANEL_DAMAGE * dmgMult, damageType = DamageType.FRAGMENTATION,
        empDamage = PANEL_DAMAGE * dmgMult,
        source = source, owner = source?.owner ?: projectile.owner,
        filter = ConeTargetFilter { it !== directTarget },   // 命中本体豁免（基建 §2.2-2 语义）
        hitShips = true, hitFighters = true, hitMissiles = true,
    )
}

fun resolve(engine, spec) {
    val hits = ConeImpactHandler.resolve(engine, spec)   // 基建件：粗筛→归属/类型筛→角度精筛→applyDamage
    // 玩家可见反馈（铁律，基建 §4.2）：逐目标伤害浮字 + 锥面/光柱特效
    for (t in hits) engine.addFloatingDamageText(落点(t), spec.damage, FRAG_TEXT_COLOR, t as? ShipAPI, spec.source)
    PiercingLanceVfx.spawnImpact(engine, spec)           // 顶点大闪光 + 大光柱 + 锥状冲击锥面
}
```

结算顺序固定为：**校验 → 命中矢量 → 难度取值（一次）→ 组装 spec → ConeImpactHandler 结算 → 浮字 → VFX**。难度值在同一次命中只取一次，禁止同帧二次取值（基建 §4.1 口径）。

### 2.3 难度缩放登记（`PiercingLanceDifficulty`）

| 机制 | ScalingEntry (v1, v2, v5) | map |
|---|---|---|
| 锥角（全角，度） | `ScalingEntry(40f, 50f, 80f)` | LINEAR |
| 锥长（su） | `ScalingEntry(300f, 375f, 600f)` | LINEAR |
| 破片/EMP 伤害倍率（面板倍数） | `ScalingEntry(1.00f, 1.25f, 2.00f)` | LINEAR |

- 三项常驻显性（含玩家版），与摧锋的 k≥3 隐性解锁不同——**直接 ScalingEntry 全段插值，无自定义映射**。
- 玩家 pin v2：`valueFor(source, entry) = if (source?.owner == 0) entry.v2 else DifficultyTuningImpl.value(entry)`（样板 `ASTDVirtualParticleLatticeWebHullMod.kt:252`）。
- 破晓档实际结算：锥角半角 40°、锥长 600su、破片 5000 + EMP 5000。

### 2.4 玩家可见反馈对照表（机制可视化铁律，基建 §4.2）

| 机制 | 反馈通道 | 落点 |
|---|---|---|
| 锥状冲击破片伤害 | 伤害浮字 | 逐命中目标 `addFloatingDamageText`（同一帧） |
| 锥状冲击 EMP | 命中特效 | 锥面内带盾/船体目标由引擎 EMP 结算自带电弧；叠加锥面特效 |
| 锥状冲击范围 | 特效 | 共享锥面组件：顶点闪光 + 沿命中矢量扩散的冲击锥（冷蓝白，规模大于正电子蓝色缩小版） |
| 单发 2500 重击 | 特效 | 命中顶点大闪光 + 大光柱（RenderEntity + bloom 管线） |
| 2s 充能窗口 | 原版 HUD | chargeup 充能条（原版机制，无需自写） |
| 难度系数存在性 | tip 文案 | 定稿 tip 末句「效果受到难度系数影响」 |

无常驻数值状态（无叠层/无自适应），**不需要** `maintainStatusForPlayerShip`。

### 2.5 0 值与边界处理（对照实现注意事项 3）

| 边界 | 行为 |
|---|---|
| `projectile.velocity` 零向量/长度≈0（命中瞬间速度被引擎改写） | fallback 为 `source→target` 方向归一化；仍不可得（source 与 target 同点且 source null）→ **记 WARN 并放弃本次锥面结算**（命中本体伤害已由引擎结算，不静默吞机制） |
| `direction` 非单位矢量 | 基建 `ConeImpactHandler` 统一 WARN + 归一化（基建 §2.4-5），本层不重复 |
| `point` 为 null（陨石等） | 回退 `projectile.location`；仍 null 直接 return（样板 `HighFluxShieldPressureOnHitEffect.kt:76`） |
| 顶点重叠目标（dist≈0） | 基建角度精筛直接纳入，不除零（基建 §2.2-3） |
| 难度倍率/锥长理论 0 值 | 三锚点常量均 >0，`ScalingEntry` 构造期不防御；`DifficultyTuningImpl.value` 对 k_s clamp [1,5] 已内建 |
| `source` 为 null（来源船同帧被毁） | 按敌版取值 + DEBUG 日志；`owner` 回退 `projectile.owner` 保证敌我过滤不退化 |
| 锥内无额外目标（只命中本体） | `resolve` 返回空清单，仅触发顶点闪光（锥面特效照常绘制——机制存在性反馈），无浮字 |

---

## 3. 特效面

### 3.1 VfxSpec 登记项（弹体）

文件：`src/main/kotlin/cn/kasuminova/astd/renderer/projectile/driver/ProjectileVfxSpecs.kt`，按合并协议：`builders` map 字面量**末尾追加**，构建函数追加在类内调色板函数之前；冷蓝白调色板**分支内内联字面量**（新调色板只允许收口人沉淀）。

- map 条目：`"astd_piercing_lance_shot" to ::piercingLanceShot`
- 构建函数：`private fun piercingLanceShot(): ProjectileVfx`
  - 形态：基于 `simpleProjectileVfx` 五旋钮形态微调（texTrail 主体 + bloom 网格弹头恒在），参数提案：
    - 主色（内联字面量）：`ASTDColor(0.55f, 0.78f, 1f, 0.95f)` 冷蓝白（ARC 主色口径）
    - `width = 36f`、`length = 260f`、`glowScale = 4.0f`（大圆形弹体观感；公式派生自动放大弹头与带宽）
  - 「较大圆形射弹被大号发光 flare 覆盖（仅见粗略形状）」的观感由「加大弹头 + bloom 管线提取遍增益」承担；若目检 flare 覆盖不足，再下探为独立 DSL 树加 flare 渲染组件——**先按五旋钮登记跑目检**。
  - 注意 `trail.startWidth` ≤ 46.7 的 widthBase 红线（文件头注释），width=36 在安全区。

### 3.2 `smd_projectile_vfx.json` 映射键

`contents/data/config/smd_projectile_vfx.json` 的 `entries` 数组**末尾追加**（收口人按 specId 字典序归位）：

```json
{ "projectileSpecId": "astd_piercing_lance_shot", "preset": "piercing_lance_shot" }
```

### 3.3 命中/飞行 VFX（`PiercingLanceVfx`）

- **命中顶点**：大号 `addHitParticle` 核心闪 + `addSmoothParticle` 光晕 + BoxUtil `DistortionEntity`（样板 `Aod7OnFireEffect.spawnDistortion`），冷蓝白。
- **大光柱**：沿命中矢量的短寿命光柱 RenderEntity，并入 bloom 管线（aod7 hero PoC 已验证 bloom 弹头管线；光柱为新增构图，目检调宽度/时长，提案：长 ≈ 锥长 × 0.6、存续 0.25s）。
- **锥状冲击锥面**：调用共享锥面 VFX 组件（基建 §2.2-5：顶点闪光 + 沿中轴扩散的冲击锥 sprite/粒子，参数化锥角/长度/调色），传本武器 halfAngle/range/冷蓝白；该组件由正电子首发落地，贯星直接复用，规模为其 ~1.5 倍（正电子为缩小蓝色调版）。
- 原版命中反馈缺失项：弹体有碰撞体积（collisionRadius 走 `.proj` 默认），无需像正电子那样手动补命中粒子。

---

## 4. 测试面

### 4.1 单元测试用例清单

落文件：`src/test/kotlin/cn/kasuminova/astd/combat/effect/arc/piercinglance/PiercingLanceConeStrikeTest.kt`（+ 难度登记测试可同文件）。全部调用真实逻辑（`buildConeSpec` 纯函数、`PiercingLanceDifficulty.valueFor`），禁止纯源码 contain 测试。Starsector API 类型以测试桩实现接口（项目已有 `src/test/.../testutil` 惯例）。

| # | 用例名 | 断言点 |
|---|---|---|
| 1 | `buildConeSpec_半角与锥长按难度档正确换算` | v2 档：`halfAngleDeg == 25f`、`range == 375f`；v5 档（`DifficultyTuningImpl.testOverride = 5f`）：`halfAngleDeg == 40f`、`range == 600f` |
| 2 | `buildConeSpec_破片与EMP同锚` | v2：`damage == 3125f && damageType == FRAGMENTATION && empDamage == 3125f`；v5：`damage == 5000f && empDamage == 5000f`；v1：`damage == 2500f && empDamage == 2500f` |
| 3 | `valueFor_玩家固定v2` | 桩 `source.owner == 0`，分别注入 `testOverride = 1f / 5f`，三项返回值恒等于 v2（40/50/80 中恒 50 等） |
| 4 | `valueFor_敌版走难度插值` | `source.owner == 1` + `testOverride = 5f` → v5；`testOverride = 1f` → v1 |
| 5 | `buildConeSpec_命中本体豁免` | 返回 spec 的 `filter.accept(directTarget) == false`；`filter.accept(其他敌舰桩) == true` |
| 6 | `buildConeSpec_命中矢量为弹体速度方向` | 桩 velocity=(3,4) → `direction` 为 (0.6, 0.8)（单位矢量），`origin === hitPoint` |
| 7 | `buildConeSpec_速度零向量回退source到target方向` | 桩 velocity=(0,0)、source/target 定位已知 → direction 为 source→target 归一化 |
| 8 | `buildConeSpec_矢量不可得记WARN并放弃` | velocity 零向量且 source null → 返回 null，捕获 logger 断言 WARN 恰好一条（不静默） |
| 9 | `buildConeSpec_来源为null时owner回退弹体归属` | source null、projectile.owner=1 → `spec.owner == 1`，且难度按敌版取值 |
| 10 | `vfxSpec登记可构建` | 调真实 `ProjectileVfxSpecs.build("astd_piercing_lance_shot")` 非 null 且 spec id 正确（对齐现有公式守护测试形态） |

锥形几何边界（锥内/锥外/半角边界/锥长边界/大目标擦边/归属矩阵）由基建 `ConeImpactHandler` 的测试覆盖（基建 §2.4），本武器不重复。

### 4.2 烟测检查点

`deployMod` + `launchSmokeTestGame`（到达终态即退出，不干等超时）：

1. 装配：贯星之矛可同时装入**大型实弹槽与大型能量槽**（HYBRID 生效）；装入后技能/船插的能量系加成作用于它（`type=ENERGY` 结算生效）。
2. 循环：2s 充能条出现 → 出膛 → 5s 冷却，周期 7s；完美精度无散布。
3. 弹体观感：大圆形弹体被 flare 覆盖、texTrail 拖尾冷蓝白，无原版弹芯穿帮。
4. 命中单体：顶点大闪光 + 大光柱，无连带浮字（锥内无其他目标）。
5. 命中集群（dev 模式摆 3~4 艘敌舰沿弹道线）：锥状冲击锥面特效方向与命中矢量一致，锥内目标出现破片浮字 + EMP 电弧，锥外目标无伤害；命中本体不重复吃锥面伤害。
6. 敌版高难：LunaLib 切破晓档，敌舰持有的贯星命中护航集群时锥面明显变大（80°/600su）。
7. devMode FPS：600su/80° 粗筛下帧率无明显塌陷。
8. codex/掉落：`no_drop` 生效，武器不出现在常规掉落与战利品。

---

## 5. 并行实装注意

### 5.1 本武器触碰的共享文件清单（合并协议 §3 键名空间）

| 共享文件 | 本武器的键名空间 / 追加位置 |
|---|---|
| `ss-csv/.../i18n/zh-cn.properties` | `weapon.astd_piercing_lance.*` / `desc.astd_piercing_lance.*`，全部集中追加**文件末尾**，不动其他武器的键 |
| `ss-csv/.../strings/Catalog_Descriptions.kt` | WEAPON 分组尾部（`Desc_astd_psi_omega` 之后）一行 |
| `ss-csv/.../weapondata/arc/Catalog_WeaponData_ARC.kt` | object 追加**文件末尾**；`number = 9219`（预分配段位） |
| `src/.../projectile/driver/ProjectileVfxSpecs.kt` | `builders` map 末尾一个条目；`piercingLanceShot()` 追加在调色板函数之前；冷蓝白**内联字面量**，不新增共享调色板函数 |
| `contents/data/config/smd_projectile_vfx.json` | `entries` 数组末尾一个对象 |
| `contents/data/campaign/special_items.csv` | **不触碰**（§1.4） |

非共享、本武器独占：`.wpn`、`.proj`（生成物）、`combat/effect/arc/piercinglance/` 整包、测试目录同名包。

### 5.2 对共享基建的依赖项

| 依赖 | 状态要求 |
|---|---|
| `ConeImpactHandler` + `ConeImpactSpec`/`ConeTargetFilter`（基建 §2） | **必须在 main 就绪**（基建先行 PR）；本武器只用其签名，不修改；确需扩展（如锥面 VFX 参数）在 PR 中单独提出先合基建 |
| 锥面 VFX 共享组件 | 由正电子首发落地；若本武器分支早于正电子完成，则锥面视觉临时只用顶点闪光 + 光柱，锥面组件就绪后一行接入 |
| `DifficultyTuningImpl` / `ScalingEntry` | 已就绪 |
| `ProjectileVfxSpecs` / `ProjectileSpecOnFireDispatcher` / `CombatVfxBootstrapEveryFrameEffect` | 已就绪 |
| HUD/浮字通道（`addFloatingDamageText` 等） | 已就绪，直接 engine API |
| Buff API | **不依赖** |

### 5.3 实现顺序位置

计划 §12 第 9 位（倒数第二）：前置验证均已完成（正电子的锥状冲击组件、七星的十字爆炸 VFX 体系、aod7 的 bloom 管线），本武器是新机制最少的一组之一，主要验证面是 **HYBRID 装配/能量结算** 与 **大光柱 + 大 flare 观感**。分支内建议落地顺序：数据面（ss-csv + .wpn + i18n）→ 无 VFX 的锥状伤害原型（dev 模式看浮字分布）→ 弹体 VfxSpec + 命中 VFX → 单测补全 → 烟测。

---

## 6. 验收要点（主代理逐项核对）

### 数据
- [ ] `Wpn_astd_piercing_lance` 列值与 §1.1 逐列一致（含 `type="ENERGY"` 是 DamageType 列、`number=9219`、ammo 三列留空）
- [ ] 生成物 `astd_piercing_lance_shot.proj` 含隐藏四件套 + 两个插件挂载点，无手改生成物
- [ ] `.wpn` 为 `"type":"ENERGY"` + `"mountTypeOverride":"HYBRID"`（不是 `"type":"HYBRID"`）
- [ ] i18n 五键齐全，name/tip/描述与定稿原文逐字一致；`customPrimaryHL` 为空串且无 `{%s}` 残留
- [ ] `special_items.csv` 无改动；`tags` 含 `no_drop, no_drop_salvage`
- [ ] `./gradlew :ss-csv:generateSsCsv` → `copyContents` → `deployMod` 全链路无报错

### 代码
- [ ] 四个类路径/命名与 §2.1 一致；无 XxxService/Manager/Controller/Runtime；无反射；无空 catch；无兜底静默分支
- [ ] 难度取值只在 `buildConeSpec` 发生一次；`source.owner == 0` 恒 v2
- [ ] 命中本体豁免、EMP 与破片同锚、`PANEL_DAMAGE=2500f` 常量口径（不取 `damageAmount`）
- [ ] §2.5 全部 0 值分支有 WARN/DEBUG 日志，无静默恒零/除零
- [ ] 未修改 `ConeImpactHandler` 签名；浮字 + 特效在同帧触发（机制可视化铁律）

### 特效
- [ ] `builders` 含 `astd_piercing_lance_shot`；`smd_projectile_vfx.json` 映射键正确
- [ ] 冷蓝白为内联字面量，未新增共享调色板函数
- [ ] 原版弹体视觉全隐（无弹芯/亮头穿帮）；顶点闪光 + 大光柱 + 锥面三层齐备

### 测试
- [ ] §4.1 十条用例全绿；均为真实逻辑调用，无纯源码 contain 测试
- [ ] 烟测 §4.2 八条检查点全过（重点：双槽可装、能量结算、锥面方向/范围/浮字、破晓档锥面放大）
- [ ] 烟测到达终态即退出，未干等超时

### 目检
- [ ] flare 覆盖观感：仅见粗略弹形，不遮蔽命中目标
- [ ] 大光柱宽度/时长不抢戏（提案 0.25s），与锥面特效层次清晰
- [ ] 2s 充能前摇对敌我可读（原版充能条）
- [ ] `fireSoundTwo` 听感与充能重矛定位匹配；不匹配则换音源复测
