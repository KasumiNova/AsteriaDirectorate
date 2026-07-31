package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDDualModeRegistry
import cn.kasuminova.astd.combat.hullmods.base.activateDualMode
import cn.kasuminova.astd.combat.hullmods.base.hasASTDDualModeAutomated
import com.fs.starfarer.api.combat.ShipHullSpecAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * arc_flare 双模式「拆即切」核心验证（Task 5：arc 从自有状态机迁移到通用框架后行为零回归）。
 *
 * 验证目标（跑真实生产状态机逻辑，非 source-contains）：
 * 1. arc config 经 [registerArcFlareDualModeConfig]（onApplicationLoad 调用）注册后，
 *    通用注册表能反查到——通用切换器 tooltip 依赖此反查才能显示「当前/目标模式」。
 * 2. 「切换器被拆下 → mode hullmod 切到对面模式 + 把切换器加回」这一拆即切语义：
 *    在真实 [activateDualMode]（arc mode hullmod 在 applyEffectsBeforeShipCreation 实际调用的函数）上
 *    跑出模式翻转 + 切换器恢复 + 原版 automated 同步，与迁移前 arc 自有 activateMode 行为逐字段一致。
 *
 * 测试驱动：用最小 [FakeArcVariant] 承载状态机真正触达的 permaMods / hullMods 集合。
 * 未触达的方法抛出，确保状态机若依赖预期外接口立即失败（Fail Fast）。不使用反射、不使用 mock 框架。
 */
class ASTDArcFlareDualModeSwitchTest {

    @Test
    fun `registration makes arc config queryable for switcher tooltip`() {
        registerArcFlareDualModeConfig()
        assertSame(
            ARC_FLARE_DUAL_MODE_CONFIG,
            ASTDDualModeRegistry.configFor(ASTDArcFlareHullModIds.HULL_ID),
            "通用切换器 tooltip 需 configFor(arc hullId) 反查到 arc config",
        )
    }

    @Test
    fun `removing switcher in crewed mode flips to automated and restores switcher`() {
        val variant = FakeArcVariant()
        // 初始稳定态：载人模式 + 切换器在位
        variant.addPermaMod(ASTDArcFlareHullModIds.MODE_CREWED)
        variant.addPermaMod(ASTDArcFlareHullModIds.NEXT_CREWED)
        variant.addMod(ARC_FLARE_DUAL_MODE_CONFIG.switcherId)
        assertFalse(variant.hasASTDDualModeAutomated(ARC_FLARE_DUAL_MODE_CONFIG), "前置：应处于载人模式")

        // 复现载人 mode hullmod 的拆即切：玩家拆下切换器 → 切到无人 + 加回切换器
        variant.removeMod(ARC_FLARE_DUAL_MODE_CONFIG.switcherId)
        assertFalse(variant.hasHullMod(ARC_FLARE_DUAL_MODE_CONFIG.switcherId), "前置：切换器已被拆下")
        variant.activateDualMode(ARC_FLARE_DUAL_MODE_CONFIG, ARC_FLARE_DUAL_MODE_CONFIG.automatedModeId, null)
        variant.addMod(ARC_FLARE_DUAL_MODE_CONFIG.switcherId)

        assertTrue(variant.hasASTDDualModeAutomated(ARC_FLARE_DUAL_MODE_CONFIG), "拆即切后应翻转到无人模式")
        assertFalse(
            variant.permaMods.contains(ASTDArcFlareHullModIds.MODE_CREWED),
            "无人模式下不应再保留载人 mode permaMod",
        )
        assertTrue(variant.permaMods.contains("automated"), "无人模式应同步原版 automated 船插")
        assertTrue(
            variant.permaMods.contains(ASTDArcFlareHullModIds.NEXT_AUTOMATED),
            "无人模式应设同向 next marker",
        )
        assertTrue(variant.hasHullMod(ARC_FLARE_DUAL_MODE_CONFIG.switcherId), "拆即切后切换器应被加回（常驻）")
    }

    @Test
    fun `removing switcher in automated mode flips to crewed and restores switcher`() {
        val variant = FakeArcVariant()
        // 初始稳定态：无人模式 + 切换器在位
        variant.addPermaMod(ASTDArcFlareHullModIds.MODE_AUTOMATED)
        variant.addPermaMod("automated")
        variant.addPermaMod(ASTDArcFlareHullModIds.NEXT_AUTOMATED)
        variant.addMod(ARC_FLARE_DUAL_MODE_CONFIG.switcherId)
        assertTrue(variant.hasASTDDualModeAutomated(ARC_FLARE_DUAL_MODE_CONFIG), "前置：应处于无人模式")

        // 复现无人 mode hullmod 的拆即切：玩家拆下切换器 → 切到载人 + 加回切换器
        variant.removeMod(ARC_FLARE_DUAL_MODE_CONFIG.switcherId)
        variant.activateDualMode(ARC_FLARE_DUAL_MODE_CONFIG, ARC_FLARE_DUAL_MODE_CONFIG.crewedModeId, null)
        variant.addMod(ARC_FLARE_DUAL_MODE_CONFIG.switcherId)

        assertFalse(variant.hasASTDDualModeAutomated(ARC_FLARE_DUAL_MODE_CONFIG), "拆即切后应翻转到载人模式")
        assertFalse(variant.permaMods.contains("automated"), "载人模式应移除原版 automated 船插")
        assertTrue(
            variant.permaMods.contains(ASTDArcFlareHullModIds.NEXT_CREWED),
            "载人模式应设同向 next marker",
        )
        assertTrue(variant.hasHullMod(ARC_FLARE_DUAL_MODE_CONFIG.switcherId), "拆即切后切换器应被加回（常驻）")
    }

    @Test
    fun `repeated switcher removal toggles back and forth`() {
        val variant = FakeArcVariant()
        variant.addPermaMod(ASTDArcFlareHullModIds.MODE_CREWED)
        variant.addMod(ARC_FLARE_DUAL_MODE_CONFIG.switcherId)

        // 第一次拆：载人 → 无人
        variant.activateDualMode(ARC_FLARE_DUAL_MODE_CONFIG, ARC_FLARE_DUAL_MODE_CONFIG.automatedModeId, null)
        assertTrue(variant.hasASTDDualModeAutomated(ARC_FLARE_DUAL_MODE_CONFIG))
        // 第二次拆：无人 → 载人
        variant.activateDualMode(ARC_FLARE_DUAL_MODE_CONFIG, ARC_FLARE_DUAL_MODE_CONFIG.crewedModeId, null)
        assertFalse(variant.hasASTDDualModeAutomated(ARC_FLARE_DUAL_MODE_CONFIG))
        // 第三次拆：载人 → 无人（确认可反复轮换，不卡死）
        variant.activateDualMode(ARC_FLARE_DUAL_MODE_CONFIG, ARC_FLARE_DUAL_MODE_CONFIG.automatedModeId, null)
        assertTrue(variant.hasASTDDualModeAutomated(ARC_FLARE_DUAL_MODE_CONFIG))
    }
}

/**
 * 最小 ShipVariantAPI 假实现：仅承载状态机触达的 permaMods / hullMods 集合。
 * getHullSpec 返回 null（被测的 [activateDualMode] / [hasASTDDualModeAutomated] 不读 hullSpec）。
 * 其余方法抛 [notUsed]，确保状态机若触达预期外接口立即失败（Fail Fast）。不使用反射 / mock 框架。
 */
private class FakeArcVariant : ShipVariantAPI {

    private val perma = linkedSetOf<String>()
    private val mods = linkedSetOf<String>()

    override fun getHullSpec(): ShipHullSpecAPI? = null
    override fun getPermaMods(): MutableSet<String> = perma
    override fun addPermaMod(id: String) { perma.add(id) }
    override fun addPermaMod(id: String, p1: Boolean) { perma.add(id) }
    override fun removePermaMod(id: String) { perma.remove(id) }
    override fun clearPermaMods() { perma.clear() }
    override fun addMod(id: String) { mods.add(id) }
    override fun removeMod(id: String) { mods.remove(id) }
    override fun hasHullMod(id: String): Boolean = mods.contains(id) || perma.contains(id)

    private fun notUsed(): Nothing =
        throw UnsupportedOperationException("FakeArcVariant: method not expected to be touched by dual-mode state machine")

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
