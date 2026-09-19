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
 * 武器补档发光层（常驻 + 蓄能两档合一）。
 *
 * 原版 `.wpn` 只有 `turretGlowSprite`/`hardpointGlowSprite`（由开火瞬间驱动），缺少两档发光槽位：
 * - **常驻档（AMBIENT）**：常亮发光贴图，透明度跟随视口淡入淡出；
 * - **蓄能档（CHARGE）**：带 `chargeup` 的武器在充能条推进期间的进度反馈，亮度取
 *   `WeaponAPI.getChargeLevel()`（开火瞬间由 .wpn 开火发光层接管，本档不显示）。
 * 两档均为加法混合叠在武器之上，共用同一扫描/渲染管线（2026-09 审查裁定合并，此前两份逐行同构）。
 *
 * 贴图约定（炮塔/挂点各一，与武器底图同画布同朝向，只含发光部位）：
 * - 常驻：`graphics/weapons/astd_<短名>_glow_ambient.png` / `astd_<短名>_hp_glow_ambient.png`
 * - 蓄能：`graphics/weapons/astd_<短名>_charge.png` / `astd_<短名>_hp_charge.png`
 *
 * 贴图缺失防线：[preloadTextures] 逐路径登记加载结果，只有加载成功的路径才会被扫描挂载
 * （SSOptimizer 延迟加载下裸 getSprite 是 textureID=0 空壳）；渲染循环因此无失败路径可兜底。
 */
internal object WeaponGlowLayer {

    private const val ENGINE_KEY = "astd_weapon_glow_layer"
    private const val SCAN_INTERVAL = 0.5f

    /** 蓄能档显示下限：低于此值视作未开始充能（上限不设——充满未开火帧保持满亮度，不留空档）。 */
    private const val CHARGE_MIN = 0.001f

    private val log = Global.getLogger(WeaponGlowLayer::class.java)

    /** 发光档位：贴图后缀（炮塔/挂点）。 */
    private enum class GlowSlot(val turretSuffix: String, val hardpointSuffix: String) {
        AMBIENT("_glow_ambient", "_hp_glow_ambient"),
        CHARGE("_charge", "_hp_charge"),
    }

    /** 登记常驻发光的武器 id；贴图路径按命名约定派生。 */
    private val AMBIENT_WEAPON_IDS = setOf(
        "astd_charge_needle",
        "astd_heavy_charge_needle",
        "astd_heavy_ion_pulse",
        "astd_qiongjue_phase_railgun",
        "astd_piercing_lance",
        "astd_electric_drive_accelerator",
        "astd_positron_shockwave",
    )

    /** 登记蓄能发光的武器 id；贴图路径按命名约定派生。 */
    private val CHARGE_WEAPON_IDS = setOf(
        "astd_piercing_lance",
    )

    /** preloadTextures 加载成功的贴图路径集合；扫描只挂载此集合内的路径。 */
    private val loadedPaths = HashSet<String>()

    /**
     * 预加载全部登记贴图并登记加载结果。
     * SSOptimizer 延迟加载下，未被任何地方引用的贴图不会上传 GL，
     * 裸 getSprite 拿到的是 textureID=0 的空壳，渲染不出任何内容。
     */
    fun preloadTextures() {
        for ((ids, slot) in listOf(AMBIENT_WEAPON_IDS to GlowSlot.AMBIENT, CHARGE_WEAPON_IDS to GlowSlot.CHARGE)) {
            for (weaponId in ids) {
                for (suffix in listOf(slot.turretSuffix, slot.hardpointSuffix)) {
                    val path = "graphics/weapons/${weaponId}${suffix}.png"
                    try {
                        Global.getSettings().loadTexture(path)
                        loadedPaths += path
                    } catch (t: Throwable) {
                        log.warn("[ASTD] WeaponGlowLayer texture preload failed: $path", t)
                    }
                }
            }
        }
    }

    fun ensureInstalled(engine: CombatEngineAPI) {
        if (engine.customData[ENGINE_KEY] != null) return
        val plugin = Plugin(engine)
        engine.addLayeredRenderingPlugin(plugin)
        engine.customData[ENGINE_KEY] = plugin
    }

    private class Attachment(
        val weapon: WeaponAPI,
        val slot: GlowSlot,
        val spritePath: String,
        val width: Float,
        val height: Float,
    )

    private class Plugin(private val engine: CombatEngineAPI) : BaseCombatLayeredRenderingPlugin(
        CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
    ) {

        private val attachments = LinkedHashMap<Pair<WeaponAPI, GlowSlot>, Attachment>()
        private var scanAcc = 0f

        override fun init(entity: CombatEntityAPI?) {
            super.init(entity)
            log.info("[ASTD] WeaponGlowLayer active")
        }

        override fun getActiveLayers(): EnumSet<CombatEngineLayers> =
            EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)

        override fun getRenderRadius(): Float = Float.MAX_VALUE

        override fun advance(amount: Float) {
            scanAcc += amount
            if (scanAcc >= SCAN_INTERVAL) {
                scanAcc = 0f
                scanWeapons()
            }

            val it = attachments.entries.iterator()
            while (it.hasNext()) {
                val weapon = it.next().value.weapon
                val ship = weapon.ship
                if (ship == null || !engine.isEntityInPlay(ship)) {
                    it.remove()
                }
            }
        }

        private fun scanWeapons() {
            for (ship in engine.ships) {
                if (ship.isHulk) continue
                for (weapon in ship.allWeapons) {
                    val weaponId = weapon.id ?: continue
                    for (slot in slotsFor(weaponId)) {
                        val key = weapon to slot
                        if (attachments.containsKey(key)) continue
                        val attachment = loadGlowSprite(weapon, weaponId, slot) ?: continue
                        attachments[key] = attachment
                    }
                }
            }
        }

        private fun slotsFor(weaponId: String): List<GlowSlot> {
            return GlowSlot.entries.filter { slot ->
                when (slot) {
                    GlowSlot.AMBIENT -> weaponId in AMBIENT_WEAPON_IDS
                    GlowSlot.CHARGE -> weaponId in CHARGE_WEAPON_IDS
                }
            }
        }

        /** 只挂载 preload 成功的路径（缺失贴图在 preload 已 WARN，此处直接跳过）。 */
        private fun loadGlowSprite(weapon: WeaponAPI, weaponId: String, slot: GlowSlot): Attachment? {
            val path = if (weapon.slot?.isHardpoint == true) {
                "graphics/weapons/${weaponId}${slot.hardpointSuffix}.png"
            } else {
                "graphics/weapons/${weaponId}${slot.turretSuffix}.png"
            }
            if (path !in loadedPaths) return null
            val sprite = Global.getSettings().getSprite(path)
            log.info("[ASTD] WeaponGlowLayer attached weapon=$weaponId slot=$slot path=$path weaponSlot=${weapon.slot?.id} hardpoint=${weapon.slot?.isHardpoint}")
            return Attachment(weapon, slot, path, sprite.width, sprite.height)
        }

        private var firstRenderLogged = false

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (layer != CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) return
            if (!firstRenderLogged) {
                firstRenderLogged = true
                log.info("[ASTD] WeaponGlowLayer first render, attachments=${attachments.size}")
            }
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT)
            try {
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
                for ((_, att) in attachments) {
                    val weapon = att.weapon
                    val ship = weapon.ship ?: continue
                    if (ship.isHulk) continue
                    val alpha = when (att.slot) {
                        GlowSlot.AMBIENT -> viewport.alphaMult
                        // 蓄能中＝充能条在推进且尚未开火；未开始/正在开火两态不显示蓄能层
                        GlowSlot.CHARGE -> {
                            if (weapon.isFiring || weapon.chargeLevel <= CHARGE_MIN) continue
                            weapon.chargeLevel * viewport.alphaMult
                        }
                    }

                    // SpriteAPI 为全局共享缓存实例，尺寸/混合状态可能被其他渲染方改写，须逐帧重取并重置
                    val sprite = Global.getSettings().getSprite(att.spritePath)
                    sprite.setAdditiveBlend()
                    sprite.color = Color(255, 255, 255, 255)
                    sprite.alphaMult = alpha
                    sprite.setSize(att.width, att.height)
                    sprite.angle = weapon.currAngle - 90f
                    sprite.renderAtCenter(weapon.location.x, weapon.location.y)
                }
            } finally {
                GL11.glPopAttrib()
            }
        }
    }
}
