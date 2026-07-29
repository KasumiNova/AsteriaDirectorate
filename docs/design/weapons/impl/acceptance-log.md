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

## 01 电荷针刺 / 重型电荷针刺（2026-07-29）

**结论**：验收通过（烟测第二十一轮 Completed，全证据链闭合，截图目检通过）。

**烟测证据（charge_needle_basic，野狼[小+重] 对 伯劳鸟[小]）**：

- 相位机 SHIELD→HULL→CEASE→COMPLETED 全通（0.97s / 2.48s / 2.50s）；淤积峰值 8 层、泄放 34 次、
  小型弹匣 2.47s 倾泻至空、重型弹匣全匣打空（最小观测 0）、衰减归零验证 true、
  受击方淤积峰值为 8 层（玩家护盾维持 60→70 遥测同步抬升）。
- 截图目检：左下 HUD 双向条目齐全——受击方 negative「电荷淤积 本舰护盾维持 +22%（11 层）」、
  攻击方「目标 N 层，护盾维持 +N%」；ASCII % 正常渲染；冷蓝白 texTrail 弹迹连贯无原版弹体残留；
  泄放 EMP 电弧与「熄火！」瘫痪可见；武器组中文名（电荷针刺/重型电荷针刺）+ 自动开火标识正常；
  FPS 164 / Idle 26%。
- 特效登记：`onFire track` 两个 spec 均 tracked=true specRegistered=true；VfxLastSpecId 两 id 均有出现。

**与规格的偏差处置（均已修，记 smallFixes）**：

1. 规格 §1.5/§4 验收口径「special_items.csv order 留空」与实机冲突：原版 CSV 解析 order 列强制数字，
   留空启动即 JSONException。按既有回声核心段位补 9200/9201（规格文本错误，留档备主代理修订规格）。
2. 泄放 soundId 规格为 null（自留确认项）：实机 null 无声，按速射 EMP 武器口径定 `shock_repeater_emp_impact`。
3. 规格 §2.3 伪码泄放落点选取与 `spawnEmpArc` 调用不一致：API 无落点指定入口（jar 已核实），
   按最终调用实现，落点由原版在目标舰上自选。
4. HUD 文案全角 ％ 原版状态栏字体不渲染（截图显 "?"）：改 ASCII %（对齐原版 "-15%" 既有约定）。
5. 弹匣倾泻观测 2.47s（规格口径 1.5s）：舞台 AutofireAI 扳机纪律非理想压满，机制射速 20 发/s 不变，不影响验收。

**automation 舞台排障结论（后续组烟测样板复用）**：

1. 每帧 `setRemainingCooldownTo(0f)` 会反复重置武器开火周期导致零弹体（isFiring=true/弹药恒定），
   已移除；开火走武器组 autofire toggle（setForceFireOneFrame 对无舰 AI 舞台舰不生效）。
2. 舞台舰 AI 置空会导致 OMNI 盾失去威胁追踪停在一个朝向（弹体全走船体路线、淤积恒 0）且敌方
   AutofireAI 拒射：钉死 staging 必须 preserveAI=true。
3. 本环境 shrike_Attack 被 VariantAcknowledged 覆盖为 safetyoverrides + converted_hangar：
   SO 射程公式 450+(700-450)*0.25=513 < 舞台间距 637 致敌方判超程拒射，任务定义移除两 hullmod。
4. 窄射界槽位（野狼 WS 004 仅 5° 弧）AutofireAI 目标采纳死锁（同槽挂小型判别一致，与重型 spec 无关，
   extraArcForAI=25 亦不解）：舞台逐帧 `setCurrAngle` 对准 + 重型 `setForceFireOneFrame` 直控取证据。
5. 重型 catalog 补 `extraArcForAI=25`（对齐 shockrepeater 先例的窄射界 AI 补偿列，实机验证非根因但为合理惯例值）。

**单测**：arc 包 14 条用例全绿（安全闸四分支/难度隔离/衰减/泄放随机确定性/HUD 双向/onRemove 回收）。

**留档大问题**：无。

## 03 电驱加速炮（2026-07-29）

**结论**：验收通过（烟测第二轮 Completed，全证据链闭合，截图目检通过；第一轮 FIRE 相位超时为舞台点火问题，修复后复跑通过）。

**烟测证据（electric_drive_basic，锤头级对锤头级，双方摘除目标定位系统）**：

- 相位机 RANGE_ZERO→RANGE_MID→RANGE_HIGH→FIRE→ENEMY_SCALE→COMPLETED 全通
  （0.60s / 1.20s / 1.80s / 2.67s / 4.95s）。
- 净空加速射程三档：0 辐能 1000.0（满额 +200）、30% 辐能 899.90（半额 +100）、50% 辐能 800.0
  （≥40% 阈值归零）——射程圈随辐能伸缩数值闭合。
- 每触发弹数：分组计数两轮齐射 [8, 8]（LINKED 双管 × burst 4），edaMaxTriggerProjectiles=8；
  弹匣最小观测 22 = 30 - 8，与单触发 8 发一致。
- 不稳定装药：玩家侧追加伤害 20 次、峰值 43.39 ∈ [0, 45]（v2 上限 80×56.25%）；
  敌版 k_s=5 档 7 次、峰值 59.85 > 45（突破玩家档上限，证明难度系数作用于装药）。
- 敌版净空加速三档：installScaleForTests 切 k_s=1/2/5 → 射程 900 / 1000 / 1200（v1=+100 / v2=+200 / v5=+400）。
- 截图目检：左下 devMode HUD 条目「电驱加速炮 射程加成：+200 su」与武器组中文名「电驱加速炮」、
  弹药 22、白色 500su texTrail 双轨弹迹连贯、敌方盾缘追加伤害浮字「59」可见；FPS 165 / Idle 55%。
- 特效登记：edaVfxTrackedCount=4，edaVfxLastSpecId=astd_electric_drive_accelerator_shot，
  唯一登记面 ProjectileVfxSpecs。

**浮字 spike 结论（规格硬性待验证项）**：javap 反编译 starfarer_obf.jar CombatEngine.applyDamage——
8 参版本末位布尔 iconst_0 恒 false，脚本 applyDamage 原生不产生伤害浮字（10 参版 iload10 驱动的
A/I.super(G,point,velocity,entity) 经符号表核实为命中音效/粒子，非浮字；原版弹体浮字由弹体命中
代码另行调用 addFloatingDamageText）。故 OnHitEffect 显式 floatingDamage 必须保留、不会叠字。
结论已同步写入 ElectricDriveAcceleratorOnHitEffect 类注释与本 commit 信息。

**与规格的偏差处置（均已修，记 smallFixes）**：

1. 规格 §1.5 special_items.csv order 留空与实机冲突：原版 CSV 解析 order 列强制数字，留空启动即
   JSONException。沿袭 01 回声核心段位补 9202（规格文本错误，留档备主代理修订规格）。
2. 规格 §2.5 要求 weapon.slot 空槽时退化 weapon.id 拼 seed：BuffHostImpl.slotIdOf 对空槽抛
   IllegalArgumentException，退化路径在 Buff 侧不可行。OnHitEffect 改 WARN once + 放弃本次结算
   （装配武器必然有槽，纯防御分支）；WeaponEffect 侧 modId 按规格退化 weapon.id + WARN（modId 无
   此限制）。
3. 烟测舞台：锤头级 WS 001 挂电驱加速炮时武器组 AutofireAI 目标采纳恒 null（第一轮 90s 零发射、
   弹药恒 30 实证），与电荷针刺 WS 004 同款槽位/组级 AI 行为。按针刺重型先例逐帧 currAngle 对准 +
   setForceFireOneFrame 直控开火（纯舞台手段；开火周期/弹药/散射由武器机制自身决定）。
4. MissionDefinition 双方摘除 targetingunit（ITU）：其射程加成会抬升射程基线，破坏 800/900/1000
   期望读数（staging 必要性）。
5. ASTDAutomationCombatPlugin.writeDiagnostics 守卫补 isEdaEnabled 分支（漏加则 EDA 场景诊断不落盘）。
6. 规格 §4.1 用例四 float 减法误差（0.3f-0.2f≈0.099999994）：断言容差放宽至 1e-4f（对齐基建
   ReferenceStackableBuff.STACK_EPS 先例）。

**留档大问题（规格算术矛盾，实现已按规格自身验收口径落地，规格文本待主代理修订）**：

1. 规格 RANGE_BONUS 指定 ScalingMap.LINEAR(100, 200, 400)，但 LINEAR 在 [2,5] 段三等分，
   k=3 输出 266.67，与规格 §4.1 单测期望 300、设计案远征锚点 +300 矛盾（同一规格文档内部冲突）。
   实现采用自定义四 knot 分段线性映射（k≤2: v1→v2；k≤3: v2→(v2+v5)/2；否则 mid→v5）使四档
   100/200/300/400 全部命中规格验收值。建议主代理修订规格文本：将 RANGE_BONUS 的映射声明改为该
   四 knot 定义（或把 v5 改为 500 使 LINEAR 天然过 300）。

**单测**：ElectricDriveAcceleratorLogicTest 七条用例全绿（chargeMaxPct 五档/rangeBonusBase 四档/
fluxDecayFactor 分段越界 NaN/rangeBonus 合成/extraDamage+shouldApplyExtra/seed 稳定区分/callIndex
递增+isHostValid）。

## 04 湮灭涡旋（2026-07-29）

**结论**：验收通过（烟测第三、四轮连续 Completed，八条检查点全闭合，第四轮截图目检通过；
第一轮 MOUNT 旗舰自动部署未就绪、第二轮宿主死亡误坍缩 + 守卫误判、第三轮截图被旗舰阵亡
增援对话框遮挡，均已修复后复跑通过）。

**烟测证据（annihilation_vortex_basic，桑德级 WS 003 + 奥德赛级 WS 001 协同槽 对
警戒级投喂舰 + 奥德赛级敌版，四舰全 reserves 手动 spawn）**：

- 相位机 MOUNT→ABSORB→COLLAPSE→EMPTY_PREP→EMPTY_FIRE→ENEMY_SCALE→HOST_DEATH→COMPLETED
  全通（0.60 / 11.61 / 13.62 / 23.62 / 25.63 / 50.63 / 53.65s，两轮一致）。
- 双槽位装配：avPlayerSlotId=WS 003（大型能量）、avSynergySlotId=WS 001（大型协同，
  ENERGY 类武器装入 SYNERGY 槽 ✓）。
- 爆发循环：avBurstOnSeconds≈1.97 / avBurstOffSeconds≈9.00（2s on / 9s off ✓）；
  Hidden 生效 avHiddenBeamOk=true（原版束宽归零，自绘束接管）。
- 牵引/吸收：avAbsorbedPlayer=9（门限 ≥3）；单次吸收入池浮字「200」（annihilator 800×0.25）
  截图可见，avFloatyCount=9；HUD 吞噬池状态条目 avHudFrames=972。
- 坍缩：COLLAPSE 相位 avLastCollapseHitsPlayer=8（投喂舰在半径内 ✓）；
  空池保底 avEmptyCollapseDamage=500.0（|500-500|≤1 ✓）。
- 敌版三档：installScaleForTests 切 k_s=1/2/5 → 半径 150 / 187.5 / 300、k_s=5 阈值 16000、
  AOE 倍率 2.5 ✓；k_s=5 档 300su 涡旋帧率 avScale5Fps=165.8（≥30 ✓）。
- 宿主死亡：协同槽宿主中束击杀（50.65s）→ avPoolRecycled=1（SELF_MANAGED 自回收 + INFO）、
  坍缩增量 0（涡旋哑火 ✓）。
- 截图目检（第四轮 frame-02/03）：深红束体贯穿约 800su 命中投喂舰（整束红调，
  与 GCP 白芯暗红边明显可区分）、束端深红涡旋辉光面可见、吸收浮字「200」入帧、
  停火后残余红晕与爆炸云；frame-01 为捕获首帧偏暗（舞台观察，不阻塞）。

**与规格的偏差处置（均已修，记 smallFixes）**：

1. 承前：zh-cn.properties notes 直引号转弯引号（文案规范）。
2. AnnihilationVortexAbsorbImpl 读取 collisionRadius 失败原静默 catch 回退 0——违反禁空
   catch，改每实例 WARN 一次后回退（不静默）。
3. 涡旋半径树参数注入二选一裁定（规格 §3）：BeamVfxSpecs.builders 为无参注册表，闭包捕获
   不可行；由 BeamEffect 建树后按节点 id 直写 VortexComponent.vortexRadius（单测覆盖默认值
   187.5f 与树结构）。
4. 单测 PoolTest 初版以 getDeclaredField 反射取私有字段做 verify——违反禁反射（含测试）
   硬规则，重写为 newPool 返回 PoolFixture(pool, host, weapon) 三元组直接持有构造引用。
5. 该版本 Mockito eq() 对 Kotlin 接口非空参数返回 null 触发 Intrinsics NPE：never() 校验
   改用真实值 `verify(host, never()).remove(pool, weapon)`（Java 接口上的 eq() 用法不受影响）。
6. 烟测舞台四舰全走 reserves + 插件手动 spawn（第一轮实证：原版旗舰自动部署相位在 0.6s
   settle 内未就绪且始终未部署，MOUNT 判 playerSlot=null）。
7. 宿主死亡帧束灭停火沿仍触发一次坍缩（第二轮实证 avCollapseCount 6→7，违反机制「宿主死亡
   涡旋哑火」）：BeamEffect 坍缩路径加 hostAlive 防线——不坍缩、不 markConsumed、记 INFO 后
   buffHost.remove 走 SELF_MANAGED 回收语义（消费/丢弃 INFO 分流）。
8. 舞台截图三轮迭代：第二轮 ships-missing 守卫在被击杀舰离场后立即误判 Failed，放行被击杀舰
   缺席；第三轮实证旗舰阵亡会弹出增援/换旗舰对话框（列 sunder 12 / odyssey 45 两舰）遮挡整个
   画面致截图全黑——HOST_DEATH 击杀对象改协同槽 odyssey_A（机制等价、owner 0 遥测键相同），
   COMPLETED 舞台改玩家旗舰开火（HUD 路径入帧），Completed 上报门控到爆发中段 0.8s
   （2s/9s 循环下随机时刻连拍三帧大概率为无束空场）。另：sunder/odyssey 自带 converted_hangar
   黄蜂联队为残余舞台噪音（敌版吸收计数 1~3 的来源），未影响任何相位门限。

**留档大问题**：无。

**单测**：AnnihilationVortexTest 20 条（Tuning 3 + Pool 9 + Absorb 5 + Collapse 3）
+ BeamVfxSpecsTest 涡旋树装配用例全绿（三锚点 k_s 精确值、类型转换比/软上限分段/保底、
SELF_MANAGED 回收、牵引纯函数边界、坍缩九参伤害调用、涡旋树 renderOrder 升序）；
全量 ./gradlew build 绿。

## 05 穷距相位轨道炮（2026-07-29）

**结论**：验收通过（烟测两轮连续 Completed，八相位全通，第二轮截图目检通过；
第一轮 Completed 上报即拍帧为空场，补截图门控后复跑通过）。

**烟测证据（qiongjue_railgun_basic，统治者级双穷距 WS 012/WS 013 对 敌版统治者级 WS 012 单装
+ 两艘无武装警戒级靶舰，四舰 reserves + 插件手动 spawn）**：

- 相位机 MOUNT→STACK→DUAL→SWITCH→DECAY→KILL→ENEMY_SCALE→COMPLETED 全通
  （两轮一致：0.60 / 18.32 / 25.32 / 27.14 / 32.4 / 39.63 / 65.5s）。
- 装配：qjWeaponRange=1200.0、双槽 WS 012/013、武器组中文名「“穷距”相位轨道炮」弯引号渲染正常、
  伤害类型动能、27 OP（weapon_data.csv 生成行逐列核对）。
- 叠层：满层伤害乘区 qjDmgMultAtFull=1.6250（10 层 × v2 6.25%）、满层射击间隔
  qjRefireMinAtFull=1.2376s（2s/1.625≈1.23s）、spike 应用 58/64 次、HUD 维持 14240/15884 帧。
- 同舰双穷距：qjDualW1Stacks=10 / qjDualW2Stacks=4 独立叠层（Weapon 级复合键 + 逐命中 DamageAPI 双隔离）。
- 切换目标：10 → floor(10×0.3125)+1=4 层（qjSwitchW1Stacks=4）、「演算转移」浮字 qjTransferPlayer=4、
  满层边沿「演算完成」浮字 qjFullPlayer=2。
- 衰减：停火窗口后 qjDecaySeconds=5.28 观测层数流失（DECAY 相位门限通过）。
- 打死目标转火：qjStacksBeforeKill=3 → qjStacksAfterKillHit=4（旧目标失效不折算 +1，规格裁定项目检确认）。
- 敌版三档：installScaleForTests 切 k_s=1/2/5 → 4 层乘区 1.20/1.25/1.40（v1 5%/v2 6.25%/v5 10%）；
  敌版命中 qjHitOther=15、叠至 5 层，AI 正常开火无「追不上不开火」僵持（90 风险 B5 目检通过）。
- 特效：命中锥面特效 qjConeVfx=58/64 次；texTrail 弹体 runtimeTrackedCount=2、
  SSOptimizer projectileObserved=true / vfxObserved=true。
- 截图目检（第二轮门控后 frame-02/screenshot.png）：左下状态条目
  「持续演算 层数 2/10 · 伤害 +12.5% · 射速 +12.5%」渲染正常（ASCII %、无键名泄漏）、
  白色细长弹迹双发在飞、敌方武器组自动开火标识与命中伤害浮字可见；FPS 165 / Idle 66%。

**射速 spike 结论（规格 §2.4 硬性二选一，禁止静默）**：
采用 **`WeaponAPI.setRemainingCooldownTo` 冷却扣减**，弃 `ballisticRoFMult` 舰体乘区——
每个开火周期起点（`cooldownRemaining` 上跳沿，跳幅 >0.5s 判定）一次性把本周期冷却压缩为
`cd / mult`，精确作用于本武器，无同舰其他实弹武器射速同步变化的副作用
（90 计划风险 #9 / 收口清单 C2 消缺）。01 验收判例「每帧 setRemainingCooldownTo(0f) 反复重置
开火周期」在本方案不成立：只在跳沿写一次。周期中途叠层变化不追溯当前周期，下一周期生效
（已写入 QiongjueCalcStacks 类注释）。实证：满层间隔 1.2376s ≈ 2/1.625。

**与规格的偏差处置（均已修，记 smallFixes）**：

1. 伤害乘区通道偏离规格 §2.2 伪码（`weapon.damage.modifier.modifyMult`）：烟测实证
   （qjDmgStatShared=true）**同舰同 spec 武器共享 `weapon.damage.modifier` 底层 MutableStat**，
   双穷距各自写入互乘（满层实测 1.625²=2.6406），破坏「同舰双穷距独立」。改走
   `DamageDealtModifier` 逐命中通道——每发弹体 DamageAPI 独立，按 `projectile.weapon` 解析槽位
   Buff 层数逐命中写入，天然逐武器隔离；Buff 层数为唯一数据源。单测
   「双穷距逐命中 DamageAPI 天然隔离」回归共享 stat 场景。规格伪码待主代理修订。
2. customPrimary 的 `{%s}` 占位：规格 §1.1 注「不含 {%s}」与原版机制冲突——原版
   customPrimaryHL 仅在 customPrimary 含 `{%s}` 时按序替换并高亮（原版 weapon_data.csv 38 处
   实证），不含则 HL 整列不渲染、「难度系数」高亮落空。本组按 `{%s}`×3 书写，并连带修正
   01/03/04 三组同构键（同因，HL 此前空转）。规格文本待主代理修订。
3. special_items.csv order「留空」与原版 CSV 解析强制数字冲突（01 判例）：沿袭段位补 9203。
4. `.wpn` 占位贴图：turret/hardpoint 四件暂用 graphics/textures/BUtil_NONE.png（规格 §1.2 允许
   占位跑烟测）；**美术贴图 graphics/weapons/astd_qiongjue_base.png / astd_qiongjue_gun.png
   未到位，本武器在贴图到位前不算完工（规格登记未完工项）**；HUD 图标暂用
   graphics/hullmods/astd_arc_loop_interface.png，贴图到位后替换为武器本体图标。
5. 烟测截图门控：第一轮 Completed 上报时刻连拍三帧全为空场（叠层已衰减、弹体不在飞）；
   补「COMPLETED 相位叠层回升 ≥3 层才上报 Completed」门控（对齐 04 AV 中段门控先例，
   保底超时 25s 防舞台卡死），第二轮 HUD/弹迹/伤害浮字全部入帧。

**观察记录（不阻塞）**：同舰双穷距共用一个 HUD 键（规格 §2.2 固定 astd_qiongjue_status），
状态条目显示后 advance 的一件（截图 2/10 为 W2，同帧 W1 为 3）；规格未定义双件 HUD 合并语义，
单件场景显示精确。

**单测**：QiongjueStackMathTest 9 条（规格 §2.5 用例 1~9：叠层上限/折算三档/归零边界/窗口边界/
清零即止/玩家固定 v2/倍率正算/失效不折算/decayRate=0 WARN 恰好一次）+
QiongjueDamageDealtModifierTest 5 条（玩家 v2/敌版 v1 乘区、零层与无 Buff 放行、非穷距弹体过滤、
双穷距逐命中隔离）全绿；全量 ./gradlew build 444 项测试全绿。

**留档大问题**：无。

## 06 正电子冲击波（2026-07-29）

**烟测场景**：`positron_shockwave_basic`（野狼清空全槽后 WS 001 小能量槽装正电子冲击波，
无武装警戒级靶舰；近炸相位由插件自 (820,±80) 每 0.9s 投喂鱼叉导弹群，两舰 reserves + 插件手动 spawn）：

- 相位机 MOUNT→PASS_THROUGH→SPLASH→FUSE→COMPLETED 全通（0.60 / 2.79 / 5.80 / 9.62s），
  state=Completed、failureReason=null、全程 0 条武器侧 WARN/ERROR。
- 装配：psSlotId=WS 001、psWeaponRange=600.0、psHintsPd=true（weapon_data.csv 生成行逐列核对，
  hints=PD、tags=astd_production、customPrimary/HL 注入成功）。
- 无触碰体积：collisionClass/collisionClassByFighter=NONE；PASS_THROUGH 靶舰置 400su 弹道上，
  两次满射程自爆后靶舰 1750/1750 满血（弹体穿舰不掉血）且 psDetonateFuse=0（舰船不触发近炸）。
- 满射程自爆：psLastDetonateDist=606.28 ∈ [570,640]（600su 空射自爆，不会静默消散）。
- 波及：SPLASH 靶舰移 700su，锥面波及舰船 psConeShipHits=2（delta≥1）且近炸计数 delta=0。
- 近炸：FUSE 相位 psDetonateFuse=4（≥2）、锥面命中导弹 psConeMissileHits=4（≥3）、
  devMode 浮字 psFloaty=6（“近炸命中 ×n”）、锥面 VFX psConeVfx=8。
- 难度锚点实证（首帧心跳）：玩家固定 v2 —— halfAngle=28.125°（56.25°/2）、
  coneRange=250.0、damage=250.0（200×1.25）。
- 截图目检（frame-01/02/03）：左下武器组「1X 正电子冲击波 · 伤害类型：破片」中文渲染正常、
  “近炸命中 ×1”浮字多处可见、投喂鱼叉拖尾弧线清晰、引爆闪点入帧；FPS 165 / Idle 71%。

**与规格的偏差处置（均已修，记 smallFixes）**：

1. `.proj` collisionClassByFighter：规格 §1.1「置空不写」与原版 ProjectileSpec 加载强制要求该键冲突
   （缺键 RuntimeException）；与 collisionClass 同写 NONE（01 special_items order 判例同族）。
   规格文本待主代理修订。
2. flightTime 边界：初版取 0.667（= range÷projSpeed），烟测实证**原版弹体寿命被钳制为
   range÷projSpeed，淡出与满射程同帧发生**，fade 抢先置 isFading 令引信脚本静默回收、
   自爆永不发生（第三/四轮诊断）。修复：引信脚本判定重排——满射程判定先于淡出兜底；
   淡出仍抢先属异常时序，记 WARN 并按满射程路径就地引爆（裁定「不会静默消散」的代码化兜底）。
   flightTime 取 0.75（≥钳制值即可，值本身不再决定时序）。catalog 注释已按实证机制改写。
3. 静默回收分支拆分：规格「弹体飞行中被移除→静默回收」仅限 !isEntityInPlay；
   isFading 独立成兜底引爆路径（见上），稳态零 WARN（第六轮实证）。

**单测**：PositronShockwaveDifficultyTest 6 条（玩家固定 v2/敌方迟暮 v1/敌方破晓 v5/
无主弹体按敌方口径取值并 WARN 恰好一次/满射程边界含等号/弹速为 0 记 ERROR 恰好一条并即爆）+
PositronShockwaveFuseTest 1 条（isFuseTarget 目标类型六宫格矩阵：敌导弹/敌战机/敌无人机触发，
友机/敌舰船/敌 hulk 不触发）全绿；全量 ./gradlew build 451 项测试全绿。

**留档大问题**：无。
