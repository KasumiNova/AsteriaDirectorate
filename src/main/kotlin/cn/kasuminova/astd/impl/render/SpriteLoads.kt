package cn.kasuminova.astd.impl.render

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.SettingsAPI
import com.fs.starfarer.api.graphics.SpriteAPI
import java.util.concurrent.ConcurrentHashMap

private val spriteLoadLog = Global.getLogger(Class.forName("cn.kasuminova.astd.impl.render.SpriteLoadsKt"))

/** 已确认进贴图缓存的路径（贴图缓存全局，加载一次即可；失败的路径保留在集合内避免每发弹体刷屏告警）。 */
private val loadedTexturePaths = ConcurrentHashMap.newKeySet<String>()

/**
 * 加载并获取贴图。
 *
 * 直接 `getSprite(路径)` 对本次运行新加入/未被 Settings 扫描的贴图会**静默返回空精灵**（0x0/texId=0，不报错不写日志），
 * 须先 `loadTexture` 进缓存。同一路径只 `loadTexture` 一次；加载失败 warn 一次（不静默），随后仍尝试 `getSprite`
 * （路径也可能是 settings.json 里按 id 注册的精灵，或调用方只想要兜底空精灵）。
 */
fun SettingsAPI.loadAndGetSprite(path: String): SpriteAPI {
    if (loadedTexturePaths.add(path)) {
        try {
            loadTexture(path)
        } catch (t: Throwable) {
            spriteLoadLog.warn("ASTD 贴图加载失败：$path", t)
        }
    }
    return getSprite(path)
}
