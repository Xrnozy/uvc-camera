package com.example.gabay

import com.example.gabay.camera.FpsCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsCounterTest {
    @Test
    fun tick_beforeOneSecond_returnsNegative() {
        val counter = FpsCounter()
        assertEquals(-1f, counter.tick())
    }

    @Test
    fun tick_afterOneSecond_reportsFps() {
        val counter = FpsCounter()
        repeat(30) { counter.tick() }
        Thread.sleep(1_100)
        val fps = counter.tick()
        assertTrue(fps >= 0f)
    }
}
