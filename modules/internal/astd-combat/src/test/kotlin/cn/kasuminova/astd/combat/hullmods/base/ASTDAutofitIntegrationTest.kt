package cn.kasuminova.astd.combat.hullmods.base

import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.plugins.AutofitPlugin.AutofitOption
import com.fs.starfarer.api.plugins.AutofitPlugin.AutofitPluginDelegate
import com.fs.starfarer.api.plugins.impl.CoreAutofitPlugin
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

class ASTDAutofitIntegrationTest {

    @Test
    fun `组合插件共享选项并接管装配与费用`() {
        val combined = RecordingAutofitPlugin()
        val plugin = ASTDAutofitPlugin(null, combined)
        val option = combined.options.single { it.id == ShipMasteryOptionId }

        assertSame(option, plugin.options.single { it.id == ShipMasteryOptionId })
        plugin.setChecked(ShipMasteryOptionId, true)
        assertTrue(combined.isChecked(ShipMasteryOptionId))
        assertEquals(123, plugin.creditCost)

        val random = Random(7L)
        plugin.setRandom(random)
        assertSame(random, combined.receivedRandom)

        val current = mock(ShipVariantAPI::class.java)
        val target = mock(ShipVariantAPI::class.java)
        val delegate = mock(AutofitPluginDelegate::class.java)
        plugin.doFit(current, target, 0, delegate)

        assertEquals(listOf(FitCall(current, target, 0, delegate)), combined.fitCalls)
    }

    private class RecordingAutofitPlugin : CoreAutofitPlugin(null) {
        val fitCalls = mutableListOf<FitCall>()
        var receivedRandom: Random? = null

        init {
            options.add(AutofitOption(ShipMasteryOptionId, "装配包含 S-插件", false, ""))
        }

        override fun getCreditCost(): Int = 123

        override fun setRandom(random: Random) {
            super.setRandom(random)
            receivedRandom = random
        }

        override fun doFit(
            current: ShipVariantAPI,
            target: ShipVariantAPI,
            maxSMods: Int,
            delegate: AutofitPluginDelegate,
        ) {
            fitCalls += FitCall(current, target, maxSMods, delegate)
        }
    }

    private data class FitCall(
        val current: ShipVariantAPI,
        val target: ShipVariantAPI,
        val maxSMods: Int,
        val delegate: AutofitPluginDelegate,
    )

    private companion object {
        const val ShipMasteryOptionId = "sms_copy_s_mods"
    }
}
