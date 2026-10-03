package com.awaz.app.event

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class KeyHoldDetectorTest {

    private class TestClock(var currentTimeMs: Long = 0L) : Clock {
        override fun now(): Long = currentTimeMs

        fun advance(ms: Long) {
            currentTimeMs += ms
        }
    }

    private class TestScheduler(private val clock: TestClock) : Scheduler {
        class ScheduledTask(
            val scheduledExecutionTime: Long,
            val action: () -> Unit
        ) : Cancellable {
            var isCancelled = false
            override fun cancel() {
                isCancelled = true
            }
        }

        val tasks = mutableListOf<ScheduledTask>()

        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val scheduled = ScheduledTask(clock.now() + delayMs, task)
            tasks.add(scheduled)
            return scheduled
        }

        fun triggerPendingTasks() {
            val now = clock.now()
            val iterator = tasks.iterator()
            while (iterator.hasNext()) {
                val t = iterator.next()
                if (!t.isCancelled && t.scheduledExecutionTime <= now) {
                    t.action()
                    iterator.remove()
                }
            }
        }
    }

    private lateinit var clock: TestClock
    private lateinit var scheduler: TestScheduler
    private lateinit var detector: KeyHoldDetector

    @Before
    fun setUp() {
        clock = TestClock(1000L)
        scheduler = TestScheduler(clock)
        detector = KeyHoldDetector(clock, scheduler, holdThresholdMs = 800L)
    }

    @Test
    fun testShortPress_yieldsRaiseVolumeOnce() {
        detector.onKeyDown(repeatCount = 0)
        clock.advance(200L)
        val action = detector.onKeyUp()
        assertEquals(KeyAction.RaiseVolume, action)
    }

    @Test
    fun testHoldFor800ms_yieldsTriggerOnceWithNoRaiseVolume() {
        var triggerCount = 0
        detector.setOnTriggerCallback { triggerCount++ }

        detector.onKeyDown(repeatCount = 0)
        clock.advance(800L)
        scheduler.triggerPendingTasks()

        assertEquals(1, triggerCount)

        val upAction = detector.onKeyUp()
        assertEquals(KeyAction.None, upAction)
        assertEquals(1, triggerCount)
    }

    @Test
    fun testRepeatDownEvents_doNotDoubleFire() {
        var triggerCount = 0
        detector.setOnTriggerCallback { triggerCount++ }

        detector.onKeyDown(repeatCount = 0)
        clock.advance(100L)
        detector.onKeyDown(repeatCount = 1)
        clock.advance(100L)
        detector.onKeyDown(repeatCount = 2)

        clock.advance(600L) // Total 800ms
        scheduler.triggerPendingTasks()

        assertEquals(1, triggerCount)

        val upAction = detector.onKeyUp()
        assertEquals(KeyAction.None, upAction)
    }

    @Test
    fun testTwoConsecutiveHolds_yieldTwoTriggers() {
        var triggerCount = 0
        detector.setOnTriggerCallback { triggerCount++ }

        // Hold 1
        detector.onKeyDown(repeatCount = 0)
        clock.advance(850L)
        scheduler.triggerPendingTasks()
        detector.onKeyUp()

        assertEquals(1, triggerCount)

        // Hold 2
        clock.advance(300L)
        detector.onKeyDown(repeatCount = 0)
        clock.advance(850L)
        scheduler.triggerPendingTasks()
        detector.onKeyUp()

        assertEquals(2, triggerCount)
    }

    @Test
    fun testReleaseAt799ms_yieldsRaiseVolume() {
        var triggerCount = 0
        detector.setOnTriggerCallback { triggerCount++ }

        detector.onKeyDown(repeatCount = 0)
        clock.advance(799L)
        val upAction = detector.onKeyUp()

        assertEquals(KeyAction.RaiseVolume, upAction)
        assertEquals(0, triggerCount)
    }

    @Test
    fun testHoldThatReaches800ms_yieldsTrigger() {
        var triggerCount = 0
        detector.setOnTriggerCallback { triggerCount++ }

        detector.onKeyDown(repeatCount = 0)
        clock.advance(800L)
        val timeoutAction = detector.onScheduledTimeout()

        assertEquals(KeyAction.Trigger, timeoutAction)
        assertEquals(1, triggerCount)

        val upAction = detector.onKeyUp()
        assertEquals(KeyAction.None, upAction)
    }
}
