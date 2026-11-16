package cn.kasuminova.astd.combat.description

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.loading.Description
import com.fs.starfarer.api.loading.WeaponSpecAPI

object DifficultyDescriptions {
    private data class Scales(override val playerFixedScale: Float, override val fixedScale: Float) : DifficultyTuning {
        override fun value(entry: ScalingEntry): Float = entry.map.value(fixedScale, entry.v1, entry.v2, entry.v5)
    }

    private data class WeaponTarget(val id: String, val spec: WeaponSpecAPI, val original: WeaponDescriptionText)
    private data class SystemTarget(val id: String, val description: Description, val original: ShipSystemDescriptionText)
    private data class Updates(
        val weapons: List<Pair<WeaponTarget, WeaponDescriptionText>>,
        val systems: List<Pair<SystemTarget, ShipSystemDescriptionText>>,
    )

    private val text = DescriptionText { key -> I18n.requiredRaw(I18n.Categories.MOD, key) }
    private var weapons: List<WeaponTarget> = emptyList()
    private var systems: List<SystemTarget> = emptyList()
    private var initialized = false
    private var appliedScales: Scales? = null

    fun initialize() {
        if (initialized) {
            refresh()
            return
        }
        val weaponIds = registeredIds("tooltip.weapon.ids")
        val systemIds = registeredIds("tooltip.system.ids")
        require(weaponIds == WeaponDescriptionParameters.ids) { "Weapon description registrations differ: templates=$weaponIds providers=${WeaponDescriptionParameters.ids}" }
        require(systemIds == ShipSystemDescriptionParameters.ids) { "System description registrations differ: templates=$systemIds providers=${ShipSystemDescriptionParameters.ids}" }
        val settings = Global.getSettings()
        weapons = weaponIds.map { id ->
            val spec = settings.getWeaponSpec(id)
            WeaponTarget(id, spec, WeaponDescriptionText(spec.customPrimary, spec.customPrimaryHL, spec.customAncillary, spec.customAncillaryHL))
        }
        systems = systemIds.map { id ->
            val description = settings.getDescription(id, Description.Type.SHIP_SYSTEM)
            require(description === settings.getDescription(id, Description.Type.SHIP_SYSTEM)) { "Unregistered system description: $id" }
            SystemTarget(id, description, ShipSystemDescriptionText(description.text1, description.text3, description.text4, description.text5))
        }
        initialized = true
        refresh()
    }

    fun refreshIfChanged() {
        require(initialized) { "Difficulty descriptions have not been initialized" }
        val scales = currentScales()
        if (scales != appliedScales) apply(scales)
    }

    fun refresh() {
        require(initialized) { "Difficulty descriptions have not been initialized" }
        apply(currentScales())
    }

    /** 开发探针在游戏线程调用；只检查默认模板与计算结果，不修改难度或描述。 */
    fun validateBaseline(): List<String> {
        require(initialized) { "Difficulty descriptions have not been initialized" }
        val baseline = Scales(2f, 2f)
        return buildList {
            weapons.forEach { target ->
                val values = WeaponDescriptionParameters.resolve(target.id, baseline, true)
                checkDefaults("weapon", target.id, values, this)
                val generated = text.weaponBase(target.id, values, target.original)
                val original = target.original.copy(
                    primaryHighlights = target.original.primaryHighlights?.takeIf(String::isNotEmpty),
                    ancillaryHighlights = target.original.ancillaryHighlights?.takeIf(String::isNotEmpty),
                )
                if (generated != original) add("weapon.${target.id}: CSV=$original; calculated=$generated")
            }
            systems.forEach { target ->
                val values = ShipSystemDescriptionParameters.resolve(target.id, baseline, true)
                checkDefaults("system", target.id, values, this)
                val generated = text.systemBase(target.id, values)
                val original = target.original.copy(text4 = target.original.text4?.takeIf(String::isNotEmpty), text5 = target.original.text5?.takeIf(String::isNotEmpty))
                if (generated != original) add("system.${target.id}: CSV=$original; calculated=$generated")
            }
        }
    }

    /** 开发探针在游戏线程调用；验证共享规格与当前难度重新生成的文本一致。 */
    fun validateApplied(): List<String> {
        require(initialized) { "Difficulty descriptions have not been initialized" }
        val expected = prepare(currentScales())
        return buildList {
            expected.weapons.forEach { (target, value) ->
                val spec = target.spec
                val actual = WeaponDescriptionText(spec.customPrimary, spec.customPrimaryHL, spec.customAncillary, spec.customAncillaryHL)
                if (actual != value) add("weapon.${target.id}: expected=$value actual=$actual")
            }
            expected.systems.forEach { (target, value) ->
                val desc = target.description
                val actual = ShipSystemDescriptionText(desc.text1, desc.text3, desc.text4, desc.text5)
                if (actual != value) add("system.${target.id}: expected=$value actual=$actual")
            }
        }
    }

    fun weaponIds(): Set<String> = WeaponDescriptionParameters.ids
    fun systemIds(): Set<String> = ShipSystemDescriptionParameters.ids

    private fun prepare(scales: Scales): Updates = Updates(
        weapons.map { target ->
            val player = WeaponDescriptionParameters.resolve(target.id, scales, true)
            target to text.weaponBase(target.id, player, target.original)
        },
        systems.map { target ->
            val player = ShipSystemDescriptionParameters.resolve(target.id, scales, true)
            target to text.systemBase(target.id, player)
        },
    )

    private fun apply(scales: Scales) {
        val updates = try {
            prepare(scales)
        } catch (failure: RuntimeException) {
            AstdLog.logger.error("[ASTD] Difficulty description update failed: player=${scales.playerFixedScale}, enemy=${scales.fixedScale}", failure)
            throw failure
        }
        updates.weapons.forEach { (target, value) ->
            target.spec.customPrimary = value.primary
            target.spec.customPrimaryHL = value.primaryHighlights
            target.spec.customAncillary = value.ancillary
            target.spec.customAncillaryHL = value.ancillaryHighlights
        }
        updates.systems.forEach { (target, value) ->
            target.description.text1 = value.text1
            target.description.text3 = value.text3
            target.description.text4 = value.text4
            target.description.text5 = value.text5
        }
        appliedScales = scales
        AstdLog.logger.info("[ASTD] Updated difficulty descriptions: player=${scales.playerFixedScale}, enemy=${scales.fixedScale}, weapons=${weapons.size}, systems=${systems.size}")
    }

    private fun checkDefaults(kind: String, id: String, values: Map<String, String>, failures: MutableList<String>) {
        values.forEach { (key, value) ->
            val expected = I18n.requiredRaw(I18n.Categories.MOD, "tooltip.$kind.$id.default.$key")
            if (value != expected) failures += "$kind.$id.$key: declared=$expected calculated=$value"
        }
    }

    private fun registeredIds(key: String): Set<String> {
        val ids = I18n.requiredRaw(I18n.Categories.MOD, key).split(',').map(String::trim)
        require(ids.all(String::isNotEmpty) && ids.size == ids.toSet().size) { "Invalid description registration: $key" }
        return ids.toSet()
    }

    private fun currentScales(): Scales = Scales(DifficultyTuningImpl.playerFixedScale, DifficultyTuningImpl.fixedScale)
}
