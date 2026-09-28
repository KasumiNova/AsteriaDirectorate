package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import org.boxutil.units.standard.entity.FlareEntity
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 坠星残翼的一次性光斑特效（规格 10-signature 坠星残翼节特效段）：
 *
 * - 炮口：每次发射在炮口放两枚无运动向量的一次性 FlareEntity——
 *   SMOOTH_DISC 盘形光斑尺寸 100→200su 扩散并同步变淡；SMOOTH 圆形光斑尺寸 100su 不变同步变淡；
 *   两者寿命 0.66s。
 * - 子射弹分裂：主弹两侧散发子射弹时在分裂点放一枚小型 SMOOTH 光斑
 *   （尺寸/寿命为 [MOTE_FLARE_SIZE_START] 等可调常量），标记分裂位置。
 *
 * 观感裁定（实机白团收敛）：光斑不喂 bloom（glowPower=0——additive 光斑叠 bloom
 * 会过曝扩散成盖住整舰的白团），核心/边缘 alpha 减半让紫色边缘主导而非白色核心。
 *
 * 实现口径：FlareEntity 的 globalTimer 只驱动 alpha 包络，
 * 尺寸动画由本类的每帧推进承担（[advance] 由 StarfallWingWeaponEffect 逐帧调用）；
 * 到期显式 delete（一次性实体不托管给 BoxUtil 注册簿的常驻清理）。
 */
object StarfallWingVfx {

    /** 炮口光斑总寿命（秒，规格定值）。 */
    const val FLARE_LIFETIME = 0.66f

    private const val DISC_START_SIZE = 100f
    private const val DISC_END_SIZE = 200f
    private const val GLOW_SIZE = 100f

    /** 炮口 alpha 包络：淡入 / 满亮 / 淡出（合计 = [FLARE_LIFETIME]）。 */
    private const val FADE_IN = 0.06f
    private const val FULL = 0.20f
    private const val FADE_OUT = 0.40f

    /** 子射弹分裂光斑：起止尺寸（su）与寿命（秒），实机可调。 */
    private const val MOTE_FLARE_SIZE_START = 14f
    private const val MOTE_FLARE_SIZE_END = 30f
    private const val MOTE_FLARE_LIFETIME = 0.22f
    private const val MOTE_FLARE_FADE_IN = 0.02f
    private const val MOTE_FLARE_FULL = 0.06f
    private const val MOTE_FLARE_FADE_OUT = 0.14f

    /** 配色与主弹同族（紫：fringe 170,110,255 / core 240,225,255），alpha 减半收敛白团观感。 */
    private val CORE = Color(240, 225, 255, 130)
    private val FRINGE = Color(170, 110, 255, 170)

    private val log = AstdLog.logger

    /** 活跃光斑（实体 + 尺寸插值区间 + 寿命）。 */
    private class ActiveFlare(
        val entity: FlareEntity,
        val startSize: Float,
        val endSize: Float,
        val lifetime: Float,
        var age: Float = 0f,
    )

    /** engine key → 活跃光斑列表。 */
    private const val KEY_FLARES = "astd_starfall_wing_muzzle_flares"

    /** 发射瞬间在 [loc] 放两枚炮口光斑（实体创建/注册失败记 WARN 并跳过该枚，不影响另一枚）。 */
    fun spawnMuzzleFlares(engine: CombatEngineAPI, loc: Vector2f) {
        spawnFlare(
            engine, loc, smoothDisc = true,
            startSize = DISC_START_SIZE, endSize = DISC_END_SIZE,
            lifetime = FLARE_LIFETIME, fadeIn = FADE_IN, full = FULL, fadeOut = FADE_OUT,
        )
        spawnFlare(
            engine, loc, smoothDisc = false,
            startSize = GLOW_SIZE, endSize = GLOW_SIZE,
            lifetime = FLARE_LIFETIME, fadeIn = FADE_IN, full = FULL, fadeOut = FADE_OUT,
        )
    }

    /** 子射弹分裂点在 [loc] 放一枚小型 SMOOTH 光斑（分裂位置可见性标记）。 */
    fun spawnMoteSplitFlare(engine: CombatEngineAPI, loc: Vector2f) {
        spawnFlare(
            engine, loc, smoothDisc = false,
            startSize = MOTE_FLARE_SIZE_START, endSize = MOTE_FLARE_SIZE_END,
            lifetime = MOTE_FLARE_LIFETIME,
            fadeIn = MOTE_FLARE_FADE_IN, full = MOTE_FLARE_FULL, fadeOut = MOTE_FLARE_FADE_OUT,
        )
    }

    /** 每帧推进（StarfallWingWeaponEffect.advance 调用）：尺寸插值 + 到期回收。 */
    fun advance(engine: CombatEngineAPI, amount: Float) {
        val flares = engine.customData[KEY_FLARES] as? MutableList<ActiveFlare> ?: return
        val it = flares.iterator()
        while (it.hasNext()) {
            val flare = it.next()
            flare.age += amount
            val t = (flare.age / flare.lifetime).coerceIn(0f, 1f)
            if (flare.age >= flare.lifetime || flare.entity.hasDelete()) {
                if (!flare.entity.hasDelete()) flare.entity.delete()
                it.remove()
                continue
            }
            val size = flare.startSize + (flare.endSize - flare.startSize) * t
            flare.entity.setSize(size, size)
        }
        if (flares.isEmpty()) engine.customData.remove(KEY_FLARES)
    }

    private fun spawnFlare(
        engine: CombatEngineAPI,
        loc: Vector2f,
        smoothDisc: Boolean,
        startSize: Float,
        endSize: Float,
        lifetime: Float,
        fadeIn: Float,
        full: Float,
        fadeOut: Float,
    ) {
        BoxUtilCombatVfx.ensureReady(engine)
        val entity = try {
            FlareEntity()
        } catch (t: Throwable) {
            log.warn("[ASTD] 坠星残翼光斑建实体失败（${t.javaClass.simpleName}），本次光斑缺席", t)
            return
        }
        entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
        entity.setAdditiveBlend()
        if (smoothDisc) entity.setSmoothDisc() else entity.setSmooth()
        entity.isFlick = false
        // 不喂 bloom：additive 光斑 glow 叠 bloom 会过曝成盖住整舰的白团（实机判例）
        entity.glowPower = 0f
        // BoxFlareComponent 注记：setCoreColor(float,…) 重载有误写 fringe 槽的上游 bug，必须用 Color 重载。
        entity.setCoreColor(CORE)
        entity.setFringeColor(FRINGE)
        entity.setSize(startSize, startSize)
        entity.setGlobalTimer(fadeIn, full, fadeOut)
        entity.setStateVanilla(Vector2f(loc), 0f)
        val state = BoxUtilCombatVfx.addEntity(engine, entity)
        if (state != 0) {
            log.warn("[ASTD] 坠星残翼光斑注册失败（addEntity 返回 $state），本次光斑缺席")
            entity.delete()
            return
        }
        val flares = engine.customData.getOrPut(KEY_FLARES) { ArrayList<ActiveFlare>() } as MutableList<ActiveFlare>
        flares += ActiveFlare(entity, startSize, endSize, lifetime)
    }
}
