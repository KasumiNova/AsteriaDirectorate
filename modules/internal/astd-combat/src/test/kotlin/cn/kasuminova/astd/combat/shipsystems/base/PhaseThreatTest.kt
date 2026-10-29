package cn.kasuminova.astd.combat.shipsystems.base

import cn.kasuminova.astd.combat.shipsystems.base.PhaseThreat.Defense
import cn.kasuminova.astd.combat.shipsystems.base.PhaseThreat.Guidance
import cn.kasuminova.astd.combat.shipsystems.base.PhaseThreat.Hit
import cn.kasuminova.astd.combat.shipsystems.base.PhaseThreat.Point
import cn.kasuminova.astd.combat.shipsystems.base.PhaseThreat.Stage
import cn.kasuminova.astd.combat.shipsystems.base.PhaseThreat.Window
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.DamageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhaseThreatTest {
    @Test
    fun relativeMotionFindsEntryRatherThanClosestApproach() {
        val result = PhaseThreat.approach(Point(100f, 0f), Point(-50f, 0f), 10f, 10f, 3f)
        assertEquals(1.8f, result.hitTime!!, 0.0001f)
        assertEquals(2f, result.closestTime)
        assertEquals(0f, result.closestDistance)
        val movingAway = PhaseThreat.approach(Point(100f, 0f), Point(10f, 0f), 10f, 10f, 3f)
        assertNull(movingAway.hitTime)
        assertEquals(100f, movingAway.closestDistance)
    }

    @Test
    fun missesExpiredShotsAndTangenciesHaveDistinctResults() {
        assertNull(PhaseThreat.approach(Point(100f, 11f), Point(-50f, 0f), 10f, 10f, 3f).hitTime)
        assertNull(PhaseThreat.approach(Point(100f, 0f), Point(-50f, 0f), 10f, 1.79f, 3f).hitTime)
        assertEquals(2f, PhaseThreat.approach(Point(100f, 10f), Point(-50f, 0f), 10f, 3f, 3f).hitTime)
        assertEquals(0f, PhaseThreat.approach(Point(0f, 0f), Point(0f, 0f), 10f, 1f, 1f).hitTime)
        assertNull(PhaseThreat.approach(Point(20f, 0f), Point(0f, 0f), 10f, 1f, 1f).hitTime)
    }

    @Test
    fun guidanceNeedsTrackingTurningAndLife() {
        val position = Point(100f, 30f)
        val velocity = Point(-100f, 0f)
        val tracking = Guidance(true, 180f, 90f)
        assertTrue(PhaseThreat.guidedApproach(position, velocity, 10f, 3f, 3f, tracking).hitTime != null)
        assertNull(PhaseThreat.guidedApproach(position, velocity, 10f, 3f, 3f, tracking.copy(tracking = false)).hitTime)
        assertNull(PhaseThreat.guidedApproach(position, velocity, 10f, 3f, 3f, tracking.copy(turnRate = 1f)).hitTime)
        assertNull(PhaseThreat.guidedApproach(position, velocity, 10f, 0.5f, 3f, tracking).hitTime)
        assertNull(PhaseThreat.guidedApproach(position, Point(100f, 0f), 10f, 0.5f, 0.5f,
            Guidance(true, 0f, 90f)).hitTime)
    }

    @Test
    fun lateralTrackingMissileCanTurnIntoTargetWithinShortWindow() {
        val position = Point(60f, 0f)
        val velocity = Point(0f, 300f)
        val guidance = Guidance(true, 90f, 1080f)
        val hit = PhaseThreat.guidedApproach(position, velocity, 20f, 1f, 0.675f, guidance).hitTime!!
        assertTrue(hit in (40f / 300f)..0.675f)
        assertNull(PhaseThreat.approach(position, velocity, 20f, 1f, 0.675f).hitTime)
        assertNull(PhaseThreat.guidedApproach(position, velocity, 20f, 1f, 0.675f,
            guidance.copy(turnRate = 10f)).hitTime)
        assertNull(PhaseThreat.guidedApproach(position, velocity, 20f, 0.1f, 0.675f, guidance).hitTime)
        assertNull(PhaseThreat.guidedApproach(position, velocity, 20f, 1f, 0.1f, guidance).hitTime)
        assertNull(PhaseThreat.guidedApproach(position, velocity, 20f, 1f, 0.675f,
            guidance.copy(tracking = false)).hitTime)
        assertNull(PhaseThreat.guidedApproach(Point(1000f, 0f), velocity, 20f, 10f, 0.675f, guidance).hitTime)
        assertNull(PhaseThreat.guidedApproach(position, Point(0f, 0f), 20f, 1f, 0.675f, guidance).hitTime)
    }

    @Test
    fun outwardTrackingMissileCanReturnOnlyWithEnoughTimeToTurn() {
        val guidance = Guidance(true, 0f, 1080f)
        assertTrue(PhaseThreat.guidedApproach(Point(60f, 0f), Point(300f, 0f), 20f,
            1f, 0.675f, guidance).hitTime != null)
        assertNull(PhaseThreat.guidedApproach(Point(60f, 0f), Point(300f, 0f), 20f,
            1f, 0.675f, guidance.copy(turnRate = 30f)).hitTime)
    }

    @Test
    fun unguidedCollisionStillCountsWhenTrackingAnotherShip() {
        assertEquals(0.9f, PhaseThreat.guidedApproach(Point(100f, 0f), Point(-100f, 0f),
            10f, 2f, 2f, Guidance(false, 0f, 0f)).hitTime!!, 0.0001f)
    }

    @Test
    fun beamsNeedDirectionRangeAndReliableChargeHistory() {
        assertTrue(PhaseThreat.beamCovers(Point(100f, 0f), 0f, 100f, 10f))
        assertFalse(PhaseThreat.beamCovers(Point(100f, 20f), 0f, 100f, 10f))
        assertFalse(PhaseThreat.beamCovers(Point(120f, 0f), 0f, 100f, 10f))
        assertFalse(PhaseThreat.beamCovers(Point(-100f, 0f), 0f, 100f, 10f))
        assertTrue(PhaseThreat.reliablyCharging(0.1f, 0.2f, true, 0f))
        assertFalse(PhaseThreat.reliablyCharging(null, 0.2f, true, 0f))
        assertFalse(PhaseThreat.reliablyCharging(0.2f, 0.1f, true, 0f))
        assertFalse(PhaseThreat.reliablyCharging(0.1f, 0.2f, true, 1f))
        assertFalse(PhaseThreat.reliablyCharging(0.1f, 0.2f, false, 0f))
    }

    @Test
    fun carriersAndDuplicateObservationsNeverAddDamage() {
        val direct = Hit("child", 0.2f, 30f, DamageType.ENERGY)
        val result = PhaseThreat.estimate(Defense(100f), listOf(
            Hit("dem", 0f, 10000f, DamageType.ENERGY, stage = Stage.CARRIER),
            direct, direct, Hit("late", 2f, 100f, DamageType.ENERGY)), Window(0.5f, 0.5f))
        assertEquals(30f, result.total.raw)
        assertEquals(30f, result.total.hull, 0.0001f)
        assertEquals(setOf<Any>("child"), result.threatKeys)
    }

    @Test
    fun shieldAbsorbsOverloadingHitButNotFollowingHits() {
        val result = PhaseThreat.estimate(Defense(100f, shieldOn = true, fluxCapacity = 100f), listOf(
            Hit("first", 0.1f, 60f, DamageType.KINETIC, shieldCovered = true),
            Hit("second", 0.3f, 40f, DamageType.ENERGY, shieldCovered = true)), Window(0.2f, 0.2f))
        assertEquals(100f, result.total.shieldFlux)
        assertEquals(0.1f, result.overloadAt)
        assertEquals(0f, result.duringChargeOut.hull)
        assertEquals(40f, result.duringReaction.hull, 0.0001f)
    }

    @Test
    fun shieldArcAndDamageTypeAndSoftFluxAreApplied() {
        val defense = Defense(100f, shieldOn = true, fluxCapacity = 1000f, shieldFluxPerDamage = 0.5f)
        val covered = Hit("beam", 0.1f, 100f, DamageType.KINETIC, stage = Stage.BEAM,
            shieldCovered = true, softFlux = true)
        val result = PhaseThreat.estimate(defense, listOf(covered), Window(0.5f, 0f))
        assertEquals(100f, result.total.shieldFlux)
        assertEquals(0f, result.total.hardFlux)
        assertEquals(0f, result.total.hull)
        val bypass = PhaseThreat.estimate(defense, listOf(covered.copy(shieldCovered = false)), Window(0.5f, 0f))
        assertEquals(100f, bypass.total.hull, 0.0001f)
    }

    @Test
    fun sustainedExposureUsesArmorAndDamageTypeInsteadOfRawDps() {
        val defense = Defense(5000f, armorRating = 700f, minimumArmorFraction = 0.15f)
        val energy = PhaseThreat.sustainedHullLoss(defense, 1000f, DamageType.ENERGY)
        val explosive = PhaseThreat.sustainedHullLoss(defense, 1000f, DamageType.HIGH_EXPLOSIVE)
        assertTrue(energy > 0f)
        assertTrue(energy < 1000f)
        assertTrue(explosive > energy)
    }

    @Test
    fun sustainedExposureUsesLocalArmorHitStrengthAndBeamModifier() {
        val intact = Defense(5000f, armor = List(5) { List(5) { 50f } }, armorRating = 700f,
            minimumArmorFraction = 0.15f, beamDamageMult = 0.5f)
        val intactLoss = PhaseThreat.sustainedHullLoss(intact, 1000f, DamageType.ENERGY,
            hitStrength = 100f, cellX = 2, cellY = 2)
        val depletedLoss = PhaseThreat.sustainedHullLoss(intact.copy(armor = emptyList()), 1000f,
            DamageType.ENERGY, hitStrength = 100f, cellX = 2, cellY = 2)
        assertTrue(intactLoss < depletedLoss)
        val weakHit = PhaseThreat.sustainedHullLoss(intact.copy(armor = emptyList()), 1000f,
            DamageType.ENERGY, hitStrength = 100f)
        val strongHit = PhaseThreat.sustainedHullLoss(intact.copy(armor = emptyList()), 1000f,
            DamageType.ENERGY, hitStrength = 1000f)
        assertTrue(weakHit < strongHit)
        val projectile = PhaseThreat.sustainedHullLoss(intact.copy(armor = emptyList()), 1000f,
            DamageType.ENERGY, hitStrength = 500f)
        val beam = PhaseThreat.sustainedHullLoss(intact.copy(armor = emptyList()), 1000f,
            DamageType.ENERGY, hitStrength = 500f, stage = Stage.BEAM)
        assertEquals(projectile * 0.5f, beam, 0.001f)
    }

    @Test
    fun armorUsesLocalTwentyOneCellDistributionAndDamageType() {
        val defense = Defense(1000f, armor = List(5) { List(5) { 100f } }, armorRating = 1500f)
        val energy = Hit("shot", 0f, 100f, DamageType.ENERGY, cellX = 2, cellY = 2)
        val e = PhaseThreat.estimate(defense, listOf(energy), Window(1f, 0f))
        val he = PhaseThreat.estimate(defense, listOf(energy.copy(type = DamageType.HIGH_EXPLOSIVE)), Window(1f, 0f))
        assertEquals(15f, e.total.armor, 0.001f)
        assertEquals(30f, he.total.armor, 0.001f)
        assertEquals(0f, e.total.hull)
        assertEquals(0f, he.total.hull)
        assertEquals(100f, defense.armor[2][2])
    }

    @Test
    fun depletedCellsAndConsecutiveHitsChangeActualLoss() {
        val defense = Defense(100f, armor = List(5) { List(5) { 1f } }, maximumArmorReduction = 0f)
        val first = Hit("a", 0.1f, 30f, DamageType.ENERGY, cellX = 2, cellY = 2)
        val second = first.copy(key = "b", time = 0.3f)
        val result = PhaseThreat.estimate(defense, listOf(second, first), Window(0.2f, 0.2f))
        assertEquals(21f, result.impacts[0].loss.armor, 0.0001f)
        assertEquals(9f, result.impacts[0].loss.hull, 0.0001f)
        assertEquals(30f, result.impacts[1].loss.hull, 0.0001f)
    }

    @Test
    fun residualArmorReducesHullDamageWithoutInventingArmorCells() {
        val hit = Hit("shot", 0f, 100f, DamageType.ENERGY)
        val result = PhaseThreat.estimate(Defense(100f, armorRating = 1000f, minimumArmorFraction = 0.05f),
            listOf(hit), Window(1f, 0f))
        assertEquals(100f * 100f / 150f, result.total.hull, 0.001f)
        assertEquals(0f, result.total.armor)
    }

    @Test
    fun friendlyAndHostileHitsShareDefenseButHaveSeparateLossesAndLethalTime() {
        val hits = listOf(Hit("enemy", 0.2f, 60f, DamageType.ENERGY),
            Hit("friend", 0.4f, 50f, DamageType.ENERGY, friendly = true))
        val result = PhaseThreat.estimate(Defense(100f), hits, Window(0.3f, 0.2f))
        assertEquals(60f, result.hostile.hull, 0.0001f)
        assertEquals(50f, result.friendly.hull, 0.0001f)
        assertTrue(result.lethal)
        assertEquals(0.4f, result.lethalAt)
        assertEquals(0f, result.remainingHull)
        assertTrue(result.exceedsHullBudget(100f))
        assertFalse(result.exceedsHullBudget(111f))
    }

    @Test
    fun beamArmorHitStrengthDoesNotDependOnIntegrationStep() {
        val defense = Defense(1000f, armorRating = 1000f, minimumArmorFraction = 0.05f)
        val whole = Hit("beam", 0.1f, 100f, DamageType.ENERGY, stage = Stage.BEAM, hitStrength = 50f)
        val split = (0..9).map { whole.copy(time = it * 0.05f, damage = 10f) }
        val a = PhaseThreat.estimate(defense, listOf(whole), Window(1f, 0f))
        val b = PhaseThreat.estimate(defense, split, Window(1f, 0f))
        assertEquals(a.total.hull, b.total.hull, 0.001f)
    }

    @Test
    fun armorLocationUsesHullOutlineInsteadOfBroadCollisionCircle() {
        val outline = listOf(Point(10f, -5f) to Point(10f, 5f), Point(10f, 5f) to Point(-10f, 5f),
            Point(-10f, 5f) to Point(-10f, -5f), Point(-10f, -5f) to Point(10f, -5f))
        assertEquals(10f, PhaseThreat.surfaceDistance(Point(100f, 0f), outline, 20f))
        assertEquals(5f, PhaseThreat.surfaceDistance(Point(0f, 100f), outline, 20f))
        assertEquals(20f, PhaseThreat.surfaceDistance(Point(100f, 0f), emptyList(), 20f))
    }

    @Test
    fun unknownImpactCellDoesNotBorrowArmorFromGridCorner() {
        val defense = Defense(100f, armor = List(5) { List(5) { 100f } })
        val result = PhaseThreat.estimate(defense, listOf(Hit("unknown", 0f, 50f, DamageType.ENERGY)), Window(1f, 0f))
        assertEquals(0f, result.total.armor)
        assertEquals(50f, result.total.hull, 0.001f)
    }

    @Test
    fun mirvPayloadsInheritCarrierThreatUntilEveryChildEnds() {
        val lineage = PhaseThreat.Lineage()
        val source = Any()
        val carrier = Any()
        val firstChild = Any()
        val secondChild = Any()
        lineage.observe(listOf(PhaseThreat.CarrierObservation(carrier, source, "warhead", Point(0f, 0f))), emptyList(), 0f)

        val children = listOf(
            PhaseThreat.ProjectileObservation(firstChild, source, "warhead", Point(20f, 0f)),
            PhaseThreat.ProjectileObservation(secondChild, source, "warhead", Point(-20f, 0f)),
        )
        lineage.observe(emptyList(), children, 0.1f)
        assertEquals(setOf(firstChild, secondChild), lineage.linkedPayloads)
        assertEquals(setOf(carrier), lineage.activeParents(setOf(firstChild, secondChild)))

        lineage.retainActive(setOf(firstChild))
        assertEquals(setOf(carrier), lineage.activeParents(setOf(firstChild)))
        lineage.retainActive(emptySet())
        assertTrue(lineage.activeParents(emptySet()).isEmpty())

        val laterChild = Any()
        lineage.observe(emptyList(), listOf(
            PhaseThreat.ProjectileObservation(laterChild, source, "warhead", Point(10f, 0f)),
        ), 1f)
        assertTrue(lineage.linkedPayloads.isEmpty())
    }

    @Test
    fun mirvLineageRequiresMatchingSourceSpecAndSplitPosition() {
        val lineage = PhaseThreat.Lineage()
        val source = Any()
        val otherSource = Any()
        val carrier = Any()
        lineage.observe(listOf(PhaseThreat.CarrierObservation(carrier, source, "warhead", Point(0f, 0f))), emptyList(), 0f)
        val children = listOf(
            PhaseThreat.ProjectileObservation(Any(), otherSource, "warhead", Point(0f, 0f)),
            PhaseThreat.ProjectileObservation(Any(), source, "other", Point(0f, 0f)),
            PhaseThreat.ProjectileObservation(Any(), source, "warhead", Point(600f, 0f)),
        )
        lineage.observe(emptyList(), children, 0.1f)
        assertTrue(lineage.linkedPayloads.isEmpty())
    }

    @Test
    fun nestedSplitKeepsAllAncestorsAcrossAnEmptyObservation() {
        val lineage = PhaseThreat.Lineage()
        val source = Any()
        val firstCarrier = Any()
        val secondCarrier = Any()
        val child = Any()
        lineage.observe(listOf(PhaseThreat.CarrierObservation(firstCarrier, source, "second", Point(0f, 0f))), emptyList(), 0f)
        lineage.observe(emptyList(), listOf(
            PhaseThreat.ProjectileObservation(secondCarrier, source, "second", Point(0f, 0f)),
        ), 0.1f)
        lineage.observe(listOf(
            PhaseThreat.CarrierObservation(secondCarrier, source, "child", Point(0f, 0f)),
        ), emptyList(), 0.2f)
        lineage.retainActive(emptySet())

        lineage.observe(emptyList(), listOf(
            PhaseThreat.ProjectileObservation(child, source, "child", Point(10f, 0f)),
        ), 0.3f)

        assertEquals(setOf(firstCarrier, secondCarrier), lineage.activeParents(setOf(child)))
    }

    @Test
    fun existingProjectileCannotBeClaimedByALaterCarrier() {
        val lineage = PhaseThreat.Lineage()
        val source = Any()
        val carrier = Any()
        val child = PhaseThreat.ProjectileObservation(Any(), source, "warhead", Point(0f, 0f))
        lineage.observe(emptyList(), listOf(child), 0f)
        lineage.observe(listOf(
            PhaseThreat.CarrierObservation(carrier, source, "warhead", Point(0f, 0f)),
        ), listOf(child), 0.1f)
        assertTrue(lineage.linkedPayloads.isEmpty())
    }

    @Test
    fun ambiguousSplitPreservesEveryCandidateWithIdentitySemantics() {
        class EqualKey {
            override fun equals(other: Any?): Boolean = other is EqualKey
            override fun hashCode(): Int = 1
        }

        val lineage = PhaseThreat.Lineage()
        val source = Any()
        val firstCarrier = EqualKey()
        val secondCarrier = EqualKey()
        val child = Any()
        lineage.observe(listOf(
            PhaseThreat.CarrierObservation(firstCarrier, source, "warhead", Point(0f, 0f)),
            PhaseThreat.CarrierObservation(secondCarrier, source, "warhead", Point(20f, 0f)),
        ), emptyList(), 0f)
        lineage.observe(emptyList(), listOf(
            PhaseThreat.ProjectileObservation(child, source, "warhead", Point(10f, 0f)),
        ), 0.1f)

        val parents = lineage.activeParents(setOf(child))
        assertEquals(2, parents.size)
        assertTrue(parents.contains(firstCarrier))
        assertTrue(parents.contains(secondCarrier))
    }

    @Test
    fun engineBeamListRemainsAuthoritativeWhenWeaponBeamListIsEmpty() {
        val previousSettings = com.fs.starfarer.api.Global.getSettings()
        val settings = org.mockito.Mockito.mock(com.fs.starfarer.api.SettingsAPI::class.java)
        org.mockito.Mockito.`when`(settings.getFloat("dpsToHitStrengthMult")).thenReturn(1f)
        com.fs.starfarer.api.Global.setSettings(settings)
        try {
            val ship = org.mockito.Mockito.mock(com.fs.starfarer.api.combat.ShipAPI::class.java,
                org.mockito.Mockito.RETURNS_DEEP_STUBS)
            val enemy = org.mockito.Mockito.mock(com.fs.starfarer.api.combat.ShipAPI::class.java,
                org.mockito.Mockito.RETURNS_DEEP_STUBS)
            val weapon = org.mockito.Mockito.mock(com.fs.starfarer.api.combat.WeaponAPI::class.java,
                org.mockito.Mockito.RETURNS_DEEP_STUBS)
            val spec = org.mockito.Mockito.mock(com.fs.starfarer.api.loading.BeamWeaponSpecAPI::class.java)
            val beam = org.mockito.Mockito.mock(com.fs.starfarer.api.combat.BeamAPI::class.java,
                org.mockito.Mockito.RETURNS_DEEP_STUBS)
            val engine = org.mockito.Mockito.mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
            org.mockito.Mockito.`when`(ship.owner).thenReturn(0)
            org.mockito.Mockito.`when`(ship.isAlive).thenReturn(true)
            org.mockito.Mockito.`when`(ship.location).thenReturn(org.lwjgl.util.vector.Vector2f())
            org.mockito.Mockito.`when`(ship.velocity).thenReturn(org.lwjgl.util.vector.Vector2f())
            org.mockito.Mockito.`when`(ship.collisionRadius).thenReturn(50f)
            org.mockito.Mockito.`when`(ship.hitpoints).thenReturn(1000f)
            org.mockito.Mockito.`when`(ship.mutableStats.timeMult.modifiedValue).thenReturn(1f)
            org.mockito.Mockito.`when`(ship.mutableStats.minArmorFraction.modifiedValue).thenReturn(0f)
            org.mockito.Mockito.`when`(ship.mutableStats.maxArmorDamageReduction.modifiedValue).thenReturn(0.85f)
            org.mockito.Mockito.`when`(ship.mutableStats.armorDamageTakenMult.modifiedValue).thenReturn(1f)
            org.mockito.Mockito.`when`(ship.mutableStats.kineticArmorDamageTakenMult.modifiedValue).thenReturn(1f)
            org.mockito.Mockito.`when`(ship.mutableStats.hullDamageTakenMult.modifiedValue).thenReturn(1f)
            org.mockito.Mockito.`when`(ship.mutableStats.beamDamageTakenMult.modifiedValue).thenReturn(1f)
            org.mockito.Mockito.`when`(ship.mutableStats.projectileDamageTakenMult.modifiedValue).thenReturn(1f)
            org.mockito.Mockito.`when`(ship.armorGrid.grid).thenReturn(emptyArray())
            org.mockito.Mockito.`when`(enemy.owner).thenReturn(1)
            org.mockito.Mockito.`when`(enemy.isAlive).thenReturn(true)
            org.mockito.Mockito.`when`(enemy.location).thenReturn(org.lwjgl.util.vector.Vector2f(500f, 0f))
            org.mockito.Mockito.`when`(enemy.velocity).thenReturn(org.lwjgl.util.vector.Vector2f())
            org.mockito.Mockito.`when`(enemy.mutableStats.timeMult.modifiedValue).thenReturn(1f)
            org.mockito.Mockito.`when`(enemy.allWeapons).thenReturn(listOf(weapon))
            org.mockito.Mockito.`when`(weapon.ship).thenReturn(enemy)
            org.mockito.Mockito.`when`(weapon.spec).thenReturn(spec)
            org.mockito.Mockito.`when`(weapon.isBeam).thenReturn(true)
            org.mockito.Mockito.`when`(weapon.isFiring).thenReturn(true)
            org.mockito.Mockito.`when`(weapon.beams).thenReturn(emptyList())
            org.mockito.Mockito.`when`(weapon.location).thenReturn(org.lwjgl.util.vector.Vector2f(500f, 0f))
            org.mockito.Mockito.`when`(weapon.currAngle).thenReturn(180f)
            org.mockito.Mockito.`when`(weapon.range).thenReturn(1000f)
            org.mockito.Mockito.`when`(weapon.distanceFromArc(org.mockito.ArgumentMatchers.any())).thenReturn(0f)
            org.mockito.Mockito.`when`(spec.collisionClass).thenReturn(com.fs.starfarer.api.combat.CollisionClass.RAY)
            org.mockito.Mockito.`when`(beam.weapon).thenReturn(weapon)
            org.mockito.Mockito.`when`(beam.source).thenReturn(enemy)
            org.mockito.Mockito.`when`(beam.from).thenReturn(org.lwjgl.util.vector.Vector2f(500f, 0f))
            org.mockito.Mockito.`when`(beam.to).thenReturn(org.lwjgl.util.vector.Vector2f())
            org.mockito.Mockito.`when`(beam.width).thenReturn(10f)
            org.mockito.Mockito.`when`(beam.brightness).thenReturn(1f)
            org.mockito.Mockito.`when`(beam.damage.damage).thenReturn(100f)
            org.mockito.Mockito.`when`(beam.damage.type).thenReturn(DamageType.ENERGY)
            org.mockito.Mockito.`when`(beam.damageTarget).thenReturn(ship)
            org.mockito.Mockito.`when`(engine.ships).thenReturn(listOf(ship, enemy))
            org.mockito.Mockito.`when`(engine.projectiles).thenReturn(emptyList())
            org.mockito.Mockito.`when`(engine.missiles).thenReturn(emptyList())
            org.mockito.Mockito.`when`(engine.beams).thenReturn(listOf(beam))

            val forecast = PhaseThreat().sample(engine, ship, Window(0f, 0.4f))

            assertEquals(40f, forecast!!.total.raw, 0.001f)
            assertTrue(forecast.impacts.isNotEmpty())
        } finally {
            com.fs.starfarer.api.Global.setSettings(previousSettings)
        }
    }

    @Test
    fun forecastThreatKeysIncludeLineageAncestorsOfActualImpacts() {
        val forecast = PhaseThreat.estimate(Defense(100f), listOf(Hit("child", 0.2f, 30f, DamageType.ENERGY)),
            Window(0.5f, 0.5f)).copy(lineageThreatKeys = setOf<Any>("carrier"))
        assertEquals(setOf<Any>("child", "carrier"), forecast.threatKeys)
    }

    @Test
    fun trackedFacingIsTurnRateLimitedAndWaitsUntilTrackingStarts() {
        val bearing: (Float) -> Float = { 90f }
        assertEquals(45f, PhaseThreat.trackedFacing(0f, 90f, 0f, 0.5f, bearing), 0.01f)
        assertEquals(90f, PhaseThreat.trackedFacing(0f, 90f, 0f, 2f, bearing), 0.01f)
        assertEquals(0f, PhaseThreat.trackedFacing(0f, 90f, 0.5f, 0.5f, bearing), 0.0001f)
        assertEquals(45f, PhaseThreat.trackedFacing(0f, 90f, 0.5f, 1f, bearing), 0.01f)
        assertEquals(0f, PhaseThreat.trackedFacing(0f, 0f, 0f, 1f, bearing), 0.0001f)
    }

    @Test
    fun demBeamRetracksSurfacedTargetWithinTurnRateLimit() {
        val dem = org.mockito.Mockito.mock(com.fs.starfarer.api.impl.combat.dem.DEMScript::class.java)
        val plain = org.json.JSONObject()
        assertTrue(demBeamForecast(dem, plain, 360f).first.impacts.isNotEmpty())
        assertTrue(demBeamForecast(dem, plain, 10f).first.impacts.isEmpty())
        val otherAi = org.mockito.Mockito.mock(com.fs.starfarer.api.combat.MissileAIPlugin::class.java)
        assertTrue(demBeamForecast(otherAi, plain, 360f).first.impacts.isEmpty())
        assertTrue(demBeamForecast(dem, org.json.JSONObject().put("bombPumped", true), 360f).first.impacts.isEmpty())
        assertTrue(demBeamForecast(
            dem,
            org.json.JSONObject().put("bombPumped", true),
            360f,
            targetY = 300f,
        ).first.impacts.isNotEmpty())
        val (forecast, missile) = demBeamForecast(dem, plain, 360f)
        assertTrue(missile in forecast.threatKeys)
        assertTrue(missile in forecast.activeThreatKeys)
    }

    @Test
    fun demBeamUsesNearestCarrierWhenDronesOverlap() {
        val dem = org.mockito.Mockito.mock(com.fs.starfarer.api.impl.combat.dem.DEMScript::class.java)
        val (forecast, nearest) = demBeamForecast(
            dem,
            org.json.JSONObject(),
            360f,
            org.lwjgl.util.vector.Vector2f(501f, 300f),
        )

        assertTrue(nearest in forecast.threatKeys)
        assertEquals(1, forecast.threatKeys.count { it is com.fs.starfarer.api.combat.MissileAPI })
    }

    @Test
    fun demPredictionUsesPublicTargetHistoryAndCarrierLifetime() {
        val dem = org.mockito.Mockito.mock(com.fs.starfarer.api.impl.combat.dem.DEMScript::class.java)
        val plain = org.json.JSONObject()
        assertTrue(demBeamForecast(dem, plain, 360f, targetOther = true).first.impacts.isEmpty())

        val lifetime = org.json.JSONObject().put("payloadWeaponId", "payload")
        val lifeImpacts = demBeamForecast(
            dem, lifetime, 360f, targetY = 300f,
            beamTo = org.lwjgl.util.vector.Vector2f(0f, 300f), carrierLife = 0.02f,
        ).first.impacts.filter { it.hit.key is com.fs.starfarer.api.combat.BeamAPI }
        assertEquals(1, lifeImpacts.size)
        assertEquals(2f, lifeImpacts.first().hit.damage, 0.01f)

        val behavior = org.json.JSONObject()
            .put("targetingLaserId", "targeting")
            .put("payloadWeaponId", "payload")
            .put("targetingTime", 3f)
            .put("firingTime", 1f)
        val (forecast, missile) = demBeamForecast(dem, behavior, 360f, targetY = 300f, prefire = true)
        val payloadImpacts = forecast.impacts.filter {
            it.hit.key is com.fs.starfarer.api.combat.WeaponAPI &&
                forecast.impactAncestorKeys[it.hit.key]?.contains(missile) == true
        }
        assertTrue(payloadImpacts.isNotEmpty())
        assertTrue(payloadImpacts.first().hit.time >= 0.5f)
        assertTrue(missile in forecast.threatKeys)
        val (otherForecast, otherMissile) = demBeamForecast(
            dem, behavior, 360f, targetY = 300f, targetOther = true, prefire = true,
        )
        assertTrue(otherForecast.impacts.none {
            it.hit.key is com.fs.starfarer.api.combat.WeaponAPI &&
                otherForecast.impactAncestorKeys[it.hit.key]?.contains(otherMissile) == true
        })
    }

    @Test
    fun demBombPumpedPrefireFiresFromFrozenExplosionPoint() {
        val dem = org.mockito.Mockito.mock(com.fs.starfarer.api.impl.combat.dem.DEMScript::class.java)
        val behavior = org.json.JSONObject()
            .put("bombPumped", true)
            .put("targetingLaserId", "targeting")
            .put("payloadWeaponId", "payload")
            .put("targetingTime", 3f)
            .put("firingTime", 1f)
        val (forecast, _) = demBeamForecast(
            dem, behavior, 360f, targetY = 300f, prefire = true,
            droneVelocity = org.lwjgl.util.vector.Vector2f(0f, 500f), droneAngularVelocity = 90f,
        )
        val payloadImpacts = forecast.impacts.filter {
            (it.hit.key as? com.fs.starfarer.api.combat.WeaponAPI)?.id == "payload"
        }
        assertTrue(payloadImpacts.isNotEmpty())
        assertTrue(payloadImpacts.maxOf { it.hit.time } >= 1.45f)
        assertEquals(100f, payloadImpacts.sumOf { it.hit.damage.toDouble() }.toFloat(), 5f)
    }

    @Test
    fun demBombPumpedLiveBeamStaysFrozenAfterCarrierRemoved() {
        val dem = org.mockito.Mockito.mock(com.fs.starfarer.api.impl.combat.dem.DEMScript::class.java)
        val behavior = org.json.JSONObject()
            .put("bombPumped", true)
            .put("targetingLaserId", "targeting")
            .put("payloadWeaponId", "payload")
            .put("targetingTime", 3f)
            .put("firingTime", 1f)
        val (forecast, _) = demBeamForecast(
            dem, behavior, 360f, targetY = 300f,
            beamTo = org.lwjgl.util.vector.Vector2f(0f, 300f),
            droneVelocity = org.lwjgl.util.vector.Vector2f(0f, 500f), droneAngularVelocity = 90f,
            fireAfterMissileRemoved = true,
        )
        val beamImpacts = forecast.impacts.filter { it.hit.key is com.fs.starfarer.api.combat.BeamAPI }
        assertTrue(beamImpacts.isNotEmpty())
        assertTrue(beamImpacts.maxOf { it.hit.time } >= 0.95f)
        assertEquals(100f, beamImpacts.sumOf { it.hit.damage.toDouble() }.toFloat(), 5f)
    }

    @Test
    fun demPrefirePayloadRangeExcludesTargetingLaserBoost() {
        val dem = org.mockito.Mockito.mock(com.fs.starfarer.api.impl.combat.dem.DEMScript::class.java)
        val behavior = org.json.JSONObject()
            .put("targetingLaserId", "targeting")
            .put("payloadWeaponId", "payload")
            .put("targetingTime", 3f)
            .put("firingTime", 1f)
            .put("targetingLaserRange", 900f)
        val (far, _) = demBeamForecast(
            dem, behavior, 360f, targetX = -700f, targetY = 300f, weaponRange = 1900f, prefire = true,
        )
        assertTrue(far.impacts.none {
            (it.hit.key as? com.fs.starfarer.api.combat.WeaponAPI)?.id == "payload"
        })
        val (near, _) = demBeamForecast(
            dem, behavior, 360f, targetX = -400f, targetY = 300f, weaponRange = 1900f, prefire = true,
        )
        assertTrue(near.impacts.any {
            (it.hit.key as? com.fs.starfarer.api.combat.WeaponAPI)?.id == "payload"
        })
    }

    @Test
    fun demFadeOutEngineBeamObeysCarrierLifetime() {
        val dem = org.mockito.Mockito.mock(com.fs.starfarer.api.impl.combat.dem.DEMScript::class.java)
        val behavior = org.json.JSONObject()
            .put("fadeOutEngineWhenFiring", true)
            .put("payloadWeaponId", "payload")
        val (forecast, _) = demBeamForecast(
            dem, behavior, 360f, targetY = 300f,
            beamTo = org.lwjgl.util.vector.Vector2f(0f, 300f), carrierLife = 0.02f,
        )
        val beamImpacts = forecast.impacts.filter { it.hit.key is com.fs.starfarer.api.combat.BeamAPI }
        assertEquals(1, beamImpacts.size)
        assertEquals(2f, beamImpacts.first().hit.damage, 0.01f)
    }

    @Test
    fun sampleKeepsLineageAncestorOfImpactingChildInThreatAndActiveKeys() {
        val ship = deepShip(0, 0f, 0f)
        val enemy = deepShip(1, 800f, 0f)
        val carrier = deepMock(com.fs.starfarer.api.combat.MissileAPI::class.java)
        given(carrier.isMirv).thenReturn(true)
        given(carrier.spec.behaviorJSON).thenReturn(org.json.JSONObject().put("projectileSpec", "warhead"))
        given(carrier.source).thenReturn(enemy)
        given(carrier.projectileSpecId).thenReturn("carrier")
        given(carrier.location).thenReturn(org.lwjgl.util.vector.Vector2f(120f, 0f))
        given(carrier.collisionClass).thenReturn(CollisionClass.NONE)
        given(carrier.owner).thenReturn(1)
        val child = deepMock(com.fs.starfarer.api.combat.DamagingProjectileAPI::class.java)
        given(child.location).thenReturn(org.lwjgl.util.vector.Vector2f(100f, 0f))
        given(child.velocity).thenReturn(org.lwjgl.util.vector.Vector2f(-100f, 0f))
        given(child.owner).thenReturn(1)
        given(child.collisionClass).thenReturn(CollisionClass.PROJECTILE_FF)
        given(child.explosionSpecIfExplosion).thenReturn(null)
        given(child.projectileSpec).thenReturn(null)
        given(child.damageAmount).thenReturn(100f)
        given(child.damageType).thenReturn(DamageType.ENERGY)
        given(child.damage.stats).thenReturn(null)
        given(child.source).thenReturn(enemy)
        given(child.projectileSpecId).thenReturn("warhead")
        val engine = org.mockito.Mockito.mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
        given(engine.ships).thenReturn(listOf(ship, enemy))
        given(engine.projectiles).thenReturn(emptyList())
        given(engine.missiles).thenReturn(listOf(carrier))
        given(engine.beams).thenReturn(emptyList())
        given(engine.getTotalElapsedTime(false)).thenReturn(0f)
        val threat = PhaseThreat()
        val window = Window(0f, 1.5f)
        threat.sample(engine, ship, window)

        given(engine.projectiles).thenReturn(listOf(child))
        given(engine.missiles).thenReturn(emptyList())
        given(engine.getTotalElapsedTime(false)).thenReturn(0.1f)
        val forecast = threat.sample(engine, ship, window)!!

        assertTrue(forecast.impacts.single().hit.key === child)
        assertTrue(forecast.threatKeys.any { it === carrier })
        assertTrue(forecast.activeThreatKeys.any { it === carrier })
        assertTrue(forecast.activeThreatKeys.any { it === child })
        assertTrue(forecast.impactAncestorKeys[child]?.any { it === carrier } == true)
    }

    private fun <T> given(call: T) = org.mockito.Mockito.`when`(call)

    private fun <T : Any> deepMock(type: Class<T>): T =
        org.mockito.Mockito.mock(type, org.mockito.Mockito.RETURNS_DEEP_STUBS)

    private fun deepShip(owner: Int, x: Float, y: Float): com.fs.starfarer.api.combat.ShipAPI {
        val ship = deepMock(com.fs.starfarer.api.combat.ShipAPI::class.java)
        given(ship.owner).thenReturn(owner)
        given(ship.isAlive).thenReturn(true)
        given(ship.location).thenReturn(org.lwjgl.util.vector.Vector2f(x, y))
        given(ship.velocity).thenReturn(org.lwjgl.util.vector.Vector2f())
        given(ship.collisionRadius).thenReturn(10f)
        given(ship.hitpoints).thenReturn(1000f)
        given(ship.mutableStats.timeMult.modifiedValue).thenReturn(1f)
        given(ship.mutableStats.minArmorFraction.modifiedValue).thenReturn(0f)
        given(ship.mutableStats.maxArmorDamageReduction.modifiedValue).thenReturn(0.85f)
        given(ship.mutableStats.armorDamageTakenMult.modifiedValue).thenReturn(1f)
        given(ship.mutableStats.kineticArmorDamageTakenMult.modifiedValue).thenReturn(1f)
        given(ship.mutableStats.hullDamageTakenMult.modifiedValue).thenReturn(1f)
        given(ship.mutableStats.beamDamageTakenMult.modifiedValue).thenReturn(1f)
        given(ship.mutableStats.projectileDamageTakenMult.modifiedValue).thenReturn(1f)
        given(ship.armorGrid.grid).thenReturn(emptyArray())
        given(ship.allWeapons).thenReturn(emptyList())
        return ship
    }

    @Test
    fun `光束时间片按完整攻击汇总并绑定载荷祖先`() {
        val beam = Any()
        val carrier = Any()
        val unrelated = Any()
        val impacts = (0 until 6).flatMap { index ->
            val beamImpact = PhaseThreat.Impact(
                Hit(beam, index * 0.1f, 40f, DamageType.ENERGY, stage = Stage.BEAM),
                PhaseThreat.Loss(hull = 40f),
            )
            if (index == 2) {
                listOf(
                    PhaseThreat.Impact(Hit(unrelated, 0.2f, 10f, DamageType.ENERGY), PhaseThreat.Loss(hull = 10f)),
                    beamImpact,
                )
            } else listOf(beamImpact)
        }
        val forecast = PhaseThreat.Forecast(
            impacts, PhaseThreat.Loss(hull = 250f), PhaseThreat.Loss(), PhaseThreat.Loss(),
            PhaseThreat.Loss(), 750f, null, null,
            impactAncestorKeys = mapOf(beam to setOf(carrier)),
        )

        assertEquals(setOf(beam, carrier), forecast.significantThreatKeys(200f))
    }

    @Test
    fun `只有多个轻微攻击累计达到门槛时才按贡献顺序绑定`() {
        val first = Any()
        val second = Any()
        val zero = Any()
        val impacts = listOf(
            PhaseThreat.Impact(Hit(first, 0.1f, 40f, DamageType.ENERGY), PhaseThreat.Loss(hull = 40f)),
            PhaseThreat.Impact(Hit(zero, 0.2f, 0f, DamageType.ENERGY), PhaseThreat.Loss()),
            PhaseThreat.Impact(Hit(second, 0.3f, 60f, DamageType.ENERGY), PhaseThreat.Loss(hull = 60f)),
        )
        val forecast = PhaseThreat.Forecast(
            impacts, PhaseThreat.Loss(hull = 100f), PhaseThreat.Loss(), PhaseThreat.Loss(),
            PhaseThreat.Loss(), 900f, null, null,
        )

        assertEquals(setOf(first, second), forecast.significantThreatKeys(90f))
    }

    @Test
    fun `致命攻击集合不受单片显著门槛限制且不绑定致命后的攻击`() {
        val first = Any()
        val second = Any()
        val later = Any()
        val impacts = listOf(
            PhaseThreat.Impact(Hit(first, 0.2f, 50f, DamageType.ENERGY), PhaseThreat.Loss(hull = 50f)),
            PhaseThreat.Impact(Hit(second, 0.8f, 60f, DamageType.ENERGY), PhaseThreat.Loss(hull = 60f)),
            PhaseThreat.Impact(Hit(later, 1.2f, 500f, DamageType.ENERGY), PhaseThreat.Loss(hull = 500f)),
        )
        val forecast = PhaseThreat.Forecast(
            impacts, PhaseThreat.Loss(hull = 610f), PhaseThreat.Loss(), PhaseThreat.Loss(),
            PhaseThreat.Loss(), 0f, null, 0.8f,
        )

        assertEquals(setOf(first, second), forecast.significantThreatKeys(200f))
    }

    private fun demBeamForecast(
        ai: com.fs.starfarer.api.combat.MissileAIPlugin,
        behavior: org.json.JSONObject,
        turnRate: Float,
        overlappingMissileAt: org.lwjgl.util.vector.Vector2f? = null,
        targetX: Float = 0f,
        targetY: Float = 0f,
        targetOther: Boolean = false,
        carrierLife: Float = 1f,
        prefire: Boolean = false,
        beamTo: org.lwjgl.util.vector.Vector2f? = null,
        weaponRange: Float = 1000f,
        droneVelocity: org.lwjgl.util.vector.Vector2f? = null,
        droneAngularVelocity: Float = 0f,
        fireAfterMissileRemoved: Boolean = false,
    ): Pair<PhaseThreat.Forecast, com.fs.starfarer.api.combat.MissileAPI> {
        val previousSettings = com.fs.starfarer.api.Global.getSettings()
        val settings = org.mockito.Mockito.mock(com.fs.starfarer.api.SettingsAPI::class.java)
        given(settings.getFloat("dpsToHitStrengthMult")).thenReturn(1f)
        com.fs.starfarer.api.Global.setSettings(settings)
        try {
            val ship = deepShip(0, targetX, targetY)
            val drone = deepShip(1, 500f, 300f)
            if (droneVelocity != null) given(drone.velocity).thenReturn(droneVelocity)
            given(drone.angularVelocity).thenReturn(droneAngularVelocity)
            val weapon = deepMock(com.fs.starfarer.api.combat.WeaponAPI::class.java)
            val spec = org.mockito.Mockito.mock(com.fs.starfarer.api.loading.BeamWeaponSpecAPI::class.java)
            val beam = deepMock(com.fs.starfarer.api.combat.BeamAPI::class.java)
            val targetingWeapon = deepMock(com.fs.starfarer.api.combat.WeaponAPI::class.java)
            val targetingSpec = org.mockito.Mockito.mock(com.fs.starfarer.api.loading.BeamWeaponSpecAPI::class.java)
            val targetingBeam = deepMock(com.fs.starfarer.api.combat.BeamAPI::class.java)
            val missile = deepMock(com.fs.starfarer.api.combat.MissileAPI::class.java)
            val engine = org.mockito.Mockito.mock(com.fs.starfarer.api.combat.CombatEngineAPI::class.java)
            given(drone.allWeapons).thenReturn(if (prefire) listOf(weapon, targetingWeapon) else listOf(weapon))
            given(weapon.ship).thenReturn(drone)
            given(weapon.spec).thenReturn(spec)
            given(weapon.id).thenReturn("payload")
            given(weapon.isBeam).thenReturn(true)
            given(weapon.isFiring).thenReturn(!prefire)
            given(weapon.beams).thenReturn(emptyList())
            given(weapon.location).thenReturn(org.lwjgl.util.vector.Vector2f(500f, 300f))
            given(weapon.currAngle).thenReturn(180f)
            given(weapon.range).thenReturn(weaponRange)
            given(weapon.damage.damage).thenReturn(100f)
            given(weapon.damageType).thenReturn(DamageType.ENERGY)
            given(weapon.distanceFromArc(org.mockito.ArgumentMatchers.any())).thenReturn(100f)
            given(spec.collisionClass).thenReturn(CollisionClass.RAY)
            val damageTarget = if (targetOther) deepShip(0, 1000f, 1000f) else ship
            val lockPoint = if (targetOther) org.lwjgl.util.vector.Vector2f(1000f, 1000f)
            else beamTo ?: org.lwjgl.util.vector.Vector2f(0f, 300f)
            given(beam.weapon).thenReturn(weapon)
            given(beam.source).thenReturn(null)
            given(beam.from).thenReturn(org.lwjgl.util.vector.Vector2f(500f, 300f))
            given(beam.to).thenReturn(lockPoint)
            given(beam.width).thenReturn(10f)
            given(beam.brightness).thenReturn(1f)
            given(beam.damage.damage).thenReturn(100f)
            given(beam.damage.type).thenReturn(DamageType.ENERGY)
            given(beam.damageTarget).thenReturn(damageTarget)
            given(targetingWeapon.ship).thenReturn(drone)
            given(targetingWeapon.spec).thenReturn(targetingSpec)
            given(targetingWeapon.id).thenReturn("targeting")
            given(targetingWeapon.isBeam).thenReturn(true)
            given(targetingWeapon.isFiring).thenReturn(true)
            given(targetingWeapon.location).thenReturn(org.lwjgl.util.vector.Vector2f(500f, 300f))
            given(targetingWeapon.range).thenReturn(1000f)
            given(targetingSpec.collisionClass).thenReturn(CollisionClass.RAY)
            given(targetingBeam.weapon).thenReturn(targetingWeapon)
            given(targetingBeam.source).thenReturn(null)
            given(targetingBeam.from).thenReturn(org.lwjgl.util.vector.Vector2f(500f, 300f))
            given(targetingBeam.to).thenReturn(lockPoint)
            given(targetingBeam.width).thenReturn(10f)
            given(targetingBeam.brightness).thenReturn(1f)
            given(targetingBeam.damage.damage).thenReturn(0f)
            given(targetingBeam.damage.type).thenReturn(DamageType.ENERGY)
            given(targetingBeam.damageTarget).thenReturn(damageTarget)
            given(missile.unwrappedMissileAI).thenReturn(ai)
            given(missile.spec.behaviorJSON).thenReturn(behavior)
            given(missile.owner).thenReturn(1)
            given(missile.location).thenReturn(org.lwjgl.util.vector.Vector2f(500f, 300f))
            given(missile.collisionClass).thenReturn(CollisionClass.NONE)
            given(missile.explosionSpecIfExplosion).thenReturn(null)
            given(missile.maxTurnRate).thenReturn(turnRate)
            given(missile.maxFlightTime).thenReturn(carrierLife)
            val overlapping = overlappingMissileAt?.let { location ->
                deepMock(com.fs.starfarer.api.combat.MissileAPI::class.java).also { other ->
                    given(other.unwrappedMissileAI).thenReturn(ai)
                    given(other.spec.behaviorJSON).thenReturn(behavior)
                    given(other.owner).thenReturn(1)
                    given(other.location).thenReturn(location)
                    given(other.collisionClass).thenReturn(CollisionClass.NONE)
                    given(other.explosionSpecIfExplosion).thenReturn(null)
                    given(other.maxTurnRate).thenReturn(turnRate)
                    given(other.maxFlightTime).thenReturn(carrierLife)
                }
            }
            given(engine.ships).thenReturn(listOf(ship))
            given(engine.projectiles).thenReturn(emptyList())
            given(engine.missiles).thenReturn(listOfNotNull(missile, overlapping))
            val signaling = prefire || fireAfterMissileRemoved
            given(engine.beams).thenReturn(listOf(if (signaling) targetingBeam else beam))
            val threat = PhaseThreat()
            if (signaling) {
                threat.sample(engine, ship, Window(0f, 0.4f))
                if (fireAfterMissileRemoved) {
                    given(engine.beams).thenReturn(listOf(beam))
                    given(engine.missiles).thenReturn(emptyList())
                }
                given(engine.getTotalElapsedTime(false)).thenReturn(2.5f)
                return threat.sample(engine, ship, Window(0f, 1.5f))!! to missile
            }
            return threat.sample(engine, ship, Window(0f, 0.4f))!! to missile
        } finally {
            com.fs.starfarer.api.Global.setSettings(previousSettings)
        }
    }
}
