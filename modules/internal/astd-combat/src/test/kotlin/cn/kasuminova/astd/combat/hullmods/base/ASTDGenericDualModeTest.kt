package cn.kasuminova.astd.combat.hullmods.base

import cn.kasuminova.astd.impl.combat.DualModeSettingsImpl
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipHullSpecAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.AfterTest
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
 * 3. 切换器缺失自动补装（[ensureDualModeSwitcherPresent]）：漏声明舰补装并自举到缺省载人模式；
 *    切换器在场不重复补装；拆即切在途（有模式状态、缺切换器）不抢先补装。
 *
 * 测试驱动方式：最小 FakeVariant 承载状态机触达的集合，未触达方法抛出（Fail Fast）。
 * Fake 本身不依赖 mock 框架；hullSpec / stats 等接口级协作者经 mockito 构造
 * （与模块内其它 hullmod 测试同例，构建约定统一走 mockito）。
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

    @Test
    fun `missing switcher is auto-installed and bootstraps to crewed mode`() {
        // 漏声明场景（曾经的 astd_zw_001_Standard / astd_xc_104_Standard）：无切换器、无模式状态
        val variant = FakeGenericVariant(astdHullSpec())
        assertTrue(
            variant.ensureDualModeSwitcherPresent(GENERIC_DUAL_MODE_CONFIG),
            "缺切换器且无模式状态：应执行补装",
        )
        assertTrue(variant.hasHullMod(GENERIC_DUAL_MODE_CONFIG.switcherId), "补装后切换器应在场")
        assertTrue(
            variant.permaMods.contains(ASTDDualModeGenericIds.MODE_CREWED),
            "补装后应自举到缺省载人模式",
        )
        assertTrue(
            variant.permaMods.contains(ASTDDualModeGenericIds.NEXT_CREWED),
            "载人模式应设同向 next marker",
        )
        assertFalse(variant.hasASTDDualModeAutomated(GENERIC_DUAL_MODE_CONFIG), "补装不得误入无人模式")
    }

    @Test
    fun `switcher bootstrap is no-op when switcher already present`() {
        val variant = FakeGenericVariant(astdHullSpec())
        variant.addMod(GENERIC_DUAL_MODE_CONFIG.switcherId)
        assertFalse(
            variant.ensureDualModeSwitcherPresent(GENERIC_DUAL_MODE_CONFIG),
            "切换器在场时不应补装",
        )
        assertTrue(variant.permaMods.isEmpty(), "切换器在场时不得触碰模式状态（自举归切换器 hullmod）")
    }

    @Test
    fun `switcher bootstrap does not preempt remove-to-switch flip`() {
        // 拆即切在途：模式状态在场、切换器刚被玩家拆下——补装会抢在 mode hullmod 翻转判定前加回切换器
        val variant = FakeGenericVariant(astdHullSpec())
        variant.addPermaMod(ASTDDualModeGenericIds.MODE_CREWED)
        variant.addPermaMod(ASTDDualModeGenericIds.NEXT_CREWED)
        assertFalse(
            variant.ensureDualModeSwitcherPresent(GENERIC_DUAL_MODE_CONFIG),
            "拆即切在途（有模式状态、缺切换器）不得补装",
        )
        assertFalse(
            variant.hasHullMod(GENERIC_DUAL_MODE_CONFIG.switcherId),
            "抢先补装会使 mode hullmod 的翻转永不触发",
        )
        assertFalse(variant.hasASTDDualModeAutomated(GENERIC_DUAL_MODE_CONFIG), "模式状态不得被改动")
    }

    @Test
    fun `switcher bootstrap stays stable across later hullmod applications`() {
        val variant = FakeGenericVariant(astdHullSpec())
        val stats = statsOf(variant)
        assertTrue(variant.ensureDualModeSwitcherPresent(GENERIC_DUAL_MODE_CONFIG))

        // 后续 stats 重建：切换器 hullmod 与载人 mode hullmod 同场结算，稳定态不得二次翻转
        ASTDDualModeSwitcherHullMod().applyEffectsBeforeShipCreation(
            ShipAPI.HullSize.CRUISER, stats, GENERIC_DUAL_MODE_CONFIG.switcherId,
        )
        ASTDCrewedModeHullMod().applyEffectsBeforeShipCreation(
            ShipAPI.HullSize.CRUISER, stats, GENERIC_DUAL_MODE_CONFIG.crewedModeId,
        )
        assertFalse(variant.hasASTDDualModeAutomated(GENERIC_DUAL_MODE_CONFIG), "稳定态不得被翻转为无人模式")
        assertTrue(
            variant.permaMods.contains(ASTDDualModeGenericIds.MODE_CREWED),
            "稳定态应保持载人模式",
        )
        assertTrue(variant.hasHullMod(GENERIC_DUAL_MODE_CONFIG.switcherId), "切换器应常驻")
    }

    @Test
    fun `remove-to-switch flip completes after bootstrap stays out of the way`() {
        val variant = FakeGenericVariant(astdHullSpec())
        val stats = statsOf(variant)
        // 拆即切现场：切换器在场 + 载人模式稳定态，玩家拆下切换器
        variant.addMod(GENERIC_DUAL_MODE_CONFIG.switcherId)
        variant.addPermaMod(ASTDDualModeGenericIds.MODE_CREWED)
        variant.addPermaMod(ASTDDualModeGenericIds.NEXT_CREWED)
        variant.removeMod(GENERIC_DUAL_MODE_CONFIG.switcherId)

        // 同一轮 stats 重建中补装判定先行（奇点能源先于 mode hullmod 结算的情形）：不得抢先加回
        assertFalse(
            variant.ensureDualModeSwitcherPresent(GENERIC_DUAL_MODE_CONFIG),
            "拆即切在途不得补装",
        )

        // 随后载人 mode hullmod 完成拆即切：翻转到无人模式并加回切换器
        ASTDCrewedModeHullMod().applyEffectsBeforeShipCreation(
            ShipAPI.HullSize.CRUISER, stats, GENERIC_DUAL_MODE_CONFIG.crewedModeId,
        )
        assertTrue(variant.hasASTDDualModeAutomated(GENERIC_DUAL_MODE_CONFIG), "拆即切应翻转到无人模式")
        assertTrue(variant.permaMods.contains("automated"), "无人模式应同步原版 automated 船插")
        assertTrue(
            variant.hasHullMod(GENERIC_DUAL_MODE_CONFIG.switcherId),
            "翻转后切换器应被加回（常驻）",
        )
    }

    @AfterTest
    fun clearExemptOverride() {
        DualModeSettingsImpl.installExemptForTests(null)
    }

    @Test
    fun `automated mode carries no_auto_penalty tag when exemption enabled`() {
        val variant = FakeGenericVariant()
        variant.activateDualMode(GENERIC_DUAL_MODE_CONFIG, GENERIC_DUAL_MODE_CONFIG.automatedModeId, null)
        assertTrue(
            variant.hasTag(NO_AUTO_PENALTY_TAG),
            "无人模式且免自动化点数选项默认开启：应挂 no_auto_penalty 标签",
        )
    }

    @Test
    fun `crewed mode never carries no_auto_penalty tag`() {
        val variant = FakeGenericVariant()
        variant.activateDualMode(GENERIC_DUAL_MODE_CONFIG, GENERIC_DUAL_MODE_CONFIG.automatedModeId, null)
        variant.activateDualMode(GENERIC_DUAL_MODE_CONFIG, GENERIC_DUAL_MODE_CONFIG.crewedModeId, null)
        assertFalse(variant.hasTag(NO_AUTO_PENALTY_TAG), "载人模式应移除 no_auto_penalty 标签")
    }

    @Test
    fun `syncDualModeAutoPenaltyTag converges tag when exemption toggled`() {
        val variant = FakeGenericVariant()
        variant.activateDualMode(GENERIC_DUAL_MODE_CONFIG, GENERIC_DUAL_MODE_CONFIG.automatedModeId, null)
        assertTrue(variant.hasTag(NO_AUTO_PENALTY_TAG), "前置：无人模式默认挂标签")

        // 选项热重载为关闭：下次 stats 刷新（ensureASTDDualModeState 内部调用本同步）收敛为无标签
        DualModeSettingsImpl.installExemptForTests(false)
        variant.syncDualModeAutoPenaltyTag(GENERIC_DUAL_MODE_CONFIG)
        assertFalse(variant.hasTag(NO_AUTO_PENALTY_TAG), "选项关闭后应在 stats 刷新收敛为无标签")

        // 选项恢复开启：收敛回有标签（幂等：重复同步不产生额外写操作）
        DualModeSettingsImpl.installExemptForTests(true)
        variant.syncDualModeAutoPenaltyTag(GENERIC_DUAL_MODE_CONFIG)
        variant.syncDualModeAutoPenaltyTag(GENERIC_DUAL_MODE_CONFIG)
        assertTrue(variant.hasTag(NO_AUTO_PENALTY_TAG), "选项重新开启后应在 stats 刷新收敛回有标签")
    }
}

/** 构造带 astd_ 舰体 id 的 hullSpec mock：仅 stub hullId（[isASTDShipVariant] 只读 hullId/baseHullId）。 */
private fun astdHullSpec(hullId: String = "astd_zw_001"): ShipHullSpecAPI {
    val spec = mock(ShipHullSpecAPI::class.java)
    `when`(spec.hullId).thenReturn(hullId)
    return spec
}

/** 构造只持有指定 variant 的 stats mock（fleetMember 未 stub 返回 null：不触发战役侧身份门分支）。 */
private fun statsOf(variant: ShipVariantAPI): MutableShipStatsAPI {
    val stats = mock(MutableShipStatsAPI::class.java)
    `when`(stats.variant).thenReturn(variant)
    return stats
}

/**
 * 最小 ShipVariantAPI 假实现：仅承载状态机触达的 permaMods / hullMods / tags 集合。
 * getHullSpec 默认返回 null（[activateDualMode] / [hasASTDDualModeAutomated] 不读 hullSpec）；
 * 需要经过 [isASTDShipVariant] 判定的路径（如 [ensureDualModeSwitcherPresent] 内部的
 * [ensureASTDDualModeState] 自举）经构造参数注入 [astdHullSpec]。
 * 其余方法抛 [notUsed]，确保状态机若触达预期外接口立即失败（Fail Fast）。Fake 本身不依赖 mock 框架。
 */
private class FakeGenericVariant(
    private val hullSpec: ShipHullSpecAPI? = null,
) : ShipVariantAPI {

    private val perma = linkedSetOf<String>()
    private val mods = linkedSetOf<String>()
    private val tags = linkedSetOf<String>()

    override fun getHullSpec(): ShipHullSpecAPI? = hullSpec
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
    override fun hasTag(p0: String): Boolean = tags.contains(p0)
    override fun addTag(p0: String) {
        tags.add(p0)
    }

    override fun removeTag(p0: String) {
        tags.remove(p0)
    }

    override fun getTags(): MutableCollection<String> = tags
    override fun clearTags() {
        tags.clear()
    }
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
