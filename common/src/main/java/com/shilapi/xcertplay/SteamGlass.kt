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
    VOICE(R.string.steam_voice, R.string.steam_voice_hint),
    LOCATION(R.string.steam_location, R.string.steam_location_hint),
    TOOLS(R.string.steam_tools, R.string.steam_tools_hint),
    MORE(R.string.steam_more, R.string.steam_more_hint);

    companion object {
        fun restore(name: String?) = entries.firstOrNull { it.name == name } ?: DISPLAY
    }
}

/** Native, static glass shading: no bitmap capture, RenderEffect or animation loop. */
internal object SteamGlass {
    val background = Color.rgb(12, 18, 29)
    val text = Color.rgb(238, 244, 252)
    val muted = Color.rgb(159, 176, 197)
    val accent = Color.rgb(127, 196, 255)
    val warning = Color.rgb(255, 203, 142)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun backdrop(): Drawable = AmbientDrawable()
    fun surface(context: Context, selected: Boolean = false, radius: Int = 20): Drawable =
        GlassDrawable(context.resources.displayMetrics.density, selected, radius)
    fun action(context: Context, selected: Boolean = false, radius: Int = 18): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x287FC4FF), surface(context, selected, radius), null)
    fun primaryAction(context: Context): Drawable = RippleDrawable(ColorStateList.valueOf(0x280C121D),
        android.graphics.drawable.GradientDrawable().apply {
            setColor(accent); cornerRadius = dp(context, 16).toFloat()
        }, null)
    fun styleAction(view: android.widget.TextView, primary: Boolean = false) {
        view.textSize = 16f
        view.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0xFF667B93.toInt(), if (primary) background else text)))
        view.background = if (primary) primaryAction(view.context) else action(view.context)
        view.minimumHeight = dp(view.context, 56)
        view.minimumWidth = 0
        view.setPadding(dp(view.context, 16), dp(view.context, 12), dp(view.context, 16), dp(view.context, 12))
        view.stateListAnimator = null
        if (view is android.widget.Button) view.isAllCaps = false
    }
    fun styleDialog(context: Context, dialog: android.app.AlertDialog) {
        dialog.window?.setBackgroundDrawable(surface(context, radius = 20))
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
    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat().coerceAtLeast(1f)
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        base.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.rgb(17, 29, 46), SteamGlass.background), null, Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, base)
    }
    override fun setAlpha(alpha: Int) { base.alpha = alpha; invalidateSelf() }
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
            if (selected) intArrayOf(0xFF223F5D.toInt(), 0xFF1D3046.toInt())
            else intArrayOf(0xFF1D2B3E.toInt(), 0xFF172335.toInt()), null, Shader.TileMode.CLAMP)
        edge.shader = LinearGradient(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
            intArrayOf(if (selected) 0xA07FC4FF.toInt() else 0x555D7795, 0x243F5570), null, Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) { canvas.drawRoundRect(rect, radiusPx, radiusPx, fill); canvas.drawRoundRect(rect, radiusPx, radiusPx, edge) }
    override fun setAlpha(alpha: Int) { fill.alpha = alpha; edge.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; edge.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Required by Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Original line glyphs shared by navigation and shortcuts. */
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
            SteamSettingsSection.VOICE -> {
                addRoundRect(8f, 3f, 16f, 15f, 4f, 4f, Path.Direction.CW)
                moveTo(5f, 11f); cubicTo(5f, 22f, 19f, 22f, 19f, 11f)
                moveTo(12f, 19f); lineTo(12f, 23f)
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
        color = SteamGlass.accent; style = Paint.Style.STROKE
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
