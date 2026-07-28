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
