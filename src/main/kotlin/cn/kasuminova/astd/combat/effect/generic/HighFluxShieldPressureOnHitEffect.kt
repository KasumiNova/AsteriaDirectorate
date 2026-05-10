package cn.kasuminova.astd.combat.effect.generic

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileVfxUtil
import cn.kasuminova.astd.renderer.effect.system.ArcFlareOverdriveVisualState
import cn.kasuminova.astd.internal.debug.CombatCaps
import cn.kasuminova.astd.combat.effect.arc.signature.tsm.TsmTerminalStrikeFx
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.max
import kotlin.math.min

/**
 * 目标幅能越高，命中护盾时额外施加少量硬幅（并做“每秒封顶”）。
 *
 * 用法：在对应的 `.proj` 里写：
 * "onHitEffect": "cn.kasuminova.astd.combat.effect.generic.HighFluxShieldPressureOnHitEffect"
 */
class HighFluxShieldPressureOnHitEffect : OnHitEffectPlugin {

    private data class Config(
        val threshold: Float,
        val capPerSecond: Float,
        /** 当目标幅能=100% 时，额外硬幅 = 伤害 * multAtMax */
        val multAtMax: Float,
        val fxColor: Color,
    )

    private fun configFor(weaponId: String?, projectileSpecId: String?): Config? {
        val wid = weaponId?.trim().orEmpty()
        if (wid.isNotEmpty()) {
            return when (wid) {
                // DRV-11：阈值更低、封顶更高，强调“护盾压制”
                "astd_drv11" -> Config(
                    threshold = 0.70f,
                    capPerSecond = 320f,
                    multAtMax = 0.35f,
                    fxColor = Color(120, 200, 255, 210),
                )

                // SLT-3：阈值更高、封顶略低，强调“连射窗口的压制墙”
                "astd_slt3" -> Config(
                    threshold = 0.80f,
                    capPerSecond = 240f,
                    multAtMax = 0.25f,
                    fxColor = Color(160, 220, 255, 210),
                )

                // AOD-7：用于“顶盾位制造窗口”的稳定压制；阈值略低，让它更常在高幅阶段发挥。
                "astd_aod7" -> Config(
                    threshold = 0.62f,
                    capPerSecond = 280f,
                    multAtMax = 0.30f,
                    fxColor = Color(255, 220, 140, 195),
                )

                else -> null
            }
        }

        // 兜底：某些场景 projectile.weapon 可能为空（例如只在 .proj 配置了脚本）。
        // 按 projectileSpecId 回退，保证机制仍能生效。
        return when (projectileSpecId?.trim().orEmpty()) {
            "astd_aod7_shot" -> Config(
                threshold = 0.62f,
                capPerSecond = 280f,
                multAtMax = 0.30f,
                fxColor = Color(255, 220, 140, 195),
            )

            // 这些弹体目前未在 .proj 上挂该脚本，但保留回退以防未来改动。
            "astd_drv11_slug" -> Config(
                threshold = 0.70f,
                capPerSecond = 320f,
                multAtMax = 0.35f,
                fxColor = Color(120, 200, 255, 210),
            )

            "astd_slt3_pulse" -> Config(
                threshold = 0.80f,
                capPerSecond = 240f,
                multAtMax = 0.25f,
                fxColor = Color(160, 220, 255, 210),
            )

            else -> null
        }
    }

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f?,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        val ship = target as? ShipAPI
        if (ship != null && (ship.isHulk || ship.isPhased)) return

        // 某些实体（例如陨石/小行星）命中回调里 point 可能为 null；回退到弹体当前位置避免崩溃。
        val hitPoint = point ?: try {
            projectile.location
        } catch (_: Throwable) {
            null
        } ?: return

        val weaponId0 = try {
            projectile.weapon?.spec?.weaponId
        } catch (_: Throwable) {
            null
        }
        val projId = try {
            projectile.projectileSpecId
        } catch (_: Throwable) {
            null
        }

        val cfg = configFor(weaponId0, projId) ?: return

        // AOD-7：命中时附加更“TSM 风格”的冲击提示（轻量版，约 25% 粒子量）。
        // 这个效果不依赖 shieldHit；对护盾/装甲/船体都会给一个可读的“冲击闪”。
        if (weaponId0 == "astd_aod7" || projId == "astd_aod7_shot") {
            spawnAod7ImpactFx(engine, ship, projectile, hitPoint, shieldHit)
        }

        // 下面是“高幅压制”机制：仅在命中护盾且目标为 Ship 时生效。
        if (!shieldHit) return

        val s = ship ?: return
        val fluxLevel = s.fluxLevel
        if (fluxLevel <= cfg.threshold) return

        val k = (fluxLevel - cfg.threshold) / max(0.0001f, (1f - cfg.threshold))
        val kk = min(1f, max(0f, k))

        // projectile.damageAmount 偶发会出现 NaN/Infinity（例如被其它链路改写/对象池脏数据）。
        // 这里必须做有限数回退，否则会把 NaN 喂给 applyPerSecondCap/fluxTracker。
        val dmg = sanitizePanelDamage(projectile.damageAmount, damageResult)
        if (dmg <= 0f) return

        val desiredExtra = dmg * cfg.multAtMax * kk
        if (desiredExtra <= 0f) return

        // 每秒封顶：按“武器 id + 目标”做桶，避免同一目标被无限叠加。
        val bucketWeaponId = weaponId0 ?: "unknown"
        val bucketKey = "hfpress:$bucketWeaponId:${System.identityHashCode(ship)}"
        val applied = CombatCaps.applyPerSecondCap(engine, bucketKey, cfg.capPerSecond, desiredExtra)
        if (applied <= 0f) return

        s.fluxTracker.increaseFlux(applied, true)

        // 轻量的命中特效（不影响性能；不依赖 shader）
        engine.addHitParticle(hitPoint, Vector2f(), 35f, 1f, 0.18f, cfg.fxColor)
        engine.addSmoothParticle(hitPoint, Vector2f(), 55f, 0.65f, 0.25f, cfg.fxColor)

        // AOD-7：额外的"弧光冲击扩散云"提示压制阶段（只在确实施加了额外硬幅时触发）。
        if (weaponId0 == "astd_aod7" || projId == "astd_aod7_shot") {
            val overdriveLevel = try {
                val ship0 = projectile.weapon?.ship
                if (ship0 != null) ArcFlareOverdriveVisualState.getLevel(ship0, engine) else 0f
            } catch (_: Throwable) { 0f }
            val puffColor = ArcFlareOverdriveVisualState.lerpColor(
                Color(140, 200, 255), ArcFlareOverdriveVisualState.hotFringe, overdriveLevel, 110
            )
            val pressureVel = try { s.velocity?.let { Vector2f(it) } ?: Vector2f() } catch (_: Throwable) { Vector2f() }
            repeat(6) {
                val ang = MathUtils.getRandomNumberInRange(0f, 360f)
                val spd = MathUtils.getRandomNumberInRange(50f, 140f)
                val dx = MathUtils.getPointOnCircumference(Vector2f(), spd, ang)
                engine.addSmoothParticle(
                    MathUtils.getRandomPointInCircle(hitPoint, 18f),
                    Vector2f(pressureVel.x + dx.x, pressureVel.y + dx.y),
                    MathUtils.getRandomNumberInRange(35f, 90f),
                    MathUtils.getRandomNumberInRange(0.55f, 1.0f),
                    MathUtils.getRandomNumberInRange(0.14f, 0.28f),
                    puffColor,
                )
            }
        }
    }

    private fun sanitizePanelDamage(raw: Float, damageResult: ApplyDamageResultAPI): Float {
        if (raw.isFinitePositiveOrZero()) return raw

        // 回退：取这次命中“实际造成”的总伤害（并不等于面板值，但至少应是有限数）。
        val fallback = (damageResult.damageToShields + damageResult.totalDamageToArmor + damageResult.damageToHull)
        return if (fallback.isFinitePositiveOrZero()) fallback else 0f
    }

    private fun Float.isFinitePositiveOrZero(): Boolean = !this.isNaN() && !this.isInfinite() && this >= 0f

    private fun spawnAod7ImpactFx(
        engine: CombatEngineAPI,
        ship: ShipAPI?,
        projectile: DamagingProjectileAPI,
        point: Vector2f,
        shieldHit: Boolean,
    ) {
        if (engine.isPaused) return

        val facing = try {
            val v = projectile.velocity
            if (v != null && (v.x * v.x + v.y * v.y) > 0.01f) org.lazywizard.lazylib.VectorUtils.getFacing(v) else projectile.facing
        } catch (_: Throwable) {
            0f
        }

        // 需求：TSM 类似的“冲击条纹 + 同色烟雾”，但数量约为 TSM 的 33%。
        // 注意：TSM 工具内部会把 intensityMult 最低夹到 0.75，为了真正控制“数量”，
        // 这里用更低的 base counts 来实现 33% 的量级，而不是依赖 intensityMult。
        // 命中特效颜色跟随弹体过载状态：无过载=蓝色，满过载=橙色。
        val overdriveLevel = try {
            val ship0 = projectile.weapon?.ship
            if (ship0 != null) ArcFlareOverdriveVisualState.getLevel(ship0, engine) else 0f
        } catch (_: Throwable) { 0f }
        val core = ArcFlareOverdriveVisualState.lerpColor(
            Color(225, 242, 255), Color(255, 245, 225), overdriveLevel, if (shieldHit) 210 else 190
        )
        val fringe = ArcFlareOverdriveVisualState.lerpColor(
            Color(130, 195, 255), Color(255, 185, 95), overdriveLevel, if (shieldHit) 190 else 165
        )
        val smoke = Color(fringe.red, fringe.green, fringe.blue, if (shieldHit) 85 else 75)

        TsmTerminalStrikeFx.spawnImpactFx(
            engine = engine,
            point = point,
            towardTargetFacing = facing,
            facingMode = TsmTerminalStrikeFx.ImpactFacingMode.INWARD,
            smokeColor = smoke,
            coreColor = core,
            fringeColor = fringe,
            intensityMult = 1f,
            smokeStyle = TsmTerminalStrikeFx.ImpactSmokeStyle(
                puffCountBase = 2,
                puffCountExtra = 2,
                spreadArc = 24f,
                sizeMin = 42f,
                sizeMax = 86f,
                speedMin = 70f,
                speedMax = 155f,
                durationMin = 0.34f,
                durationMax = 0.62f,
            ),
            sprayStyle = TsmTerminalStrikeFx.ImpactSprayStyle(
                baseRaysMin = 7,
                baseRaysExtra = 3,
                arc = 58f,
                lengthMin = 95f,
                lengthMax = 210f,
                widthMin = 7.5f,
                widthMax = 15.0f,
                fullMin = 0.05f,
                fullMax = 0.10f,
                fadeOutMin = 0.30f,
                fadeOutMax = 0.52f,
                speedMin = 220f,
                speedMax = 520f,
                impactScale = 0.85f,
                introRampSeconds = 0.05f,
            ),
        )

        // 轻微中心闪（可读性）：不依赖 ship
        val vel = try {
            ship?.velocity?.let { Vector2f(it) }
                ?: projectile.velocity?.let { Vector2f(it) }
                ?: Vector2f()
        } catch (_: Throwable) {
            Vector2f()
        }
        // 爆炸烟雾云：多个大型软粒子向外扩散，增强命中冲击感
        val smokeBaseColor = Color(72, 54, 46, 120)
        repeat(4) {
            val ang = MathUtils.getRandomNumberInRange(0f, 360f)
            val spd = MathUtils.getRandomNumberInRange(35f, 95f)
            val dx = MathUtils.getPointOnCircumference(Vector2f(), spd, ang)
            val smokeVel = Vector2f(vel.x + dx.x, vel.y + dx.y)
            engine.addSmoothParticle(
                MathUtils.getRandomPointInCircle(point, 20f),
                smokeVel,
                MathUtils.getRandomNumberInRange(55f, 115f),
                MathUtils.getRandomNumberInRange(0.20f, 0.48f),
                MathUtils.getRandomNumberInRange(0.55f, 1.1f),
                smokeBaseColor,
            )
        }
        engine.addHitParticle(point, vel, 58f, 1.0f, 0.11f, core)
    }
}
