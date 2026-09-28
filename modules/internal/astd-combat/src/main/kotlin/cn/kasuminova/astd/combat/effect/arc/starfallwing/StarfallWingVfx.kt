package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.units.standard.entity.FlareEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 坠星残翼的一次性炮口光斑特效（规格 10-signature 坠星残翼节特效段）：
 * 每次发射在炮口放两枚无运动向量的一次性 FlareEntity——
 * - SMOOTH_DISC 盘形光斑：尺寸 100→200su 扩散并同步变淡；
 * - SMOOTH 圆形光斑：尺寸 100su 不变，同步变淡。
 * 两者寿命 0.66s。
 *
 * 实现口径：FlareEntity 的 globalTimer 只驱动 alpha 包络（fadeIn 0.06 + full 0.2 + fadeOut 0.4），
 * 尺寸动画由本类的每帧推进承担（[advance] 由 StarfallWingWeaponEffect 逐帧调用）；
 * 到期显式 delete（一次性实体不托管给 BoxUtil 注册簿的常驻清理）。
 * 配色与主弹同族（紫： fringe 170,110,255 / core 240,225,255）。
 */
object StarfallWingVfx {

    /** 炮口光斑总寿命（秒）。 */
    const val FLARE_LIFETIME = 0.66f

    private const val DISC_START_SIZE = 100f
    private const val DISC_END_SIZE = 200f
    private const val GLOW_SIZE = 100f

    /** alpha 包络：淡入 / 满亮 / 淡出（合计 = [FLARE_LIFETIME]）。 */
    private const val FADE_IN = 0.06f
    private const val FULL = 0.20f
    private const val FADE_OUT = 0.40f

    private val CORE = Color(240, 225, 255, 220)
    private val FRINGE = Color(170, 110, 255, 255)

    private val log = AstdLog.logger

    /** 活跃光斑（实体 + 年龄 + 种类）；尺寸动画每帧插值。 */
    private class ActiveFlare(
        val entity: FlareEntity,
        val expanding: Boolean,
        var age: Float = 0f,
    )

    /** engine key → 活跃光斑列表。 */
    private const val KEY_FLARES = "astd_starfall_wing_muzzle_flares"

    /** 发射瞬间在 [loc] 放两枚炮口光斑（实体创建/注册失败记 WARN 并跳过该枚，不影响另一枚）。 */
    fun spawnMuzzleFlares(engine: CombatEngineAPI, loc: Vector2f) {
        spawnFlare(engine, loc, expanding = true)
        spawnFlare(engine, loc, expanding = false)
    }

    /** 每帧推进（StarfallWingWeaponEffect.advance 调用）：尺寸插值 + 到期回收。 */
    fun advance(engine: CombatEngineAPI, amount: Float) {
        val flares = engine.customData[KEY_FLARES] as? MutableList<ActiveFlare> ?: return
        val it = flares.iterator()
        while (it.hasNext()) {
            val flare = it.next()
            flare.age += amount
            val t = (flare.age / FLARE_LIFETIME).coerceIn(0f, 1f)
            if (flare.age >= FLARE_LIFETIME || flare.entity.hasDelete()) {
                if (!flare.entity.hasDelete()) flare.entity.delete()
                it.remove()
                continue
            }
            if (flare.expanding) {
                val size = DISC_START_SIZE + (DISC_END_SIZE - DISC_START_SIZE) * t
                flare.entity.setSize(size, size)
            }
        }
        if (flares.isEmpty()) engine.customData.remove(KEY_FLARES)
    }

    private fun spawnFlare(engine: CombatEngineAPI, loc: Vector2f, expanding: Boolean) {
        BoxUtilCombatVfx.ensureReady(engine)
        val entity = try {
            FlareEntity()
        } catch (t: Throwable) {
            log.warn("[ASTD] 坠星残翼炮口光斑建实体失败（${t.javaClass.simpleName}），本次开火该枚光斑缺席", t)
            return
        }
        entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
        entity.setAdditiveBlend()
        if (expanding) entity.setSmoothDisc() else entity.setSmooth()
        entity.isFlick = false
        entity.glowPower = 0.6f
        // BoxFlareComponent 注记：setCoreColor(float,…) 重载有误写 fringe 槽的上游 bug，必须用 Color 重载。
        entity.setCoreColor(CORE)
        entity.setFringeColor(FRINGE)
        val size = if (expanding) DISC_START_SIZE else GLOW_SIZE
        entity.setSize(size, size)
        entity.setGlobalTimer(FADE_IN, FULL, FADE_OUT)
        entity.setStateVanilla(Vector2f(loc), 0f)
        val state = BoxUtilCombatVfx.addEntity(engine, entity)
        if (state != 0) {
            log.warn("[ASTD] 坠星残翼炮口光斑注册失败（addEntity 返回 $state），本次开火该枚光斑缺席")
            entity.delete()
            return
        }
        val flares = engine.customData.getOrPut(KEY_FLARES) { ArrayList<ActiveFlare>() } as MutableList<ActiveFlare>
        flares += ActiveFlare(entity, expanding)
    }
}
