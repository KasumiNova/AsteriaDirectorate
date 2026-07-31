package cn.kasuminova.astd.combat.effect.arc

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnFireEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.util.vector.Vector2f

/**
 * “七星”折跃发射器 `.proj` 侧发射回调（规格 07 §2.1）：
 * 发射瞬间——弹体速度清零、快照难度取值、执行首发折跃 + 闪光爆炸，
 * 随后弹体即移除，连跳锚点移交纯位置（[SevenStarsChainScript] 锚点设计，
 * 规格 §1 flightTime 6.0 意图的等效落地；首发无 PD 目标时以 TERMINAL_PENDING 注册，
 * 首帧进终结判定）。
 *
 * 动机：难度取值调用点唯一（[SevenStarsDifficulty.snapshot] 发射时刻一次）；
 * FIRST_STRIKE 在 onFire 内同步完成，其余状态机推进全部交给 [SevenStarsChainScript]
 * （规格 §2.2 状态机）。
 *
 * 0 值防线（规格 §2.4）：面板伤害 <= 0 时记 WARN，跳过全部结算直接消散
 * （摧毁判定/段数表全部以 0 伤害走通但不产生任何 applyDamage 调用）。
 */
class SevenStarsOnFireEffect : OnFireEffectPlugin {

    override fun onFire(projectile: DamagingProjectileAPI, weapon: WeaponAPI, engine: CombatEngineAPI) {
        if (engine.isPaused) return
        val source = weapon.ship
        val owner = source?.owner ?: projectile.owner

        val panelDamage = projectile.damageAmount
        if (panelDamage.isNaN() || panelDamage <= 0f) {
            log.warn("“七星”弹体面板伤害非法（$panelDamage），跳过全部结算直接消散（不产生任何 applyDamage 调用）")
            SevenStarsVfx.dissipate(engine, projectile.location)
            engine.removeEntity(projectile)
            SevenStarsChainScript.bumpTelemetry(engine, SevenStarsChainScript.TELEMETRY_DISSIPATE_NO_KILL)
            return
        }

        val tuning = SevenStarsDifficulty.snapshot(source)
        val jumpRange = SevenStarsChainMath.jumpRange(weapon.range)
        // 弹体由脚本瞬移接管：发射即停速（规格 §2.1），collisionClass=NONE 无触碰路径。
        projectile.velocity.set(0f, 0f)

        SevenStarsChainScript.bumpTelemetry(engine, SevenStarsChainScript.TELEMETRY_ONFIRE)

        val firstMult = SevenStarsChainMath.flashMult(tuning, 1)
        val target = SevenStarsTargetSelector.select(
            engine = engine,
            from = projectile.location,
            jumpRange = jumpRange,
            owner = owner,
            aoeDamage = panelDamage * firstMult,
        )

        if (target == null) {
            // 首发无 PD 候选：注册脚本首帧进对舰终结判定（规格 §2.2 FIRST_STRIKE --无PD目标--> TERMINAL）。
            engine.addPlugin(
                SevenStarsChainScript(
                    anchor = Vector2f(projectile.location),
                    source = source,
                    tuning = tuning,
                    panelDamage = panelDamage,
                    jumpRange = jumpRange,
                    owner = owner,
                    initialJumps = 0,
                    initialState = SevenStarsChainScript.State.TERMINAL_PENDING,
                ),
            )
            engine.removeEntity(projectile)
            return
        }

        // 首发折跃 + 闪光爆炸（同步完成）。
        SevenStarsVfx.teleport(engine, projectile.location, target.location, source, target as? ShipAPI)
        SevenStarsChainScript.bumpTelemetry(engine, SevenStarsChainScript.TELEMETRY_TELEPORT_ARC)
        projectile.location.set(target.location)
        projectile.velocity.set(0f, 0f)
        val kills = SevenStarsDamageHandler.flashExplosion(
            engine = engine,
            at = projectile.location,
            direct = target,
            source = source,
            owner = owner,
            panelDamage = panelDamage,
            mult = firstMult,
            aoeRadius = SevenStarsDifficulty.AOE_RADIUS,
        )
        SevenStarsVfx.crossFlash(engine, projectile.location, scale = 1f)
        SevenStarsChainScript.bumpTelemetry(engine, SevenStarsChainScript.TELEMETRY_FLASH)
        SevenStarsChainScript.bumpTelemetry(engine, SevenStarsChainScript.TELEMETRY_CROSS_FLASH)
        SevenStarsChainScript.addTelemetry(engine, SevenStarsChainScript.TELEMETRY_KILLS, kills)
        SevenStarsChainScript.trackChainJumps(engine, 1)

        when (SevenStarsChainMath.decideAfterFlash(kills, jumps = 1, hasPdCandidates = true)) {
            SevenStarsChainMath.ChainDecision.CONTINUE -> {
                engine.addPlugin(
                    SevenStarsChainScript(
                        anchor = Vector2f(projectile.location),
                        source = source,
                        tuning = tuning,
                        panelDamage = panelDamage,
                        jumpRange = jumpRange,
                        owner = owner,
                        initialJumps = 1,
                        initialState = SevenStarsChainScript.State.CHAIN_COOLDOWN,
                    ),
                )
                engine.removeEntity(projectile)
            }
            SevenStarsChainMath.ChainDecision.TERMINAL -> {
                engine.addPlugin(
                    SevenStarsChainScript(
                        anchor = Vector2f(projectile.location),
                        source = source,
                        tuning = tuning,
                        panelDamage = panelDamage,
                        jumpRange = jumpRange,
                        owner = owner,
                        initialJumps = 1,
                        initialState = SevenStarsChainScript.State.TERMINAL_PENDING,
                    ),
                )
                engine.removeEntity(projectile)
            }
            SevenStarsChainMath.ChainDecision.DISSIPATE -> {
                // 首发空爆（未击杀断链安全闸）：直接消散，不触发终结，不注册脚本。
                SevenStarsVfx.dissipate(engine, projectile.location)
                engine.removeEntity(projectile)
                SevenStarsChainScript.bumpTelemetry(engine, SevenStarsChainScript.TELEMETRY_DISSIPATE_NO_KILL)
            }
        }
    }

    private companion object {
        private val log = Global.getLogger(SevenStarsOnFireEffect::class.java)
    }
}
