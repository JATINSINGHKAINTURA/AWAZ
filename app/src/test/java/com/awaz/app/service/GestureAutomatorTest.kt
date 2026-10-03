package com.awaz.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GestureAutomatorTest {

    private lateinit var automator: GestureAutomator

    @Before
    fun setUp() {
        automator = GestureAutomator { null }
    }

    @Test
    fun tapCoordinates_whenServiceNull_returnsFalse() {
        assertFalse(automator.tapCoordinates(100f, 200f))
    }

    @Test
    fun swipe_whenServiceNull_returnsFalse() {
        assertFalse(automator.swipe(100f, 500f, 100f, 200f))
    }

    @Test
    fun longPressCoordinates_whenServiceNull_returnsFalse() {
        assertFalse(automator.longPressCoordinates(150f, 250f))
    }

    @Test
    fun doubleTapCoordinates_whenServiceNull_returnsFalse() {
        assertFalse(automator.doubleTapCoordinates(150f, 250f))
    }

    @Test
    fun pressBack_whenServiceNull_returnsFalse() {
        assertFalse(automator.pressBack())
    }

    @Test
    fun pressHome_whenServiceNull_returnsFalse() {
        assertFalse(automator.pressHome())
    }

    @Test
    fun pressRecents_whenServiceNull_returnsFalse() {
        assertFalse(automator.pressRecents())
    }

    @Test
    fun pressSystemButton_withRecognizedButtons_whenServiceNull_returnsFalse() {
        assertFalse(automator.pressSystemButton("BACK"))
        assertFalse(automator.pressSystemButton("HOME"))
        assertFalse(automator.pressSystemButton("RECENTS"))
    }

    @Test
    fun pressSystemButton_withUnrecognizedButton_returnsFalse() {
        assertFalse(automator.pressSystemButton("UNKNOWN_BUTTON"))
        assertFalse(automator.pressSystemButton("NOTIFICATIONS"))
        assertFalse(automator.pressSystemButton("QUICK_SETTINGS"))
        assertFalse(automator.pressSystemButton("POWER_DIALOG"))
        assertFalse(automator.pressSystemButton("VOLUME_MUTE"))
    }

    @Test
    fun findFirstScrollableNode_whenNodeNull_returnsNull() {
        assertNull(automator.findFirstScrollableNode(null))
    }

    @Test
    fun scroll_whenServiceNull_fallsBackGracefullyAndReturnsFalse() {
        assertFalse(automator.scroll(ScrollDirection.FORWARD))
        assertFalse(automator.scroll(ScrollDirection.BACKWARD))
    }
}
