// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.touchcontrols

import com.metallic.chiaki.lib.ControllerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ControllerStateMergeTest {
    @Test
    fun everyControlsFragmentContributesToTheSessionState() {
        val onScreenControls = MutableStateFlow(
            ControllerState(buttons = ControllerState.BUTTON_CROSS, leftX = 1000.toShort())
        )
        val touchpadControls = MutableStateFlow(
            ControllerState(buttons = ControllerState.BUTTON_TOUCHPAD)
        )
        val result = runBlocking {
            mergeControllerStates(listOf(onScreenControls, touchpadControls)).first()
        }
        assertEquals(ControllerState.BUTTON_CROSS or ControllerState.BUTTON_TOUCHPAD, result.buttons)
        assertEquals(1000.toShort(), result.leftX)
    }

    @Test
    fun copiedControllerStateOwnsItsTouchObjects() {
        val original = ControllerState()
        val copy = original.copy()
        original.startTouch(100U, 200U)
        assertEquals((-1).toByte(), copy.touches[0].id)
    }

    @Test
    fun touchMoveChangesThePublishedStateValue() {
        val original = ControllerState()
        val touchId = original.startTouch(100U, 200U)!!
        val beforeMove = original.copy()
        original.setTouchPos(touchId, 300U, 400U)
        assertNotEquals(beforeMove, original.copy())
    }
}
