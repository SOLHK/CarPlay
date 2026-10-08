package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayVideoLayout
import org.junit.Assert.*
import org.junit.Test

class CarPlayVideoSurfaceTest {
    @Test fun windowCapabilityKeepsTexturesAndSelectsFallbackWithoutHardwareAcceleration() {
        assertEquals(CarPlayVideoSurfaceMode.TEXTURE, carPlayVideoSurfaceMode(true))
        assertEquals(CarPlayVideoSurfaceMode.SURFACE, carPlayVideoSurfaceMode(false))
    }

    @Test fun replacingTextureDetachesBeforeReleaseAndDoesNotReleaseHolderSurface() {
        val texture = Any()
        val holder = Any()
        val events = mutableListOf<Pair<String, Any>>()
        val owner = CarPlayVideoSurfaceOwner<Any>(
            detach = { surface, done -> events.add("detach" to surface); done() }, release = { events.add("release" to it) })
        owner.replace(texture, releaseOnDetach = true)
        owner.replace(holder, releaseOnDetach = false)
        assertEquals(listOf("detach" to texture, "release" to texture), events)
        owner.clear(texture) // The old TextureView may deliver destruction after replacement.
        assertSame(holder, owner.current)
        owner.clear(holder)
        owner.clear() // Activity destruction after surfaceDestroyed must be idempotent.
        assertNull(owner.current)
        assertEquals(listOf("detach" to texture, "release" to texture, "detach" to holder), events)
    }

    @Test fun recreatingHolderIgnoresStaleDestructionAndReusesTheSameSurface() {
        val old = Any()
        val recreated = Any()
        val detached = mutableListOf<Any>()
        val released = mutableListOf<Any>()
        val owner = CarPlayVideoSurfaceOwner<Any>(
            detach = { surface, done -> detached.add(surface); done() }, release = { released.add(it) })
        owner.replace(old, releaseOnDetach = false)
        owner.replace(recreated, releaseOnDetach = false)
        owner.replace(recreated, releaseOnDetach = false)
        owner.clear(old)
        assertSame(recreated, owner.current)
        assertEquals(listOf(old), detached)
        assertTrue(released.isEmpty())
    }

    @Test fun oldWindowCannotReleaseUntilBothDecodersDetachOrClearTheNewWindow() {
        val old = Any()
        val current = Any()
        val acknowledgements = mutableListOf<() -> Unit>()
        val released = mutableListOf<Any>()
        val owner = CarPlayVideoSurfaceOwner<Any>(
            detach = { _, done -> acknowledgements.add(done) }, release = released::add)
        owner.replace(old, true)
        owner.replace(current, true)
        assertTrue(released.isEmpty())
        owner.clear(old)
        assertSame(current, owner.current)
        acknowledgements[0](); acknowledgements[0]()
        assertEquals(listOf(old), released)
        owner.clear()
        assertEquals(listOf(old), released)
        acknowledgements[1]()
        assertEquals(listOf(old, current), released)
    }

    @Test fun fittedSurfaceAndTouchContentShareLetterboxBounds() {
        val content = CarPlayVideoLayout.fit(1920, 1080, 1280, 720 + 80)
        val bounds = CarPlaySurfaceBounds.from(content)
        assertEquals(CarPlaySurfaceBounds(0, 40, 1280, 720), bounds)
        assertFalse(content.contains(640f, 20f))
        assertTrue(content.contains(640f, 400f))
    }

    @Test fun croppingRetainsNegativeOriginAndFullCanvasSize() {
        // A selected CarPlay view area shows the center of a larger canvas. Clamping it to
        // the window would squash the video while touches still reference the full canvas.
        val content = CarPlayVideoLayout(-320f, -180f, 1920f, 1080f)
        assertEquals(CarPlaySurfaceBounds(-320, -180, 1920, 1080), CarPlaySurfaceBounds.from(content))
        val normalizedX = (0f - content.left) / content.width
        val normalizedY = (0f - content.top) / content.height
        assertEquals(1f / 6f, normalizedX, 0.00001f)
        assertEquals(1f / 6f, normalizedY, 0.00001f)
    }
}
