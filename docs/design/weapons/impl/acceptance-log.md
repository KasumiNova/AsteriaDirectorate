# 首批武器实装验收日志

> 各组验收结论的追加式记录；大问题按「## 组号 + 标题」小节留档（现象/证据/建议），由主代理处理。

## infra1 共享基建 PR#1：叠层 Buff API + CombatRandom + HUD 通道（2026-07-29）

**范围**：`api/buff`（Buff/BuffLifetime、StackableBuff/StackDecayMode、BuffHost、BuffAccess 扩展族）、
`impl/buff`（BuffHostImpl、BuffTickPlugin）、`impl/combat/CombatRandom`、
`api/combat/CombatFeedback` + `impl/combat/CombatFeedbackImpl`。
依据规格：`docs/design/weapons/impl/00-共享基建.md` §1/§4.1/§4.2。本组无数据面（ss-csv）改动、无特效面改动，不要求启动游戏烟测。

**验收结论**：pass_with_small_fixes。

- §1.4 单元测试清单 7 项全部实现并全绿（复合键隔离/换装不可见、getOrCreate 幂等、addStacks clamp、
  同键覆盖 WARN、CONTINUOUS/WINDOWED/EXPIRE_ALL 衰减语义、心跳回收 onRemove 恰好一次、0 值防线）；
  §6 风险表换装回收（实现内比对 weaponId + 心跳回收）、`Misc.getRandom` 派生确定性均有对应测试。
- 新增测试 35 项全绿；`./gradlew build` 全量 360 项测试全绿。

**小修记录（实现层决策，语义与规格一致）**：

1. CombatRandom 未直接调用 `Misc.getRandom`，改为逐位复现其算法。证据：`Misc.<clinit>` 依赖
   `Global.getSettings()`（Misc.java:196，读 fluxPerCapacitor 等设置项），裸单测环境类初始化即 NPE，
   规格要求的「无需启动游戏验证派生确定性」无法触达真实 Misc。处置：按 jar 反编译核实的算法
   （`new Random(seed)` 丢弃 numCalls 个 nextLong，再以 `new Random(r.nextLong())` 出序列）在
   CombatRandom 内复现，游戏内产出序列与直接调用 Misc.getRandom 完全一致；单测以参照算法逐位对照。
2. seedOf 采用 Long 算术（`shipId.hashCode().toLong() * 31L + slotId.hashCode()`）替代 Int 溢出算术，
   避免 Int 乘法溢出造成的种子碰撞，派生稳定性语义不变。
3. 测试参考实现（ReferenceStackableBuff）整层结算引入 1e-4 浮点容差：边界上 10/s × 0.1s 的浮点积为
   0.99999905，直接截断会吞掉恰满的一层。

**留档大问题**：无。

## infra2 共享基建 PR#2：锥状冲击结算组件 + 共用锥面 VFX（2026-07-29）

**范围**：`api/combat/ConeImpact.kt`（ConeImpactSpec + ConeTargetFilter）、
`impl/combat/ConeImpactHandler.kt`（object 无状态结算器，LazyLib 空间网格粗筛）、
`impl/render/ConeImpactVfx.kt`（ConeImpactVfxSpec + spawn 入口 + 射线布局纯函数）、
`impl/render/ConeImpactVfxComponent.kt`（锥面 RenderEntity 组件）、
`impl/render/OneShotVfxPlugin.kt`（一次性世界锚点特效驱动 + PointHost）。
依据规格：`docs/design/weapons/impl/00-共享基建.md` §2。本组无数据面（ss-csv）改动，不要求启动游戏烟测。

**验收结论**：pass_with_small_fixes。

- §2.4 单元测试清单 5 项全部实现并全绿：锥内/锥外/恰在半角边界/恰在锥长边界/顶点重叠；
  大目标擦边半径放宽（角度放宽 + 表面距离锥长判定）；敌/我/中立 × 舰/机/弹过滤矩阵
  （含 hulk 剔除、非导弹弹体不纳入、hitShips/hitFighters/hitMissiles 独立开关、filter 豁免命中本体）；
  粗筛（LazyLib 网格语义复刻）与全表扫 1000 次随机布点一致性对照；direction 非单位矢量 WARN + 归一化、
  零长度方向 WARN + 不结算。
- 另覆盖：applyDamage 参数透传 + 落点取目标朝爆点表面点 + 伤害浮字开启（§4.2 玩家可见反馈）；
  damage/empDamage 负值 clamp、range 非正不结算、halfAngle 越界 clamp、双零无结算量 WARN；
  VFX 射线布局（奇数对称含中轴两缘、条数 clamp）、射线基宽缩放与 clamp、spawn 入参防线、
  OneShotVfxPlugin 暂停跳过/到期 detach+removePlugin 恰好一次/FrameState 锚点透传。
- 新增测试 28 项全绿；`./gradlew build --rerun-tasks` 全量 388 项测试全绿。

**小修记录（实现层决策，语义与规格一致）**：

1. 粗筛注入点：resolve 增加 `coarseQuery` 默认参数（默认 = LazyLib `getEntitiesWithinRange`）。
   证据：LazyLib 网格查询内部走 `Global.getCombatEngine()`（反编译 CombatUtils.getEntitiesWithinRange
   首行即 invokestatic Global.getCombatEngine），裸单测环境 NPE，规格 §2.4-4「粗筛与全表扫一致性对照」
   无法触达真实网格。处置：粗筛提供者抽为可注入函数值，测试注入网格语义复刻（已反编译核实其判定为
   `dist ≤ 半径 + 目标碰撞半径`，MathUtils.isWithinRange 计入 collisionRadius）与全表扫两个变体对照；
   游戏内永远走默认实现，接线不变。
2. 锥长判定取「表面距离」（dist - 碰撞半径 ≤ range）：与 LazyLib 粗筛「表面进入半径」语义对齐，
   保证精筛结果恒为粗筛候选子集（一致性对照的成立前提），且大目标贴边波及的观感更正确。
3. 角度/距离比较引入 1e-3 浮点容忍（ANGLE_EPS/RANGE_EPS）：恰在边界的目标按「含边界」语义纳入，
   避免 acos 浮点误差导致边界目标取舍抖动。
4. 测试桩：驱动生命周期测试的 RenderEntity 树用手写 RecordingTree 桩，不用 mockito——Kotlin 接口
   非空形参经 mockito matcher（any() 返 null）调用即 NPE，jar 侧 Java 接口不受影响。
5. 一次性特效宿主 PointHost 直接实现 RenderHost、不另立空接口：无宿主侧查询行为（几何常量由组件
   spec 持有），另立接口即空接口过度设计；BeamHost 立接口的先例是因为节点需下转型查询 baseWidth。

**留档大问题**：无。
