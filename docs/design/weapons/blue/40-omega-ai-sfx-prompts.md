# 蓝线 Omega AI 音效生成提示词（ARC-Ω / SLT-Ω / TSM-Ω）

## 1. 使用说明

- 目标：生成可直接用于游戏实现的战斗 SFX（非 BGM）。
- 语言：提示词以英文为主，便于主流音效模型稳定理解。
- 输出建议：`48kHz, 24-bit, mono, no clipping, no long reverb tail`。
- 统一负面要求（可附加到每条提示词后）：
  - `no melody, no vocals, no choir, no tonal pad, no cinematic trailer boom, no UI click, no music rhythm, no noise floor hiss`

## 2. DRV-Ω 提示词

### 2.1 `astd_drv_omega_charge_normal_loop`

- Type: loop
- Length: 0.5s
- Prompt:
`clean high-tech energy weapon charge loop, magnetic whine, sci-fi plasma buildup`

### 2.2 `astd_drv_omega_charge_finisher_loop`

- Type: loop
- Length: 1.5s
- Prompt:
`heavy sci-fi weapon overcharge loop, deep low-frequency hum, dangerous unstable energy buildup`

### 2.3 `astd_drv_omega_fire_normal`

- Type: one-shot
- Length: 0.22s
- Prompt:
`fast hard plasma cannon fire, sharp kinetic punch, sci-fi weapon shot`

### 2.4 `astd_drv_omega_fire_finisher`

- Type: one-shot
- Length: 0.32s
- Prompt:
`devastating heavy sci-fi cannon blast, deep low-end slam, violent energy discharge`

### 2.5 `astd_drv_omega_impact`

- Type: one-shot
- Length: 0.40s
- Prompt:
`heavy sci-fi kinetic impact, metallic crunch with energy flare, hard armor hit`

## 3. SLT-Ω 提示词

### 3.1 `astd_slt_omega_fire_loop`

- Type: loop
- Length: 0.45s
- Prompt:
`continuous sci-fi energy beam loop, heavy plasma stream, steady suppression fire`

### 3.2 `astd_slt_omega_hit_tick`

- Type: one-shot
- Length: 0.08s
- Prompt:
`short crisp energy hit tick, light sci-fi impact`

### 3.3 `astd_slt_omega_push_pulse`

- Type: one-shot
- Length: 0.20s
- Prompt:
`heavy kinetic push pulse, sci-fi force field burst, low-mid impact`

## 4. TSM-Ω 提示词

### 4.1 `astd_tsm_omega_launch`

- Type: one-shot
- Length: 0.30s
- Prompt:
`heavy sci-fi missile launch, deep tube kick, ion thruster ignition`

### 4.2 `astd_tsm_omega_cruise_loop`

- Type: loop
- Length: 0.80s
- Prompt:
`sci-fi missile flight loop, steady ion thruster hum, clean propulsion noise`

### 4.3 `astd_tsm_omega_sprint_enter`

- Type: one-shot
- Length: 0.20s
- Prompt:
`sudden sci-fi thruster acceleration, sharp energy boost, missile sprint engage`

### 4.4 `astd_tsm_omega_sprint_loop`

- Type: loop
- Length: 0.35s
- Prompt:
`high-speed sci-fi missile sprint loop, intense plasma engine scream, aggressive flight`

### 4.5 `astd_tsm_omega_impact_primary`

- Type: one-shot
- Length: 0.45s
- Prompt:
`violent sci-fi missile impact, heavy energy explosion, sharp kinetic strike`

### 4.6 `astd_tsm_omega_shield_breach`

- Type: one-shot
- Length: 0.18s
- Prompt:
`sci-fi energy shield shatter, sharp glass-like crack, electric burst`

### 4.7 `astd_tsm_omega_impact_verdict`

- Type: one-shot
- Length: 0.55s
- Prompt:
`massive sci-fi energy puncture, deep heavy explosion, devastating secondary strike`

### 4.8 `astd_tsm_omega_aoe_emp_burst`

- Type: one-shot
- Length: 0.42s
- Prompt:
`heavy EMP shockwave burst, loud electric crackle, expanding sci-fi energy blast`

## 5. 批量生成建议

- 每条 one-shot 生成 3 个变体：`_v1`, `_v2`, `_v3`。
- 每条 loop 生成 2 个相位变体：`_a`, `_b`，用于随机切换防疲劳。
- 对 `DRV-Ω` 和 `TSM-Ω` 的关键节点（finisher/second strike）额外做一组 `heavy` 版本，用于 Boss 或高威胁词缀场景。

## 6. 快速复制模板

可将下列模板复制到任意模型后，替换占位符批量生成：

`Generate one game-ready sci-fi weapon sound effect. Asset ID: <ASSET_ID>. Type: <one-shot or seamless loop>. Target length: <LENGTH>. Style: hard military sci-fi, high clarity, no music, no vocals. Creative brief: <PROMPT_BODY>. Technical target: 48kHz 24-bit, clean transients, controlled tail, no clipping. Negative constraints: no melody, no choir, no cinematic trailer boom, no UI click, no broadband hiss.`