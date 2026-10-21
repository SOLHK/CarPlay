package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real receiver views at large type; generated PNGs are reviewed separately from test success. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarPlayOwnUiScreenshotsTest {
    @Test @Config(qualifiers = "zh-rCN-w1280dp-h720dp")
    fun wideLightHome() = capture("home-wide-light", AppAppearance.LIGHT, "home")

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h720dp")
    fun wideDarkSettings() = capture("settings-wide-dark", AppAppearance.DARK, "settings")

    @Test @Config(qualifiers = "ar-ldrtl-w600dp-h360dp")
    fun compactRtlLightSettings() = capture("settings-compact-ar-light", AppAppearance.LIGHT, "settings")

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h720dp")
    fun wideLightMusic() = capture("music-wide-light", AppAppearance.LIGHT, "music")

    @Test @Config(qualifiers = "en-w600dp-h360dp")
    fun compactDarkMusic() = capture("music-compact-dark", AppAppearance.DARK, "music")

    private fun capture(name: String, appearance: AppAppearance, page: String) {
        val context = RuntimeEnvironment.getApplication()
        val previous = AirPlayPersistence.loadAppAppearance(context)
        AirPlayPersistence.saveAppAppearance(context, appearance)
        RuntimeEnvironment.setFontScale(1.3f)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", page)).setup()
        try {
            val activity = controller.get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val metrics = activity.resources.displayMetrics
            root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            val expectedTitle = when (page) {
                "home" -> R.string.app_name
                "music" -> R.string.music_now_playing
                else -> R.string.settings
            }
            assertTrue(descendants(root).filterIsInstance<TextView>().any {
                it.text.toString() == activity.getString(expectedTitle)
            })
            assertEquals(DiPlayPalette.of(appearance == AppAppearance.DARK).background,
                (root.background as android.graphics.drawable.ColorDrawable).color)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val output = File("build/reports/carplay-ui/$name.png")
                output.parentFile!!.mkdirs()
                FileOutputStream(output).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
        } finally {
            controller.pause().stop().destroy()
            AirPlayPersistence.saveAppAppearance(context, previous)
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
