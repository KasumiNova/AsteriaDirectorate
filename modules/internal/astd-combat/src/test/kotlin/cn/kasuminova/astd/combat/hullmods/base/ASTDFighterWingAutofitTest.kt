package cn.kasuminova.astd.combat.hullmods.base

import cn.kasuminova.astd.sscsv.entries.catalog.wingdata.lens.Wing_astd_gemini_bomber_wing
import cn.kasuminova.astd.sscsv.entries.catalog.wingdata.lens.Wing_astd_gravwell_interceptor_wing
import cn.kasuminova.astd.sscsv.entries.catalog.wingdata.lens.Wing_astd_surge_fighter_wing
import com.fs.starfarer.loading.specs.FighterWingSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ASTDFighterWingAutofitTest {

    @Test
    fun `量产联队均具有原版自动装配分类`() {
        val entries = listOf(
            Wing_astd_gemini_bomber_wing,
            Wing_astd_surge_fighter_wing,
            Wing_astd_gravwell_interceptor_wing,
        )

        for (entry in entries) {
            val spec = FighterWingSpec()
            entry.tags.split(',').map { it.trim() }.forEach(spec::addTag)

            assertTrue(spec.autofitCategoriesInPriorityOrder.isNotEmpty(), entry.id)
            assertEquals(entry.role.lowercase(), spec.autofitCategory, entry.id)
        }
    }
}
