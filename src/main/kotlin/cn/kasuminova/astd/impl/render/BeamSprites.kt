package cn.kasuminova.astd.impl.render

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.graphics.SpriteAPI

/**
 * 光束束体共用的 core/fringe 贴图加载。三个光束（Psi/GravityCollapse/StellarJet）的 4 件套束体都用同一对
 * beam 贴图；集中一处加载并缓存，失败落到原版 `BUtil_ONE` 兜底并 warn（不静默吞异常）。
 */
internal object BeamSprites {

    private val log = Global.getLogger(BeamSprites::class.java)

    /** 束体 core 贴图（厚实核心）。 */
    const val CORE_PATH: String = "graphics/fx/beamcoreb.png"

    /** 束体 fringe 贴图（边缘辉光）。 */
    const val FRINGE_PATH: String = "graphics/fx/beamfringeb.png"

    /** (core, fringe) 贴图对；两次加载都失败时返回 null（调用方据此放弃建实体并 warn）。 */
    fun load(corePath: String = CORE_PATH, fringePath: String = FRINGE_PATH): Pair<SpriteAPI, SpriteAPI>? {
        val settings = Global.getSettings()
        return try {
            Pair(settings.getSprite(corePath), settings.getSprite(fringePath))
        } catch (t: Throwable) {
            log.warn("光束束体贴图加载失败（core=$corePath fringe=$fringePath），回退 BUtil_ONE", t)
            try {
                val fallback = settings.getSprite("textures", "BUtil_ONE")
                Pair(fallback, fallback)
            } catch (t2: Throwable) {
                log.warn("光束束体贴图回退 BUtil_ONE 亦失败，束体将不可用", t2)
                null
            }
        }
    }
}
