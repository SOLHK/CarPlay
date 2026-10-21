package com.shilapi.xcertplay

import android.content.Intent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
class CarPlayMusicPageTest {
    @Test fun disconnectedPageShowsNoInventedTrackOrEnabledControls() {
        CarPlayBackgroundSession.clear()
        ClusterMusicState.publish(CarPlayNowPlaying(title = "Stale track"), null)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val controller = openPage()
        try {
            val activity = controller.get()
            assertTrue(texts(activity).any { it.text == activity.getString(R.string.music_no_track) })
            val play = buttons(activity).single { it.contentDescription == activity.getString(R.string.video_play) }
            assertFalse(play.isEnabled)
        } finally {
            controller.pause().stop().destroy()
            ClusterMusicState.publish(CarPlayNowPlaying(), null)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    @Test fun activePageUsesIphoneMetadataAndSendsTheNextCommand() {
        val session = mock(CarPlayController::class.java)
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { }
        CarPlayBackgroundSession.active = true
        ClusterMusicState.publish(CarPlayNowPlaying(title = "Track from iPhone", artist = "Actual artist",
            durationMillis = 120_000L, elapsedMillis = 30_000L, playing = true), null)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val controller = openPage()
        try {
            val activity = controller.get()
            assertTrue(texts(activity).any { it.text == "Track from iPhone" })
            assertTrue(texts(activity).any { it.text == "Actual artist" })
            val next = buttons(activity).single { it.contentDescription == activity.getString(R.string.wheel_key_role_next) }
            assertTrue(next.isEnabled)
            next.performClick()
            verify(session).sendMediaButton(CarPlayMediaButton.NEXT)
        } finally {
            controller.pause().stop().destroy()
            CarPlayBackgroundSession.clear()
            ClusterMusicState.publish(CarPlayNowPlaying(), null)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    private fun openPage() = Robolectric.buildActivity(DiPlayActivity::class.java,
        Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java).putExtra("page", "music")).setup()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun texts(activity: DiPlayActivity) = descendants(activity.window.decorView).filterIsInstance<TextView>()
    private fun buttons(activity: DiPlayActivity) = descendants(activity.window.decorView).filterIsInstance<Button>()
}
