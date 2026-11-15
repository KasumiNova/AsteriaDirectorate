package cn.kasuminova.astd.combat.effect.arc.starfallwing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StarfallWingTargetingTest {

    private val speed = 1100f
    private val turnRate = StarfallWingTuning.turnRate(speed)
    private val range = 900f

    private fun score(
        x: Float,
        y: Float,
        vx: Float = 0f,
        vy: Float = 0f,
        radius: Float = 60f,
        facing: Float = 0f,
        remaining: Float = range,
    ) = StarfallWingTargeting.aimScore(x, y, vx, vy, radius, facing, speed, turnRate, remaining)

    @Test
    fun `分组优先级为锁定在前战机居次射程外舰船最后`() {
        val lock = StarfallWingTargeting.classOf(isFighter = false, locked = true, inRange = true)
        val ship = StarfallWingTargeting.classOf(isFighter = false, locked = false, inRange = true)
        val fighter = StarfallWingTargeting.classOf(isFighter = true, locked = false, inRange = true)
        val far = StarfallWingTargeting.classOf(isFighter = false, locked = false, inRange = false)

        assertEquals(StarfallWingShot.AVAILABILITY_LOCK, lock)
        assertEquals(StarfallWingShot.AVAILABILITY_SHIP, ship)
        assertEquals(StarfallWingShot.AVAILABILITY_FIGHTER, fighter)
        assertEquals(StarfallWingShot.AVAILABILITY_SHIP_OUT_OF_RANGE, far)
        assertTrue(lock < ship && ship < fighter && fighter < far)
    }

    @Test
    fun `射程外的舰船锁定退回普通分组`() {
        val demoted = StarfallWingTargeting.classOf(isFighter = false, locked = true, inRange = false)
        assertEquals(StarfallWingTargeting.classOf(isFighter = false, locked = false, inRange = false), demoted)

        val fighterLock = StarfallWingTargeting.classOf(isFighter = true, locked = true, inRange = true)
        assertEquals(StarfallWingShot.AVAILABILITY_LOCK, fighterLock)
        val farFighterLock = StarfallWingTargeting.classOf(isFighter = true, locked = true, inRange = false)
        assertEquals(StarfallWingShot.AVAILABILITY_FIGHTER, farFighterLock)
    }

    @Test
    fun `拦截时刻在迎头与静止目标上等于实际飞行时间`() {
        val head = StarfallWingTargeting.interceptTime(600f, 0f, -300f, 0f, speed)
        assertTrue(head > 0f && head < 600f / speed, "迎头接近目标的拦截时间应短于落点飞行时间，实际 $head")

        val still = StarfallWingTargeting.interceptTime(600f, 800f, 0f, 0f, speed)
        assertEquals(1000f / speed, still, 1e-4f)

        assertEquals(0f, StarfallWingTargeting.interceptTime(0f, 0f, 500f, 500f, speed), 1e-6f)
    }

    @Test
    fun `目标以高于弹速的速度拉开距离时无拦截解`() {
        val fleeing = StarfallWingTargeting.interceptTime(300f, 0f, speed * 1.5f, 0f, speed)
        assertTrue(fleeing.isNaN(), "追不上的目标应无解，实际 $fleeing")

        val slowFlee = StarfallWingTargeting.interceptTime(300f, 0f, speed * 0.5f, 0f, speed)
        assertTrue(slowFlee > 0f && slowFlee.isFinite(), "仍可追上的目标应有解，实际 $slowFlee")
    }

    @Test
    fun `高速脱离的近处目标让位给可命中的远处静止目标`() {
        val nearFleeing = score(315f, 0f, vx = speed * 2f)
        val farStill = score(585f, 100f)

        assertTrue(farStill > nearFleeing, "远处静止目标 $farStill 应优于近处脱离目标 $nearFleeing")
    }

    @Test
    fun `横移目标按拦截点而非当前位置评分`() {
        val still = score(600f, 0f, radius = 40f)
        val crossing = score(600f, 0f, vy = 400f, radius = 40f)

        assertTrue(still > crossing, "横移目标需要提前量，评分应低于同位置静止目标")
        assertTrue(crossing > 0f, "可拦截的横移目标仍应保留正分，实际 $crossing")
    }

    @Test
    fun `可命中的近处小目标优于可命中的远处大目标`() {
        val nearSmall = score(200f, 0f, radius = 10f)
        val farLarge = score(700f, 0f, radius = 100f)

        assertTrue(nearSmall > farLarge, "近处小目标 $nearSmall 应优于远处大目标 $farLarge")
    }

    @Test
    fun `转向耗时超出飞行时间的目标评分被压低`() {
        val ahead = score(100f, 0f)
        val behind = score(-100f, 0f, facing = 0f)

        assertTrue(behind < ahead, "背后目标 $behind 应劣于正前目标 $ahead")
    }

    @Test
    fun `剩余射程不足的目标按不可达压权`() {
        val plenty = score(600f, 0f, radius = 40f, remaining = 900f)
        val short = score(600f, 0f, radius = 40f, remaining = 100f)

        assertTrue(short < plenty, "剩余射程不足时评分应下降，实际 $short 对 $plenty")
    }

    @Test
    fun `双方都在射程内时射程余量更大的目标评分更高`() {
        val inner = score(300f, 0f, radius = 40f, remaining = 900f)
        val edge = score(880f, 0f, radius = 40f, remaining = 900f)

        assertTrue(inner > edge, "射程边缘目标 $edge 应劣于内侧目标 $inner")
    }

    @Test
    fun `同组换锁需要分数优势超过阈值`() {
        assertFalse(StarfallWingTargeting.outbids(3.2f, 3.1f), "近似等价的候选不应抢走现目标")
        assertFalse(StarfallWingTargeting.outbids(3.0f, 3.0f))
        assertTrue(StarfallWingTargeting.outbids(4.5f, 2.5f), "显著更优的候选应当换锁")
    }

    @Test
    fun `战机占用互斥且只有持有者能释放`() {
        val first = StarfallWingShot(StarfallWingTuning.ShuttleType.BLUE, 1f, speed, range, 0f)
        val second = StarfallWingShot(StarfallWingTuning.ShuttleType.RED, 1f, speed, range, 0f)
        val pool = StarfallWingTargetPool<String>()

        assertTrue(pool.isOpen("wing", first))
        pool.claim("wing", first)
        assertTrue(pool.isOpen("wing", first), "持有者应能继续锁定自己占用的战机")
        assertFalse(pool.isOpen("wing", second), "第二发不得占用同一架战机")
        assertTrue(pool.isOpen("other", second), "其他战机不受占用影响")

        pool.release("wing", second)
        assertFalse(pool.isOpen("wing", second), "非持有者的释放不得解除占用")

        pool.release("wing", first)
        assertTrue(pool.isOpen("wing", second), "持有者释放后其他星梭可占用")
    }

    @Test
    fun `释放不存在的占用不影响该战机的现有持有者`() {
        val holder = StarfallWingShot(StarfallWingTuning.ShuttleType.GOLD, 1f, speed, range, 0f)
        val other = StarfallWingShot(StarfallWingTuning.ShuttleType.PURPLE, 1f, speed, range, 0f)
        val pool = StarfallWingTargetPool<Int>()

        pool.claim(7, holder)
        pool.release(null, other)
        pool.release(9, other)

        assertTrue(pool.isOpen(7, holder))
        assertFalse(pool.isOpen(7, other))
    }
}
