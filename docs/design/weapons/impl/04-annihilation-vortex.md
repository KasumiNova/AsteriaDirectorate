# 湮灭涡旋（astd_annihilation_vortex）逐件实现规格 v1（待评审）

> 依据：`docs/design/weapons/impl/00-共享基建.md` v1（Buff API / 合并协议 / HUD 通道）、`docs/design/weapons/90-首批实装计划.md` v6（全局约定 + §4）、设计案定稿 `docs/design/weapons/purple/30-superlative.md`「湮灭涡旋」v1.0（2026-07-28）。
> 状态：规划文档，不改动 `src/` 与 `ss-csv/`。
> API 核查时间：2026-07-29，对照 `starfarer.api.jar`（0.98）与现有 `src/`、`ss-csv/` 代码逐条核实。

---

## 0. 已核实的 API 事实（本武器结论的地基）

| 事实 | 证据 |
|---|---|
| `DamagingProjectileAPI.getDamageType() : DamageType`、`getDamageAmount()`、`getBaseDamageAmount()`、`didDamage()` 存在；`MissileAPI` 继承它们 | jar 签名 |
| `CombatEngineAPI.getProjectiles()` / `getMissiles()` / `removeEntity(CombatEntityAPI)` 存在 | jar 签名 |
| `CombatEntityAPI.getVelocity() : Vector2f` / `getLocation()` / `getOwner()` 存在；速度矢量为 live reference，直接改写生效（星域惯例） | jar 签名 |
| `BeamAPI.getTo()` / `getFrom()` / `getBrightness()` / `getDamageTarget()` 存在；`WeaponAPI.getBeams()` / `getChargeLevel()` 已在 GCP 验证 | jar + `GravityCollapseBeamEveryFrameEffect.kt` |
| `CombatEngineAPI.applyDamage(entity, point, dmg, type, emp, bypassShields, dealsSoftFlux, source, showDamageFloaty)` 九参重载在用；末参 `true` 自动弹伤害数字 | `GravityCollapseOnHitHandler.kt:153` |
| `CombatEngineAPI.spawnExplosion(point, vel, color, size, duration)`、`addNebulaParticle(...)`（8 参/9 参两重载）、`addFloatingText(...)`、`maintainStatusForPlayerShip(...)` 存在 | jar + 00-共享基建 §0 |
| LazyLib `CombatUtils.getEntitiesWithinRange(point, radius)` 走空间网格，GCP 路径已验证 | `GravityCollapseOnHitHandler.kt:112` |
| BoxUtil `DistortionEntity`（`org.boxutil.units.standard.entity`）+ `BoxUtilCombatVfx.ensureReady / addEntity(engine, BoxEnum.ENTITY_DISTORTION, e)` 可用 | `GravityCollapseOnHitHandler.kt:391-424`、`renderer/boxutil/BoxUtilCombatVfx.kt` |
| 爆发光束 EveryFrame 三段式状态机（充能门控 / 发射 / 淡出）样板：`GravityCollapseBeamEveryFrameEffect`；本武器 chargeup=0，可裁掉充能段 | 已读源码 |
| 原版光束渲染隐藏：`HiddenBeamRenderEffect`（core/fringe alpha=0、width=0.01、hitGlow=null），`.wpn` 挂 `beamEffect` 即用，勿动 `beam.from/to` | `HiddenBeamRenderEffect.kt` |
| `BeamVfxDriverImpl(BeamHostImpl(id, baseWidth), tree)` + `advance(engine, BeamFrame(...), amount)` + `dispose()`；`BeamFrame(start, facing, length, endpoint, firing, strength, fadeMul)` | `GravityCollapseBeamEveryFrameEffect.kt:94-112` |
| `BeamVfxSpecs.builders: Map<String, () -> RenderEntity>`、`has(id)`、`build(id)`；`BeamCoreComponent(id, spec)` 支持自定义 core spec（`GcBeam.coreSpec()` 样板） | `BeamVfxSpecs.kt`、`BeamVfxSpecsTest.kt` |
| `DifficultyTuning.value(ScalingEntry)` + 测试注入 `DifficultyTuningImpl.installScaleForTests(Float?)` | `DifficultyTuning.kt`、`DifficultyTuningImplTest.kt` |
| ss-csv：`WeaponDataEntry` 列全集、`LocalizedDescription(id, "WEAPON")`（text1~5 + notes 自动读键）、`SsI18n.t` 缺 key 返回 key 本身（**空 HL 键必须显式给空值**，否则 CSV 落键名） | `WeaponDataEntry.kt`、`Catalog_Descriptions.kt`、`SsI18n.kt:54` |
| 现有 beam `.wpn` 骨架：`astd_gcp8.wpn`（specClass/beamEffect/everyFrameEffect/collisionClass/pierceSet/颜色组） | 已读文件 |
| `CombatEntityAPI.getCustomData()` 存在但 `WeaponAPI` 没有 customData——吞噬池状态必须走 BuffHost 舰船侧复合键（00-共享基建 §0/§1.2） | jar + 00-共享基建 |

---

## 1. 数据面

### 1.1 ss-csv catalog 条目

文件：`ss-csv/src/main/kotlin/cn/kasuminova/astd/sscsv/entries/catalog/weapondata/lens/Catalog_WeaponData_LENS.kt`，
`object Wpn_astd_annihilation_vortex : WeaponDataEntry()` **追加在文件末尾**（`Wpn_astd_gcp2` 之后）。逐列取值（未列出的列用基类默认值）：

| 列 | 值 | 依据 |
|---|---|---|
| `id` | `"astd_annihilation_vortex"` | 全局 ID 表 |
| `name` | `weaponName(id)` | 同 GCP 惯例（读 i18n） |
| `tier` | `3` | 准超规格，对齐 GCP-12（tier 3）；评审可调 |
| `baseValue` | `36000` | 提案：GCP-12 24000/22OP 斜率外推 30OP；评审可调 |
| `range` | `900` | 设计案面板 |
| `damagePerSecond` | `800` | 爆发 2s × 800 = 1600 能量 |
| `damagePerShot` / `emp` / `impact` | `0` | beam 走 DPS 列 |
| `turnRate` | `20` | 大型光束对齐 GCP-12 |
| `ops` | `30` | 设计案面板 |
| `type` | `"ENERGY"` | 爆发能量光束；协同槽可装 ENERGY |
| `energyPerSecond` | `1600` | 爆发辐能 3200 / 2s（设计案） |
| `chargeup` / `chargedown` | `0.0` / `0.0` | 首批计划 §4.1 |
| `burstSize` | `2.0` | beam 的 burstDuration = 爆发 2s |
| `burstDelay` | `9.0` | burstCooldown = 冷却 9s |
| `beamSpeed` | `10000` | 光束瞬时延伸，同 GCP 全族 |
| `projSpeed` | `0` | beam 无射弹速度 |
| `tags` | `"no_drop, no_drop_salvage"` | P6 前口径（首批计划 §14）；P6 后改赏金掉落，另立任务 |
| `groupTag` | `"astd"` | 同 LENS 族 |
| `tech` | `"透镜矩阵"` | 同 LENS 族 |
| `primaryRoleStr` | `SsI18n.t("weapon.$id.primaryRoleStr")` | 见 §1.3 |
| `customPrimary` | `SsI18n.t("weapon.$id.tooltip.customPrimary")` | 见 §1.3 |
| `customPrimaryHL` | `SsI18n.t("weapon.$id.tooltip.customPrimaryHL")` | 见 §1.3（**空值键必须存在**，缺 key 会落键名进 CSV） |
| `noDpsInTooltip` | `false` | DPS 面板正常展示 |
| `number` | `9220` | 合并协议预分配段位（湮灭涡旋 9220） |
| `aiHints` / `hints` | 不覆写 | 非 PD 武器，不挂 PD 提示 |

 descriptions 条目：`Catalog_Descriptions.kt` **WEAPON 分组尾部**（`Desc_astd_psi_omega` 之后）追加一行：

```kotlin
object Desc_astd_annihilation_vortex : LocalizedDescription("astd_annihilation_vortex", "WEAPON")
```

### 1.2 `.wpn` JSON 骨架

文件：`contents/data/weapons/astd_annihilation_vortex.wpn`（手写，不生成）。以 `astd_gcp8.wpn` 为样板：

```json
{
    "id": "astd_annihilation_vortex",
    "specClass": "beam",
    "beamEffect": "cn.kasuminova.astd.combat.effect.generic.gravitycollapse.HiddenBeamRenderEffect",
    "everyFrameEffect": "cn.kasuminova.astd.combat.effect.lens.AnnihilationVortexBeamEffect",
    "type": "ENERGY",
    "size": "LARGE",

    "collisionClass": RAY,
    "collisionClassByFighter": RAY_FIGHTER,

    "displayArcRadius": 900,

    "turretSprite": "graphics/weapons/astd_ftb_omega_base.png",
    "turretGunSprite": "graphics/weapons/astd_ftb_omega_gun.png",
    "hardpointSprite": "graphics/weapons/astd_ftb_omega_base.png",
    "hardpointGunSprite": "graphics/weapons/astd_ftb_omega_gun.png",

    "visualRecoil": 0.0,
    "turretOffsets": [0, 0],
    "turretAngleOffsets": [0],
    "hardpointOffsets": [0, 0],
    "hardpointAngleOffsets": [0],

    "barrelMode": "LINKED",
    "animationType": "MUZZLE_FLASH",

    "fringeColor": [180, 20, 40, 255],
    "coreColor": [255, 60, 70, 255],
    "glowColor": [220, 30, 50, 220],
    "darkCore": false,

    "width": 14.0,
    "textureType": ROUGH,
    "textureScrollSpeed": 320.0,
    "pixelsPerTexel": 5.0,

    "pierceSet": [PROJECTILE_FF, PROJECTILE_NO_FF, PROJECTILE_FIGHTER, MISSILE_FF, MISSILE_NO_FF]
}
```

挂载点说明：

- `beamEffect = HiddenBeamRenderEffect`：隐藏原版渲染，保留原版命中/伤害结算（光束本体 800 DPS 由原版结算）。
- `everyFrameEffect = AnnihilationVortexBeamEffect`：全部机制（涡旋牵引/吸收/吞噬池/坍缩 + 自绘 VFX）的唯一入口。
- `pierceSet` 与 GCP 相同：光束不被射弹/导弹阻挡（弹体的归宿是被涡旋吞噬，不是挡光束）。
- 炮塔贴图暂复用 GCP 同族 `astd_ftb_omega_*`，**TODO：美术件到位后替换为 LENS 深红系炮塔**。
- 颜色组为 LENS 深红；`.wpn` 颜色仅供兜底（渲染被 Hidden 后实际以 BeamVfxSpecs 树为准）。

### 1.3 i18n 键清单

文件：`ss-csv/src/main/resources/i18n/zh-cn.properties`，**集中插在文件末尾**（合并协议）。键值原文（取自设计案定稿文案，逐字）：

```properties
weapon.astd_annihilation_vortex.name=湮灭涡旋
weapon.astd_annihilation_vortex.tooltip.customPrimary=光束终点会展开引力涡旋，将附近的敌方射弹与导弹牵引并吞噬；光束结束时涡旋坍缩爆炸，把吞噬的火力转化为范围能量伤害——吞噬得越多，爆炸越强。效果受到难度系数影响。
weapon.astd_annihilation_vortex.tooltip.customPrimaryHL=
weapon.astd_annihilation_vortex.primaryRoleStr=区域拒止,弹药反制
desc.astd_annihilation_vortex.text1=渊暮原种光束武器的逆向工程产物。它将光束终点作为引力锚点展开吞噬涡旋——任何途经的敌方射弹与导弹都会被没收，成为下一次爆炸的燃料。
desc.astd_annihilation_vortex.notes=研究档案：关于涡旋的成因，学术界至今维持着三种互不兼容的理论：引力透镜效应的亚稳态残留、伪粒子流的自聚集临界，以及"光束只是诱饵，真正开火的是别的东西"。第三种理论的支持者在最近一次实地观测中失去了一整艘探测船和船上全部记录设备——除了最后三秒的弹道摄像：十六枚已发射的反舰导弹调转方向，排着队飞进了那团红光里。
```

- `customPrimaryHL` 显式空值（tip 静态无数值，设计案裁定）；**缺 key 会把键名写进 CSV**（`SsI18n.t` 缺 key 返回 key 本身）。
- `desc.text2~text5` 不提供（`LocalizedDescription` 读不到键时 fallback 为空串）。
- 研究档案段落入 `notes` 列（descriptions.csv 的 notes 呈现位，对齐 GCP notes 用法）；深灰斜体样式由 UI 层既有规则处理，本文不在文本内夹带样式标记。
- 逻辑代码零硬编码文本；HUD 状态栏标题/描述短句（见 §2.3）为运行时战斗 UI 文本，不走 ss-csv，按本地化规范走运行时 I18n（`strings.json` 额外字符串表，键空间 `astd.annihilation_vortex.hud.*`）。

### 1.4 special_items.csv 条目

**不新增条目**。湮灭涡旋为稀有掉落口径：P6 前 `tags = no_drop, no_drop_salvage`，仅 dev 仓储/控制台投放（首批计划 §14）；P6 后接 T3~T4 支线赏金掉落，届时另立任务改 tags 并接事件。合并协议中 `special_items.csv` 的追加约定对本组不适用。

---

## 2. 代码面

### 2.1 类清单表

新增包：`cn.kasuminova.astd.combat.effect.lens`（LENS 线武器机制首包；现有 `arc/`、`generic/`、`psi/` 之外的第四条业务线，首批计划数据链路第 6 步已预留）。接口落 `api/combat/`（与 00-共享基建 §2 的 `ConeImpact.kt` 同包位）。

| 类名 | 形态 | 职责 | 挂载点 | 文件路径 |
|---|---|---|---|---|
| `AnnihilationVortexPool` | **接口**（`: Buff`，`lifetime = SELF_MANAGED`） | 吞噬池：单武器一次开火周期的吞噬累计载体。成员：`convertedTotal`（折算后池值）、`absorbedCount`、`threshold`（难度折算后吸收阈值）、`addAbsorbed(type, baseDamage): Float`（含类型转换比 + 软上限折算，返回实际入池量） | 经 `ShipAPI.getOrCreateBuffByWeapon("astd_annihilation_vortex_pool", weapon, ...)` 挂 BuffHost 武器级复合键 | `src/main/kotlin/cn/kasuminova/astd/api/combat/AnnihilationVortexPool.kt` |
| `AnnihilationVortexAbsorb` | **接口** | 每帧牵引/吸收结算：输入涡旋中心/半径/归属/帧长，输出本帧被吸收弹体清单（类型 + 面板伤害）与被牵引计数。不含 VFX、不含池记账（回调由调用方做） | 由 `AnnihilationVortexBeamEffect` 每帧调用 | `src/main/kotlin/cn/kasuminova/astd/api/combat/AnnihilationVortex.kt`（含 `AbsorbedShot` 数据类） |
| `AnnihilationVortexCollapse` | **接口** | 坍缩一次性结算：输入中心/半径/伤害/来源，对半径内敌方目标 `applyDamage`（ENERGY，flat 无衰减），返回命中目标数。无状态 | 由 `AnnihilationVortexBeamEffect` 停火帧调用一次 | `src/main/kotlin/cn/kasuminova/astd/api/combat/AnnihilationVortexCollapse.kt` |
| `AnnihilationVortexPoolImpl` | 实现 | 池记账：类型转换比表、软上限（阈值内全额、超出部分 ×25%）、宿主失效自回收（advance 内判定 + `BuffHost.remove` + INFO 日志） | — | `src/main/kotlin/cn/kasuminova/astd/combat/effect/lens/AnnihilationVortexPoolImpl.kt` |
| `AnnihilationVortexAbsorbImpl` | 实现 | `CombatUtils.getEntitiesWithinRange(center, radius)` 一次粗筛 → 敌方 `DamagingProjectileAPI` 过滤 → 指向中心加速度改写 velocity → 进入吸收半径 `engine.removeEntity` 并计入返回清单 | — | `src/main/kotlin/cn/kasuminova/astd/combat/effect/lens/AnnihilationVortexAbsorbImpl.kt` |
| `AnnihilationVortexCollapseImpl` | 实现 | `CombatUtils.getEntitiesWithinRange(center, radius)` 粗筛 → 敌方舰船/战机/导弹过滤（剔除 hulk）→ 逐目标 `applyDamage(..., showDamageFloaty = true)` | — | `src/main/kotlin/cn/kasuminova/astd/combat/effect/lens/AnnihilationVortexCollapseImpl.kt` |
| `AnnihilationVortexBeamEffect` | 实现（`EveryFrameWeaponEffectPlugin`） | 总控状态机：光束起/停检测、难度取值（开火起点一次性缓存）、BuffHost 池生命周期、驱动 BeamVfxDriver、HUD/浮字反馈、停火触发坍缩 | `.wpn` 的 `everyFrameEffect` | `src/main/kotlin/cn/kasuminova/astd/combat/effect/lens/AnnihilationVortexBeamEffect.kt` |
| `AnnihilationVortexDifficulty` | object（纯声明） | 三锚点登记 + 玩家固定 v2 取值入口（`resolve(entry, sourceOwner)`：`sourceOwner == 0` → `entry.v2`，否则 `DifficultyTuning.value(entry)`；先就地实现，对齐首批计划 §11「沉淀前各自实现」口径） | 被 `BeamEffect` 使用 | `src/main/kotlin/cn/kasuminova/astd/combat/effect/lens/AnnihilationVortexDifficulty.kt` |
| `AnnihilationVortexVfx` | 实现（无状态 object） | 坍缩一次性视觉：深红 `spawnExplosion` + `addNebulaParticle` 烟云 + 内收 `DistortionEntity`（对照 GCP `spawnSustainedHitCollapseDistortion` 形态，方向为外→内快速坍缩） | 由 `BeamEffect` 坍缩帧调用 | `src/main/kotlin/cn/kasuminova/astd/combat/effect/lens/AnnihilationVortexVfx.kt` |
| `AvBeam` + `VortexComponent` | 渲染组件 | `AvBeam.coreSpec()`（深红束体 4 件套 spec，对照 `GcBeam.coreSpec()` 同型）；`VortexComponent`：光束端点涡旋节点（间隔抛短寿命 `DistortionEntity` + 旋转环粒子 + nebula 底尘，无常驻 BoxUtil 句柄，对照 `PsiSiphonComponent` 抛粒子模式） | 被 `BeamVfxSpecs.annihilationVortex()` 引用 | `src/main/kotlin/cn/kasuminova/astd/impl/render/AnnihilationVortexBeamComponents.kt` |

类名检查：无 Service/Manager/Controller/Runtime；无反射；接口注释必须含类简介、动机、每个成员作用（全局规范）。

### 2.2 核心逻辑伪代码

**常量与难度锚点**（`AnnihilationVortexDifficulty`）：

```kotlin
val RADIUS       = ScalingEntry(150f, 187.5f, 300f, ScalingMap.LINEAR)  // 涡旋半径（设计案定稿三锚点，线性为设计裁定口径）
val AOE_MULT     = ScalingEntry(0.5f, 1.0f, 2.5f)                        // 坍缩 AOE 倍率
val ABSORB_LIMIT = ScalingEntry(3200f, 8800f, 16000f)                    // 吸收阈值（软上限拐点）
const val EXCESS_RATIO   = 0.25f   // 超出阈值部分折算比（定稿提案值，目检可调）
const val POOL_FLOOR     = 500f    // 空爆保底（无缩放）
const val COLLAPSE_RAD_MUL = 1.5f  // 坍缩半径 = 涡旋半径 × 150%（不缩放，跟随半径本身）
const val PULL_ACCEL_MAX = 1200f   // 涡旋边缘处指向中心的最大加速度 su/s²（目检可调）
// 类型转换比：ENERGY 1.0 / HIGH_EXPLOSIVE 0.5 / KINETIC 0.5 / FRAGMENTATION 0.25（固定，不缩放）
```

**状态机**（`AnnihilationVortexBeamEffect.advance`，对照 GCP 三段式裁剪充能段）：

```
IDLE ──(beam != null && brightness > 0.05)──▶ FIRING
FIRING ──(beam == null || brightness ≤ 0.05)──▶ COLLAPSE_ONCE ──▶ IDLE
```

- `weapon.ship == null`：一次性 WARN 后本帧 return（异常装配，不静默）。
- **进入 FIRING（开火起点，每周期一次）**：
  1. 难度取值并缓存本周期：`radius = resolve(RADIUS, ship.owner)`、`threshold = resolve(ABSORB_LIMIT, ship.owner)`、`aoeMult = resolve(AOE_MULT, ship.owner)`。
  2. 池就位：`ship.getOrCreateBuffByWeapon("astd_annihilation_vortex_pool", weapon) { AnnihilationVortexPoolImpl(threshold, host, weapon) }`。
  3. 建 VFX 树：`BeamVfxSpecs.build("astd_annihilation_vortex")` → `BeamVfxDriverImpl(BeamHostImpl("avortex@<idhash>", BASE_WIDTH), tree)`；记 `beamStartedAt`。
- **FIRING 每帧**：
  1. `BeamLineUtil.fromBeamOrWeapon(weapon, beam)` 取束几何；前 `BEAM_GROW_TIME = 0.08s` 视觉渐长（仅视觉）。
  2. 驱动束体树（`BeamFrame(..., firing = true, strength = 1f, fadeMul = 1f)`）——涡旋节点读 frame.endpoint 自绘。
  3. 吸收结算：`absorbed = absorb.advance(engine, center = beam.to, radius, absorbRadius = max(30f, radius * 0.25f), sourceOwner = ship.owner, amount)`。
  4. 逐被吸收弹体：`pool.addAbsorbed(shot.type, shot.baseDamage)`；玩家船携带时节流浮字（见 §2.3）。
  5. 玩家船携带时 HUD 刷新（见 §2.3）。
- **进入 COLLAPSE_ONCE（停火首帧）**：
  1. 坍缩伤害 = `max(pool.convertedTotal, POOL_FLOOR) * aoeMult`。
  2. 坍缩半径 = `radius * COLLAPSE_RAD_MUL`，中心 = 最后记录的 `beam.to`。
  3. `collapse.resolve(engine, center, collapseRadius, damage, source = ship)` → 命中数。
  4. `AnnihilationVortexVfx.collapse(engine, center, collapseRadius)`；玩家船携带时中心浮字（见 §2.3）。
  5. `ship.buffHost().remove(pool, weapon)`（池消费完毕，触发 `onRemove`）。
- **宿主失效**（船 hulk/移除/换装）：池 `advance` 内自判 `!isHostValid()` → 记 INFO「涡旋吞噬池随宿主失效丢弃 X」→ `host.remove(this, weapon)`，**不触发坍缩**（机制明确行为：宿主死亡涡旋哑火）。
- **结算顺序铁律**：每帧内 束体驱动 → 牵引/吸收 → 池记账 → HUD；停火帧 坍缩结算 → 坍缩 VFX → 池移除。坍缩只在停火首帧恰好一次（`beamStarted` 标志位保证，对照 GCP `beamStarted/fadeStartedAt` 模式）。

**牵引/吸收**（`AnnihilationVortexAbsorbImpl.advance`）：

```
candidates = CombatUtils.getEntitiesWithinRange(center, radius)   // 一次圆形粗筛（性能：半径 ≤300su）
for e in candidates:
    if e !is DamagingProjectileAPI: continue                       // MissileAPI 是其子接口，天然覆盖导弹
    if e.owner == sourceOwner: continue                            // 仅敌方（定稿裁定）
    dist = distance(center, e.location)
    reach = dist - e.collisionRadius
    if reach <= absorbRadius:                                      // 吸收
        engine.removeEntity(e)                                     // 弹体移除、不计伤害
        results += AbsorbedShot(e.damageType, e.baseDamageAmount)  // 面板伤害用 base（设计：「其面板伤害」）
        continue
    // 牵引：a = PULL_ACCEL_MAX * (1 - dist/radius)，方向指向中心，直接改写 live velocity
    if dist > 1f:                                                  // 0 值防线见 §2.4
        v = e.velocity; k = PULL_ACCEL_MAX * (1f - dist/radius) * amount / dist
        v.x += (center.x - e.location.x) * k
        v.y += (center.y - e.location.y) * k
```

**池记账**（`AnnihilationVortexPoolImpl.addAbsorbed`）：

```
converted = baseDamage * CONVERSION[type]          // 类型转换比
room = max(0f, threshold - convertedTotal)
effective = min(converted, room) + (converted - min(converted, room)) * EXCESS_RATIO
convertedTotal += effective; absorbedCount++
return effective
```

### 2.3 玩家可见反馈（对照实现注意事项 2：有机制必有反馈）

| 机制 | 通道 | 落点 |
|---|---|---|
| 吞噬池数值 | HUD 左侧状态栏 | 仅 `weapon.ship == engine.playerShip` 时每帧 `engine.maintainStatusForPlayerShip("astd_annihilation_vortex_pool", <武器图标>, <湮灭涡旋>, <吞噬池 X · 坍缩预估 Y>, false)`（样板 `LensMarkStatusBar.kt`）；池移除即停刷新，状态栏自然消失 |
| 单次吸收 | 自定义浮字（节流） | 玩家船携带时，每 0.4s 合并一次：`addFloatingText(center, "+N", 16f, 深红, null, 0f, 0f)`，N 为本窗口实际入池总量 |
| 涡旋本体 | 特效 | 端点 `VortexComponent`：深红 DistortionEntity 间隔抛 + 旋转环粒子 + nebula 底尘（玩家与 AI 均可见，AI 侧数值豁免 HUD 的例外不影响视觉） |
| 坍缩爆炸 | 伤害浮字 + 中心浮字 + 特效 | 逐目标 `applyDamage(..., showDamageFloaty = true)` 自动伤害数字；玩家船携带时中心 `addFloatingText(center, <坍缩释放 X>, 28f, 深红, null, ...)`；深红 `spawnExplosion` + 内收 Distortion + nebula 烟云 |
| 软上限触发 | 浮字（隐性缩放不进文案，但玩家可见池增长放缓由 HUD 数字直接呈现，无需额外通道） | — |

### 2.4 0 值与边界处理（对照实现注意事项 3）

| 场景 | 行为 |
|---|---|
| 空池停火 | 保底 `POOL_FLOOR = 500` 生效：`max(0, 500) × aoeMult`——空爆也有一次保底爆炸（定稿裁定），浮字/特效照常 |
| `baseDamageAmount == 0` 的弹体（照明弹/信号弹类） | 照常移除（吸收成立），`addAbsorbed` 入池 0、`absorbedCount + 1`，记 INFO（节流，同弹种同帧只记一条）——禁止静默恒零 |
| 牵引 `dist ≈ 0`（弹体已在中心） | 走吸收分支（`reach <= absorbRadius` 先判），加速度分支有 `dist > 1f` 门控，不除零 |
| `radius` 输入 ≤ 0（配置错误） | `AbsorbImpl` 入口 clamp 到最小 30f 并记 WARN 一次，不静默产出零半径涡旋 |
| `threshold` 输入 ≤ 0 | 池构造时 clamp 到 1f 并记 WARN（软上限退化为全程 25% 折算，语义明确） |
| 池阈值恰好等于 `convertedTotal`（`room = 0`） | `min(converted, 0) + converted × 0.25`——恰在拐点全额折算结束，无跳变 |
| `weapon.ship == null` / `engine.isPaused` | WARN 一次 / 直接 return（对照 GCP `isPaused` 门控） |
| 光束被异常打断（船 hulk/换装） | 池自回收 + INFO，**不坍缩**（§2.2 宿主失效条） |
| `CombatUtils.getEntitiesWithinRange` 返回空 | 本帧无牵引无吸收，正常路径无日志 |
| 坍缩半径内 0 目标 | 伤害结算空转（命中数 0），爆炸特效与中心浮字照常（空爆保底反馈） |

---

## 3. 特效面

**BeamVfxSpecs 登记项**（`src/main/kotlin/cn/kasuminova/astd/renderer/beam/driver/BeamVfxSpecs.kt`）：

- `builders` map 字面量**末尾**追加：`"astd_annihilation_vortex" to { annihilationVortex() }`。
- 私有构建函数 `annihilationVortex(): RenderEntity` 追加在类内（本组独占 BeamVfxSpecs，无并行冲突；仍按合并协议末尾追加 + 收口字典序）：

```kotlin
/** 湮灭涡旋：深红束体 4 件套 + 端点引力涡旋节点。吞噬/坍缩逻辑由 BeamEffect 维护，本树只画视觉。 */
private fun annihilationVortex(): RenderEntity = renderEntity("astd_annihilation_vortex") {
    addChild(BeamCoreComponent("astd_annihilation_vortex_core", AvBeam.coreSpec()))
    addChild(VortexComponent("astd_annihilation_vortex_vortex"))
}
```

- 参数：单武器固定规格，构建函数无参（scale 恒 1）；`BeamHostImpl` baseWidth 由 `BeamEffect` 传 `BASE_WIDTH = 18f`（对应 `.wpn` width 14 的观感放大，目检可调）。
- 主色：LENS 深红（核心 `Color(255, 60, 70)`、辉光 `Color(180, 20, 40)`、涡旋环粒子同族），对齐「湮灭涡旋用深红涡旋 + 深红坍缩爆炸」美术口径。
- `VortexComponent`：读 `RenderContext.frame.endpoint`，`frame.active` 时按 IntervalUtil 间隔抛短寿命 `DistortionEntity`（尺寸随设计半径档位由 BeamEffect 经树参数传入——构建函数闭包捕获不可行时改由 `frame.intensity` 映射，实装时二选一并写明）+ 每帧旋转环粒子（`addSmoothParticle`，6~10 枚环布、角速度恒定）。

**smd_projectile_vfx.json 映射键**：**不触碰**。本武器为 beam 无 `.proj`，弹体 VFX 管线不涉及；光束树由 `BeamVfxSpecs.build(id)` 直取，无 JSON 映射层。

---

## 4. 测试面

### 4.1 单元测试清单（`src/test/kotlin/cn/kasuminova/astd/combat/effect/lens/` + BeamVfxSpecsTest 追加）

全部调用真实逻辑（kotlin.test + `DifficultyTuningImpl.installScaleForTests` 注入系数），禁止纯源码 contain 测试：

1. **类型转换比**：`pool.addAbsorbed(ENERGY, 100f)=100`；`HIGH_EXPLOSIVE/KINETIC → 50`；`FRAGMENTATION → 25`。断言 `convertedTotal` 与返回值。
2. **软上限分段**：threshold=8800 时——池 0 加 400 全入；池 8700 加 400 → 入 100 + 300×0.25=175；池 8800 加 400 → 全 ×0.25=100；恰在拐点（池 8800-room 0）无跳变。
3. **保底结算**：坍缩伤害函数 `max(pool, 500) × mult` 三档（pool=0/300/600）。
4. **难度取值**：`installScaleForTests(1f/2f/5f)` 下 `resolve(RADIUS, owner=1)` = 150/187.5/300；`resolve(ABSORB_LIMIT)` = 3200/8800/16000；`resolve(AOE_MULT)` = 0.5/1.0/2.5；`owner=0` 时任意注入系数恒取 v2（187.5/8800/1.0）。
5. **牵引加速度**：纯函数路径——`dist=radius` 加速度 0；`dist=radius/2` 半额；输出矢量指向中心；`dist ≤ 1f` 不触发除零（走吸收判定）。
6. **吸收半径边界**：`dist - collisionRadius == absorbRadius` 吸收；`+ε` 不吸收只牵引；`absorbRadius = max(30f, radius×0.25)` 两档（radius=100 → 30；radius=300 → 75）。
7. **0 值防线**：`baseDamage=0` → 入池 0、`absorbedCount+1`、产出 INFO 日志（捕获 logger 断言）；`radius=0` 输入 clamp 30 + WARN；`threshold=0` 构造 clamp 1 + WARN。
8. **池生命周期（SELF_MANAGED）**：宿主失效后 `advance` 一次 → 恰好调用一次 `host.remove`、INFO 一条、不重复移除。
9. **BeamVfxSpecs 装配**（追加进 `BeamVfxSpecsTest.kt` 末尾）：`has("astd_annihilation_vortex")`；`build` 树子节点 = `{astd_annihilation_vortex_core, astd_annihilation_vortex_vortex}`；renderOrder 升序；core 为 `BeamCoreComponent`。

### 4.2 烟测检查点（`deployMod` + `launchSmokeTestGame`，automation 到终态即退出，不干等超时）

1. dev 仓储取得并装配到大型能量槽与协同槽（两种槽位均验证）。
2. 开火 2s / 冷却 9s 循环正确；原版光束不可见（Hidden 生效），深红束体 + 端点涡旋可见。
3. 敌方导弹/射弹进入涡旋：可见被牵引偏航 → 吸入消失、无伤害数字；HUD 吞噬池数值上涨、浮字节流弹出。
4. 停火坍缩：深红爆炸 + 范围内敌舰伤害数字；玩家船中心「坍缩释放 X」浮字。
5. 空池停火：保底爆炸仍触发（500 × 1.0 = 500 基础伤害数字）。
6. 软幅能特性：爆发辐能 3200 符合面板（装配界面目检）。
7. v5（300su/16000 阈值/250%）devMode FPS 监控：`getEntitiesWithinRange` 粗筛下无明显掉帧。
8. 宿主死亡中束：涡旋消失、无坍缩爆炸、日志 INFO 一条。

---

## 5. 并行实装注意

**触碰的共享文件清单**（按 00-共享基建 §3 合并协议标注键名空间）：

| 共享文件 | 本组键名空间 / 追加位置 |
|---|---|
| `ss-csv/src/main/resources/i18n/zh-cn.properties` | `weapon.astd_annihilation_vortex.*` + `desc.astd_annihilation_vortex.*`，**文件末尾集中追加**，不插中间、不动他组键 |
| `ss-csv/.../strings/Catalog_Descriptions.kt` | `Desc_astd_annihilation_vortex` 一行，WEAPON 分组尾部（`Desc_astd_psi_omega` 之后） |
| `ss-csv/.../weapondata/lens/Catalog_WeaponData_LENS.kt` | `Wpn_astd_annihilation_vortex`，**number = 9220**（预分配段位），文件末尾追加 |
| `src/.../renderer/beam/driver/BeamVfxSpecs.kt` | builders map 末尾 `"astd_annihilation_vortex"` 条目 + `annihilationVortex()` 私有函数；协议注明本组独占该文件，冲突风险为零 |
| `src/test/.../BeamVfxSpecsTest.kt` | 装配测试一个用例，类内末尾追加（不在协议表内，若与他组撞行同样由收口人归位） |
| `contents/data/campaign/special_items.csv` | **不触碰**（§1.4） |
| `contents/data/config/smd_projectile_vfx.json` | **不触碰**（§3） |
| `src/.../renderer/projectile/driver/ProjectileVfxSpecs.kt` | **不触碰** |

**对共享基建的依赖项**：

- **Buff API 全套**（`api/buff/` 四文件 + `impl/buff/` 两件）必须先落地 main：本组 `AnnihilationVortexPool : Buff`（SELF_MANAGED 档位正是 00-共享基建 §1.3 为湮灭涡旋预留的语义）、`ShipAPI.getOrCreateBuffByWeapon` 便捷入口、复合键生成均由基建提供。本组只依赖接口，不修改签名；确需扩展时在 PR 单独提出先合基建。
- `HiddenBeamRenderEffect`（generic/gravitycollapse，既有件直接复用）。
- `BeamVfxDriver/BeamVfxDriverImpl/BeamHostImpl/BeamCoreComponent/BeamLineUtil`（既有光束管线）。
- `DifficultyTuning`（既有）。
- 不依赖 `ConeImpactHandler`（坍缩为径向 AOE，非锥状）。
- 不依赖 `CombatRandom`（本武器无结算随机）。

**预估实现顺序内的位置**：首批计划 §12 第 4 位。前置：Buff API + CombatRandom PR 进 main（00-共享基建 §5 第 1/3 步）；与第 1~3 位（电荷针刺/离子脉冲/电驱）无文件交集，可并行。本组是 LENS 线光束 EveryFrame 模式的首个武器级落地（GCP 为同族样板），同时验证「BeamVfxSpecs 注册表 + 武器级 Buff 池」组合。

---

## 6. 验收要点（主代理逐项检查清单）

**数据面**

- [ ] `Catalog_WeaponData_LENS.kt` 追加 `Wpn_astd_annihilation_vortex`，number=9220，逐列值与本规格 §1.1 一致（重点：burstSize=2.0/burstDelay=9.0/beamSpeed=10000/energyPerSecond=1600/type=ENERGY/tags=no_drop 系）。
- [ ] `Catalog_Descriptions.kt` WEAPON 分组尾部新增 `Desc_astd_annihilation_vortex`。
- [ ] `zh-cn.properties` 六个键齐全且文案与设计案逐字一致；`customPrimaryHL` 为显式空值而非缺键。
- [ ] `astd_annihilation_vortex.wpn` 挂载 `HiddenBeamRenderEffect` + `AnnihilationVortexBeamEffect`，size=LARGE，pierceSet 含导弹/射弹五件套。
- [ ] `./gradlew :ss-csv:generateSsCsv` 产物 weapon_data.csv / descriptions.csv 行正确；`deployMod` 后游戏内装配面板数值与 tip 正确。
- [ ] special_items.csv 与 smd_projectile_vfx.json **无改动**。

**代码面**

- [ ] 三个接口（Pool/Absorb/Collapse）落 `api/combat/`，注释含类简介 + 动机 + 成员作用；实现落 `combat/effect/lens/`，命名无 Service/Manager/Controller/Runtime，零反射。
- [ ] 池状态走 BuffHost 武器级复合键（`astd_annihilation_vortex_pool`），无 `weapon.customData` 误用；SELF_MANAGED 档位，宿主失效自回收有 INFO 日志。
- [ ] 难度三锚点经 `AnnihilationVortexDifficulty.resolve` 统一取值，开火起点一次性缓存；`owner==0` 恒 v2。
- [ ] 牵引用 `CombatUtils.getEntitiesWithinRange` 粗筛（无全图遍历）；仅敌方 `DamagingProjectileAPI`；速度改写有 `dist > 1f` 门控。
- [ ] 吸收用 `baseDamageAmount`（面板口径）× 类型转换比；软上限分段实现与 §2.2 伪代码一致；保底 500 在坍缩伤害计算处生效。
- [ ] 坍缩恰好触发一次（停火首帧），半径 = 涡旋半径 × 1.5，`applyDamage` ENERGY 且 `showDamageFloaty = true`；宿主失效路径不坍缩。
- [ ] 无空 catch、无刻意兜底；异常装配路径均有 WARN/INFO。
- [ ] 玩家反馈四通道齐：HUD 状态栏（仅玩家船）、吸收节流浮字、坍缩中心浮字、自动伤害数字。

**特效面**

- [ ] `BeamVfxSpecs.builders` 末尾追加 `"astd_annihilation_vortex"` 条目 + `annihilationVortex()` 构建函数；core 为 `BeamCoreComponent`（深红 spec）。
- [ ] `VortexComponent` 读 `frame.endpoint`、`frame.active` 门控，无常驻 BoxUtil 句柄。
- [ ] 坍缩 VFX：深红 spawnExplosion + 内收 DistortionEntity + nebula 烟云。

**测试面**

- [ ] §4.1 九条单测全绿；无纯源码 contain 测试；难度用例走 `installScaleForTests` 注入。
- [ ] `BeamVfxSpecsTest` 新增装配用例通过。
- [ ] 烟测八条检查点全过，automation 到终态即退出。

**目检**

- [ ] 涡旋吞噬观感：敌方导弹成群偏航入红光；池值/HUD 同步上涨。
- [ ] 坍缩爆炸规模与半径 150% 匹配（150su 档 225su / 187.5su 档 281su / 300su 档 450su）。
- [ ] v5 档 300su 涡旋 FPS 无明显下降；深红色系与 LENS 紫红主色协调，不与 GCP 暗红混淆。
