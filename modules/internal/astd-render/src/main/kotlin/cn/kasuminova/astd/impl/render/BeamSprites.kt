package cn.kasuminova.astd.impl.render

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.graphics.SpriteAPI

object BeamSprites {
    private val log = Global.getLogger(BeamSprites::class.java)
    const val CORE_PATH: String = "graphics/fx/beamcoreb.png"
    const val FRINGE_PATH: String = "graphics/fx/beamfringeb.png"

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
