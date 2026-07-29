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

## 07 “七星”折跃发射器（2026-07-29）

**烟测场景**：`seven_stars_basic`（玩家奥德赛 WS 001 大能量槽装七星 + 无武装警戒级靶舰 A/B
分相位部署 + 敌版奥德赛 ENEMY_MULTI 相位投喂；相位机 MOUNT→BREAK→CHAIN→TERMINAL→
ENEMY_MULTI→COMPLETED 全通 0.60 / 0.62 / 4.62 / 8.64 / 17.15s）：

- state=Completed、failureReason=null、全程 0 条武器侧 WARN/ERROR；连跳峰值 ssChainFps=152.7。
- 装配：ssSlotId=WS 001、spec.maxRange=800 校验通过、ssHintsPd=true（weapon_data.csv
  生成行逐列核对：弯引号名、hints=PD、tags=no_drop, no_drop_salvage、flight time=6、
  customPrimary/HL 注入成功无键名泄漏；descriptions.csv text1+notes 注入成功）。
- BREAK（检查点 5+7）：增压鱼叉（HP 抬至一击不毁）→ 闪光发生但 kills delta=0、
  dissipateNoKill +1、terminal 计数恒 0（未击杀断链不触发终结）；靶舰 A 置弹道上 600su
  HP 恒满（collisionClass=NONE 穿舰无触碰伤害）。
- CHAIN（检查点 2/3/4b）：无敌舰空域持续投喂 → ssChainJumpsMax=7（7 跳硬上限未突破）、
  ssKills=61、ssCrossFlash=56 ≥ jumps（每跳一次十字闪光）、ssTeleportArc=41（折跃起止电弧）、
  7 跳后无处可去 → dissipateNoShip +1（无舰消散）。
- TERMINAL（检查点 4a）：靶舰 B 盾折叠完毕后放行 → terminalSingle delta≥1 且
  靶舰掉血（ssTargetHitpoints=1101.7/1750）、EMP 电弧 delta=0（玩家单段终结无 EMP）。
- ENEMY_MULTI（检查点 6）：installScaleForTests(5) + 敌版携带 → ssTerminalMulti=3、
  ssTerminalSegmentsMax=7（段表拉满）、ssTerminalEmpArcs=18（逐段 EMP 电弧）、
  玩家掉血 ssEnemyMinPlayerHp=9993.6/10000（多段终结实际结算）。
- 截图目检（frame-01/02/03）：十字闪光双正交臂 + 中心亮闪构图成立（蓝白 ARC 色族）、
  折跃路径电弧清晰、伤害浮字（382/245/663）随跳递增、武器组/武器条「“七星”折跃发射器」
  弯引号渲染正常、bloom 连跳高频无过曝糊屏、FPS 165 / Idle 59%。
- 游戏进程已关闭（到达终态即退出，未干等超时）。

**与规格的偏差处置**：

1. `.proj` collisionClassByFighter：规格 §1.1「null 省略」与原版 ProjectileSpec 加载强制要求
   该键冲突（缺键 RuntimeException，06 组实机判例同族）；与 collisionClass 同写 NONE。
   规格文本待主代理修订。
2. **锚点设计（规格 §0-2 裁定实机不成立）**：规格假定 flightTime=6.0 可保住弹体寿命跑完
   7 跳链；实机证实 BALLISTIC 弹体寿命被钳制为 range÷projSpeed（1280/3000=0.43s 即 fade，
   weapon_data.csv「flight time」列仅对 MISSILE 类生效）。改为：首发结算后弹体即
   removeEntity，连跳锚点移交纯位置（SevenStarsChainScript.anchor）——弹体全程不可见且无
   碰撞，移除无可观测差异；脚本生命周期随引擎回收，弹体引用失效故障面整体消失
   （规格 §2.4 该防线守护的场景在锚点设计下不存在）。数据面 flightTime=6.0 保留但已无
   实际作用。规格文本待主代理修订。
3. **沿舰体取点算法替换**：规格的碰撞圆盘拒绝采样实机命中率仅 ~3%（64 次仅得 2/7 点），
   且 applyDamage 落精确舰心点全额无效；改为射线二分法（均布方向 + 抖动，取界内最远
   半径 80% 处），退化射线记 INFO。规格 §2.2 sampleHullPoints 伪代码待修订。
4. **applyDamage 落点与 bypassShields 实机判例**（烟测第 4/5/7/8 轮对照）：带盾舰船盾关闭时
   bypassShields=false 全额无伤害 → 改为 bypass = !盾覆盖；脚本 applyDamage 非舰心落点
   结算不可靠（isPointInBounds=true 的界内边缘点恒 0 伤害）→ 非盾路径落点收敛舰心
   （落点仅影响装甲格选择与浮字位置，不影响伤害量）。spawn 免疫窗口非固定时长
   （同相位同 4.0s 时刻两轮结果相反），TERMINAL/ENEMY_MULTI 相位加部署免疫宽限闸。

**观察记录（不阻塞）**：规格 §6 验收要点写「zh-cn.properties 8 个键齐全」，§1.3 实际定义
6 键（name/customPrimary/customPrimaryHL/primaryRoleStr/desc.text1/desc.notes，text2~5 按规格
留空不生成）——实装 6 键齐全，§6 数字为笔误。tip 两段显示：自动化无法开装配界面，
以生成物 customPrimary 双 {%s} 与 HL「7 | 难度系数」对齐 + HUD 武器组名截图为证
（与 01/03/04/05/06 同口径）。

**单测**：SevenStarsChainMathTest 4 条（用例 1 flashMult 三锚点链逐档逐跳 / 用例 4 段表 /
用例 5 jumpRange 与 0 值 WARN / 用例 6 决策矩阵）+ SevenStarsDifficultyTest 2 条
（用例 2 玩家固定 v2 / 用例 3 多段终结解锁口径 1/2/3/4.99/5 边界）+
SevenStarsTargetSelectorTest 4 条（用例 7 可摧毁优先排序 / 用例 8 过滤矩阵恰等值纳入 /
用例 9 终结最近敌舰空集 null / 用例 10 护盾不预估已知简化）全绿；
全量 ./gradlew build 461 项测试全绿。

**留档大问题**：规格 §0-2「flightTime=6.0 覆盖弹体寿命」裁定与实机不符（见偏差 2，
已实现锚点方案规避，规格文本待修订）。

## 10 双子星 DEM 发射器 / 双子星 DEM 发射舱（2026-07-29）

**范围**：数据面 6 件（launcher/pod 9221/9222 + 隐藏弹头×2 9223/9224 + 隐藏 payload 光束×2 9225/9226，
`MissileProjSpec.behaviorSpec` 公共扩展）、6 件手写 .wpn、i18n 13 键 + 2 个 Desc（pod notes 走 notesId 复用）、
special_items 两行单件蓝图（params=武器 id）；代码面 5 类（GeminiDemSalvoOnFireEffect / GeminiDemTrackAI /
GeminiDemPayloadBeamEffect / GeminiDemSyncHandler / GeminiDemDifficulty，包 `combat/effect/arc`）；
特效面按规格 §3 不登记 ProjectileVfxSpecs/BeamVfxSpecs（导弹走原版渲染 + .proj engineSlots 双色尾焰，
payload 光束三色 .wpn 直配，同步冲击 spawnExplosion 白闪）。依据规格：`impl/10-gemini-dem.md` v1。

**验收结论**：pass_with_small_fixes。

**单测**：§4.1 十三用例全覆盖（SyncHandler 9 + Salvo 2 + TrackAI 3 + PayloadBeam 3，共 17 条）全绿；
`./gradlew build` 全量 478 项测试全绿。

**烟测**（`gemini_dem_basic` 相位机 MOUNT→SALVO→KILL_ONE→POD→ENEMY_SCALE→COMPLETED，
第 6 轮 COMPLETED at 25.81s，到达终态即退出，未干等超时）：

1. 装配/数据面：MOUNT 相位校验槽位（WS 019/WS 001）、射程 2500×2、spec maxAmmo 2/4（csv 口径）、
   隐藏四件 no_drop+no_drop_salvage tags、payload SYSTEM hint 全过；weapon_data.csv 生成物六行与 §1.1 逐列一致；
   .proj 生成物 behaviorSpec 键名逐字（含 `destroyMissleWhenDoneFiring` 原版拼写）。
2. 齐射：一次触发恰两枚弹头（warheads=salvo×2 断言），ammo 基线差分一轮一耗（launcher 4→3 / pod 8→7，
   环境倍率下等价 2→1 / 4→3）；齐射日志批次间隔 12.61/24.61/36.61s 恰 12s（chargedown 证据）。
3. **R1（最高优先）证伪风险**：供给侧面 TrackAI 装配 10/10 且目标非空 10/10；
   读回诊断实锤包装形态——`getAI()/getMissileAI()` 读回为引擎 `Missile$GuidedMissileAIWrapper`/`MissileAIWrapper`
   （非 GuidedMissileAI），`getUnwrappedMissileAI()` 读回为真实例（TrackAI / DEMScript）；
   DEMScript 接管硬证据 = payload 光束命中本身（规格 §0.1 事实 #7：payload 只能由 DEMScript 打击段结算），
   gdDemTakeoverSeen=9/10（第 10 枚为 KILL_ONE 相位被击落的高爆弹头，未活到接管，符合预期）。
4. **R2 读数校准通过**：payload 首伤帧日志 beamDamage=1000.0（动能）/ 1500.0（高爆），
   与「damage/second × burstSize 1s」口径完全一致，原版龙炎 8000×0.75 的未明差异未在本组复现，
   payload 行 damage/second 无需调整；动能首伤帧恰 4 道 EMP 电弧（empArcs=16=4 命中×4）。
5. 同步冲击：SALVO/POD 相位各触发一次（Δt=0.0187s/0.0s），damage=1093.75=2500×0.4375（玩家恒 v2）；
   KILL_ONE 相位击落高爆弹头后动能独发命中、同步计数恒不变（Δ=0）、高爆命中 Δ=0（反面证据闭合）；
   原生伤害数字 + 白闪截图可见。
6. 隐藏四件：tags/hints 校验过；codex/掉落未实机开界面（automation 限制），以 tags 证据为准（同 01~07 口径）。
7. 归属：ENEMY_SCALE 敌版（installScaleForTests(5)）同步 mult=1.0、damage=2500、玩家舰掉血观测通过；
   玩家侧恒 v2=0.4375；beam.source 打印 id 为空但 owner 正确（DEM 内部挂架舰，同源判定按 id 可判，设计容许）。
8. 目检：截图可见 payload 光束照射、伤害浮字、HUD 武器组「双子星 DEM 发射器/发射舱」中文渲染正常、
   双弹尾焰（frame-02 可见弹头拖尾）；FPS 165 无掉帧。

**小修记录（全部在烟测接线/任务面，机制语义与规格一致）**：

1. `MissionDefinition.java` 靶舰变体 `dominator_Standard` 不存在（addToFleet 静默落空、靶舰全程不在场），
   改现役 `dominator_Assault`（hullId=dominator，保留 stock 武备由插件逐帧缴械的既有设计不变）。
2. 相位机 ammo 断言重构：实机本机任务环境 `missileAmmoBonus` pct=+100（来源未定位，非本 mod、
   非 enabled mods 的 hullmod/skill 静态文本可考），runtime maxAmmo 翻倍（2→4/4→8）；
   规格检查点 8 的「ammo 2/4」为 weapon_data.csv 口径，改断言 `spec.maxAmmo`（数据面），
   「一次触发一轮齐射」改用基线差分（before-1），不吃环境倍率。
3. R1 观测面重构：实机判明 `engine.getMissiles()` **不含脚本 spawn 的弹头**（customData/weaponSpec
   扫描观测面全部落空，三轮失败均源于此）；GeminiDemSalvoOnFireEffect 增弹头出生登记簿
   （engine.customData 的 WarheadRef 列表，配置完成态原始引用），相位机与诊断改走登记簿；
   另增供给侧遥测 TELEMETRY_TRACK_AI_CREATED/TARGET_NONNULL。
4. KILL_ONE 高爆弹头移除改登记簿驱动 + 相位内持续移除全部在场高爆（相位内多轮齐射时后续高爆同样拆解，
   防 12s 后再配对污染「击落一枚无同步」证据）。
5. R1 读回诊断三路全扫（getAI/getMissileAI/unwrappedMissileAI），初版取 firstNotNull 恒命中包装对象
   导致 TrackAI/DEMScript 计数为 0，修正后 10/10、9/10。

**与规格的偏差处置**：

1. 规格 §1.1 payload 光束行未给 range 列，缺省 0 会令光束长度归零无法命中——按原版 dragon_payload=1000
   判例补齐 range=1000（前序实装已落，本轮确认合理）。规格表格待补列。
2. 规格 §1.6 special_items 示例行与「order 列留空」表述，与 05/06 组收口判例（`single_bp, astd` tags +
   order 9203/9204 编号）不一致——按仓库现行判例落 `single_bp, astd` + order 9205/9206。
3. 规格 §4.2 检查点 3 的观察手段「日志确认 setMissileAI 后 missile.getAI() 读回的 GuidedMissileAI 目标非空」
   实机不可行（getAI 读回为引擎包装对象，非 GuidedMissileAI）；但包装只存在于 API 面读回，
   DEMScript 内部持有引擎内层引用、WAIT 段 instanceof 正常工作（payload 命中硬证据 + 接管后
   unwrapped=DEMScript 双证），**无需启用规格 §5.3 R1 的任何处置方案，机制裁定成立**。
   规格检查点表述建议修订为「unwrappedMissileAI 读回 + payload 命中双证」。

**留档大问题**：规格 §1.1 payload range 列缺失、§4.2-3 观察手段与实机包装行为不符（均见上，
机制无推翻项，规格文本待主代理修订）。

## 02 重型离子脉冲（2026-07-29）

**结论**：big_issue_logged——§2.5 待验证项（A9：贯穿追加量被目标 empDamageTakenMult 二次减免）实机证实，
机制在 mult≈0 目标上的设计下限不成立，按规格预案留档待基建 PR 修正；其余验收项全部通过
（烟测一轮 Completed at 29.54s，到达终态即退出，未干等超时）。

**数据面**：`Catalog_WeaponData_ARC.kt` object 逐列与 §1.1 一致（number 9212、ammo 40/2.67/8、
burst 4/0.067、chargedown 0.175、emp 600、energy 150/400、OPs 26、tags astd_production）；
`generateSsCsv` 生成物 weapon_data.csv 第 37 行逐列核对通过；`.proj` 隐藏四件套齐全 +
onHitEffect 指向 HeavyIonPulseOnHitEffect；`.wpn` LARGE + 双炮管坐标 ×2 + ALTERNATING +
ion_pulser_fire；zh-cn.properties 键齐（{%s}×3 与 HL 三段一一对应）；special_items.csv 一行 params 裸 id。

**单测**：§4.1 十条全绿（Tuning 4：三锚点精确命中/玩家恒 v2/k_s=3 线性插值；Pierce 5：贯穿三档
<0.1/=0.1/>0.1 + mult=0 防线 750→750 + 0.099f 下沿；Discharge 1：边界 < 口径；VfxRegistration 1：
has/build 真实管线调用）；`./gradlew test` 全量无失败。

**烟测证据（heavy_ion_pulse_basic 相位机 MOUNT→SHIELD→HULL→SCALE5_PLAYER→PIERCE_K2→PIERCE_K5→COMPLETED，
双方桑德级 WS 003 大能量槽对射舞台）**：

1. 装配（检查点 1/2）：MOUNT 校验 slot=WS 003、range=700.0、spec maxAmmo=40、barrels=2（双管 ALTERNATING）、
   VfxSpec 登记断言全过。
2. 命中护盾无电弧（检查点 3 反面）：SHIELD 相位 8 发命中敌盾、零泄放（discharge=0）。
3. 船体泄放（检查点 3）：HULL 相位 discharge=4 / hits=13 ≈ 30.8%，与 v2 31.25% 体感吻合；
   mult=1.0 正向对照敌舰 enemyMaxDisabled=2（EMP 瘫痪武器机制生效）；弹匣节奏 minAmmo 追踪
   （13 发约 1.7s ≈ 爆发节奏，与 burst 4×0.067 + chargedown 0.175 口径一致；相位取证达标即转段，
   满匣 40 发未倾泻至空，节奏数字已由弹药差分覆盖）。
4. 难度隔离（检查点 4）：installScaleForTests(5) + 敌舰 mult→0 下玩家 hitsDelta=12、pierceDelta=0
   （玩家恒 v2 无贯穿，反面断言闭合）；敌版 k_s=2 pierceDelta=0（v2 档无贯穿特效）。
5. EMP 贯穿（检查点 5）：installScaleForTests(5) 敌版对 mult=0 玩家舰 pierce=3 次，
   lastExtra=1800.0 =（baseEmp 600 + arcEmp 1200）×（0.1−0）/0.1 公式逐位吻合；
   玩家舰为受击方，补伤浮字在其屏上可见（截图大数字浮字 + 火花）。
6. 弹体 VFX（检查点 7）：截图可见冷蓝白 texTrail 弹体连向敌舰、无原版弹体残留、
   泄放电弧冷蓝白；HUD 武器组「重型离子脉冲」中文渲染正常、弹药计数 40 正常。
7. FPS（检查点 8）：K5 持续命中相位 166.7、截图 164，无掉帧。
8. 日志：无 NPE/异常（既有 shaderlib 贴图与 SSOptimizer mixin 报错为环境固有，非本组引入）；
   规格登记的 WARN 防线（baseEmp≤0 / 游离弹）按其触发条件未出现（未触发即正确）。

**§2.5 待验证项现场核对结论（检查点 6，A9 风险证实）**：PIERCE_K5 相位对 mult=0 玩家舰，
贯穿浮字 lastExtra=1800 正常发出，但玩家舰被瘫痪武器数 0→max 0——追加 EMP 经 `applyDamage`
管线被目标 empDamageTakenMult=0 折算为零，未造成任何武器/引擎瘫痪；对照 HULL 相位 mult=1.0
敌舰 disabled=2，舞台 EMP 瘫痪机制本身生效。证据链：浮字发出 ≠ 实际结算生效，设计案
「EMP 贯穿保底」意图在 mult≈0 目标上被原版管线二次减免完全吞没。

**与规格的偏差处置（均已修，记 smallFixes）**：

1. special_items.csv `order` 列：规格 §1.5「order 留空」与 01 组实机判例冲突（原版 CSV 解析 order
   列强制数字，留空启动即 JSONException）——按既有段位补 9207（规格文本错误，同 01 口径留档）。
2. 规格 §1.5 蓝图描述文案用「重型离子脉冲」直角引号——全局铁律禁「」（原版字体无效符号），
   落实为弯引号“重型离子脉冲”（同 01 组既有行口径）。
3. 相位机 ammo 断言取 `spec.maxAmmo`（weapon_data.csv 数据面口径，不吃任务环境 ammo 倍率），
   同 10 组判例。

**留档大问题**：

1. **A9 证实：EMP 贯穿追加量被目标 empDamageTakenMult 二次减免，设计下限不成立**。
   现象：规格 §2.2/§2.3 的贯穿补伤走 `engine.applyDamage(empDamage=extra)`，原版管线按目标
   `empDamageTakenMult.modifiedValue` 折算 EMP 结算量；mult≈0 目标（贯穿机制的唯一服务对象）
   实际吃到 extra×mult≈0，「追加整发等值 EMP」的设计意图（mult=0 时退化为 emp×1.0）完全不成立——
   纯函数输出正确（单测钉死 750→750），但落进原版管线后被二次减免归零。
   证据：heavy_ion_pulse_basic PIERCE_K5 相位日志 lastExtra=1800.0/lastMult=0.0 + 玩家舰
   disabledWeapons 0→0（HULL 相位 mult=1.0 正向对照 disabled=2）；浮字显示 1800 与实际结算 0 脱节，
   另造成浮字误导（玩家看到大额 EMP 浮字但目标毫无反应）。
   建议：按规格 §2.5 预案二选一——(a) 折算 `extra / max(mult, 0.01f)` 使实际结算量回到设计口径；
   (b) 改走 `spawnEmpArc` 的 emp 通道（需先核实 spawnEmpArc 是否同样过 mult 折算）。
   规格要求「先改基建 PR 单独提出」，本组按设计案定稿口径实现并钉死纯函数，不在本分支处置；
   修正落地后建议同步处理浮字口径（显示值应与实际结算量一致，避免误导）。

## 09 贯星之矛（2026-07-29）

**结论**：pass_with_small_fixes——烟测一轮 MOUNT→CYCLE→CLUSTER→ENEMY_SCALE→COMPLETED（36.19s 转段，
到达终态即退出，未干等超时），八检查点全部闭合；唯一小修为烟测舞台外来实体清扫（第三方 mod 污染
舞台导致 CYCLE 零连带断言两次实机失败，武器行为本身正确）。

**数据面**：`Catalog_WeaponData_ARC.kt` object 逐列与 §1.1 一致（number 9219、tier 3、baseValue 60000、
range 1000、damage 357/2500、type="ENERGY"（DamageType 列）、energy 3000/429、chargeup 2.0/chargedown 5.0、
burst 1/0.0、spread 全 0、proj speed 3000、ammo 三列留空、OPs 30、tags "no_drop, no_drop_salvage"）；
`generateSsCsv` 生成物 weapon_data.csv 第 39 行逐列核对通过；`.proj` 隐藏四件套齐全 +
onFireEffect=ProjectileSpecOnFireDispatcher + onHitEffect=PiercingLanceOnHitEffect，无手改生成物；
`.wpn` 为 `"type":"ENERGY"` + `"mountTypeOverride":"HYBRID"`（不是 `"type":"HYBRID"`）+
everyFrameEffect=CombatVfxBootstrapEveryFrameEffect + tachyon_lance_fire；
zh-cn.properties 五键齐（{%s}×2 与 HL「125% | 难度系数」两段一一对应，name/desc 与定稿原文逐字一致）；
Catalog_Descriptions.kt WEAPON 分组尾部一行；special_items.csv 无改动（§1.4 稀有掉落口径）。

**代码面**：piercinglance 包四类与 §2.1 一致；难度取值只在 `buildConeSpec` 发生一次；
`PANEL_DAMAGE=2500f` 常量口径（不取 damageAmount）；命中本体豁免 filter（`it !== directTarget`）+
契约守护 ERROR（零触发）；EMP 与破片同锚同值；§2.5 各 0 值分支 WARN/DEBUG 防线齐备（烟测未触发即正确）；
未修改 ConeImpactHandler 签名；浮字 + 特效同帧。

**特效面**：`ProjectileVfxSpecs.builders` 末尾登记 `astd_piercing_lance_shot`（五旋钮形态 width 36 /
length 260 / glowScale 4.0，width 在 widthBase 46.7 红线内，冷蓝白内联字面量，未新增共享调色板函数）；
命中三层（顶点闪光 + 大光柱 + 共享锥面组件 ConeImpactVfx 复用自 06）遥测计数齐备。

**单测**：§4.1 十条全绿（半角/锥长三档换算、破片与 EMP 同锚三档、玩家恒 v2、敌版插值、本体豁免、
速度方向矢量、零向量回退、矢量不可得 WARN 恰好一条放弃、source null owner 回退、VfxSpec 真实构建）；
`./gradlew build` 全量 515 项测试全绿。

**烟测证据（piercing_lance_basic 相位机，onslaught WS 019 大实弹槽 + champion WS 008 大能量槽双射手，
三 enforcer 靶 + 两 enforcer 僚 + 敌版 champion 射手）**：

1. HYBRID 双槽可装（检查点 1）：A=WS 019/BALLISTIC/LARGE、B=WS 008/ENERGY/LARGE 装配成功，
   spec type=ENERGY / mountType=HYBRID、range=1000、cooldown=5.0、OP=30、no_drop 两件套、VfxSpec 登记断言全过。
2. 能量结算（检查点 1）：探针 r0=1600 → energyWeaponRangeBonus+50% 后 r1=2100（能量加成生效）→
   ballisticWeaponRangeBonus+50% 后 r2=1600（实弹加成不生效），正反双向证明按能量武器结算；
   HUD 武器组显示「贯星之矛 伤害类型：能量」。
3. 循环（检查点 2）：首充 1.99s（2s 充能条窗口 chargeLevel 观测可读）、出膛间隔 7.00s
   （2s 充能 + 5s 冷却）、完美精度走 spec 面板。
4. 弹体观感（检查点 3）：ProjectileVfxDriver trackedCount>0 且 lastProjectileSpecId=astd_piercing_lance_shot
   （texTrail + bloom 弹头接管）；截图可见冷蓝白拖尾细线，无原版弹芯穿帮（.proj 隐藏四件套）。
5. 命中单体（检查点 4）：resolves=2、flash=2、pillar=2、coneVfx=2 三层齐备；锥内零连带
   （coneHits=0、floaty=0）；玩家恒 v2 读数 25°/375su/3125 逐位吻合。
6. 命中集群（检查点 5）：E2/E3 并入弹道线后 lastConeHits=2、coneDelta=2、floatyDelta=2
   （破片浮字逐目标同帧）；本体豁免契约零破坏（exemptViolations=0）。
7. 敌版三档（检查点 6）：installScaleForTests 逐档——k_s=1：20°/300su/2500；k_s=2：25°/375su/3125；
   k_s=5：40°/600su/5000，读数逐位吻合；破晓档僚舰被锥面波及（coneHits=2），截图可见 5000 浮字。
8. FPS（检查点 7）：破晓档 600su/80° 粗筛采样 fps=164.8，无塌陷。
9. 掉落（检查点 8）：tags 含 no_drop, no_drop_salvage（MOUNT 断言）；日志无 NPE/ASTD 侧异常
   （既有 RAT/UW 数据缺失与 xstream mixin 报错为环境固有，非本组引入）。

**目检**：截图（COMPLETED 敌版舞台连帧 ×3 + 主截图）可见顶点大闪光 + 冷蓝白大光柱沿命中矢量展开、
5000 破晓档浮字、锥面遥测同帧；大光柱存续 0.25s 不遮蔽战场；玩家侧 HUD 充能条/武器组中文渲染正常。

**小修记录**：

1. **烟测舞台外来实体清扫（sweepPlForeignEntities）**：CYCLE「锥内零连带」断言两次实机失败
   （coneHits=9 / 5）。排障日志定位连带实体为第三方 mod 注入的中立/敌方战机与导弹
   （talon/sarissa 战机，owner=0/1/100，生成位置贴各场景舰船锚点——实机判例显示某启用 mod
   会向 mission 战斗的所有舰船派发护航战机），导弹归属不明（owner=1）。锥面命中这些真实战场实体
   属武器正确行为，是舞台「空锥」语义被环境污染。处置：相位机每帧移除三舰体
   （onslaught/champion/enforcer 本场景全部自有舰）以外的舰船（含战机）与全部导弹，
   逐条 INFO 留证（41 条）；清扫后一轮全绿。排障用临时日志已删除，未残留在结算层。
