package cn.kasuminova.astd.combat.hullmods.arc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ASTDVectorBoostThrusterHullModTest {

    @Test
    fun `航速比例夹到 0-1 并线性映射机动与质量`() {
        assertEquals(0f, VectorBoostThrusterTuning.speedRatio(80f, 0f))
        assertEquals(0f, VectorBoostThrusterTuning.speedRatio(Float.NaN, 100f))
        assertEquals(0.5f, VectorBoostThrusterTuning.speedRatio(50f, 100f))
        assertEquals(1f, VectorBoostThrusterTuning.speedRatio(140f, 100f))

        assertEquals(50f, VectorBoostThrusterTuning.maneuverPercent(0.5f))
        assertEquals(100f, VectorBoostThrusterTuning.maneuverPercent(1.4f))
        assertEquals(1.5f, VectorBoostThrusterTuning.massMultiplier(0.5f))
        assertEquals(2f, VectorBoostThrusterTuning.massMultiplier(2f))
        assertEquals(200f, VectorBoostThrusterTuning.scaledMass(100f, 1f))
        assertEquals(50f, vectorSpeed(30f, 40f))
    }

    @Test
    fun `硬辐能 0 满加成 80 衰减到 10 更高保持`() {
        assertEquals(50f, VectorBoostThrusterTuning.zeroFluxFlat(0f))
        assertEquals(1f, VectorBoostThrusterTuning.zeroFluxMult(0f))
        assertEquals(50f, VectorBoostThrusterTuning.zeroFluxFlat(Float.NaN))

        val midpoint = VectorBoostThrusterTuning.zeroFluxFlat(0.40f)
        assertEquals(27.5f, midpoint, 1e-3f)
        assertEquals(0.55f, VectorBoostThrusterTuning.zeroFluxMult(0.40f), 1e-3f)

        assertEquals(5f, VectorBoostThrusterTuning.zeroFluxFlat(0.80f), 1e-3f)
        assertEquals(0.10f, VectorBoostThrusterTuning.zeroFluxMult(0.80f), 1e-3f)
        assertEquals(5f, VectorBoostThrusterTuning.zeroFluxFlat(1.2f), 1e-3f)
    }

    @Test
    fun `质量缓存按舰隔离 系统占用时不写回`() {
        val data = mutableMapOf<String, Any?>()

        assertEquals(800f, VectorBoostThrusterTuning.resolveBaseMass(data, "ship-a", 800f))
        assertEquals(800f, data[VectorBoostThrusterTuning.massBaseKey("ship-a")])
        assertEquals(800f, VectorBoostThrusterTuning.resolveBaseMass(data, "ship-a", 1600f))
        assertEquals(900f, VectorBoostThrusterTuning.resolveBaseMass(data, "ship-b", 900f))
        assertNull(VectorBoostThrusterTuning.resolveBaseMass(data, "ship-c", Float.NaN))
        assertFalse(data.containsKey(VectorBoostThrusterTuning.massBaseKey("ship-c")))

        data[VectorBoostThrusterTuning.massOwnerKey("ship-a")] = true
        assertTrue(VectorBoostThrusterTuning.isMassOwnedBySystem(data, "ship-a"))
        assertFalse(VectorBoostThrusterTuning.isMassOwnedBySystem(data, "ship-b"))

        assertNull(VectorBoostThrusterTuning.releaseBaseMass(data, "ship-a"))
        assertFalse(data.containsKey(VectorBoostThrusterTuning.massBaseKey("ship-a")))
        assertTrue(data.containsKey(VectorBoostThrusterTuning.massOwnerKey("ship-a")))

        assertEquals(900f, VectorBoostThrusterTuning.releaseBaseMass(data, "ship-b"))
        assertFalse(data.containsKey(VectorBoostThrusterTuning.massBaseKey("ship-b")))
        assertNull(VectorBoostThrusterTuning.releaseBaseMass(data, "ship-b"))
    }

}
