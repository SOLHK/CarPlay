// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/** The receiver's waiting/recovery UI. Projection itself stays on the underlying TextureView. */
internal class SteamConnectionPanel(
    context: Context,
    private val mode: String,
    private val wireless: Boolean,
    private val onHome: () -> Unit,
    private val onSetup: () -> Unit,
    private val onRetry: () -> Unit,
    private val onRecovery: (SteamRecoveryAction) -> Unit,
) : ScrollView(context) {
    val stage: TextView = text(context.getString(R.string.steam_stage_prepare), 28, true).apply {
        tag = "steam_connection_stage"
    }
    val hint: TextView = text(defaultHint(), 16).apply { tag = "steam_connection_hint" }
    val gestureHint: TextView = text("", 13)
    val recoveryButton = action("", true) {}.apply {
        tag = "steam_connection_recovery"; visibility = View.GONE
    }
    private val retryButton = action(context.getString(R.string.steam_retry_connection), false, onRetry).apply {
        tag = "steam_connection_retry"; visibility = View.GONE
    }
    private val body = column()
    private val artwork = SteamLinkArtwork(context)
    private val details = column()
    private var wide = false
    private var feedback: SteamConnectionFeedback? = null

    init {
        tag = "steam_connection_workspace"
        background = SteamGlass.backdrop()
        isFillViewport = true; isVerticalScrollBarEnabled = false
        isClickable = true
        val shell = column().apply { setPadding(dp(28), dp(24), dp(28), dp(28)) }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("STEAM  /  CarPlay", 22, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(action(context.getString(R.string.back), false, onHome), LinearLayout.LayoutParams(dp(96), -2))
        shell.addView(header)
        shell.addView(text(context.getString(R.string.steam_session_title), 30, true).apply {
            setPadding(0, dp(26), 0, dp(8))
        })
        shell.addView(text(context.getString(R.string.steam_session_mode, mode), 14).apply {
            setPadding(0, 0, 0, dp(24))
        })
        body.background = SteamGlass.surface(context, radius = 32)
        body.gravity = Gravity.CENTER_VERTICAL
        body.setPadding(dp(24), dp(28), dp(24), dp(28))
        details.addView(stage)
        details.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(24) })
        details.addView(recoveryButton, LinearLayout.LayoutParams(-1, -2))
        details.addView(retryButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        details.addView(action(context.getString(R.string.steam_review_connection), false, onSetup),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        shell.addView(body)
        shell.addView(gestureHint.apply { setPadding(0, dp(20), 0, 0); gravity = Gravity.CENTER_HORIZONTAL })
        addView(shell)
        reflow(context.resources.configuration.screenWidthDp >= 760)
    }

    private fun defaultHint() = context.getString(if (wireless)
        R.string.keep_your_iphone_nearby_with_bluetooth_and_wi_fi_on_allow
        else R.string.use_a_usb_data_cable_and_unlock_your_iphone_allow_trust_an)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val next = w >= dp(760)
        if (next != wide) reflow(next)
    }

    private fun reflow(nextWide: Boolean) {
        wide = nextWide
        body.removeAllViews()
        body.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        body.addView(artwork, LinearLayout.LayoutParams(if (wide) dp(248) else -1, dp(if (wide) 230 else 140)).apply {
            if (wide) marginEnd = dp(28) else bottomMargin = dp(20)
        })
        body.addView(details, LinearLayout.LayoutParams(if (wide) 0 else -1, -2, if (wide) 1f else 0f))
    }

    fun show(feedback: SteamConnectionFeedback) {
        if (this.feedback == feedback) return
        this.feedback = feedback
        stage.text = context.getString(feedback.title)
        hint.text = feedback.hint?.let(context::getString) ?: defaultHint()
        stage.setTextColor(if (feedback.hint != null) 0xFFFFD9AD.toInt() else SteamGlass.text)
        retryButton.visibility = if (feedback.hint != null && !feedback.automaticRetry) View.VISIBLE else View.GONE
        val action = feedback.action
        recoveryButton.visibility = if (action == null) View.GONE else View.VISIBLE
        if (action != null) {
            recoveryButton.text = context.getString(when (action) {
                SteamRecoveryAction.WIFI -> R.string.open_car_wi_fi_settings
                SteamRecoveryAction.HOTSPOT -> R.string.open_car_hotspot_settings
                SteamRecoveryAction.PERMISSIONS -> R.string.steam_open_permissions
                SteamRecoveryAction.AUTO_CHANNEL -> R.string.steam_use_auto_channel
                SteamRecoveryAction.RESET -> R.string.reset_carplay_wi_fi
                SteamRecoveryAction.BLUETOOTH -> R.string.open_bluetooth
                SteamRecoveryAction.SETUP -> R.string.steam_review_connection
            })
            recoveryButton.setOnClickListener { onRecovery(action) }
        }
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Int, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(if (bold) SteamGlass.text else SteamGlass.muted)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun action(value: String, primary: Boolean, click: () -> Unit) = Button(context).apply {
        text = value; textSize = 16f; isAllCaps = false; setTextColor(SteamGlass.text)
        background = SteamGlass.action(context, primary, 24); stateListAnimator = null
        minHeight = dp(56); minimumWidth = 0; setPadding(dp(20), dp(12), dp(20), dp(12))
        setOnClickListener { click() }
    }
    private fun dp(value: Int) = SteamGlass.dp(context, value)
}
