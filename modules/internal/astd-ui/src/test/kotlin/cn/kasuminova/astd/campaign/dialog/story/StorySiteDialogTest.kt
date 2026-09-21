package cn.kasuminova.astd.campaign.dialog.story

import cn.kasuminova.astd.campaign.dialog.DialogTestRig
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorySiteDialogTest {

    private class StubSiteBackend(
        var state: StorySiteDialog.SiteOrderState = StorySiteDialog.SiteOrderState.ACTIVE,
        var assets: List<String> = listOf("astd_asset_design_prototype"),
        var recoverResult: Boolean = true,
    ) : StorySiteDialog.StorySiteBackend {
        val recovered = mutableListOf<String>()
        override fun orderState(): StorySiteDialog.SiteOrderState = state
        override fun recoverableAssets(): List<String> = assets
        override fun recoverAsset(itemId: String): Boolean {
            if (recoverResult) recovered += itemId
            return recoverResult
        }
    }

    @Test
    fun `describe plays state text then menu offers recover and leave`() {
        val backend = StubSiteBackend()
        val rig = DialogTestRig()
        val plugin = StorySiteDialog.createPlugin("starfall_main", backend)
        plugin.init(rig.dialog)

        // Escape=leave 注入
        verify(rig.dialog, times(1)).setOptionOnEscape(anyString(), eq("leave"))

        // describe 播完自动进 menu（队列自然播完，不点跳过）
        assertTrue(rig.awaitOption(plugin, "recover_"), "describe 播完应出现回收选项；当前=${rig.shownIds()}")
        assertTrue(
            rig.paras.any { it.contains("story.site.starfall_main.state.active") },
            "执行中工单应播 active 态文本；实际=${rig.paras}",
        )

        assertEquals(listOf("recover_astd_asset_design_prototype", "leave"), rig.shownIds())

        rig.select(plugin, "recover_astd_asset_design_prototype")
        assertEquals(listOf("astd_asset_design_prototype"), backend.recovered)
        assertTrue(rig.paras.any { it.contains("story.site.custody.recovered") })

        rig.select(plugin, "leave")
        assertTrue(rig.dismissed, "离开选项应关闭对话")
    }

    @Test
    fun `menu omits recover option when nothing is recoverable`() {
        val backend = StubSiteBackend(assets = emptyList())
        val rig = DialogTestRig()
        val plugin = StorySiteDialog.createPlugin("aster_core_vault", backend)
        plugin.init(rig.dialog)

        assertTrue(rig.awaitOption(plugin, "leave"))
        assertEquals(listOf("leave"), rig.shownIds())
    }

    @Test
    fun `failed recovery prints rejection and keeps item unrecorded`() {
        val backend = StubSiteBackend(recoverResult = false)
        val rig = DialogTestRig()
        val plugin = StorySiteDialog.createPlugin("starfall_main", backend)
        plugin.init(rig.dialog)

        assertTrue(rig.awaitOption(plugin, "recover_"))
        rig.select(plugin, "recover_astd_asset_design_prototype")
        assertEquals(listOf(), backend.recovered)
        assertTrue(rig.paras.any { it.contains("story.site.custody.rejected") })
    }

    @Test
    fun `escape leaves the dialog even while describe is still playing`() {
        val rig = DialogTestRig()
        val plugin = StorySiteDialog.createPlugin("starfall_main", StubSiteBackend())
        plugin.init(rig.dialog)

        // describe 播报中（锁定态）按 Escape：closeOnEscapeOptionId 直通关闭
        rig.select(plugin, "leave")
        assertTrue(rig.dismissed)
    }
}
