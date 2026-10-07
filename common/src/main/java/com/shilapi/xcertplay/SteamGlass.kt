// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import com.shilapi.xcertplay.host.R

/** Steam's settings navigation. Stable names are also saved across activity recreation. */
internal enum class SteamSettingsSection(val title: Int, val hint: Int) {
    CONNECTION(R.string.steam_connection, R.string.steam_connection_hint),
    DISPLAY(R.string.steam_display, R.string.steam_display_hint),
    AUDIO(R.string.steam_audio, R.string.steam_audio_hint),
    LOCATION(R.string.steam_location, R.string.steam_location_hint),
    TOOLS(R.string.steam_tools, R.string.steam_tools_hint),
    MORE(R.string.steam_more, R.string.steam_more_hint);

    companion object {
        fun restore(name: String?) = entries.firstOrNull { it.name == name } ?: DISPLAY
    }
}

/** Native, static glass shading: no bitmap capture, RenderEffect or animation loop. */
internal object SteamGlass {
    val text = Color.rgb(244, 248, 255)
    val muted = Color.rgb(184, 199, 222)
    val accent = Color.rgb(189, 219, 255)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun backdrop(): Drawable = AmbientDrawable()
    fun surface(context: Context, selected: Boolean = false, radius: Int = 24): Drawable =
        GlassDrawable(context.resources.displayMetrics.density, selected, radius)
    fun action(context: Context, selected: Boolean = false, radius: Int = 18): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x26D1E9FF), surface(context, selected, radius), null)
    fun styleDialog(context: Context, dialog: android.app.AlertDialog) {
        dialog.window?.setBackgroundDrawable(surface(context, selected = true, radius = 28))
        dialog.window?.setDimAmount(.55f)
        for (which in listOf(android.app.AlertDialog.BUTTON_POSITIVE,
            android.app.AlertDialog.BUTTON_NEGATIVE, android.app.AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.apply {
                setTextColor(SteamGlass.text); isAllCaps = false; minHeight = dp(context, 48)
            }
        }
    }
}

private class AmbientDrawable : Drawable() {
    private val base = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glows = Array(3) { Paint(Paint.ANTI_ALIAS_FLAG) }
    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat().coerceAtLeast(1f)
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        base.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.rgb(19, 29, 51), Color.rgb(35, 41, 67), Color.rgb(12, 26, 42)), null, Shader.TileMode.CLAMP)
        val centers = arrayOf(floatArrayOf(w * .12f, h * .22f), floatArrayOf(w * .83f, h * .05f), floatArrayOf(w * .8f, h * .9f))
        val colors = intArrayOf(0x68778DE3, 0x626F52A8, 0x4845A3B2)
        glows.forEachIndexed { index, paint ->
            paint.shader = RadialGradient(centers[index][0], centers[index][1], maxOf(w, h) * .72f,
                colors[index], Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
    }
    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, base)
        glows.forEach { canvas.drawRect(bounds, it) }
    }
    override fun setAlpha(alpha: Int) { base.alpha = alpha; glows.forEach { it.alpha = alpha }; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { base.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Required by Drawable") override fun getOpacity() = PixelFormat.OPAQUE
}

private class GlassDrawable(private val density: Float, private val selected: Boolean, radius: Int) : Drawable() {
    private val radiusPx = radius * density
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        rect.inset(density * .6f, density * .6f)
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        fill.shader = LinearGradient(0f, bounds.top.toFloat(), bounds.width().toFloat(), bounds.top + h,
            if (selected) intArrayOf(0xA07B9FC4.toInt(), 0x6449699D, 0x585A5D94)
            else intArrayOf(0x666F809E, 0x323A4B69, 0x3C334D67), null, Shader.TileMode.CLAMP)
        edge.shader = LinearGradient(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
            intArrayOf(if (selected) 0xD8E9F9FF.toInt() else 0xA0D8E9FF.toInt(), 0x225A729B, 0x646F9FB7), null, Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) { canvas.drawRoundRect(rect, radiusPx, radiusPx, fill); canvas.drawRoundRect(rect, radiusPx, radiusPx, edge) }
    override fun setAlpha(alpha: Int) { fill.alpha = alpha; edge.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; edge.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Required by Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Six original line glyphs, independent of the upstream ic_dp_* artwork. */
internal class SteamSettingIcon(context: Context, section: SteamSettingsSection) : View(context) {
    private val glyph = Path().apply {
        when (section) {
            SteamSettingsSection.CONNECTION -> {
                moveTo(8f, 4f); lineTo(16f, 12f); lineTo(8f, 20f); lineTo(8f, 4f)
                moveTo(4f, 8f); lineTo(16f, 20f); moveTo(4f, 16f); lineTo(16f, 4f)
            }
            SteamSettingsSection.DISPLAY -> {
                addRoundRect(3f, 4f, 21f, 17f, 3f, 3f, Path.Direction.CW)
                moveTo(12f, 17f); lineTo(12f, 21f); moveTo(8f, 21f); lineTo(16f, 21f)
            }
            SteamSettingsSection.AUDIO -> {
                moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close()
                moveTo(16f, 8f); quadTo(21f, 12f, 16f, 16f)
            }
            SteamSettingsSection.LOCATION -> {
                moveTo(4f, 10f); lineTo(21f, 3f); lineTo(14f, 21f); lineTo(11f, 13f); close()
            }
            SteamSettingsSection.TOOLS -> {
                addRoundRect(5f, 3f, 19f, 21f, 3f, 3f, Path.Direction.CW)
                moveTo(8f, 15f); lineTo(10f, 11f); lineTo(13f, 16f); lineTo(16f, 9f)
            }
            SteamSettingsSection.MORE -> {
                for (x in listOf(5f, 12f, 19f)) addCircle(x, 12f, 1.4f, Path.Direction.CW)
            }
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(191, 220, 251); style = Paint.Style.STROKE
        strokeWidth = 1.65f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width, height) / 26f
        canvas.save(); canvas.translate((width - 24f * scale) / 2f, (height - 24f * scale) / 2f)
        canvas.scale(scale, scale); canvas.drawPath(glyph, paint); canvas.restore()
    }
}
