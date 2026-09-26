package cn.kasuminova.astd.renderer.effect.system

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.lwjgl.opengl.GL11
import java.awt.Color
import java.util.EnumSet

/**
 * 双子星 DEM 发射架（astd_gemini_dem_launcher / astd_gemini_dem_pod）的装填弹体视觉层。
 *
 * 职责：
 * - 逐管弹体染色：原版 `RENDER_LOADED_MISSILES` 按炮管渲染 dummy 弹体贴图（双子星弹体.png），
 *   本层通过 `WeaponAPI.getMissileRenderData()` 把 0 号管（左舷）染红（高爆弹头位）、
 *   1 号管（右舷）染蓝（动能弹头位），与 [GeminiDemSalvoOnFireEffect] 的齐射编成舷侧一致；
 * - 弹体发光叠加：逐管在弹体中心以加法混合渲染红/蓝光效贴图，
 *   透明度跟随原版装填渲染的 brightness（发射熄管 / 冷却回填的明暗变化自动同步）。
 * - 冷却期双弹隐藏：原版 `MissileWeapon` 充能下降段只复燃已击发炮管，未击发管 brightness 恒 1
 *   （冷却期总亮一枚弹）；本层在冷却期（cooldownRemaining > 0 或弹药不足一次齐射）把两管
 *   `sprite.color` 写成全透明并跳过发光（原版每帧只重写 alphaMult 不动 color，染色写 color 对本版安全）。
 *
 * 弹体染色与发光都依赖 `missileRenderData`（无 RENDER_LOADED_MISSILES 时为 null，记一次 WARN 后停摆）。
 *
 * 染色安全性：原版 `MissileWeapon` 为每个炮管 `new Sprite(...)` 独立实例
 * （MissileRenderSprite per barrel），`getMissileRenderData()` 逐管包装返回，
 * 因此逐管写 `sprite.color` 不会互相覆盖。
 */
internal object GeminiDemRackVisuals {

    private const val ENGINE_KEY = "astd_gemini_dem_rack_visuals"

    private val RACK_WEAPON_IDS = setOf(
        "astd_gemini_dem_launcher",
        "astd_gemini_dem_pod",
    )

    /** 弹体光效贴图（按炮管序号：0=左舷红/高爆位，1=右舷蓝/动能位）。 */
    private val BARREL_GLOW_PATHS = listOf(
        "graphics/weapons/astd_gemini_dem_missile_glow_red.png",
        "graphics/weapons/astd_gemini_dem_missile_glow_blue.png",
    )

    /** 弹体染色（作用于灰白弹体底图）：红=高爆位，蓝=动能位。 */
    private val BARREL_TINTS = listOf(
        Color(255, 125, 115, 255),
        Color(135, 170, 255, 255),
    )

    private val log = Global.getLogger(GeminiDemRackVisuals::class.java)

    /** preloadTextures 加载成功的贴图路径集合；渲染只使用此集合内的路径。 */
    private val loadedPaths = HashSet<String>()

    /**
     * 预加载弹体光效贴图（SSOptimizer 延迟加载下未被数据文件引用的贴图不会上传 GL，
     * 裸 getSprite 是 textureID=0 空壳）。
     */
    fun preloadTextures() {
        for (path in BARREL_GLOW_PATHS) {
            try {
                Global.getSettings().loadTexture(path)
                loadedPaths += path
            } catch (t: Throwable) {
                log.warn("[ASTD] GeminiDemRackVisuals texture preload failed: $path", t)
            }
        }
    }

    /** 每帧由武器的 everyFrameEffect 驱动：登记武器 + 逐管染色。 */
    fun onWeaponFrame(engine: CombatEngineAPI, weapon: WeaponAPI) {
        val weaponId = weapon.spec?.weaponId ?: return
        if (weaponId !in RACK_WEAPON_IDS) return

        ensureInstalled(engine)
        getPlugin(engine)?.track(weapon)

        val renderData = weapon.missileRenderData
        if (renderData == null) {
            if (missingRenderDataWarned.add(weaponId)) {
                log.warn("[ASTD] 双子星 DEM 导轨：weapon=$weaponId 无 missileRenderData（缺失 RENDER_LOADED_MISSILES？），弹体染色/发光停摆")
            }
            return
        }
        val loaded = isSalvoLoaded(weapon)
        for ((index, data) in renderData.withIndex()) {
            val tint = BARREL_TINTS.getOrNull(index) ?: break
            try {
                // 冷却期隐藏装填弹体：原版充能下降段只复燃已击发管，未击发管 brightness 恒 1
                // （不隐藏会总亮一枚弹）；color alpha 不会被原版逐帧重写（原版只写 alphaMult）
                data.sprite.color = if (loaded) tint
                else Color(tint.red, tint.green, tint.blue, 0)
            } catch (t: Throwable) {
                log.warn("[ASTD] 双子星 DEM 导轨：弹体染色失败 weapon=$weaponId barrel=$index", t)
            }
        }
    }

    /** 一次齐射是否装填完毕（冷却完毕且弹药够双管）：不满足时两管弹体均不渲染。 */
    private fun isSalvoLoaded(weapon: WeaponAPI): Boolean =
        weapon.cooldownRemaining <= 0f && weapon.ammo >= BARREL_TINTS.size

    private val missingRenderDataWarned = HashSet<String>()

    private fun ensureInstalled(engine: CombatEngineAPI) {
        if (engine.customData[ENGINE_KEY] != null) return
        val plugin = Plugin(engine)
        engine.addLayeredRenderingPlugin(plugin)
        engine.customData[ENGINE_KEY] = plugin
    }

    private fun getPlugin(engine: CombatEngineAPI): Plugin? = engine.customData[ENGINE_KEY] as? Plugin

    private class Plugin(private val engine: CombatEngineAPI) : BaseCombatLayeredRenderingPlugin(
        CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
    ) {

        private val weapons = LinkedHashSet<WeaponAPI>()
        private var firstRenderLogged = false

        fun track(weapon: WeaponAPI) {
            weapons += weapon
        }

        override fun init(entity: CombatEntityAPI?) {
            super.init(entity)
            log.info("[ASTD] GeminiDemRackVisuals active")
        }

        override fun getActiveLayers(): EnumSet<CombatEngineLayers> =
            EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)

        override fun getRenderRadius(): Float = Float.MAX_VALUE

        override fun advance(amount: Float) {
            val it = weapons.iterator()
            while (it.hasNext()) {
                val ship = it.next().ship
                if (ship == null || !engine.isEntityInPlay(ship)) {
                    it.remove()
                }
            }
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (layer != CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) return
            if (weapons.isEmpty()) return
            if (!firstRenderLogged) {
                firstRenderLogged = true
                log.info("[ASTD] GeminiDemRackVisuals first render, weapons=${weapons.size}")
            }

            GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT)
            try {
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
                for (weapon in weapons) {
                    val ship = weapon.ship ?: continue
                    if (ship.isHulk || weapon.isDisabled) continue
                    if (!isSalvoLoaded(weapon)) continue
                    val renderData = weapon.missileRenderData ?: continue
                    for ((index, data) in renderData.withIndex()) {
                        val glowPath = BARREL_GLOW_PATHS.getOrNull(index) ?: break
                        if (glowPath !in loadedPaths) continue
                        val brightness = data.brightness.coerceIn(0f, 1f)
                        if (brightness <= 0.002f) continue

                        // SpriteAPI 为全局共享缓存实例，尺寸/混合状态可能被其他渲染方改写，须逐帧重取并重置
                        val sprite = Global.getSettings().getSprite(glowPath)
                        sprite.setAdditiveBlend()
                        sprite.color = Color(255, 255, 255, 255)
                        sprite.alphaMult = brightness * viewport.alphaMult
                        sprite.setSize(data.sprite.width, data.sprite.height)
                        sprite.angle = data.missileFacing - 90f
                        val center = data.missileCenterLocation ?: continue
                        sprite.renderAtCenter(center.x, center.y)
                    }
                }
            } finally {
                GL11.glPopAttrib()
            }
        }
    }
}
