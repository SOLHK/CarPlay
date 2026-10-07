// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus

internal enum class SteamRecoveryAction { WIFI, HOTSPOT, PERMISSIONS, AUTO_CHANNEL, RESET, BLUETOOTH, SETUP }

/** Decode the controller's raw status before translation; never parse a localized UI label. */
internal data class SteamConnectionFeedback(
    val title: Int,
    val hint: Int? = null,
    val action: SteamRecoveryAction? = null,
    val automaticRetry: Boolean = true,
) {
    companion object {
        fun from(status: CarPlayStatus): SteamConnectionFeedback {
            if (status is CarPlayStatus.Failed) return failure(status)
            return SteamConnectionFeedback(when (status) {
                CarPlayStatus.StartingHotspot -> R.string.steam_stage_wifi
                is CarPlayStatus.HotspotReady, CarPlayStatus.WaitingForPairedIphone -> R.string.steam_stage_phone
                CarPlayStatus.ConnectingBluetooth, CarPlayStatus.Pairing -> R.string.steam_stage_bluetooth
                CarPlayStatus.DiscoveringIphone, CarPlayStatus.WaitingForIphone,
                CarPlayStatus.WaitingForReenumeration, CarPlayStatus.RequestingIphonePermission -> R.string.steam_stage_usb
                CarPlayStatus.RunningWireless, CarPlayStatus.WirelessActive, CarPlayStatus.AttachingNetwork,
                CarPlayStatus.RunningControl, CarPlayStatus.OpeningDataPaths,
                CarPlayStatus.ConnectingControl -> R.string.steam_stage_open
                else -> R.string.steam_stage_prepare
            })
        }

        private fun failure(status: CarPlayStatus.Failed): SteamConnectionFeedback {
            val message = status.message
            fun has(value: String) = message.contains(value, ignoreCase = true)
            fun issue(title: Int, hint: Int, action: SteamRecoveryAction) =
                SteamConnectionFeedback(title, hint, action, automaticRetry = false)
            return when {
                status.wifiResetRequired || has("needs a reset") -> issue(
                    R.string.steam_issue_reset, R.string.steam_issue_reset_hint, SteamRecoveryAction.RESET)
                has("Turn on Wi-Fi") -> issue(
                    R.string.steam_issue_wifi_off, R.string.steam_issue_wifi_off_hint, SteamRecoveryAction.WIFI)
                has("car hotspot is off") -> issue(
                    R.string.steam_issue_hotspot_off, R.string.steam_issue_hotspot_off_hint, SteamRecoveryAction.HOTSPOT)
                has("automatic hotspot startup") || has("could not start its hotspot") || has("waiting for the car hotspot") -> issue(
                    R.string.steam_issue_hotspot_off, R.string.steam_issue_hotspot_off_hint, SteamRecoveryAction.HOTSPOT)
                has("permission") || has("Allow precise Location") || has("Allow Nearby devices") -> issue(
                    R.string.steam_issue_permission, R.string.steam_issue_permission_hint, SteamRecoveryAction.PERMISSIONS)
                has("could not use channel") -> issue(
                    R.string.steam_issue_channel, R.string.steam_issue_channel_hint, SteamRecoveryAction.AUTO_CHANNEL)
                has("unsupported") || has("not supported") || has("unavailable") && has("WifiP2pManager") -> issue(
                    R.string.steam_issue_unsupported, R.string.steam_issue_unsupported_hint, SteamRecoveryAction.SETUP)
                has("createGroup failed") || has("Wi-Fi Direct startup timed out") -> issue(
                    R.string.steam_issue_busy, R.string.steam_issue_busy_hint, SteamRecoveryAction.WIFI)
                has("SSID is not configured") || has("passphrase") || has("hotspot credentials") || has("Manual hotspot") -> issue(
                    R.string.steam_issue_setup, R.string.steam_issue_setup_hint, SteamRecoveryAction.SETUP)
                has("RFCOMM") || has("Bluetooth") || has("no longer paired") -> SteamConnectionFeedback(
                    R.string.steam_issue_phone, R.string.steam_issue_phone_hint, SteamRecoveryAction.BLUETOOTH)
                else -> SteamConnectionFeedback(R.string.steam_issue_retry, R.string.steam_issue_retry_hint,
                    SteamRecoveryAction.SETUP)
            }
        }
    }
}
