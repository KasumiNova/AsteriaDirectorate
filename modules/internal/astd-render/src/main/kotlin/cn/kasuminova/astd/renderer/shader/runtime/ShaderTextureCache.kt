package cn.kasuminova.astd.renderer.shader.runtime

import com.fs.starfarer.api.Global

internal class ShaderTextureCache {
    private val textureIds = HashMap<String, Int>()
    private val warnedPaths = HashSet<String>()

    fun textureIdFor(path: String): Int? {
        textureIds[path]?.let { return it }
        val sprite = Global.getSettings().getSprite(path)
        if (sprite == null || sprite.textureId == 0) {
            if (warnedPaths.add(path)) {
                log.warn("[ASTD] Shader 贴图未上传 GL，跳过对应绘制（path=$path，需在 onApplicationLoad 预加载）")
            }
            return null
        }
        if ((sprite.textureWidth < 1f || sprite.textureHeight < 1f) && warnedPaths.add(path)) {
            log.warn(
                "[ASTD] Shader 贴图被补齐到 2 的幂（texWidth=${sprite.textureWidth} texHeight=${sprite.textureHeight}），" +
                    "Repeat 采样会带入补齐区域（path=$path）",
            )
        }
        textureIds[path] = sprite.textureId
        return sprite.textureId
    }

    fun clear() {
        textureIds.clear()
        warnedPaths.clear()
    }

    companion object {
        private val log = Global.getLogger(ShaderTextureCache::class.java)
    }
}
