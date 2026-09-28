package cn.kasuminova.astd.combat.automation.api

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI

/**
 * 坠星残响取景舞台的访问接口（xc_001 默认场景与 trail_pause_probe 探针共用同一舞台）。
 *
 * 动机：xc_001 场景是全部场景的默认分支——未命中任何具体场景时主干按 xc_001 范式运转；
 * 诊断 JSON 尾部对所有场景都读取兜底弹体状态（fallbackInPlay/Expired/Fading），
 * 故舞台状态不能私有于 xc_001 处理器，经本接口向主干与探针开放。
 *
 * 实现位于内部包（战斗级生命周期，随一次战斗的插件实例创建）。
 */
interface StarfallEchoStageAccess {

    /** 取景相机锁定（captureCenter 600su 视高）。 */
    fun lockCamera(engine: CombatEngineAPI)

    /** 按 xc_001 范式查找舞台舰（hull/variant 双口径）。 */
    fun findStageShip(engine: CombatEngineAPI): ShipAPI?

    /** 站位钉死：玩家舰锚点可开火，其余舰钉到敌方锚点禁火。 */
    fun arrangeShips(engine: CombatEngineAPI, playerShip: ShipAPI?)

    /** 逐帧把坠星残响弹体对齐到取景曲线（兜底弹体走完整曲线驱动）。 */
    fun alignProjectilesForEvidence(ctx: AutomationCombatContext, engine: CombatEngineAPI)

    /** 武器拒射时的兜底弹体 spawn（spawnProjectile 直出 + 曲线取景 + onFire 特效分发）。 */
    fun spawnFallbackProjectile(ctx: AutomationCombatContext, engine: CombatEngineAPI, ship: ShipAPI, weapon: WeaponAPI)

    /** 兜底弹体是否已 spawn（每战斗仅一次）。 */
    var fallbackSpawned: Boolean

    /** 兜底弹体引用（诊断 JSON 尾部三字段的数据源；未 spawn 为 null）。 */
    val fallbackProjectile: DamagingProjectileAPI?

    /** trail_pause_probe 探针专用全速 spawn（不做取景曲线对齐，正对 +x 按 spec 弹速自由飞行）。 */
    fun spawnFullSpeedProbeProjectile(ctx: AutomationCombatContext, engine: CombatEngineAPI, ship: ShipAPI, weapon: WeaponAPI)

    /** 弹体观测：VFX 驱动遥测或引擎在飞弹体命中坠星残响 spec。 */
    fun projectileObserved(engine: CombatEngineAPI): Boolean

    /** xc_001 默认场景的相位态（CombatReady/FireObserved/Completed/Failed），失败时写 [AutomationCombatContext.failureReason]。 */
    fun currentState(ctx: AutomationCombatContext, engine: CombatEngineAPI, ship: ShipAPI?, weapon: WeaponAPI?): String

    /**
     * 默认完成帧捕获（xc_001 范式）：完成后以 0.18s 间隔抓 3 帧，
     * 抓帧前重钉站位并对齐弹体取景。对没有专属捕获分支的场景同样生效（历史行为）。
     */
    fun renderDefaultCapture(ctx: AutomationCombatContext)
}
