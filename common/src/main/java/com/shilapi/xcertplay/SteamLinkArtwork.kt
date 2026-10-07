// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** Original receiver illustration, drawn once per invalidation without bitmaps or timers. */
internal class SteamLinkArtwork(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(25f, 20f, 190f, 170f,
            intArrayOf(0x847EA7E7.toInt(), 0x345D709D, 0x545C92B1), null, Shader.TileMode.CLAMP)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDCE0EDFF.toInt(); style = Paint.Style.STROKE
        strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val route = Path().apply {
        moveTo(49f, 103f); lineTo(70f, 85f); lineTo(89f, 97f); lineTo(119f, 67f)
        moveTo(109f, 67f); lineTo(119f, 67f); lineTo(119f, 77f)
    }
    private val rect = RectF()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 220f, height / 180f)
        canvas.save(); canvas.translate((width - 220f * scale) / 2f, (height - 180f * scale) / 2f)
        canvas.scale(scale, scale)
        stroke.color = 0x3888B6FF
        canvas.drawCircle(110f, 90f, 78f, stroke); canvas.drawCircle(110f, 90f, 62f, stroke)
        stroke.color = 0xDCE0EDFF.toInt()
        rect.set(28f, 41f, 169f, 127f)
        canvas.drawRoundRect(rect, 17f, 17f, fill); canvas.drawRoundRect(rect, 17f, 17f, stroke)
        canvas.drawPath(route, stroke)
        canvas.drawLine(78f, 140f, 118f, 140f, stroke); canvas.drawLine(98f, 128f, 98f, 140f, stroke)
        rect.set(143f, 76f, 191f, 155f)
        fill.color = Color.WHITE
        canvas.drawRoundRect(rect, 12f, 12f, fill); canvas.drawRoundRect(rect, 12f, 12f, stroke)
        canvas.drawLine(157f, 85f, 177f, 85f, stroke)
        canvas.drawCircle(167f, 139f, 2f, stroke)
        canvas.drawArc(153f, 102f, 181f, 125f, 215f, 110f, false, stroke)
        canvas.drawArc(160f, 109f, 174f, 121f, 215f, 110f, false, stroke)
        canvas.restore()
    }
}
