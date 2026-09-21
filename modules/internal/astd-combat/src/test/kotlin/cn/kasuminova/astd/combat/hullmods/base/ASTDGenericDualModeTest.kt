package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.combat.ShipHullSpecAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 通用（舰船无关）双模式验证：
 * 1. 注册表兜底——未显式注册的 ASTD 舰（如 astd_xc_101）经 [ASTDDualModeRegistry.genericConfigFor]
 *    反查到 [GENERIC_DUAL_MODE_CONFIG]，非 ASTD 舰不兜底。
 * 2. 通用配置上的「拆即切」状态机语义与专属舰一致（模式翻转 + 原版 automated 同步 + 同向 next marker）。
 *
 * 测试驱动方式与 ASTDXc001DualModeSwitchTest 相同：最小 FakeVariant 承载状态机触达的集合，
 * 未触达方法抛出（Fail Fast），不使用反射 / mock 框架。
 */
class ASTDGenericDualModeTest {

    @Test
    fun `generic fallback resolves for unregistered astd hulls only`() {
        assertSame(
            GENERIC_DUAL_MODE_CONFIG,
            ASTDDualModeRegistry.genericConfigFor("astd_xc_101"),
            "未注册的 ASTD 舰应兜底到通用双模式配置",
        )
        assertSame(
            GENERIC_DUAL_MODE_CONFIG,
            ASTDDualModeRegistry.genericConfigFor("astd_zw_102"),
            "未注册的 ASTD 舰（其它设计系）同样兜底",
        )
        assertNull(ASTDDualModeRegistry.genericConfigFor("wolf"), "非 ASTD 舰不得兜底")
        assertNull(ASTDDualModeRegistry.genericConfigFor(null), "空 hullId 不得兜底")
    }

    @Test
    fun `registered ships keep their explicit config instead of generic fallback`() {
        assertSame(
            GENERIC_DUAL_MODE_CONFIG.crewedSystemId,
            null,
            "通用配置不应声明模式分版系统（前置：无系统互换）",
        )
        // 专属舰的显式注册仍优先于兜底（configFor 只查显式注册表）
        assertNull(ASTDDualModeRegistry.configFor("astd_xc_101"), "未注册舰在显式注册表中应为空")
    }

    @Test
    fun `generic config switcher removal toggles crewed and automated`() {
        val variant = FakeGenericVariant()
        // 初始稳定态：通用载人模式 + 切换器在位
        variant.addPermaMod(ASTDDualModeGenericIds.MODE_CREWED)
        variant.addPermaMod(ASTDDualModeGenericIds.NEXT_CREWED)
        variant.addMod(GENERIC_DUAL_MODE_CONFIG.switcherId)
        assertFalse(variant.hasASTDDualModeAutomated(GENERIC_DUAL_MODE_CONFIG), "前置：应处于载人模式")

        // 第一次拆：载人 → 无人
        variant.removeMod(GENERIC_DUAL_MODE_CONFIG.switcherId)
        variant.activateDualMode(GENERIC_DUAL_MODE_CONFIG, GENERIC_DUAL_MODE_CONFIG.automatedModeId, null)
        variant.addMod(GENERIC_DUAL_MODE_CONFIG.switcherId)
        assertTrue(variant.hasASTDDualModeAutomated(GENERIC_DUAL_MODE_CONFIG), "拆即切后应翻转到无人模式")
        assertTrue(variant.permaMods.contains("automated"), "无人模式应同步原版 automated 船插")
        assertTrue(
            variant.permaMods.contains(ASTDDualModeGenericIds.NEXT_AUTOMATED),
            "无人模式应设同向 next marker",
        )
        assertTrue(variant.hasHullMod(GENERIC_DUAL_MODE_CONFIG.switcherId), "拆即切后切换器应被加回（常驻）")

        // 第二次拆：无人 → 载人
        variant.removeMod(GENERIC_DUAL_MODE_CONFIG.switcherId)
        variant.activateDualMode(GENERIC_DUAL_MODE_CONFIG, GENERIC_DUAL_MODE_CONFIG.crewedModeId, null)
        variant.addMod(GENERIC_DUAL_MODE_CONFIG.switcherId)
        assertFalse(variant.hasASTDDualModeAutomated(GENERIC_DUAL_MODE_CONFIG), "再次拆即切应翻转回载人模式")
        assertFalse(variant.permaMods.contains("automated"), "载人模式应移除原版 automated 船插")
        assertTrue(
            variant.permaMods.contains(ASTDDualModeGenericIds.NEXT_CREWED),
            "载人模式应设同向 next marker",
        )
    }
}

/**
 * 最小 ShipVariantAPI 假实现：仅承载状态机触达的 permaMods / hullMods 集合。
 * getHullSpec 返回 null（被测的 [activateDualMode] / [hasASTDDualModeAutomated] 不读 hullSpec）。
 * 其余方法抛 [notUsed]，确保状态机若触达预期外接口立即失败（Fail Fast）。不使用反射 / mock 框架。
 */
private class FakeGenericVariant : ShipVariantAPI {

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
        throw UnsupportedOperationException("FakeGenericVariant: method not expected to be touched by dual-mode state machine")

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
