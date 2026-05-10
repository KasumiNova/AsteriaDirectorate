# 蓝线 Omega 音效设计案（ARC-Ω / SLT-Ω / TSM-Ω）

## 1. 分析范围与事实来源

本设计案以以下文件为事实来源，优先以代码行为为准：

- `docs/design/weapons/blue/40-omega.md`
- `docs/design/tech/ARC-OMEGA_Implementation.md`
- `contents/data/weapons/astd_drv_omega.wpn`
- `contents/data/weapons/astd_slt_omega.wpn`
- `contents/data/weapons/astd_tsm_omega.wpn`
- `contents/data/weapons/proj/astd_drv_omega_slug.proj`
- `contents/data/weapons/proj/astd_slt_omega_stream.proj`
- `contents/data/weapons/proj/astd_tsm_omega_missile.proj`
- `src/main/kotlin/cn/kasuminova/asteriadirectorate/weapons/arc/omega/DrvOmegaEveryFrameEffect.kt`
- `src/main/kotlin/cn/kasuminova/asteriadirectorate/weapons/arc/omega/DrvOmegaSlugInstantOnSpawn.kt`
- `src/main/kotlin/cn/kasuminova/asteriadirectorate/weapons/arc/omega/DrvOmegaOnHitEffect.kt`
- `src/main/kotlin/cn/kasuminova/asteriadirectorate/weapons/arc/signature/tsm/Tsm2TerminalSprintAI.kt`
- `src/main/kotlin/cn/kasuminova/asteriadirectorate/weapons/arc/signature/tsm/TsmOmegaTerminalVerdictOnHitEffect.kt`

## 2. 代码侧结论（和文案差异）

- `DRV-Ω` 当前是 `BALLISTIC` 弹体武器，不是 beam。
- `DRV-Ω` 终结弹由 `DrvOmegaSlugInstantOnSpawn` 判定（开火后余弹为 0），并把该发伤害放大到 `300%`。
- `DRV-Ω` 终结弹慢充能通过 `ballisticRoFMult=0.33333334` 达成。结合 `chargeup=0.5`，实际听感窗口更接近 `1.5s`，而不是文档里常写的 `2s`。
- `DRV-Ω` 存在目标门限：前方无有效目标会 `forceNoFire + stopFiring`，因此需要“锁定失败/无效充能”音频反馈。
- `DRV-Ω` 终结命中额外爆发 `3~5` 道 EMP 电弧，且护盾命中时存在穿盾概率（代码 35%）。
- `TSM-Ω` 导弹使用两段 AI：巡航段 -> 终端冲刺段，冲刺触发距离 `900`，冲刺速度约 `1800`，并有独立“二段裁决”命中逻辑。
- `TSM-Ω` 护盾过载后 `0.12s` 会触发第二击（船体直击），并在命中点释放范围 EMP。
- `SLT-Ω` 目前没有额外命中机制脚本，核心表现来自射流 VFX 与命中体感，需要靠音效强化“绝对压制线”的身份。
- 三把 ARC Omega 武器 `.wpn` 目前 `fireSoundTwo` 均为 `autocannon_fire`，属于占位级别。

## 3. 音效目标

- 明确区分 ARC Omega 三把武器的听觉角色：
  - `DRV-Ω`: 三发弹匣 + 红移终结。
  - `SLT-Ω`: 连续压制流与推离感。
  - `TSM-Ω`: 巡航导弹 -> 终端裁决 -> 破盾追击。
- 让玩家只靠声音就能判断：
  - 是否进入 DRV 终结弹充能。
  - TSM 是否进入终端冲刺。
  - TSM 是否触发了“二段裁决”。
- 保持硬科幻、军事工业、非旋律化（避免“音乐化音效”）。

## 4. 事件到音效映射

| 武器 | 事件 | 代码触发点 | 音效 ID 建议 | 建议时长 |
|---|---|---|---|---|
| DRV-Ω | 常规充能 | `DrvOmegaEveryFrameEffect` 充能窗口 | `astd_drv_omega_charge_normal_loop` | 0.4-0.6s loop |
| DRV-Ω | 终结充能 | `ammo==1` 且充能中（慢充） | `astd_drv_omega_charge_finisher_loop` | 1.3-1.6s loop |
| DRV-Ω | 常规开火 | `astd_drv_omega.wpn` 开火 | `astd_drv_omega_fire_normal` | 0.15-0.25s |
| DRV-Ω | 终结开火 | `DrvOmegaSlugInstantOnSpawn` 判定终结弹 | `astd_drv_omega_fire_finisher` | 0.22-0.35s |
| DRV-Ω | 统一命中 | `DrvOmegaOnHitEffect` 命中 | `astd_drv_omega_impact` | 0.25-0.45s |
| SLT-Ω | 持续压制开火 | `astd_slt_omega` 发射流 | `astd_slt_omega_fire_loop` | 0.35-0.60s loop |
| SLT-Ω | 命中 tick | 命中节奏反馈 | `astd_slt_omega_hit_tick` | 0.05-0.12s |
| SLT-Ω | 推离脉冲 | 压制命中峰值反馈 | `astd_slt_omega_push_pulse` | 0.12-0.25s |
| TSM-Ω | 导弹发射 | `astd_tsm_omega.wpn` | `astd_tsm_omega_launch` | 0.20-0.35s |
| TSM-Ω | 巡航段循环 | `Tsm2TerminalSprintAI` CRUISE | `astd_tsm_omega_cruise_loop` | 0.6-1.0s loop |
| TSM-Ω | 进入冲刺 | `enterSprintPhase()` | `astd_tsm_omega_sprint_enter` | 0.12-0.25s |
| TSM-Ω | 冲刺段循环 | `Tsm2TerminalSprintAI` SPRINT | `astd_tsm_omega_sprint_loop` | 0.25-0.45s loop |
| TSM-Ω | 第一击裁决 | `onHit` 主命中 | `astd_tsm_omega_impact_primary` | 0.30-0.55s |
| TSM-Ω | 盾破提示 | `spawnShieldBreachFx` | `astd_tsm_omega_shield_breach` | 0.12-0.25s |
| TSM-Ω | 第二击裁决 | `scheduleSecondStrike` 延时 0.12s | `astd_tsm_omega_impact_verdict` | 0.35-0.60s |
| TSM-Ω | 范围 EMP 爆发 | `spawnAoeEmpBurst` | `astd_tsm_omega_aoe_emp_burst` | 0.25-0.50s |

## 5. 声学方向（按武器）

### 5.1 DRV-Ω（相对论聚能炮）

- 关键词：高能、洁白、硬边、短促、判决感。
- 常规两发：青蓝系，频段偏中高，瞬态锐。
- 终结一发：红移色听感，加入下潜低频与离散电弧噪声，形成与前两发强反差。
- 核心听感锚点：
  - 终结充能的“时间拉长”。
  - 命中瞬间的“空间碎裂”。
  - EMP 子弧的“多段碎裂电噪”。

### 5.2 SLT-Ω（零散布压制阵列）

- 关键词：连续、压迫、收束、推进。
- 主体是稳定的高密度发射循环，避免“机枪点射感”。
- 命中反馈应更像“压力墙推开目标”，而不是爆炸。

### 5.3 TSM-Ω（终端裁决）

- 关键词：狩猎、突进、决断、二段判罚。
- 巡航段偏冷静与计算感。
- 冲刺段必须有可识别的“升档”音色变化（比巡航更尖、更亮、更近）。
- 若触发破盾二段，必须有第二次独立冲击峰值，且与第一击间隔约 0.12s。

## 6. 混音和技术约束

- 建议输出：`48kHz / 24-bit / mono one-shot`，loop 可立体声但尽量中置。
- 峰值建议：`-3 dBFS` 以内，留给游戏总线余量。
- 避免长尾混响，太空战斗里应优先“直接、清晰、可读”。
- 所有音效禁用旋律性音高走向，避免和 BGM 抢主题。
- ARC 线建议统一保留少量电弧高频纹理，作为家族识别。

## 7. 代码接入建议（后续实现）

- 当前仓库未见 `contents/data/config/sounds.json` 与 `contents/sounds/`。
- 建议新增：
  - `contents/data/config/sounds.json`
  - `contents/sounds/weapons/arc/omega/*.ogg`
- 建议接入点：
  - `DrvOmegaEveryFrameEffect`: 常规/终结充能 loop 的起停与切换。
  - `DrvOmegaSlugInstantOnSpawn`: 常规开火与终结开火分离触发。
  - `DrvOmegaOnHitEffect`: 统一命中音效。
  - `Tsm2TerminalSprintAI.enterSprintPhase`: 冲刺进入提示。
  - `TsmOmegaTerminalVerdictOnHitEffect`: 第一击、盾破、第二击、AOE EMP。

## 8. 交付清单（音效资产最小集）

- DRV-Ω: 5 个
- SLT-Ω: 3 个
- TSM-Ω: 7 个
- 合计：15 个基础资产（不含随机变体）

建议每个关键 one-shot 再出 2 个轻微变体（`_v1/_v2`），避免听感疲劳。