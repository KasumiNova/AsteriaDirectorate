package cn.kasuminova.astd.campaign.items

import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.campaign.econ.SubmarketAPI
import kotlin.test.Test
import kotlin.test.assertEquals

internal class EchoCoreItemPluginTest {

    @Test
    fun `echo core price tolerates null market and submarket during storage value calculation`() {
        val getPrice = EchoCoreItemPlugin::class.java.getMethod(
            "getPrice",
            MarketAPI::class.java,
            SubmarketAPI::class.java,
        )

        assertEquals(0, getPrice.invoke(EchoCoreItemPlugin(), null, null))
    }
}
