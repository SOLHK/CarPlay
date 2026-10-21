package com.shilapi.xcertplay

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DiPlayPaletteTest {
    @Test fun darkPaletteUsesTheCarPlayControlTokens() {
        val palette = DiPlayPalette.DARK
        assertEquals(Color.BLACK, palette.background)
        assertEquals(Color.rgb(28, 28, 30), palette.surface)
        assertEquals(Color.rgb(44, 44, 46), palette.button)
        assertEquals(Color.rgb(58, 58, 60), palette.outline)
        assertEquals(Color.rgb(22, 50, 79), palette.railSelected)
        assertEquals(Color.rgb(52, 100, 150), palette.railSelectedOutline)
        assertEquals(Color.WHITE, palette.primaryText)
        assertEquals(Color.rgb(169, 169, 176), palette.secondaryText)
        assertEquals(Color.rgb(10, 132, 255), palette.accent)
        assertEquals(Color.rgb(255, 184, 107), palette.warning)
        assertEquals(Color.rgb(127, 205, 154), palette.success)
        assertEquals(Color.rgb(190, 45, 45), palette.danger)
        assertEquals(0x330A84FF, palette.ripple)
        assertFalse(palette.systemBarIconsAreDark)
    }

    @Test fun waitingScreenDelegationKeepsItsExactLightAndDarkColors() {
        assertEquals(
            WaitingScreenColors(Color.BLACK, Color.WHITE, Color.rgb(169, 169, 176)),
            WaitingScreenColors.of(night = true),
        )
        assertEquals(
            WaitingScreenColors(Color.rgb(242, 242, 247), Color.BLACK, Color.rgb(90, 90, 99)),
            WaitingScreenColors.of(night = false),
        )
    }

    @Test fun semanticRolesMeetTheirWcagContrastTargets() {
        for (palette in listOf(DiPlayPalette.DARK, DiPlayPalette.LIGHT)) {
            assertContrastAtLeast(palette.primaryText, palette.background, 4.5)
            assertContrastAtLeast(palette.secondaryText, palette.background, 4.5)
            assertContrastAtLeast(palette.accent, palette.background, 4.5)
            assertContrastAtLeast(palette.onAccent, palette.accent, 4.5)
            assertContrastAtLeast(palette.warning, palette.background, 4.5)
            assertContrastAtLeast(palette.success, palette.background, 4.5)
            // The legacy dark danger role is used for graphical/status emphasis, not body text.
            assertContrastAtLeast(palette.danger, palette.background, 3.0)
            assertContrastAtLeast(palette.overlayPrimaryText, palette.overlayBackground, 4.5)
            assertContrastAtLeast(palette.overlaySecondaryText, palette.overlayBackground, 4.5)
            assertContrastAtLeast(palette.overlayAccent, palette.overlayBackground, 4.5)
            assertContrastAtLeast(palette.overlayOnAccent, palette.overlayAccent, 4.5)
            assertContrastAtLeast(palette.overlayDanger, palette.overlayBackground, 3.0)
        }
        assertContrastAtLeast(DiPlayPalette.LIGHT.accentTrack, DiPlayPalette.LIGHT.trackOff, 3.0)
        assertContrastAtLeast(
            DiPlayPalette.LIGHT.overlayAccentTrack,
            DiPlayPalette.LIGHT.overlayTrackOff,
            3.0,
        )
        assertTrue(DiPlayPalette.LIGHT.systemBarIconsAreDark)
    }

    private fun assertContrastAtLeast(foreground: Int, background: Int, minimum: Double) {
        val foregroundLuminance = luminance(foreground)
        val backgroundLuminance = luminance(background)
        val ratio = (max(foregroundLuminance, backgroundLuminance) + 0.05) /
            (min(foregroundLuminance, backgroundLuminance) + 0.05)
        assertTrue("Expected contrast >= $minimum, was $ratio", ratio >= minimum)
    }

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92
            else ((normalized + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }
}
