# ARC-Ω 技术实现文档

## 核心机制
- **武器类型**: `ENERGY` (使用隐形投射物规范，即 Projectile with Invisible Spec)。
- **弹匣**: 3 发。
- **装填**: 标准弹药恢复机制。
- **充能时间**: 1.0秒（基础）。
- **可变充能**: 最后一发（当前弹药量=1）需要 2.0秒 进行充能。
- **终结技**: 最后一发造成 3倍伤害 + 额外 EMP 电弧 + 红色视觉特效。

## 1. 武器 CSV 与 Spec 配置
- **ID**: `astd_arc_omega`
- **Spec**:
  ```json
  {
    "id": "astd_arc_omega",
    "specClass": "projectile",
    "type": "ENERGY",
    "size": "LARGE",
    "turretSprite": "graphics/weapons/astd_arc_omega_turret.png",
    "hardpointSprite": "graphics/weapons/astd_arc_omega_hardpoint.png",
    "projectileSpecId": "astd_invisible_proj",
    "everyFrameEffect": "cn.kasuminova.asteriadirectorate.weapons.ArcOmegaEffect",
    "ammo": 3,
    "ammoRegen": 0.2,
    "chargeup": 1.0, 
    "chargedown": 0.5
  }
  ```

## 2. 脚本逻辑: `ArcOmegaEffect`

实现 `EveryFrameWeaponEffectPlugin` 和 `OnFireEffectPlugin` 接口。

### A. 可变充能时间（"慢速重击"）
在 `advance(amount, engine, weapon)` 中：
1.  **检查弹药**: `int ammo = weapon.getAmmo();`
2.  **应用修正**:
    -   如果 `ammo == 1` 且 `weapon.isFiring()`（正在尝试开火/充能）：
        -   我们需要将充能时间翻倍（1s -> 2s）。
        -   实际上是将充能速度降低 50%。
        -   `weapon.getShip().getMutableStats().getEnergyWeaponChargeupMult().modifyMult(id, 0.5f);`
    -   否则：
        -   `weapon.getShip().getMutableStats().getEnergyWeaponChargeupMult().unmodify(id);`
    -   *注意*: 这会通过 `getEnergyWeaponChargeupMult` 影响船上所有的能量武器。
    -   **权衡**: 我们无法轻易地通过 `MutableShipStats` 只改变 *单个* 武器的充能时间。
    -   **替代方案**: 虽然存在手动操纵 `weapon.getChargeLevel()` 的方案，但那是和引擎作对。
    -   **决定方案 (全局统计修改)**:
        -   我们坚持使用 **全局状态修正方法**，但要知道这可能会在那一瞬间影响其他能量武器。
        -   考虑到这是 Omega 武器（英雄舰船，通常以此为主武器），这是可接受的。
        -   *优化*: 仅在 `weapon.getChargeLevel() > 0 && weapon.getChargeLevel() < 1` 时应用修正，将影响窗口降到最低。

### B. 终结技逻辑 (OnFire)
在 `onFire(projectile, weapon, engine)` 中：
1.  **移除投射物**: `engine.removeEntity(projectile)`
2.  **判断状态**:
    -   我们需要知道这是否是“最后一发”。
    -   因为弹药通常在开火瞬间（或前后微小时间差）扣除，需要检查 `weapon.getAmmo()`。
    -   如果 `weapon.getAmmo() == 0`（假设简单的扣除逻辑）：**这就是终结一击**。
    -   (需在开发中反复验证实际的弹药扣除时机)。
3.  **视觉与伤害**:
    -   **通用**:
        -   起点: `weapon.getFirePoint(0)`
        -   终点: `target.getLocation()` 或 射线检测 (Raycast)。
    -   **普通射击** (Ammo > 0):
        -   颜色: `青/蓝 (Cyan/Blue)`
        -   宽度: `标准`
        -   伤害: `100%`
        -   调用 `CurveUtil.spawnCurveBeam(..., bluePhase)`
    -   **终结射击** (Ammo == 0):
        -   颜色: `深红 (Deep Red)` (+ 扭曲/抖动)
        -   宽度: `200%`
        -   伤害: `300%`
        -   调用 `CurveUtil.spawnCurveBeam(..., redPhase)`
        -   **额外 EMP**:
            ```java
            // 生成 3 到 5 个 EMP 电弧
            for (int i=0; i<3+(int)(Math.random()*3); i++) {
                engine.spawnEmpArc(..., damage, DamageType.ENERGY, ...);
            }
            ```

## 3. 视觉参考 (BoxUtil)

需要在 `AsteriaArcRenderer` 中定义专门的 `ArcPhase.OMEGA_BLUE` 和 `ArcPhase.OMEGA_RED`。

```java
public enum ArcPhase {
    OMEGA_BLUE(new Color(0, 200, 255, 255), 30f),
    OMEGA_RED(new Color(255, 50, 50, 255), 60f); // 更宽，红色
}
```

## 4. 实现细节 (代码骨架)

```java
public class ArcOmegaEffect implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {
    
    private static final String CHARGE_MOD_ID = "astd_omega_charge_slower";

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine.isPaused() || weapon.getShip() == null) return;
        
        // 动态充能时间逻辑
        MutableShipStatsAPI stats = weapon.getShip().getMutableStats();
        // 如果当前余弹 1 发（下一发是终结技）且正在开火/充能
        if (weapon.getAmmo() == 1 && weapon.isFiring()) {
            // 应用 0.5x 倍率使充能时间变为 2s (1s / 0.5 = 2s)
            stats.getEnergyWeaponChargeupMult().modifyMult(CHARGE_MOD_ID, 0.5f);
        } else {
            // 不在开火或弹药充足时立刻清除修正
            stats.getEnergyWeaponChargeupMult().unmodify(CHARGE_MOD_ID);
        }
    }

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        engine.removeEntity(projectile);
        
        int ammoRemaining = weapon.getAmmo(); // 理论上如果刚打完 3 发中的最后一发，这里应该是 0
        // 逻辑核对: 开始 3 -> 开火 -> 剩 2. 开始 2 -> 开火 -> 剩 1. 开始 1 -> 开火 -> 剩 0.
        // 所以如果 ammoRemaining == 0, 说明刚才那发是终结技。
        
        boolean isFinisher = (ammoRemaining == 0);
        
        AsteriaArcRenderer.spawnOmegaArc(engine, weapon, isFinisher);
        
        // 为保险起见，开火后再次清除修正
        weapon.getShip().getMutableStats().getEnergyWeaponChargeupMult().unmodify(CHARGE_MOD_ID);
    }
}
```

## 注意事项

- **效果冲突**: 修改全局舰船属性 (`EnergyWeaponChargeupMult`) 会影响 *所有* 能量武器。由于这是 Omega 武器，玩家大概率会理解这种“全力输出”的状态，但如同前文所述，需注意副作用。
- **弹药逻辑**: 确保 `ammo` 的检查逻辑在不同帧率下是稳健的。
