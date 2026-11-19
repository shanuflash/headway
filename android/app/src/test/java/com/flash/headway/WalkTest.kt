package com.flash.headway

import com.flash.headway.engine.IST
import com.flash.headway.engine.Schedule
import com.flash.headway.engine.WalkSpeed
import com.flash.headway.engine.changeMinutes
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkTest {
    private fun at(h: Int, mi: Int): Long = LocalDateTime.of(2026, 11, 19, h, mi).atZone(IST).toInstant().toEpochMilli()

    @Test
    fun walkingSpeedScalesTheTimeToChange() {
        // Alandur, platform 2 to 4: about a minute's walk, timed by a rider.
        assertEquals(2, changeMinutes("alandur"))
        assertEquals(1, changeMinutes("alandur", WalkSpeed.FAST))
        assertEquals(3, changeMinutes("alandur", WalkSpeed.SLOW))
        assertEquals(4, changeMinutes("vadapalani", WalkSpeed.FAST))
        assertEquals(9, changeMinutes("vadapalani", WalkSpeed.SLOW))
    }

    @Test
    fun walkingFasterNeverArrivesLater() {
        for (h in listOf(8, 13, 17, 20)) {
            val normal = Schedule().plan("guindy", "porur-junction", at(h, 5))!!
            val fast = Schedule(walk = WalkSpeed.FAST).plan("guindy", "porur-junction", at(h, 5))!!
            val slow = Schedule(walk = WalkSpeed.SLOW).plan("guindy", "porur-junction", at(h, 5))!!
            assertTrue(fast.arr <= normal.arr)
            assertTrue(slow.arr >= normal.arr)
        }
    }
}
