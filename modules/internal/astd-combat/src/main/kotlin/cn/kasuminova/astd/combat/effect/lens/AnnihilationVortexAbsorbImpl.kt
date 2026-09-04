package cn.kasuminova.astd.combat.effect.lens

import cn.kasuminova.astd.api.combat.AbsorbOutcome
import cn.kasuminova.astd.api.combat.AbsorbedShot
import cn.kasuminova.astd.api.combat.AnnihilationVortexAbsorb
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f

/**
 * [AnnihilationVortexAbsorb] 实现（规格 04 §2.2）。
 *
 * 流程：`CombatUtils.getEntitiesWithinRange(center, radius)` 一次圆形粗筛（LazyLib 空间网格，
 * GCP 路径已验证；半径 ≤300su 候选量可控）→ 敌方 `DamagingProjectileAPI` 过滤（MissileAPI 是其子接口，
 * 导弹天然覆盖）→ 吸收半径内 `engine.removeEntity` 并计入返回清单，其余按「指向中心、
 * 随距离衰减」的加速度直接改写 live velocity（速度矢量为 live reference，星域惯例）。
 *
 * [coarseQuery] 可注入（默认 LazyLib 网格查询）：LazyLib 内部走 `Global.getCombatEngine()`，
 * 裸单测环境不可达，测试注入候选清单提供者驱动同一结算路径（infra2 同款处置）。
 */
class AnnihilationVortexAbsorbImpl(
    private val coarseQuery: (CombatEngineAPI, Vector2f, Float) -> List<CombatEntityAPI> = { _, center, radius ->
        CombatUtils.getEntitiesWithinRange(center, radius)
    },
) : AnnihilationVortexAbsorb {

    /** 半径配置错误 WARN 闸：每实例（每件武器）一次，不静默产出零半径涡旋也不刷屏。 */
    private var warnedBadRadius = false

    /** 碰撞半径读取失败 WARN 闸：每实例一次（禁空 catch——回退 0 必须有日志）。 */
    private var warnedCollision = false

    override fun advance(
        engine: CombatEngineAPI,
        center: Vector2f,
        radius: Float,
        absorbRadius: Float,
        sourceOwner: Int,
        amount: Float,
        onAbsorbedFx: (Vector2f) -> Unit,
    ): AbsorbOutcome {
        var r = radius
        if (r <= 0f) {
            if (!warnedBadRadius) {
                warnedBadRadius = true
                log.warn("[ASTD] 湮灭涡旋半径输入非正（$radius），clamp 到 ${AnnihilationVortexDifficulty.ABSORB_RADIUS_MIN}（配置错误）")
            }
            r = AnnihilationVortexDifficulty.ABSORB_RADIUS_MIN
        }

        val absorbed = ArrayList<AbsorbedShot>(4)
        var pulled = 0
        for (e in coarseQuery(engine, center, r)) {
            if (e == null) continue
            if (e !is DamagingProjectileAPI) continue
            // 仅敌方（定稿裁定）；owner 读取异常视为非敌方跳过（不静默吸收归属不明弹体）。
            val owner = try {
                e.owner
            } catch (t: Throwable) {
                log.warn("[ASTD] 湮灭涡旋读取弹体归属失败，跳过该弹体: ${t.javaClass.simpleName}: ${t.message}")
                continue
            }
            if (owner == sourceOwner) continue

            val loc = e.location ?: continue
            val dist = MathUtils.getDistance(center, loc)
            val collision = try {
                e.collisionRadius
            } catch (t: Throwable) {
                if (!warnedCollision) {
                    warnedCollision = true
                    log.warn("[ASTD] 湮灭涡旋读取弹体碰撞半径失败，按 0 计（吸收判定退回圆心距）: ${t.javaClass.simpleName}: ${t.message}")
                }
                0f
            }.coerceAtLeast(0f)
            val reach = dist - collision

            if (reach <= absorbRadius) {
                // 吸收：弹体移除、不计伤害；面板伤害用 base（设计：「其面板伤害」）。
                engine.removeEntity(e)
                onAbsorbedFx(Vector2f(loc))
                absorbed += AbsorbedShot(e.damageType, e.baseDamageAmount, Vector2f(loc))
                continue
            }

            // 牵引：a = PULL_ACCEL_MAX × (1 - dist/radius)，方向指向中心；dist>1f 门控不除零（规格 04 §2.4）。
            if (dist > 1f) {
                val k = pullAccel(dist, r) * amount / dist
                if (k > 0f) {
                    val v = e.velocity
                    if (v != null) {
                        v.x += (center.x - loc.x) * k
                        v.y += (center.y - loc.y) * k
                        pulled++
                    }
                }
            }
        }
        return AbsorbOutcome(absorbed, pulled)
    }

    companion object {
        private val log = Global.getLogger(AnnihilationVortexAbsorbImpl::class.java)

        /**
         * 牵引加速度（su/s²）：涡旋边缘（dist = radius）为 0，越近越强，中心方向最大
         * [AnnihilationVortexDifficulty.PULL_ACCEL_MAX]；dist 超出半径（粗筛边界实体）clamp 为 0 不反推。
         */
        fun pullAccel(dist: Float, radius: Float): Float =
            AnnihilationVortexDifficulty.PULL_ACCEL_MAX * (1f - dist / radius).coerceIn(0f, 1f)
    }
}
