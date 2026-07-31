package cn.kasuminova.astd.combat.hullmods.arc

import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ASTDArcAuraUtilTest {

    @Test
    fun `selects closest eligible same-side targets deterministically`() {
        val targets = ASTDArcAuraUtil.selectTargets(
            sourceOwner = 1,
            sourceLocation = Vector2f(0f, 0f),
            maxRange = 1200f,
            maxCount = 3,
            eligibleHullSizes = setOf(ShipAPI.HullSize.FRIGATE, ShipAPI.HullSize.DESTROYER),
            candidates = listOf(
                candidate("far-destroyer", 1, 900f, 0f, ShipAPI.HullSize.DESTROYER),
                candidate("enemy", 2, 100f, 0f, ShipAPI.HullSize.FRIGATE),
                candidate("near-frigate", 1, 250f, 0f, ShipAPI.HullSize.FRIGATE),
                candidate("same-distance-b", 1, 500f, 0f, ShipAPI.HullSize.FRIGATE),
                candidate("same-distance-a", 1, -500f, 0f, ShipAPI.HullSize.DESTROYER),
                candidate("cruiser", 1, 100f, 0f, ShipAPI.HullSize.CRUISER),
            ),
        )

        assertEquals(
            listOf("near-frigate", "same-distance-a", "same-distance-b"),
            targets.map { it.id },
        )
    }

    @Test
    fun `score bias is normalized against max range before target ordering`() {
        val targets = ASTDArcAuraUtil.selectTargets(
            sourceOwner = 1,
            sourceLocation = Vector2f(0f, 0f),
            maxRange = 1500f,
            maxCount = 1,
            eligibleHullSizes = setOf(ShipAPI.HullSize.FRIGATE, ShipAPI.HullSize.CRUISER),
            candidates = listOf(
                candidate("near-low-pressure", 1, 700f, 0f, ShipAPI.HullSize.FRIGATE, scoreBias = 0f),
                candidate("far-high-pressure", 1, 850f, 0f, ShipAPI.HullSize.CRUISER, scoreBias = 0.35f),
            ),
        )

        assertEquals(listOf("far-high-pressure"), targets.map { it.id })
    }

    @Test
    fun `excludes hulks fighters drones and out of range candidates`() {
        val targets = ASTDArcAuraUtil.selectTargets(
            sourceOwner = 0,
            sourceLocation = Vector2f(0f, 0f),
            maxRange = 1000f,
            maxCount = 5,
            eligibleHullSizes = setOf(ShipAPI.HullSize.FRIGATE, ShipAPI.HullSize.DESTROYER),
            candidates = listOf(
                candidate("hulk", 0, 100f, 0f, ShipAPI.HullSize.FRIGATE, isHulk = true),
                candidate("fighter", 0, 120f, 0f, ShipAPI.HullSize.FIGHTER, isFighter = true),
                candidate("drone", 0, 140f, 0f, ShipAPI.HullSize.FRIGATE, isDrone = true),
                candidate("too-far", 0, 1201f, 0f, ShipAPI.HullSize.FRIGATE),
                candidate("valid", 0, 600f, 0f, ShipAPI.HullSize.DESTROYER),
            ),
        )

        assertEquals(listOf("valid"), targets.map { it.id })
    }

    @Test
    fun `falloff is full inside inner range and reaches edge floor at max range`() {
        assertEquals(1f, ASTDArcAuraUtil.distanceFalloff(400f, fullRange = 1000f, maxRange = 2000f, edgeScale = 0.25f))
        assertEquals(1f, ASTDArcAuraUtil.distanceFalloff(1000f, fullRange = 1000f, maxRange = 2000f, edgeScale = 0.25f))
        assertEquals(0.625f, ASTDArcAuraUtil.distanceFalloff(1500f, fullRange = 1000f, maxRange = 2000f, edgeScale = 0.25f))
        assertEquals(0.25f, ASTDArcAuraUtil.distanceFalloff(2000f, fullRange = 1000f, maxRange = 2000f, edgeScale = 0.25f))
        assertEquals(0f, ASTDArcAuraUtil.distanceFalloff(2001f, fullRange = 1000f, maxRange = 2000f, edgeScale = 0.25f))
    }

    @Test
    fun `named profiles match arc production design ranges`() {
        assertEquals(1f, ASTDArcAuraUtil.arcJetPassiveFalloff(1000f))
        assertEquals(0.25f, ASTDArcAuraUtil.arcJetPassiveFalloff(2000f))

        assertEquals(1f, ASTDArcAuraUtil.arcJetSystemFalloff(750f))
        assertEquals(0.25f, ASTDArcAuraUtil.arcJetSystemFalloff(1500f))

        assertEquals(1f, ASTDArcAuraUtil.radiationBeltNetworkFalloff(1199f))
        assertEquals(0f, ASTDArcAuraUtil.radiationBeltNetworkFalloff(1201f))
    }

    @Test
    fun `radiation belt network selects at most five closest frigates and destroyers`() {
        val targets = ASTDArcAuraUtil.selectTargets(
            sourceOwner = 0,
            sourceLocation = Vector2f(0f, 0f),
            maxRange = ASTDArcAuraUtil.RADIATION_BELT_NETWORK_RANGE,
            maxCount = 5,
            eligibleHullSizes = setOf(ShipAPI.HullSize.FRIGATE, ShipAPI.HullSize.DESTROYER),
            candidates = listOf(
                candidate("f-1", 0, 100f, 0f, ShipAPI.HullSize.FRIGATE),
                candidate("d-2", 0, 200f, 0f, ShipAPI.HullSize.DESTROYER),
                candidate("f-3", 0, 300f, 0f, ShipAPI.HullSize.FRIGATE),
                candidate("d-4", 0, 400f, 0f, ShipAPI.HullSize.DESTROYER),
                candidate("f-5", 0, 500f, 0f, ShipAPI.HullSize.FRIGATE),
                candidate("d-6", 0, 600f, 0f, ShipAPI.HullSize.DESTROYER),
                candidate("cruiser", 0, 50f, 0f, ShipAPI.HullSize.CRUISER),
                candidate("same-distance-a", 0, 700f, 0f, ShipAPI.HullSize.FRIGATE),
                candidate("same-distance-b", 0, -700f, 0f, ShipAPI.HullSize.DESTROYER),
            ),
        )

        assertEquals(listOf("f-1", "d-2", "f-3", "d-4", "f-5"), targets.map { it.id })
    }

    @Test
    fun `ship ids are centralized for all redesigned hullmods and systems`() {
        assertTrue(ASTDArcProductionShipIds.UNIQUE_HULLMOD_IDS.contains("astd_arc_advanced_fire_control"))
        assertTrue(ASTDArcProductionShipIds.UNIQUE_HULLMOD_IDS.contains("astd_plasma_armor_shield"))
        assertTrue(ASTDArcProductionShipIds.UNIQUE_HULLMOD_IDS.contains("astd_distributed_pursuit_network"))
        assertEquals("astd_arc_shared_flux_network", ASTDArcProductionShipIds.SYSTEM_ARC_SHARED_FLUX_NETWORK)
        assertEquals("astd_plasma_armor_shield_boost", ASTDArcProductionShipIds.SYSTEM_PLASMA_ARMOR_SHIELD_BOOST)
        assertEquals("astd_limit_temporal_thruster", ASTDArcProductionShipIds.SYSTEM_LIMIT_TEMPORAL_THRUSTER)
    }

    private fun candidate(
        id: String,
        owner: Int,
        x: Float,
        y: Float,
        hullSize: ShipAPI.HullSize,
        isHulk: Boolean = false,
        isFighter: Boolean = false,
        isDrone: Boolean = false,
        scoreBias: Float = 0f,
    ): ASTDArcAuraUtil.CandidateSummary = ASTDArcAuraUtil.CandidateSummary(
        id = id,
        owner = owner,
        location = Vector2f(x, y),
        hullSize = hullSize,
        isHulk = isHulk,
        isFighter = isFighter,
        isDrone = isDrone,
        scoreBias = scoreBias,
    )
}
