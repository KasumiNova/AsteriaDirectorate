package cn.kasuminova.astd.renderer.effect.explosion

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.impl.combat.NegativeExplosionVisual
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * 裂隙爆炸的调色板（主色族参数化；模组内当前统一使用 [BLUE] 冷蓝族）。
 *
 * @property border 裂隙边缘/大气环主色（NegativeExplosionVisual 的 color，同时决定
 *   命中闪光与负粒子反色基调）。
 * @property underglow 裂隙底部正向星云尘色（低 alpha，原版本字段为暗红 EXPLOSION_UNDERCOLOR）。
 * @property windup 起爆征兆 ping 光圈色（低 alpha）。
 */
data class RiftExplosionPalette(
    val border: Color,
    val underglow: Color,
    val windup: Color,
) {
    companion object {
        /**
         * 冷蓝主色族（需求定案：裂隙洪流发射极同款特效换蓝色）：
         * 边缘亮蓝 (70,130,255)，底部星云暗蓝低 alpha，ping 光圈亮蓝低 alpha。
         */
        @JvmField
        val BLUE: RiftExplosionPalette = RiftExplosionPalette(
            border = Color(70, 130, 255, 255),
            underglow = Color(10, 40, 140, 100),
            windup = Color(90, 150, 255, 60),
        )
    }
}

/**
 * 裂隙爆炸渲染原语（模组内通用组件；首批接入点：「七星」折跃发射器连跳/终结爆炸、
 * 辉星 MRM 爆炸）——复刻原版「裂隙洪流发射极」地雷爆炸（RiftCascadeMineExplosion
 * → NegativeExplosionVisual）的观感：黑色噪声圆盘 + corona_hard 扰动大气环 +
 * 首裂隙命中闪光（负星云 ×7 + 正向星云 ×15）+ 后续裂隙向首裂隙回漂。
 *
 * 与原版路径的差异（动机注记）：
 * - 原版 `spawnStandardRift` 第一行即解引用弹体（addDamagedAlready/getLocation），
 *   纯视觉场景无弹体可传——本组件直接复刻其生成循环，经
 *   `engine.addLayeredRenderingPlugin(NegativeExplosionVisual(params))` 落地，
 *   参数经 [buildRiftParams] 纯函数构造（单测可验）；
 * - 总尺寸相对原版 -30%（[SIZE_SCALE]，需求定案）：半径/厚度同比缩放，
 *   粒子尺寸随半径自动缩放（NegativeExplosionVisual 内部全部按 p.radius 推导）；
 * - 原版爆炸伤害走地雷 proximity 引爆（0.5s windup），本组件只管视觉与征兆，
 *   延迟伤害结算由调用方状态机自持（七星链脚本 pending 队列）；
 * - 音效（[SOUND_EXPLOSION]/[SOUND_WINDUP]，原版地雷同款）由调用方播放——组件本体
 *   只触 engine API，保持桩引擎单测可驱动（Global.getSoundPlayer 在单测环境不可用）；
 * - 组件内随机散布/几何走 LazyLib MathUtils 而非 Misc（一次性纯视觉随机同口径）——
 *   Misc 在单测 JVM 静态初始化失败（实机判例：StellarMrmStrikeImplTest 桩引擎驱动路径
 *   ExceptionInInitializerError），MathUtils 在既有测试链路已验证可用。
 */
object RiftExplosionVfx {
    private val log = Global.getLogger(RiftExplosionVfx::class.java)

    /** 爆炸音效 id（原版裂隙地雷起爆音；由调用方在结算点播放，见类文档注）。 */
    const val SOUND_EXPLOSION = "riftcascade_rift"

    /** 起爆征兆音效 id（原版裂隙地雷 windup 音；由调用方在征兆点播放）。 */
    const val SOUND_WINDUP = "riftcascade_windup"

    /** 相对原版裂隙洪流地雷爆炸的总尺寸倍率（需求定案 -30%）。 */
    const val SIZE_SCALE = 0.7f

    /** 原版地雷爆炸基准半径（RiftCascadeMineExplosion：25 × sizeMult，sizeMult=1 时）。 */
    const val VANILLA_BASE_RADIUS = 25f

    /** 本组件默认单裂隙基准半径（su）= 原版基准 × [SIZE_SCALE]。 */
    const val DEFAULT_RADIUS = VANILLA_BASE_RADIUS * SIZE_SCALE

    /** 每次爆炸生成的裂隙个数（原版 numRiftsToSpawn 默认 2：首裂隙带命中闪光，次裂隙回漂）。 */
    const val NUM_RIFTS = 2

    /** 起爆征兆时长（秒，对齐原版地雷 proximity delay 0.5s：ping 光圈 + windup 音）。 */
    const val WINDUP_SECONDS = 0.5f

    /** 裂隙淡出时长（秒，原版地雷爆炸口径 fadeOut = 1.0）。 */
    const val DEFAULT_FADE_OUT = 1.0f

    /** 原版厚度/半径比（NEParams 默认 thickness 25 / 地雷 radius 25 = 1.0），缩放时同比保持。 */
    private const val THICKNESS_RATIO = 1.0f

    /** 单裂隙半径逐枚抖动（原版口径：0.75 ~ 1.25）。 */
    private const val RADIUS_JITTER_MIN = 0.75f
    private const val RADIUS_JITTER_SPAN = 0.5f

    /** 裂隙落点随机散布半径占比（原版口径：0.4 × 半径）。 */
    private const val SPAWN_SCATTER_RATIO = 0.4f

    /** 后续裂隙回漂速度系数（原版口径：dist / (fadeIn + fadeOut) × 0.7）。 */
    private const val DRIFT_SPEED_RATIO = 0.7f

    /**
     * 构造一枚裂隙的渲染参数（纯函数，单测直接断言字段映射）：
     * 命中闪光/噪声/淡入口径对齐原版 `createStandardRiftParams`；厚度随半径同比
     * （[THICKNESS_RATIO]）；颜色族由 [palette] 给出。
     */
    internal fun buildRiftParams(
        palette: RiftExplosionPalette,
        radius: Float,
        fadeOut: Float,
        withHitGlow: Boolean,
    ): NegativeExplosionVisual.NEParams {
        val p = NegativeExplosionVisual.NEParams()
        p.hitGlowSizeMult = 0.75f
        p.spawnHitGlowAt = 0.0f
        p.noiseMag = 1.0f
        p.fadeIn = 0.1f
        p.fadeOut = fadeOut
        p.underglow = palette.underglow
        p.withHitGlow = withHitGlow
        p.radius = radius
        p.thickness = radius * THICKNESS_RATIO
        p.color = palette.border
        return p
    }

    /**
     * 生成一发裂隙爆炸视觉（无伤害；伤害结算由调用方负责）。
     * 复刻原版生成循环：逐枚半径抖动 0.75~1.25、落点在 0.4×半径内散布、
     * 仅首枚带命中闪光、后续枚向首枚回漂（速度 = 间距/(fadeIn+fadeOut) × 0.7）。
     *
     * @param at 爆心（世界坐标，su）。
     * @param radius 单裂隙基准半径（su，默认 [DEFAULT_RADIUS] = 原版 -30%）；
     *   非正/NaN 属配置错误，记 WARN 且本次不生成特效（不产出半成品）。
     * @param fadeOut 淡出时长（秒，原版地雷口径 1.0）。
     * @param palette 主色族（默认 [RiftExplosionPalette.BLUE]）。
     * @return 实际生成的裂隙枚数（正常 = [NUM_RIFTS]，入参非法 = 0）。
     */
    fun riftExplosion(
        engine: CombatEngineAPI,
        at: Vector2f,
        radius: Float = DEFAULT_RADIUS,
        fadeOut: Float = DEFAULT_FADE_OUT,
        palette: RiftExplosionPalette = RiftExplosionPalette.BLUE,
    ): Int {
        if (radius.isNaN() || radius <= 0f || fadeOut.isNaN() || fadeOut <= 0f) {
            log.warn("裂隙爆炸参数非法（radius=$radius fadeOut=$fadeOut），属配置错误，本次不生成特效")
            return 0
        }
        var prev: CombatEntityAPI? = null
        repeat(NUM_RIFTS) {
            val p = buildRiftParams(
                palette = palette,
                radius = radius * (RADIUS_JITTER_MIN + RADIUS_JITTER_SPAN * MathUtils.getRandom().nextFloat()),
                fadeOut = fadeOut,
                withHitGlow = prev == null,
            )
            val loc = MathUtils.getRandomPointOnCircumference(at, p.radius * SPAWN_SCATTER_RATIO)
            val entity = engine.addLayeredRenderingPlugin(NegativeExplosionVisual(p))
            entity.location.set(loc)
            val anchor = prev
            if (anchor != null) {
                // 回漂：方向 = 本枚 → 上一枚，速度 = 间距/(fadeIn+fadeOut) × 0.7（原版口径）。
                val vel = Vector2f.sub(anchor.location, loc, null)
                val dist = vel.length()
                if (dist > 0f) {
                    vel.normalise()
                    vel.scale(dist / (p.fadeIn + p.fadeOut) * DRIFT_SPEED_RATIO)
                    entity.velocity.set(vel)
                }
            }
            prev = entity
        }
        return NUM_RIFTS
    }

    /**
     * 起爆征兆（对齐原版地雷 proximity windup 0.5s）：低 alpha ping 光圈维持整个
     * 征兆期。伤害与裂隙主体视觉在 [WINDUP_SECONDS] 后由调用方触发；
     * 征兆音 [SOUND_WINDUP] 由调用方同点播放。
     */
    fun spawnWindup(
        engine: CombatEngineAPI,
        at: Vector2f,
        radius: Float = DEFAULT_RADIUS,
        palette: RiftExplosionPalette = RiftExplosionPalette.BLUE,
    ) {
        engine.addSmoothParticle(at, ZERO_VEL, radius * 4f, 0.6f, WINDUP_SECONDS, palette.windup)
    }

    /** 静止粒子速度（避免逐次分配）。 */
    private val ZERO_VEL = Vector2f(0f, 0f)
}
