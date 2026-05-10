# ARC-13 "Trinity" Technical Implementation

## 概述
ARC-13 (Trinity Spectrum Projector) 是一种三相循环打击武器，使用 "Invisible Projectile" + "OnFireEffect" 的组合来实现“伪光束”瞬时打击。

## 核心机制
- **Weapon Type**: `ENERGY` (Beam) — *Changed from Projectile to support Beam Hullmods/UI*
- **Burst**: 3 shots (Simulated via script or actual burst beam with 3 ticks? No, script-managed cycle).
- **Burst Delay**: 0.33s (Managed by script or multiple barrels? Single barrel cycling is better).
- **Cycle**: Kinetic (Blue) -> High Explosive (Red) -> Fragmentation (White)
- **Visuals**: A curved beam (Bezier) rendered for a brief duration (0.1s - 0.2s) fading out.

## 文件结构

### 1. Weapon CSV & Spec
- **ID**: `astd_arc13`
- **Spec**:
  ```json
  {
    "id": "astd_arc13",
    "specClass": "beam",
    "type": "ENERGY",
    "size": "LARGE",
    "turretSprite": "graphics/weapons/astd_arc13_turret_base.png",
    "turretGlowSprite": "graphics/weapons/astd_arc13_turret_glow.png",
    "hardpointSprite": "graphics/weapons/astd_arc13_hardpoint_base.png",
    "hardpointGlowSprite": "graphics/weapons/astd_arc13_hardpoint_glow.png",
    "turretOffsets": [10, 0],
    "turretAngleOffsets": [0],
    "hardpointOffsets": [15, 0],
    "hardpointAngleOffsets": [0],
    "barrelMode": "LINKED",
    "animationType": "GLOW_AND_FLASH",
    "glowColor": [100, 150, 255, 255],
    "fireSoundTwo": "astd_arc13_fire",
    "textureType": ["graphics/fx/beam_fringe.png", "graphics/fx/beam_core.png"],
    "textureScrollSpeed": 64.0,
    "pixelsPerTexel": 5.0,
    "beamEffect": "cn.kasuminova.asteriadirectorate.weapons.ArcTrinityBeamEffect",
    "everyFrameEffect": "cn.kasuminova.asteriadirectorate.weapons.ArcTrinityBeamEffect"
  }
  ```
- **Stats Configuration (Hack for "Instant Beam")**:
  - **Damage/Second**: 0.
  - **Flux/Second**: 0 (Handle flux in script? Or set flux per shot in CSV and let beam duration handle it?)
  - *Better Way*: 设置为 **Burst Beam**。
    - `burst sizes`: 1
    - `chargedown`: 2.5
    - `derived stats`: set strictly for UI display if possible, or accept that "Damage/sec" will be 0 and rely on custom tooltip to explain "Instant Damage".
  - *Alternative*: Use high DPS and very short duration (e.g. 0.1s).
    - If UI priority is high, we want the tooltip to show "300 damage".
    - Standard Beam tooltip shows "Damage/sec".
    - Users prefer seeing "Damage per shot" for burst weapons.
    - **Decision**: Keep `specClass: beam` but set texture to **transparent**. The script handles the ACTUAL damage and visual. The CSV stats are dummy or set to 1 DPS to validitate the file. We override the tooltip Stats (using `TooltipMakerAPI`) to show the real "Tri-phase Burst" stats.

### 2. The Script (`ArcTrinityBeamEffect`)

Implements `BeamEffectPlugin` AND `EveryFrameWeaponEffectPlugin`.

#### 逻辑流程

1.  **State Management**:
    -   Need to detect the "Fire" event.
    -   Standard beams fire continuously.
    -   We use `beam.getBrightness()` or `weapon.getChargeLevel()` to detect the *start* of a firing cycle.
    -   Since we want a 3-shot pattern (Kinetic -> HE -> Frag) over time (0.33s intervals), the script must manage this "Mini-burst" internally if the weapon itself fires once every 2.5s.
    -   *Logic*:
        -   User clicks fire.
        -   Weapon charges up -> Fires (2.5s cooldown).
        -   Script detects Fire.
        -   Script triggers a **Coroutine / State Machine** that executes 3 times with 0.33s delays.
        -   **Shot 1 (Blue)**: t=0.0s
        -   **Shot 2 (Red)**: t=0.33s
        -   **Shot 3 (White)**: t=0.66s

2.  **Targeting (Beam Style)**:
    -   Use `beam.getDamageTarget()`?
    -   No, because we might be firing when the beam isn't actually touching anything (if we use invisible beam logic/short duration).
    -   Better: Use `weapon.getShip().getMouseTarget()` (for player) or `weapon.getShip().getShipTarget()` (AI).
    -   **Curvature Logic**:
        -   The beam should curve *towards* the target.
        -   If no target, curve towards the cursor/aim point at max range.

3.  **Visuals (BoxUtil Helper)**:
    -   Call `AsteriaArcRenderer.spawnArc(...)`.

### 3. Encapsulation: `AsteriaArcRenderer` (New BoxUtil Wrapper)

专门对接 BoxUtil 的特效工具类，封装本项目特定的美术风格。

```java
public class AsteriaArcRenderer {
    
    public enum ArcPhase {
        KINETIC(Color.BLUE, ...),
        HE(Color.RED, ...),
        FRAG(Color.WHITE, ...);
        // defined colors, widths, fringe textures
    }

    public static void spawnTrinityArc(CombatEngineAPI engine, WeaponAPI weapon, CombatEntityAPI target, ArcPhase phase) {
        // 1. Calculate Start/End Nodes with Tangents
        NodeData start = new NodeData();
        start.setLocation(weapon.getFirePoint(0));
        // Randomize tangent for "wild arc" look
        
        // 2. Resolve DealtController (Damage + FX)
        SimpleDealtController controller = new SimpleDealtController(
             engine,
             phase.damage,
             phase.type,
             ...
        );
        
        // 3. Call CurveUtil
        CurveUtil.spawnCurveBeam(..., controller, ...);
        
        // 4. Add extra flair (Lens flare at muzzle, Ring shock at impact)
    }
}
```

### 4. UI/Tooltip Override
Since the CSV stats will be misleading (0 DPS), use `UpdateOpCost` or `addPostDescriptionSection` in the specific `ModPlugin` or `description.csv` listener to overwrite the tooltip stats block with a custom "Tri-phase Projector" breakdown.


## 注意事项

1.  **Flux/Heat**: Weapon Spec 中设置 flux/shot，引擎会自动处理。
2.  **Cooldown**: Weapon Spec 设置 burst delay 和 chargedown。
3.  **Consistency**: 确保 `INDEX_KEY` 在战斗结束后清理，或者依靠 Weapon 实例销毁自动丢失（Weapon custom data is transient usually）。
4.  **Range**: `findTarget` 必须限制在 `weapon.getRange()` 内。
