package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.combat.ShipHullSpecAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 自动装配双模式恢复决策（[ASTDAutofitPlugin.resolveAutofitGoalMode]）验证：
 * 1. 目标方案声明了模式 → 遵守方案模式（即使与装配前不同）；
 * 2. 目标方案未携带模式 → 保持装配前模式（装配不得翻转模式）；
 * 3. 非双模式舰 / 无配置 → 不干预（null）。
 *
 * 测试驱动方式与 ASTDGenericDualModeTest 相同：最小 FakeVariant 承载 permaMods 集合，
 * 未触达方法抛出（Fail Fast），不使用反射 / mock 框架。
 */
class ASTDAutofitGoalModeTest {

    private val config = GENERIC_DUAL_MODE_CONFIG

    @Test
    fun `goal variant mode wins over pre-fit mode`() {
        val current = FakeAutofitVariant().apply { addPermaMod(config.crewedModeId) }
        val target = FakeAutofitVariant().apply { addPermaMod(config.automatedModeId) }
        assertEquals(
            config.automatedModeId,
            ASTDAutofitPlugin.resolveAutofitGoalMode(current, target, config),
            "方案保存为无人模式时，装配后应切到无人模式",
        )
    }

    @Test
    fun `pre-fit mode is kept when goal variant carries no mode`() {
        val current = FakeAutofitVariant().apply { addPermaMod(config.automatedModeId) }
        val target = FakeAutofitVariant()
        assertEquals(
            config.automatedModeId,
            ASTDAutofitPlugin.resolveAutofitGoalMode(current, target, config),
            "方案未携带模式状态时应保持装配前模式",
        )
    }

    @Test
    fun `null when neither side has mode state or config missing`() {
        val current = FakeAutofitVariant()
        val target = FakeAutofitVariant()
        assertNull(
            ASTDAutofitPlugin.resolveAutofitGoalMode(current, target, config),
            "双方都无模式状态时不干预",
        )
        assertNull(
            ASTDAutofitPlugin.resolveAutofitGoalMode(current, target, null),
            "非双模式舰（无配置）不干预",
        )
    }

    @Test
    fun `active mode id reads perma mods only`() {
        val variant = FakeAutofitVariant()
        assertNull(ASTDAutofitPlugin.activeDualModeId(variant, config), "无模式 permaMod 时应为 null")
        variant.addPermaMod(config.crewedModeId)
        assertEquals(config.crewedModeId, ASTDAutofitPlugin.activeDualModeId(variant, config))
        // 普通 hullMod 形式的模式标记不应被当作激活模式（稳定态以 permaMod 为准）
        val legacy = FakeAutofitVariant().apply { addMod(config.automatedModeId) }
        assertNull(ASTDAutofitPlugin.activeDualModeId(legacy, config), "非 permaMod 的模式标记不应计入")
    }
}

/**
 * 最小 ShipVariantAPI 假实现：仅承载恢复决策触达的 permaMods / hullMods 集合。
 * 其余方法抛 [notUsed]（Fail Fast），不使用反射 / mock 框架。
 */
private class FakeAutofitVariant : ShipVariantAPI {

    private val perma = linkedSetOf<String>()
    private val mods = linkedSetOf<String>()

    override fun getHullSpec(): ShipHullSpecAPI? = null
    override fun getPermaMods(): MutableSet<String> = perma
    override fun addPermaMod(id: String) {
        perma.add(id)
    }

    override fun addPermaMod(id: String, p1: Boolean) {
        perma.add(id)
    }

    override fun removePermaMod(id: String) {
        perma.remove(id)
    }

    override fun clearPermaMods() {
        perma.clear()
    }

    override fun addMod(id: String) {
        mods.add(id)
    }

    override fun removeMod(id: String) {
        mods.remove(id)
    }

    override fun hasHullMod(id: String): Boolean = mods.contains(id) || perma.contains(id)

    private fun notUsed(): Nothing =
        throw UnsupportedOperationException("FakeAutofitVariant: method not expected to be touched by autofit goal mode resolution")

    override fun clone(): ShipVariantAPI = notUsed()
    override fun getDisplayName(): String = notUsed()
    override fun getDesignation(): String = notUsed()
    override fun getHullMods(): MutableCollection<String> = notUsed()
    override fun clearHullMods() = notUsed()
    override fun getHints() = notUsed()
    override fun addWeapon(p0: String, p1: String) = notUsed()
    override fun getNumFluxVents(): Int = notUsed()
    override fun getNumFluxCapacitors(): Int = notUsed()
    override fun getNonBuiltInWeaponSlots(): MutableList<String> = notUsed()
    override fun getWeaponId(p0: String): String = notUsed()
    override fun setNumFluxCapacitors(p0: Int) = notUsed()
    override fun setNumFluxVents(p0: Int) = notUsed()
    override fun setSource(p0: com.fs.starfarer.api.loading.VariantSource?) = notUsed()
    override fun clearSlot(p0: String) = notUsed()
    override fun getWeaponSpec(p0: String) = notUsed()
    override fun getFittedWeaponSlots(): MutableCollection<String> = notUsed()
    override fun autoGenerateWeaponGroups() = notUsed()
    override fun hasUnassignedWeapons(): Boolean = notUsed()
    override fun assignUnassignedWeapons() = notUsed()
    override fun getGroup(p0: Int) = notUsed()
    override fun computeOPCost(p0: com.fs.starfarer.api.characters.MutableCharacterStatsAPI?): Int = notUsed()
    override fun computeWeaponOPCost(p0: com.fs.starfarer.api.characters.MutableCharacterStatsAPI?): Int = notUsed()
    override fun computeHullModOPCost(): Int = notUsed()
    override fun computeHullModOPCost(p0: com.fs.starfarer.api.characters.MutableCharacterStatsAPI?): Int = notUsed()
    override fun getSource() = notUsed()
    override fun isStockVariant(): Boolean = notUsed()
    override fun isEmptyHullVariant(): Boolean = notUsed()
    override fun setHullVariantId(p0: String) = notUsed()
    override fun getHullVariantId(): String = notUsed()
    override fun getWeaponGroups(): MutableList<com.fs.starfarer.api.loading.WeaponGroupSpec> = notUsed()
    override fun addWeaponGroup(p0: com.fs.starfarer.api.loading.WeaponGroupSpec?) = notUsed()
    override fun setVariantDisplayName(p0: String) = notUsed()
    override fun getHullSize() = notUsed()
    override fun isFighter(): Boolean = notUsed()
    override fun getFullDesignationWithHullName(): String = notUsed()
    override fun getSlot(p0: String) = notUsed()
    override fun isCombat(): Boolean = notUsed()
    override fun isStation(): Boolean = notUsed()
    override fun getWingId(p0: Int): String = notUsed()
    override fun setWingId(p0: Int, p1: String) = notUsed()
    override fun getWings(): MutableList<String> = notUsed()
    override fun getLaunchBaysSlotIds(): MutableList<String> = notUsed()
    override fun getFittedWings(): MutableList<String> = notUsed()
    override fun setHullSpecAPI(p0: ShipHullSpecAPI?) = notUsed()
    override fun isCarrier(): Boolean = notUsed()
    override fun getSortedMods(): MutableList<String> = notUsed()
    override fun getSuppressedMods(): MutableSet<String> = notUsed()
    override fun addSuppressedMod(p0: String) = notUsed()
    override fun removeSuppressedMod(p0: String) = notUsed()
    override fun clearSuppressedMods() = notUsed()
    override fun isGoalVariant(): Boolean = notUsed()
    override fun setGoalVariant(p0: Boolean) = notUsed()
    override fun getNonBuiltInHullmods(): MutableCollection<String> = notUsed()
    override fun getWing(p0: Int) = notUsed()
    override fun getUnusedOP(p0: com.fs.starfarer.api.characters.MutableCharacterStatsAPI?): Int = notUsed()
    override fun isCivilian(): Boolean = notUsed()
    override fun getModuleSlots(): MutableList<String> = notUsed()
    override fun getStatsForOpCosts() = notUsed()
    override fun isLiner(): Boolean = notUsed()
    override fun isFreighter(): Boolean = notUsed()
    override fun isTanker(): Boolean = notUsed()
    override fun isDHull(): Boolean = notUsed()
    override fun getStationModules(): MutableMap<String, String> = notUsed()
    override fun getNonBuiltInWings(): MutableList<String> = notUsed()
    override fun hasTag(p0: String): Boolean = notUsed()
    override fun addTag(p0: String) = notUsed()
    override fun removeTag(p0: String) = notUsed()
    override fun getTags(): MutableCollection<String> = notUsed()
    override fun clearTags() = notUsed()
    override fun clear() = notUsed()
    override fun getOriginalVariant(): String = notUsed()
    override fun setOriginalVariant(p0: String) = notUsed()
    override fun getModuleVariant(p0: String): ShipVariantAPI = notUsed()
    override fun setModuleVariant(p0: String, p1: ShipVariantAPI?) = notUsed()
    override fun isTransport(): Boolean = notUsed()
    override fun getVariantFilePath(): String = notUsed()
    override fun getSMods(): LinkedHashSet<String> = notUsed()
    override fun getFullDesignationWithHullNameForShip(): String = notUsed()
    override fun refreshBuiltInWings() = notUsed()
    override fun hasDMods(): Boolean = notUsed()
    override fun getSModdedBuiltIns(): LinkedHashSet<String> = notUsed()
    override fun isMayAutoAssignWeapons(): Boolean = notUsed()
    override fun setMayAutoAssignWeapons(p0: Boolean) = notUsed()
    override fun getFullDesignationForShip(): String = notUsed()
    override fun toJSONObject() = notUsed()
}
