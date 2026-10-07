// SPDX-License-Identifier: AGPL-3.0-only
// Receiver UI uses Steam’s independent glass layout; open-source notices remain in About.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.hud.BydAdbAccess
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.BydFieldSource
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.hud.BydVehicleCapabilities
import com.shilapi.xcertplay.hud.BydVehicleField
import com.shilapi.xcertplay.hud.BydVehicleFieldStore
import com.shilapi.xcertplay.hud.BydVehicleProbeOutcome
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.EvChargingConnectors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** CarPlay receiver, with Steam's native glass settings workspace. */
class DiPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var pendingCarHotspotSetup = false
    private var setupError: String? = null
    private var status: TextView? = null
    private var homeWarning: TextView? = null
    private var homeWifiAction: Button? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var navigationStreamType = 14
    private var testToneTrack: AudioTrack? = null
    private var toneStop: Runnable? = null
    private var exportButton: Button? = null
    private var rootScroll: ScrollView? = null
    private var renderedPage: String? = null
    private var pendingScrollY: Int? = null
    private var settingsSection = SteamSettingsSection.DISPLAY
    private var renderedSettingsSection: SteamSettingsSection? = null
    private val settingsScrollPositions = mutableMapOf<SteamSettingsSection, Int>()
    private var openedFromSettings = false
    private var settingsSummary: TextView? = null
    private data class SettingsNavigationItem(val view: LinearLayout, val label: TextView)
    private data class SettingsWorkspace(
        val configuration: Configuration,
        val root: LinearLayout,
        val scroll: ScrollView,
        val sections: LinearLayout,
        val title: TextView,
        val hint: TextView,
        val summary: TextView,
        val navigation: Map<SteamSettingsSection, SettingsNavigationItem>,
        val horizontalNavigation: HorizontalScrollView?,
    )
    private var settingsWorkspace: SettingsWorkspace? = null
    private var settingsRenderGeneration = 0
    private val audioPreviews = mutableSetOf<AudioChannelPreview>()
    private var bydVehicleAdvancedExpanded = false
    private var adbAccessState: BydAdbAccess.State? = null
    private var adbCheckInProgress = false
    private var adbCheckMayAsk = false
    private var adbCheckFailed = false
    private var vehicleProbeAuthorizationInProgress = false
    private var vehicleProbeInProgress = false
    private var vehicleProbeOutcome: BydVehicleProbeOutcome? = null
    private var adbCheckGeneration = 0
    private var adbStatus: TextView? = null
    private var bydAdbControls: LinearLayout? = null
    private var adbSwitchChangePending = false
    private var pausedForAdbSwitchChange = false
    private var updatingAdbSwitches = false
    private val adbSwitches = mutableMapOf<Int, Pair<Switch, () -> Boolean>>()
    private var hotspotStartupResult: CarHotspotTethering.Result? = null
    @Volatile private var startupHotspotCancelled = false
    @Volatile private var vehicleProbeGeneration = 0
    @Volatile private var vehicleValidationGeneration = 0
    private val vehicleOperationLock = Any()
    private var automaticVehicleValidationStarted = false
    private var automaticVehicleValidationInProgress = false
    private var automaticVehicleValidationPending = false
    private var pendingVehicleReplacement: BydVehicleCapabilities? = null
    // The saved snapshot [pendingVehicleReplacement] was compared with.
    private var pendingVehicleReplacementExpected: BydVehicleCapabilities? = null
    private var pendingVehicleLostFields: Set<BydVehicleField> = emptySet()
    private var defaultVehicleStatus: BydAdbAccess.Status? = null
    private var vehicleDataReconnectPending = false
    private val automaticVehicleValidation = Runnable {
        automaticVehicleValidationPending = false
        validateSavedVehicleConfigurationAutomatically()
    }
    private data class VehicleProbeAttempt(
        val outcome: BydVehicleProbeOutcome,
        val heldCandidate: BydVehicleCapabilities? = null,
        val lostFields: Set<BydVehicleField> = emptySet(),
        val snapshotChanged: Boolean = false,
        val allowedOnlyOnce: Boolean = false,
    )

    private data class VehicleValidationAttempt(
        val status: BydAdbAccess.Status,
        val outcome: BydVehicleProbeOutcome? = null,
        val heldCandidate: BydVehicleCapabilities? = null,
        val lostFields: Set<BydVehicleField> = emptySet(),
        val snapshotChanged: Boolean = false,
        val savedFieldsReadable: Boolean = false,
    )
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) {
            applyLocationReporting(true)
        } else {
            render()
            permissionHelp(getString(R.string.location), getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p))
        }
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession()) {
            openProjection(); finish(); return
        }
        CarPlayCallKeys.install(this)
        WheelKeyService.restoreIfNeeded(this)
        RuntimeDiagnostics.start(this)
        CarPlayProfileDefaults.apply(this)
        languagePreferenceAtCreate = AppLocale.preference(this)
        com.shilapi.xcertplay.hud.BydNavigationOutputs.onAppOpened(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = BG; window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setupError = runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
            android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        bydVehicleAdvancedExpanded = savedInstanceState?.getBoolean("byd_vehicle_advanced") ?: false
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        settingsSection = SteamSettingsSection.restore(savedInstanceState?.getString("steam_settings_section"))
        openedFromSettings = savedInstanceState?.getBoolean("steam_settings_child") ?: false
        SteamSettingsSection.entries.forEach { section ->
            savedInstanceState?.getInt("steam_settings_scroll_${section.name}")?.let { settingsScrollPositions[section] = it }
        }
        render()
        scheduleAutomaticVehicleValidation()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") { returnFromPage(); render() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession()) {
            page = "home"
            openedFromSettings = false
            openProjection(); finish(); return
        }
        rememberSettingsScroll()
        openedFromSettings = false
        page = intent.getStringExtra("page") ?: "home"; render()
        automaticVehicleValidationStarted = false
        scheduleAutomaticVehicleValidation()
        handleWirelessRecovery()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        rememberSettingsScroll()
        outState.putString("page", page)
        outState.putString("steam_settings_section", settingsSection.name)
        outState.putBoolean("steam_settings_child", openedFromSettings)
        settingsScrollPositions.forEach { (section, y) -> outState.putInt("steam_settings_scroll_${section.name}", y) }
        outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup)
        outState.putBoolean("byd_vehicle_advanced", bydVehicleAdvancedExpanded)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    private fun openOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        if (runCatching { startActivity(intent) }.isFailure) {
            android.widget.Toast.makeText(this, R.string.center_map_no_permission_screen, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        CenterMapOverlay.onDiPlayScreenShown()
    }

    override fun onStop() {
        startupHotspotCancelled = true
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && !adbSwitchChangePending && !pausedForAdbSwitchChange &&
            (page == "home" || page == "settings" || page == "features" || page == "connection")) render()
        pausedForAdbSwitchChange = false
        if (initialLaunch) {
            initialLaunch = false
            startCarHotspotOnLaunch()
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(DiPlayPreferences.autoConnectWireless(this)) }
            }
        }
    }
    override fun onPause() {
        cancelSiriLearning()
        pausedForAdbSwitchChange = adbSwitchChangePending
        handler.removeCallbacks(tick)
        audioPreviews.forEach { it.stop() }
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(automaticVehicleValidation)
        audioPreviews.forEach { it.close() }
        audioPreviews.clear()
        settingsRenderGeneration++
        settingsWorkspace = null
        adbCheckGeneration++
        synchronized(vehicleOperationLock) {
            vehicleProbeGeneration++
            vehicleValidationGeneration++
        }
        adbCheckInProgress = false
        vehicleProbeAuthorizationInProgress = false
        vehicleProbeInProgress = false
        automaticVehicleValidationInProgress = false
        super.onDestroy()
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        // A restore still waiting for layout keeps its target: the old page was never laid out.
        val previousScrollY = (pendingScrollY ?: rootScroll?.scrollY)?.takeIf {
            renderedPage == page && (page != "settings" || renderedSettingsSection == settingsSection)
        }
        status = null; homeWarning = null; homeWifiAction = null; connectButton = null; disconnectButton = null; lastRunning = null
        bydAdbControls = null
        adbSwitches.clear()
        adbStatus = null
        exportButton = null
        settingsSummary = null
        if (page == "settings") {
            renderSettingsWorkspace(previousScrollY ?: settingsScrollPositions[settingsSection]); return
        }
        settingsRenderGeneration++
        settingsWorkspace = null
        val scroll = ScrollView(this).apply {
            if (usesSteamGlass()) background = SteamGlass.backdrop() else setBackgroundColor(BG)
            isFillViewport = true; clipToPadding = false
        }
        rootScroll = scroll
        val content = column().apply { setPadding(dp(if (resources.configuration.screenWidthDp >= 760) 28 else 16), dp(24), dp(if (resources.configuration.screenWidthDp >= 760) 28 else 16), dp(28)) }
        scroll.addView(content)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(SteamSettingIcon(this, SteamSettingsSection.DISPLAY), LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })
        val brand = column().apply {
            addView(label(if (resources.configuration.screenWidthDp >= 760) "STEAM  /  CarPlay" else "CarPlay", 23, TEXT, true))
            addView(label("STEAM · ${version()}", 12, MUTED))
        }
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        if (page == "home") header.addView(button(getString(R.string.settings), false) {
            page = "settings"; render()
        }.apply { tag = "steam_home_settings" }, LinearLayout.LayoutParams(dp(if (resources.configuration.screenWidthDp >= 760) 88 else 72), -2).apply { marginEnd = dp(8) })
        header.addView(button(if (page == "home") getString(if (resources.configuration.screenWidthDp >= 760)
            R.string.car_home else R.string.steam_car_home_short) else getString(R.string.back), false) {
            if (page == "home") startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            else { returnFromPage(); render() }
        }.apply { tag = "steam_home_carhome"; contentDescription = getString(if (page == "home") R.string.car_home else R.string.back) },
            LinearLayout.LayoutParams(dp(if (page == "home" && resources.configuration.screenWidthDp >= 760) 104 else 72), -2))
        content.addView(header)
        content.addView(space(24))
        when (page) {
            "connection" -> connectionSetup(content)
            "about" -> about(content)
            "features" -> additionalFeatures(content)
            else -> home(content)
        }
        setContentView(scroll)
        renderedPage = page
        renderedSettingsSection = null
        refreshStatus()
        pendingScrollY = previousScrollY
        // A stopped window still dispatches pre-draw but skips layout, so wait for a real layout;
        // the listener stays on this view and goes away with it.
        previousScrollY?.let { y ->
            scroll.doOnLayout {
                if (rootScroll === scroll) {
                    scroll.scrollTo(0, y)
                    pendingScrollY = null
                }
            }
        }
    }

    private fun home(content: LinearLayout) {
        val wide = resources.configuration.screenWidthDp >= 760
        content.tag = "steam_home_workspace"
        content.addView(label(getString(R.string.steam_home_title), if (wide) 34 else 28, TEXT, true))
        content.addView(label(getString(R.string.steam_home_hint), 16, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        val deck = card().apply {
            tag = "steam_home_deck"
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = SteamGlass.surface(this@DiPlayActivity, radius = 32)
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }
        deck.addView(SteamLinkArtwork(this), LinearLayout.LayoutParams(if (wide) dp(230) else -1, dp(if (wide) 208 else 116)).apply {
            if (wide) marginEnd = dp(32) else bottomMargin = dp(12)
        })
        val controls = column()
        controls.addView(label(getString(R.string.steam_wireless_label), 12, ACCENT, true).apply { letterSpacing = .08f })
        status = label(getString(R.string.ready_when_you_are), 26, TEXT, true).apply {
            tag = "steam_home_status"; setPadding(0, dp(8), 0, dp(16))
        }
        controls.addView(status)
        connectButton = button(getString(R.string.connect_phone), true) {
            if (CarPlayBackgroundSession.hasSession() && CarPlayBackgroundSession.connectionFeedback?.hint != null)
                connect(AirPlayPersistence.loadWirelessEnabled(this))
            else if (CarPlayBackgroundSession.hasSession()) openProjection() else connect(true)
        }.apply { tag = "steam_home_connect"; minHeight = dp(64) }
        controls.addView(connectButton, matchButton())
        controls.addView(label(getString(if (AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL)
            R.string.steam_mode_manual_hint else R.string.steam_mode_p2p_hint), 14, MUTED).apply {
            setPadding(0, dp(12), 0, 0)
        })
        homeWarning = label("", 14, WARNING).apply { tag = "steam_home_warning"; visibility = View.GONE; setPadding(0, dp(12), 0, 0) }
        controls.addView(homeWarning)
        homeWifiAction = button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }.apply {
            visibility = View.GONE
        }
        controls.addView(homeWifiAction, matchButton(10))
        disconnectButton = button(getString(R.string.disconnect), false) {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop { runOnUiThread { if (!isFinishing && !isDestroyed) refreshStatus() } }
        }.apply { visibility = View.GONE }
        controls.addView(disconnectButton, matchButton(10))
        deck.addView(controls, LinearLayout.LayoutParams(if (wide) 0 else -1, -2, if (wide) 1f else 0f))
        content.addView(deck)
        content.addView(space(20))
        val shortcuts = if (wide) row() else column()
        shortcuts.tag = "steam_home_shortcuts"
        fun shortcut(title: String, value: String, section: SteamSettingsSection, tag: String, click: () -> Unit) = row().apply {
            this.tag = tag; gravity = Gravity.CENTER_VERTICAL
            background = SteamGlass.action(this@DiPlayActivity, radius = 24)
            minimumHeight = dp(88); setPadding(dp(20), dp(18), dp(20), dp(18))
            isFocusable = true; contentDescription = "$title, $value"; setOnClickListener { click() }
            addView(SteamSettingIcon(this@DiPlayActivity, section), LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(14) })
            addView(column().apply {
                addView(label(title, 16, TEXT, true))
                addView(label(value, 13, MUTED).apply { setPadding(0, dp(4), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val items = listOf(
            shortcut(getString(R.string.steam_mode_title), wirelessModeName(), SteamSettingsSection.CONNECTION, "steam_home_mode") { page = "connection"; render() },
            shortcut(getString(R.string.steam_phone_title), if (DiPlayPreferences.phoneAddress(this) == null) getString(R.string.steam_phone_empty)
                else DiPlayPreferences.phoneName(this), SteamSettingsSection.MORE, "steam_home_phone") { choosePhone() },
            shortcut(getString(R.string.steam_usb_title), getString(R.string.steam_usb_hint), SteamSettingsSection.DISPLAY, "steam_home_usb") { connect(false) },
        )
        items.forEachIndexed { index, view -> shortcuts.addView(view, LinearLayout.LayoutParams(if (wide) 0 else -1, -2, if (wide) 1f else 0f).apply {
            if (index > 0) { if (wide) marginStart = dp(16) else topMargin = dp(12) }
        }) }
        content.addView(shortcuts)
        setupError?.let { content.addView(label(it, 16, WARNING).apply { setPadding(0, dp(16), 0, 0) }) }
    }

    private fun wirelessModeName() = getString(if (AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL)
        R.string.built_in_car_hotspot else R.string.wifi_direct)

    private fun usesSteamGlass() = true

    private fun rememberSettingsScroll() {
        if (renderedPage == "settings") renderedSettingsSection?.let {
            settingsScrollPositions[it] = pendingScrollY ?: rootScroll?.scrollY ?: 0
        }
    }

    private fun openSettingsChild(target: String) {
        rememberSettingsScroll()
        openedFromSettings = true
        page = target
        render()
    }

    private fun returnFromPage() {
        rememberSettingsScroll()
        page = if (openedFromSettings && page != "settings") "settings" else "home"
        openedFromSettings = false
        pendingScrollY = null
    }

    private fun selectSettingsSection(section: SteamSettingsSection) {
        if (settingsSection == section) return
        rememberSettingsScroll()
        settingsSection = section
        pendingScrollY = null
        render()
    }

    private fun settingsSummaryText(): String = when (settingsSection) {
        SteamSettingsSection.CONNECTION -> DiPlayPreferences.phoneName(this)
        SteamSettingsSection.DISPLAY -> getString(R.string.steam_screen_summary,
            AirPlayPersistence.loadFps(this), AirPlayPersistence.loadDisplayScaleTenths(this) * 10)
        SteamSettingsSection.AUDIO -> getString(R.string.steam_audio_summary, AirPlayPersistence.loadMediaBufferMillis(this))
        SteamSettingsSection.LOCATION -> getString(R.string.steam_location_summary)
        SteamSettingsSection.TOOLS -> getString(R.string.steam_diagnostics_summary)
        SteamSettingsSection.MORE -> getString(R.string.steam_brand_version, version())
    }

    private fun renderSettingsWorkspace(scrollY: Int?) {
        val generation = ++settingsRenderGeneration
        val existing = settingsWorkspace?.takeIf { it.configuration == resources.configuration }
        val workspace = existing ?: createSettingsWorkspace().also { settingsWorkspace = it }
        val categoryChanged = renderedSettingsSection != settingsSection
        val selectedSection = settingsSection
        rootScroll = workspace.scroll
        settingsSummary = workspace.summary
        setSettingsText(workspace.title, getString(selectedSection.title))
        setSettingsText(workspace.hint, getString(selectedSection.hint))
        setSettingsText(workspace.summary, settingsSummaryText())
        workspace.navigation.forEach { (section, item) ->
            val selected = section == selectedSection
            if (item.view.isSelected != selected) {
                item.view.isSelected = selected
                item.view.background = SteamGlass.action(this, selected, 18)
                item.label.setTextColor(if (selected) TEXT else MUTED)
                item.label.typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            }
        }
        // Retain header, navigation, backdrop and scroll host; only rebuild setting controls.
        workspace.sections.removeAllViews()
        settings(workspace.sections)
        if (existing == null) setContentView(workspace.root, ViewGroup.LayoutParams(-1, -1))
        renderedPage = page
        renderedSettingsSection = selectedSection
        val targetY = scrollY ?: 0
        pendingScrollY = targetY
        workspace.scroll.doOnLayout {
            // The same scroll host is reused; identity alone cannot reject an older restore.
            if (rootScroll === workspace.scroll && settingsRenderGeneration == generation) {
                workspace.scroll.scrollTo(0, targetY)
                // Finish old momentum at the restored position, rather than the old page's offset.
                if (categoryChanged) workspace.scroll.fling(0)
                pendingScrollY = null
                settingsScrollPositions[selectedSection] = workspace.scroll.scrollY
            }
        }
        if (categoryChanged || existing == null) workspace.horizontalNavigation?.doOnLayout { strip ->
            if (settingsRenderGeneration == generation) {
                workspace.navigation[selectedSection]?.view?.let { item ->
                    val start = item.left - dp(8)
                    val end = item.right + dp(8)
                    when {
                        start < strip.scrollX -> strip.scrollTo(maxOf(0, start), 0)
                        end > strip.scrollX + strip.width -> strip.scrollTo(end - strip.width, 0)
                    }
                }
            }
        }
        refreshStatus()
    }

    private fun setSettingsText(view: TextView, text: String) {
        if (view.text.toString() != text) view.text = text
    }

    private fun createSettingsWorkspace(): SettingsWorkspace {
        val wide = resources.configuration.screenWidthDp >= 760
        val shell = column().apply {
            background = SteamGlass.backdrop()
            setPadding(dp(if (wide) 24 else 16), dp(16), dp(if (wide) 24 else 16), 0)
        }
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(16)) }
        val titles = column().apply {
            addView(label(getString(R.string.settings), 28, TEXT, true))
            addView(label("CarPlay · Steam", 13, MUTED).apply { setPadding(0, dp(4), 0, 0) })
        }
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button(getString(R.string.steam_done), false) {
            returnFromPage(); render()
        }, LinearLayout.LayoutParams(dp(88), -2))
        shell.addView(header)

        val navigation = if (wide) column() else row()
        navigation.tag = "steam_settings_navigation"
        navigation.setPadding(dp(8), dp(8), dp(8), dp(8))
        navigation.background = SteamGlass.surface(this)
        if (wide) navigation.addView(label(getString(R.string.steam_preferences), 13, MUTED).apply {
            setPadding(dp(14), dp(10), 0, dp(12))
        })
        val navigationItems = mutableMapOf<SteamSettingsSection, SettingsNavigationItem>()
        SteamSettingsSection.entries.forEach { section ->
            val selected = section == settingsSection
            val item = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = SteamGlass.action(this@DiPlayActivity, selected, 18)
                isSelected = selected
                isFocusable = true
                contentDescription = getString(section.title)
                tag = "steam_settings_${section.name.lowercase(Locale.ROOT)}"
                setOnClickListener { selectSettingsSection(section) }
            }
            item.addView(SteamSettingIcon(this, section), LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
            val itemLabel = label(getString(section.title), 16, if (selected) TEXT else MUTED, selected).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            item.addView(itemLabel, LinearLayout.LayoutParams(if (wide) 0 else -2, -2, if (wide) 1f else 0f))
            navigationItems[section] = SettingsNavigationItem(item, itemLabel)
            navigation.addView(item, LinearLayout.LayoutParams(if (wide) -1 else -2, -2).apply {
                if (wide) bottomMargin = dp(6) else marginEnd = dp(6)
            })
        }
        val navigationScroll: View = if (wide) ScrollView(this).apply {
            isFillViewport = false; isVerticalScrollBarEnabled = false
            addView(navigation)
        } else HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(navigation)

        }
        val detail = column().apply {
            tag = "steam_settings_detail"
            setPadding(0, dp(if (wide) 0 else 16), 0, dp(24))
        }
        val intro = column().apply { setPadding(dp(20), dp(4), dp(20), dp(20)) }
        val sectionTitle = label(getString(settingsSection.title), 26, TEXT, true)
        sectionTitle.tag = "steam_settings_section_title"
        val sectionHint = label(getString(settingsSection.hint), 14, MUTED).apply { setPadding(0, dp(8), 0, dp(12)) }
        val summary = label(settingsSummaryText(), 13, ACCENT, true).apply {
            background = SteamGlass.surface(this@DiPlayActivity, selected = true, radius = 12)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        intro.addView(sectionTitle)
        intro.addView(sectionHint)
        intro.addView(summary, LinearLayout.LayoutParams(-2, -2))
        detail.addView(intro)
        val sections = column()
        detail.addView(sections)
        val scroll = ScrollView(this).apply {
            isFillViewport = false; clipToPadding = false
            isVerticalScrollBarEnabled = false
            addView(detail)
        }
        if (wide) {
            val body = row()
            body.addView(navigationScroll, LinearLayout.LayoutParams(dp(200), -1).apply { marginEnd = dp(24) })
            body.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
            shell.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            shell.addView(navigationScroll, LinearLayout.LayoutParams(-1, -2))
            shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        shell.tag = "steam_settings_workspace"
        return SettingsWorkspace(Configuration(resources.configuration), shell, scroll, sections,
            sectionTitle, sectionHint, summary, navigationItems, navigationScroll as? HorizontalScrollView)
    }

    private fun settings(content: LinearLayout) {
        when (settingsSection) {
            SteamSettingsSection.CONNECTION -> {
                section(content, getString(R.string.connection_setup), R.drawable.ic_dp_connection) { card ->
                    card.addView(label(getString(R.string.choose_how_to_connect_follow_the_setup_steps_and_save_your), 16, MUTED))
                    card.addView(button(getString(R.string.open_connection_setup), false) { openSettingsChild("connection") }, matchButton(12, 60))
                }
                section(content, getString(R.string.automatic_connection), R.drawable.ic_dp_automation) { card ->
                    toggle(card, getString(R.string.connect_when_diplay_opens), getString(R.string.default_connection_description), DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
                    val modes = DefaultConnectionMode.entries
                    choice(card, getString(R.string.default_connection_mode), listOf(
                        getString(R.string.default_connection_last_used), getString(R.string.default_connection_wireless),
                        getString(R.string.default_connection_usb)), modes.indexOf(DiPlayPreferences.defaultConnectionMode(this)), reconnects = false) {
                        DiPlayPreferences.saveDefaultConnectionMode(this, modes[it])
                    }
                    adbToggle(card, R.string.open_after_the_car_starts,
                        R.string.availability_depends_on_your_head_unit_s_startup_settings,
                        read = { AirPlayPersistence.loadAutoStartOnBoot(this) },
                        needsAdb = { CarHotspotSettings.enabled(this) &&
                            AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL },
                        permissions = { listOf(CarHotspotSetup.Permission.BOOT_LAUNCH) }) {
                        AirPlayPersistence.saveAutoStartOnBoot(this, it)
                    }
                    card.addView(button("${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
                }
            }
            SteamSettingsSection.DISPLAY -> {
                section(content, getString(R.string.display_and_performance), R.drawable.ic_dp_display) { card ->
                    carPlaySizeControl(card)
                    choice(card, getString(R.string.resolution), listOf(getString(R.string.resolution_native), getString(R.string.s_80_lighter_load), getString(R.string.s_60_lightest_load)), listOf(10, 8, 6).indexOf(AirPlayPersistence.loadDisplayScaleTenths(this)).coerceAtLeast(0)) { AirPlayPersistence.saveDisplayScaleTenths(this, listOf(10, 8, 6)[it]) }
                    choice(card, getString(R.string.frame_rate), listOf(getString(R.string.s_30_fps_lighter_load), getString(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
                    toggle(card, getString(R.string.efficient_video), getString(R.string.use_hevc_leave_off_for_the_widest_head_unit_compatibility), AirPlayPersistence.loadHevcEnabled(this)) { AirPlayPersistence.saveHevcEnabled(this, it) }
                    toggle(card, getString(R.string.full_screen), getString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), AirPlayPersistence.loadHideTopBar(this) && AirPlayPersistence.loadHideBottomBar(this)) {
                        AirPlayPersistence.saveHideTopBar(this, it); AirPlayPersistence.saveHideBottomBar(this, it)
                    }
                }
            }
            SteamSettingsSection.AUDIO -> {
                section(content, getString(R.string.audio_routing)) { card ->
                    toggle(card, getString(R.string.contrib_audio_home_toggle_audio_focus), getString(R.string.contrib_audio_home_toggle_audio_focus_desc), AirPlayPersistence.loadAudioFocusEnabled(this)) { AirPlayPersistence.saveAudioFocusEnabled(this, it) }
                    toggle(card, getString(R.string.audio_focus_auto_yield), getString(R.string.audio_focus_auto_yield_desc),
                        AirPlayPersistence.loadAudioFocusAutoYield(this)) { AirPlayPersistence.saveAudioFocusAutoYield(this, it) }
                    if (resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)) {
                        toggle(card, getString(R.string.advanced_audio_channel_mapping),
                            getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                            AirPlayPersistence.loadAdvancedAudioChannelMapping(this)) {
                            AirPlayPersistence.saveAdvancedAudioChannelMapping(this, it)
                        }
                    }
                    card.addView(space(8))
                    mediaChannelControl(card)
                    card.addView(space(10))
                    navigationChannelControl(card)
                }
                section(content, getString(R.string.steam_audio_buffer)) { card ->
                    val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
                    choice(card, getString(R.string.music_buffer), bufferPresets.map { getString(if (it == com.shilapi.xcertplay.media.MediaAudioBuffer.DEFAULT_MILLIS) R.string.steam_buffer_default else R.string.steam_buffer_ms, it) },
                        bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                        AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
                    }
                }
                section(content, getString(R.string.carplay_call_audio)) { card ->
                    toggle(card, getString(R.string.call_echo_cancellation), getString(R.string.call_echo_cancellation_description),
                        AirPlayPersistence.loadCallEchoCancellation(this)) { AirPlayPersistence.saveCallEchoCancellation(this, it) }
                    toggle(card, getString(R.string.call_voice_filter), getString(R.string.call_voice_filter_description),
                        AirPlayPersistence.loadCallVoiceFilter(this)) { AirPlayPersistence.saveCallVoiceFilter(this, it) }
                }
                section(content, getString(R.string.wheel_siri_key)) { card -> wheelSiriControls(card) }
                section(content, getString(R.string.carplay_call_vehicle)) { card ->
                    toggle(card, getString(R.string.carplay_calls_on_dashboard), getString(R.string.carplay_calls_on_dashboard_description),
                        BydOutputSettings.carPlayCalls(this)) {
                        BydOutputSettings.setCarPlayCalls(this, it)
                        if (it) checkAdbState(mayAsk = true)
                        BydNavigationOutputs.carPlayCallsChanged(it)
                    }
                    toggle(card, getString(R.string.carplay_call_controls_experimental), getString(R.string.carplay_call_controls_experimental_description),
                        BydOutputSettings.carPlayCallControls(this)) {
                        BydOutputSettings.setCarPlayCallControls(this, it)
                        WheelKeyService.restoreIfNeeded(this)
                    }
                    wheelKeyServiceControls(card)
                }
            }
            SteamSettingsSection.LOCATION -> {
                section(content, getString(R.string.location), R.drawable.ic_dp_navigation) { card ->
                    toggle(card, getString(R.string.report_location_to_iphone),
                        getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                        AirPlayPersistence.loadLocationReportingEnabled(this), save = ::onLocationReportingChanged)
                    card.addView(label(getString(R.string.location_reporting_reconnects), 14, MUTED))
                    card.addView(label("", 14, MUTED).also { LocationPanel.bind(it) })
                    card.addView(button(getString(if (bydVehicleAdvancedExpanded)
                        R.string.hide_advanced_vehicle_data else R.string.advanced_vehicle_data), false) {
                        bydVehicleAdvancedExpanded = !bydVehicleAdvancedExpanded
                        render()
                    }, matchButton(12, 56))
                    if (bydVehicleAdvancedExpanded) {
                        advancedVehicleData(card)
                        // Dashboard song needs ADB, not the navigation receiver; show it here when that card is hidden.
                        if (!BydOutputSettings.navigationAvailable(this)) clusterSongSwitch(card)
                    }
                }
            }
            SteamSettingsSection.TOOLS -> {
                section(content, getString(R.string.diagnostics), R.drawable.ic_dp_diagnostics) { card ->
                    exportButton = button(if (exportInProgress) getString(R.string.saving_report) else getString(R.string.save_diagnostic_report), false) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportDiagnostics()
                        else chooseReportDestination()
                    }.apply { isEnabled = !exportInProgress }
                    card.addView(exportButton, matchButton(10, 60))
                    card.addView(button(getString(R.string.choose_save_location), false) { chooseReportDestination() }, matchButton(10, 60))
                    val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getString(R.string.reports_save_to_downloads_diplay) else getString(R.string.choose_where_to_save_your_report)
                    card.addView(label(destination + getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
                }
                section(content, getString(R.string.permissions_and_connection_help), R.drawable.ic_dp_permissions) { card ->
                    card.addView(label(getString(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir), 16, MUTED))
                    card.addView(button(getString(R.string.app_permissions), false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
                    card.addView(button(getString(R.string.bluetooth_settings), false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(10, 60))
                    card.addView(button(getString(R.string.wireless_connection_help), false) { wirelessHelp() }, matchButton(10, 60))
                }
            }
            SteamSettingsSection.MORE -> {
                section(content, getString(R.string.steam_more), R.drawable.ic_dp_connection) { card ->
                    card.addView(button(getString(R.string.steam_extensions), false) { openSettingsChild("features") }, matchButton(0, 60))
                }
                section(content, getString(R.string.about), R.drawable.ic_dp_about) { card ->
                    card.addView(button(getString(R.string.about_diplay), false) { openSettingsChild("about") }, matchButton(0, 60))
                }
                languageSettings(content)
            }
        }
    }

    private fun additionalFeatures(content: LinearLayout) {
        content.addView(label(getString(R.string.steam_extensions), 28, TEXT, true))
        content.addView(label(getString(R.string.steam_features_hint), 16, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, getString(R.string.carplay_controls), R.drawable.ic_dp_display) { card ->
            val gestureFingers = listOf(2, 3, 4)
            choice(card, getString(R.string.settings_gesture_fingers_label),
                gestureFingers.map { getString(R.string.settings_gesture_fingers_option, it) },
                gestureFingers.indexOf(AirPlayPersistence.loadSettingsGestureFingers(this)).coerceAtLeast(0),
                reconnects = false) {
                AirPlayPersistence.saveSettingsGestureFingers(this, gestureFingers[it])
            }
            card.addView(label(getString(R.string.settings_gesture_fingers_hint), 14, MUTED).apply {
                setPadding(0, dp(10), 0, 0)
            })
        }
        section(content, "安卓 CarPlay 接收屏 · Steam", R.drawable.ic_dp_connection) { card ->
            val usb = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
            val direct = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
            card.addView(label("用安卓手机或车机接收另一部 iPhone 的 CarPlay。无线连接需蓝牙和 Wi-Fi Direct（Android 10+）；有线连接需 USB 主机 / OTG。\nUSB 主机：$usb；Wi-Fi Direct：$direct。", 16, MUTED))
        }
        bydAdbSettings(content)
        if (BydOutputSettings.navigationAvailable(this)) section(content, getString(R.string.byd_navigation), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.navigation_on_hud_and_instrument_cluster),
                getString(R.string.show_phone_navigation_arrows_distance_and_street_names_on),
                com.shilapi.xcertplay.hud.BydOutputSettings.enabled(this)) { com.shilapi.xcertplay.hud.BydOutputSettings.setEnabled(this, it) }
            if (ClusterMapPresentation.findDisplay(this) != null) {
                toggle(card, getString(R.string.carplay_map_on_instrument_cluster_experimental),
                    getString(R.string.shows_the_iphone_s_cluster_map_on_the_instrument_cluster_c),
                    AirPlayPersistence.loadClusterMapEnabled(this)) {
                    AirPlayPersistence.saveClusterMapEnabled(this, it)
                    reconnectForClusterMap()
                }
                toggle(card, getString(R.string.center_map_card), getString(R.string.center_map_card_description),
                    AirPlayPersistence.loadCenterMapOverlay(this)) {
                    AirPlayPersistence.saveCenterMapOverlay(this, it)
                    if (it && !CenterMapOverlay.permitted(this)) openOverlayPermission()
                    render()
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    toggle(card, getString(R.string.center_map_follows_dashboard), getString(R.string.center_map_follows_dashboard_description),
                        AirPlayPersistence.loadCenterMapFollowsDashboard(this)) {
                        AirPlayPersistence.saveCenterMapFollowsDashboard(this, it)
                    }
                }
                toggle(card, getString(R.string.launcher_map_sharing), getString(R.string.launcher_map_sharing_description),
                    AirPlayPersistence.loadLauncherMapSharing(this)) {
                    AirPlayPersistence.saveLauncherMapSharing(this, it)
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    val overlay = CenterMapOverlay.permitted(this)
                    card.addView(label(if (overlay) getString(R.string.center_map_overlay_allowed)
                        else getString(R.string.center_map_overlay_missing, packageName), 14, if (overlay) MUTED else WARNING))
                    val usage = HomeScreenMonitor.hasAccess(this)
                    card.addView(label(if (usage) getString(R.string.center_map_usage_allowed)
                        else getString(R.string.center_map_usage_missing, packageName), 14, if (usage) MUTED else WARNING))
                }
                if (DiLink51ClusterLayout.supported()) {
                    val automatic = DiLink51ClusterLayout.automatic(this)
                    toggle(card, getString(R.string.follow_instrument_theme_and_map_card),
                        getString(R.string.show_the_side_map_only_when_its_card_is_open_and_switch_to), automatic) {
                        DiLink51ClusterLayout.saveAutomatic(this, it)
                        render()
                        reconnectForClusterMap()
                    }
                    val allowed = DiLink51ClusterMonitor.hasAccess(this)
                    card.addView(label(if (allowed) getString(R.string.usage_access_enabled)
                        else getString(R.string.usage_access_setup_needed_for_automatic_mode), 14, if (allowed) MUTED else WARNING))
                    card.addView(button(getString(R.string.automatic_map_setup_adb), false) { showClusterAccessSetup() }, matchButton(10, 56))
                    if (!automatic) {
                        val themes = DiLink51ClusterLayout.Theme.entries
                        choice(card, getString(R.string.instrument_theme), themes.map { it.localizedLabel(this) }, themes.indexOf(DiLink51ClusterLayout.theme(this))) {
                            DiLink51ClusterLayout.saveTheme(this, themes[it])
                            reconnectForClusterMap()
                        }
                        card.addView(label(getString(R.string.manual_mode_match_the_cluster_theme_here_the_map_cannot_fo), 14, MUTED))
                    }
                    val contrasts = DiLink51ClusterLayout.Contrast.entries
                    choice(card, getString(R.string.instrument_contrast), contrasts.map { it.localizedLabel(this) }, contrasts.indexOf(DiLink51ClusterLayout.contrast(this))) {
                        DiLink51ClusterLayout.saveContrast(this, contrasts[it])
                        reconnectForClusterMap()
                    }
                } else {
                    val sizes = CarPlayClusterDisplay.scalePresets
                    val contents = CarPlayClusterDisplay.Content.entries
                    val content = AirPlayPersistence.loadClusterContent(this)
                    val customCard = CarPlayClusterDisplay.usesCustomTurnCard(content)
                    val officialCardOnly = content == CarPlayClusterDisplay.Content.TURN_CARD
                    choice(card, getString(R.string.dashboard_shows), listOf(
                        getString(R.string.dashboard_content_map),
                        getString(R.string.dashboard_content_turn_card),
                        getString(R.string.dashboard_content_map_with_turn_card),
                        getString(R.string.dashboard_content_map_with_custom_turn_card),
                    ), contents.indexOf(content).coerceAtLeast(0), reconnects = false) {
                        val next = contents[it]
                        AirPlayPersistence.saveClusterContent(this, next)
                        render()
                        if (content.url != next.url) reconnectForClusterMap()
                    }
                    if (customCard) {
                        val overlaySizes = CarPlayClusterDisplay.OverlaySize.entries
                        choice(card, getString(R.string.turn_card_overlay_size), listOf(
                            getString(R.string.turn_card_overlay_small),
                            getString(R.string.turn_card_overlay_medium),
                            getString(R.string.turn_card_overlay_large),
                        ), overlaySizes.indexOf(AirPlayPersistence.loadClusterTurnCardOverlaySize(this)).coerceAtLeast(0), reconnects = false) {
                            AirPlayPersistence.saveClusterTurnCardOverlaySize(this, overlaySizes[it])
                        }
                        card.addView(overlaySliderRow(
                            getString(R.string.turn_card_overlay_horizontal),
                            ClusterTurnCardOverlay.xPercents,
                            AirPlayPersistence.loadClusterTurnCardOverlayXPercent(this),
                        ) { it -> overlayOffsetLabel(it, getString(R.string.marker_left), getString(R.string.marker_right), 50) }
                            .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOverlayXPercent(this, v) } })
                        card.addView(overlaySliderRow(
                            getString(R.string.turn_card_overlay_vertical),
                            ClusterTurnCardOverlay.yPercents,
                            AirPlayPersistence.loadClusterTurnCardOverlayYPercent(this),
                        ) { it -> overlayOffsetLabel(it, getString(R.string.marker_up), getString(R.string.marker_down), 40) }
                            .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOverlayYPercent(this, v) } })
                        card.addView(button(getString(R.string.reset_turn_card_overlay), false) {
                            AirPlayPersistence.saveClusterTurnCardOverlayXPercent(this, ClusterTurnCardOverlay.DEFAULT_X_PERCENT)
                            AirPlayPersistence.saveClusterTurnCardOverlayYPercent(this, ClusterTurnCardOverlay.DEFAULT_Y_PERCENT)
                            render()
                        }, matchButton(10, 56))
                        card.addView(label(getString(R.string.turn_card_overlay_note), 14, MUTED))
                    }
                    val turnCard = officialCardOnly
                    choice(card, getString(if (turnCard) R.string.turn_card_size else R.string.cluster_map_size),
                        listOf(getString(R.string.cluster_size_standard), getString(R.string.cluster_size_larger), getString(R.string.cluster_size_largest)),
                        sizes.indexOf(AirPlayPersistence.loadClusterMapScalePercent(this)).coerceAtLeast(0)) {
                        AirPlayPersistence.saveClusterMapScalePercent(this, sizes[it])
                    }
                    val across = CarPlayClusterDisplay.horizontalSteps.toList()
                    choice(card, getString(if (turnCard) R.string.turn_card_horizontal else R.string.car_marker_horizontal), across.map { markerStepLabel(it, getString(R.string.marker_left), getString(R.string.marker_right)) },
                        across.indexOf(AirPlayPersistence.loadClusterMarkerHorizontalStep(this)).coerceAtLeast(0)) {
                        AirPlayPersistence.saveClusterMarkerHorizontalStep(this, across[it])
                    }
                    val upDown = CarPlayClusterDisplay.verticalSteps.toList()
                    choice(card, getString(if (turnCard) R.string.turn_card_vertical else R.string.car_marker_vertical), upDown.map { markerStepLabel(it, getString(R.string.marker_up), getString(R.string.marker_down)) },
                        upDown.indexOf(AirPlayPersistence.loadClusterMarkerVerticalStep(this)).coerceAtLeast(0)) {
                        AirPlayPersistence.saveClusterMarkerVerticalStep(this, upDown[it])
                    }
                    card.addView(button(getString(if (turnCard) R.string.reset_turn_card_to_centre else R.string.reset_car_marker_to_centre), false) {
                        AirPlayPersistence.saveClusterMarkerHorizontalStep(this, 0)
                        AirPlayPersistence.saveClusterMarkerVerticalStep(this, 0)
                        render()
                        reconnectForClusterMap()
                    }, matchButton(10, 56))
                    toggle(card, getString(R.string.dashboard_map_only_in_small_and_full_navi),
                        getString(R.string.dashboard_map_only_in_small_and_full_navi_description),
                        BydOutputSettings.clusterStreamPause(this)) {
                        BydOutputSettings.setClusterStreamPause(this, it)
                        if (it) checkAdbState(mayAsk = true)
                    }
                }
            }
            clusterSongSwitch(card)
        }
        languageSettings(content)
    }

    private fun about(content: LinearLayout) {
        content.addView(label("CarPlay", 32, TEXT, true))
        content.addView(label("Steam · ${version()}", 16, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "${getString(R.string.about_public_preview_prefix)}${version()}") { card ->
            card.addView(label(getString(R.string.an_independent_carplay_receiver_for_android_head_units_wir), 17, TEXT))
        }
        section(content, getString(R.string.made_possible_by_open_source)) { card ->
            card.addView(label(getString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay), 16, MUTED))
        }
    }

    // An opted-in connection prepares the hotspot in the controller instead of stopping at this reminder.
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false &&
            !(CarHotspotSettings.enabled(this) && CarHotspotTethering.permitted(this))

    private fun bydAdbSettings(parent: LinearLayout) {
        if (AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) return
        if (!CarHotspotSetup.isBydHeadUnit(this)) {
            Log.i("DiPlay-Hotspot", "settings hidden: BYD head unit not detected")
            return
        }
        val controls = column().apply { visibility = View.GONE }
        bydAdbControls = controls
        parent.addView(controls)
        Thread({
            val access = runCatching { CarHotspotSetup.check(applicationContext) }
                .onFailure { Log.w("DiPlay-Hotspot", "settings ADB check failed", it) }
                .getOrDefault(LocalAdb.Access.UNREACHABLE)
            Log.i("DiPlay-Hotspot", "settings eligibility: byd=true adb=$access visible=${CarHotspotSettings.visible(true, access)}")
            runOnUiThread {
                if (bydAdbControls !== controls || isFinishing || isDestroyed) return@runOnUiThread
                if (CarHotspotSettings.visible(true, access)) {
                    controls.visibility = View.VISIBLE
                    renderBydAdbControls(controls, access)
                }
            }
        }, "diplay-hotspot-adb-check").start()
    }

    private fun renderBydAdbControls(controls: LinearLayout, access: LocalAdb.Access) {
        controls.removeAllViews()
        adbSwitches.keys.retainAll(setOf(R.string.open_after_the_car_starts))
        adbStatus = null
        controls.visibility = if (CarHotspotSettings.visible(true, access)) View.VISIBLE else View.GONE
        if (controls.visibility == View.GONE) return
        if (AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) {
            controls.visibility = View.GONE
            return
        }
        section(controls, getString(R.string.byd_adb_features), R.drawable.ic_dp_permissions) { card ->
            if (AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL) {
                adbToggle(card, R.string.auto_car_hotspot_title, R.string.auto_car_hotspot_description,
                    read = { CarHotspotSettings.enabled(this) },
                    permissions = {
                        buildList {
                            add(CarHotspotSetup.Permission.HOTSPOT)
                            if (AirPlayPersistence.loadAutoStartOnBoot(this@DiPlayActivity)) {
                                add(CarHotspotSetup.Permission.BOOT_LAUNCH)
                            }
                        }
                    }) {
                    CarHotspotSettings.setEnabled(this, it)
                    if (!it) startupHotspotCancelled = true
                }
            }
            adbStatus = label(getString(if (access == LocalAdb.Access.READY)
                R.string.adb_access_ready else R.string.adb_not_approved), 14, MUTED).also(card::addView)
        }
    }

    private fun adbToggle(parent: LinearLayout, title: Int, description: Int, read: () -> Boolean,
        needsAdb: () -> Boolean = { true },
        permissions: () -> List<CarHotspotSetup.Permission> = { emptyList() }, save: (Boolean) -> Unit) {
        val control = toggle(parent, getString(title), getString(description), read(),
            enabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress()) { enabled ->
            if (updatingAdbSwitches || adbSwitchChangePending) return@toggle
            if (enabled && needsAdb()) requestAdbSwitchChange(permissions()) { save(true) }
            else save(enabled)
        }
        adbSwitches[title] = control to read
    }

    private fun requestAdbSwitchChange(permissions: List<CarHotspotSetup.Permission>, save: () -> Unit) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) {
            updateAdbSwitches()
            return
        }
        cancelAutomaticVehicleValidationForUserOperation(
            resumeAfter = automaticVehicleValidationInProgress || automaticVehicleValidationPending,
        )
        val app = applicationContext
        adbSwitchChangePending = true
        updateAdbSwitches()
        adbStatus?.setText(R.string.adb_checking_may_ask)
        if (page == "settings" && settingsSection == SteamSettingsSection.LOCATION && bydVehicleAdvancedExpanded) render()
        Thread({
            var access = LocalAdb.Access.UNREACHABLE
            val ready = runCatching {
                access = CarHotspotSetup.grant(app, permissions)
                access == LocalAdb.Access.READY && permissions.all { it.granted(app) }
            }.getOrDefault(false)
            runOnUiThread {
                adbSwitchChangePending = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = if (ready) {
                    if (permissions.isEmpty()) R.string.adb_access_ready else R.string.hotspot_permission_granted
                } else when (access) {
                    LocalAdb.Access.NOT_APPROVED -> R.string.adb_not_approved
                    LocalAdb.Access.UNREACHABLE -> R.string.adb_off
                    LocalAdb.Access.UNSUPPORTED -> R.string.adb_pairing_only
                    else -> R.string.hotspot_permission_failed
                }
                adbStatus?.setText(if (ready) R.string.adb_access_ready else message)
                if (ready) save()
                updateAdbSwitches()
                toast(getString(message))
                runPendingAutomaticVehicleValidation()
                // Re-enable the vehicle controls disabled while this authorization was outstanding.
                if (page == "settings" && settingsSection == SteamSettingsSection.LOCATION && bydVehicleAdvancedExpanded) render()
            }
        }, "diplay-adb-switch").start()
    }

    private fun updateAdbSwitches() {
        updatingAdbSwitches = true
        for ((control, read) in adbSwitches.values) {
            control.isChecked = read()
            control.isEnabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress()
        }
        updatingAdbSwitches = false
    }

    private fun vehicleAdbWorkInProgress(): Boolean =
        adbCheckInProgress || vehicleProbeAuthorizationInProgress || vehicleProbeInProgress

    private fun startCarHotspotOnLaunch() {
        if (!startupHotspotEligible()) return
        val app = applicationContext
        Thread({
            val result = CarHotspotTethering.enable(app,
                isCancelled = { startupHotspotCancelled || !startupHotspotEligible() },
                log = { Log.i("DiPlay-Hotspot", it) },
            )
            runOnUiThread {
                if (isFinishing || isDestroyed || result == CarHotspotTethering.Result.CANCELLED) return@runOnUiThread
                hotspotStartupResult = result
                if (page == "home") render()
            }
        }, "diplay-hotspot-startup").start()
    }

    private fun startupHotspotEligible(): Boolean =
        CarHotspotSetup.shouldStartOnLaunch(applicationContext, CarPlayBackgroundSession.hasSession())

    private fun hotspotResultText(result: CarHotspotTethering.Result): String = getString(when (result) {
        CarHotspotTethering.Result.READY -> R.string.hotspot_control_on
        CarHotspotTethering.Result.PERMISSION_REQUIRED -> R.string.hotspot_control_missing
        CarHotspotTethering.Result.UNSUPPORTED -> R.string.hotspot_control_unsupported
        CarHotspotTethering.Result.TIMED_OUT -> R.string.hotspot_control_timeout
        else -> R.string.hotspot_control_failed
    })

    private fun carHotspotOffDialog() {
        AlertDialog.Builder(this).setTitle(getString(R.string.car_hotspot_is_off))
            .setMessage(getString(R.string.msg_car_hotspot_connect, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(getString(R.string.open_car_settings)) { _, _ -> openCarWifiSettings() }
            .setNeutralButton(getString(R.string.connect)) { _, _ -> connect(true) }
            .setNegativeButton(getString(R.string.cancel), null).show().also { styleSettingsDialog(it) }
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun openCarClientWifiSettings() {
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS)
        if (packageManager.resolveActivity(wifi, 0)?.activityInfo?.packageName == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        openSystem(wifi)
    }

    private fun connectionSetup(content: LinearLayout) {
        content.tag = "steam_connection_setup_workspace"
        content.addView(label(getString(R.string.steam_setup_title), 30, TEXT, true))
        content.addView(label(getString(R.string.steam_setup_hint), 16, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "01  /  ${getString(R.string.steam_mode_title)}") { card -> wirelessLinkControls(card) }
        val wide = resources.configuration.screenWidthDp >= 760
        val lower = if (wide) row().apply { gravity = Gravity.TOP } else column()
        val phone = column()
        section(phone, "02  /  ${getString(R.string.steam_pair_step)}") { card ->
            card.addView(label(getString(R.string.keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car), 15, MUTED))
            card.addView(button("${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12))
            card.addView(button(getString(R.string.review_app_permissions), false) {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, matchButton(12))
        }
        val connect = column()
        section(connect, "03  /  ${getString(R.string.steam_ready_step)}") { card ->
            card.addView(label(getString(R.string.return_from_car_settings_to_diplay_then_connect_accept_the), 15, MUTED))
            card.addView(button(getString(R.string.connect_phone), true) { connect(true) }.apply { tag = "steam_setup_connect" }, matchButton(12))
            card.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton(12))
        }
        lower.addView(phone, LinearLayout.LayoutParams(if (wide) 0 else -1, -2, if (wide) 1f else 0f))
        lower.addView(connect, LinearLayout.LayoutParams(if (wide) 0 else -1, -2, if (wide) 1f else 0f).apply {
            if (wide) marginStart = dp(18)
        })
        content.addView(lower)
    }

    private fun wirelessLinkControls(parent: LinearLayout) {
        val mode = if (pendingCarHotspotSetup) WirelessHotspotMode.MANUAL else AirPlayPersistence.loadWirelessHotspotMode(this)
        val modes = if (Build.VERSION.SDK_INT >= 29) listOf(WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.MANUAL) else listOf(WirelessHotspotMode.MANUAL)
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row().apply { gravity = Gravity.TOP } else column()
        parent.addView(choices)
        modes.forEachIndexed { index, candidate ->
            val option = column().apply {
                background = SteamGlass.surface(this@DiPlayActivity, mode == candidate, radius = 24)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            choices.addView(option, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(16)
            } else LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(12) })
            val title = getString(if (candidate == WirelessHotspotMode.MANUAL) R.string.built_in_car_hotspot else R.string.wifi_direct)
            option.addView(button("${if (mode == candidate) "✓  " else ""}$title", mode == candidate) {
                if (candidate == WirelessHotspotMode.MANUAL) {
                    pendingCarHotspotSetup = true
                    render()
                } else {
                    pendingCarHotspotSetup = false
                    applyWirelessLink(candidate)
                }
            }.apply { tag = "steam_mode_${candidate.name.lowercase(Locale.ROOT)}" }, matchButton())
            option.addView(label(getString(if (candidate == WirelessHotspotMode.MANUAL) R.string.steam_mode_manual_hint else R.string.steam_mode_p2p_hint), 15, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) })
        }
        parent.addView(space(20))
        if (mode == WirelessHotspotMode.MANUAL) {
            parent.addView(label(getString(R.string.hotspot_setup), 22, TEXT, true))
            parent.addView(label(getString(R.string.s_1_open_car_hotspot_settings_turn_the_hotspot_on_and_sele), 16, MUTED).apply { setPadding(0, dp(8), 0, dp(12)) })
            parent.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(0, 60))
            parent.addView(button(if (pendingCarHotspotSetup) getString(R.string.save_hotspot_details_and_use_this_mode) else "${getString(R.string.edit_saved_hotspot_prefix)}${storedSsid()}", false) {
                askHotspotCredentials { ssid, password ->
                    saveHotspotCredentials(ssid, password)
                    pendingCarHotspotSetup = false
                    applyWirelessLink(WirelessHotspotMode.MANUAL)
                }
            }, matchButton(12, 60))
            parent.addView(label(if (pendingCarHotspotSetup) getString(R.string.finish_setup_save_your_hotspot_details_to_use_this_mode) else if (carHotspotOff()) getString(R.string.hotspot_details_off) else getString(R.string.hotspot_details_saved), 15, if (carHotspotOff()) WARNING else MUTED).apply { setPadding(0, dp(12), 0, 0) })
        } else {
            parent.addView(label(getString(R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic), 16, MUTED))
            wifiDirectChannelControl(parent)
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
        }
    }

    private fun wifiDirectChannelLabel(channel: Int): String = if (channel == WifiP2pChannels.AUTO) {
        getString(R.string.auto)
    } else {
        getString(R.string.wifi_direct_channel_choice, channel,
            getString(if (channel < 36) R.string.s_2_4_ghz else R.string.s_5_ghz))
    }

    private fun wifiDirectChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.wifi_direct_channel_summary, wifiDirectChannelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadWifiP2pPreferredChannel(this)), false) {}
        control.setOnClickListener {
            val choices = listOf(WifiP2pChannels.AUTO) + WifiP2pChannels.channels
            val current = AirPlayPersistence.loadWifiP2pPreferredChannel(this)
            var selection = current
            AlertDialog.Builder(this).setTitle(R.string.wifi_direct_channel_title)
                .setSingleChoiceItems(choices.map(::wifiDirectChannelLabel).toTypedArray(),
                    choices.indexOf(current)) { _, which -> selection = choices[which] }
                .setPositiveButton(R.string.save) { _, _ ->
                    if (selection != current) {
                        AirPlayPersistence.saveWifiP2pPreferredChannel(this, selection)
                        control.text = summary(selection)
                        toast(getString(R.string.saved_for_your_next_connection))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show().also { styleSettingsDialog(it) }
        }
        parent.addView(control, matchButton(12, 60))
        parent.addView(label(getString(R.string.wifi_direct_channel_description), 15, MUTED).apply {
            setPadding(0, dp(6), 0, dp(12))
        })
    }

    private fun mediaChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.contrib_audio_home_choice_summary, getString(R.string.contrib_audio_home_media_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadMediaAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadMediaAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_media_channel_label),
                current = current,
                navigation = false,
                onApply = { value -> applyMediaChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(0, 60))
    }

    private fun navigationChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.contrib_audio_home_choice_summary, getString(R.string.contrib_audio_home_nav_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadNavigationAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadNavigationAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_nav_channel_label),
                current = current,
                navigation = true,
                onApply = { value -> applyNavigationChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(label(getString(R.string.contrib_audio_home_nav_channel_note), 14, MUTED).apply {
            setPadding(0, dp(8), 0, dp(18))
        })
    }

    private fun showChannelDialog(title: String, current: Int, navigation: Boolean, onApply: (Int) -> Unit) {
        val preview = AudioChannelPreview { channel ->
            if (!isFinishing && !isDestroyed) toast(getString(R.string.contrib_audio_home_channel_preview_unavailable, channel))
        }
        audioPreviews.add(preview)
        val channels = AirPlayPersistence.AUDIO_CHANNELS
        val labels = channels.map(Int::toString).toTypedArray()
        var selection = current.coerceIn(channels.first, channels.last)
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels, selection) { _, which ->
                selection = which
                preview.play(which, navigation)
            }
            .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.apply_and_reconnect) else getString(R.string.save)) { _, _ ->
                onApply(selection)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setOnDismissListener { preview.close(); audioPreviews.remove(preview) }
            .show().also { styleSettingsDialog(it) }
    }

    private fun applyMediaChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveMediaAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyNavigationChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveNavigationAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun channelLabel(value: Int): String = value.toString()

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun askHotspotCredentials(done: (String, String) -> Unit) {
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label(getString(R.string.copy_these_from_the_car_s_hotspot_settings_use_5_ghz_if_av), 16, MUTED))
        val ssid = EditText(this).apply { hint = getString(R.string.hotspot_name); setText(storedSsid()); setSingleLine() }
        val password = EditText(this).apply {
            hint = getString(R.string.hotspot_password); setText(storedPassword()); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ssid.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fun hideKeyboard() {
            val token = password.windowToken ?: ssid.windowToken
            (this.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
            ssid.clearFocus(); password.clearFocus()
        }
        ssid.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        fields.addView(ssid); fields.addView(password)
        fields.addView(CheckBox(this).apply {
            text = getString(R.string.show_password)
            setOnCheckedChangeListener { _, checked ->
                password.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        })
        val error = label("", 14, WARNING)
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.car_hotspot_details))
            .setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton(getString(R.string.save_details), null).setNegativeButton(getString(R.string.cancel)) { _, _ -> hideKeyboard() }
            .setNeutralButton(getString(R.string.hide_keyboard), null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = ssid.text.toString().trim()
                val secret = password.text.toString()
                val problem = hotspotError(name, secret)
                if (problem != null) error.text = problem
                else { hideKeyboard(); dialog.dismiss(); done(name, secret) }
            }
        }
        dialog.show()
        styleSettingsDialog(dialog)
    }

    // "Left 20 %", "Centre · default", "Down 10 %": a signed step reads as a direction and a distance.
    private fun markerStepLabel(step: Int, negative: String, positive: String): String = when {
        step == 0 -> getString(R.string.marker_centre_default)
        step < 0 -> "$negative ${-step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
        else -> "$positive ${step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
    }

    /** A 2%-step slider row for overlay placement; every step saves, so the card moves live. */
    private fun overlaySliderRow(title: String, values: List<Int>, current: Int, describe: (Int) -> String): OverlaySliderRow =
        OverlaySliderRow(this, title, values, current, describe)

    private inner class OverlaySliderRow(
        context: android.content.Context,
        title: String,
        private val steps: List<Int>,
        current: Int,
        private val describe: (Int) -> String,
    ) : LinearLayout(context) {
        var onSave: (Int) -> Unit = {}
        val slider: SeekBar

        init {
            orientation = VERTICAL
            val valueView = label(describe(current), 16, ACCENT, true)
            val head = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
            head.addView(label(title, 16, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(valueView)
            addView(head)
            slider = SeekBar(context).apply {
                max = steps.lastIndex
                progress = steps.indexOf(current).coerceIn(steps.indices)
                minHeight = dp(44)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = steps[progress.coerceIn(steps.indices)]
                        valueView.text = describe(value)
                        if (fromUser) onSave(value)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            addView(slider, LinearLayout.LayoutParams(-1, dp(44)))
        }
    }

    private fun overlayOffsetLabel(percent: Int, negative: String, positive: String, centre: Int): String {
        val delta = percent - centre
        return when {
            delta == 0 -> getString(R.string.marker_centre_default)
            delta < 0 -> "$negative ${-delta} %"
            else -> "$positive $delta %"
        }
    }

    private fun showClusterAccessSetup() {
        val command = "adb shell appops set $packageName GET_USAGE_STATS allow"
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.one_time_setup_on_this_car), 20, TEXT, true))
        body.addView(label(getString(R.string.usage_access_lets_diplay_follow_the_instrument_theme_and_m), 15, MUTED))
        body.addView(label(getString(R.string.s_1_connect_a_computer_with_adb_installed_to_the_car_using), 16, TEXT))
        body.addView(label(command, 16, TEXT).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, dp(16))
        })
        body.addView(button(getString(R.string.copy_command), false) {
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                android.content.ClipData.newPlainText(getString(R.string.clipboard_usage_access), command))
            toast(getString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(0, 56))
        body.addView(label(getString(R.string.cluster_adb_multi_device, packageName), 14, MUTED))
        body.addView(label(getString(R.string.s_3_tap_check_and_enable_below_this_enables_the_cluster_ma), 16, TEXT))
        val status = label(if (DiLink51ClusterMonitor.hasAccess(this)) getString(R.string.permission_enabled_ready) else getString(R.string.permission_not_enabled), 16, TEXT)
        body.addView(status)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.automatic_cluster_map_setup))
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton(getString(R.string.close), null)
            .setPositiveButton(getString(R.string.check_and_enable), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (DiLink51ClusterMonitor.hasAccess(this)) {
                    AirPlayPersistence.saveClusterMapEnabled(this, true)
                    DiLink51ClusterLayout.saveAutomatic(this, true)
                    dialog.dismiss()
                    render()
                    toast(getString(R.string.automatic_map_enabled_open_the_cluster_map_card_or_select))
                    reconnectForClusterMap()
                } else {
                    status.text = getString(R.string.still_waiting_for_usage_access_check_that_the_command_ran)
                }
            }
        }
        dialog.show()
        styleSettingsDialog(dialog)
    }

    /** The 0.2.9 Dashboard song setting, shown once: in the BYD navigation card, or under Advanced vehicle data. */
    private fun clusterSongSwitch(card: LinearLayout) {
        toggle(card, getString(R.string.cluster_song),
            getString(R.string.cluster_song_description),
            BydOutputSettings.clusterSong(this), enabled = !adbSwitchChangePending) {
            BydOutputSettings.setClusterSong(this, it)
            if (it) checkAdbState(mayAsk = true)
            BydNavigationOutputs.clusterSongChanged(it)
        }
    }

    private fun advancedVehicleData(card: LinearLayout) {
        val legacyMode = BydOutputSettings.legacyVehicleProbe(this)
        card.addView(label(getString(R.string.advanced_vehicle_data_description), 14, MUTED)
            .apply { setPadding(0, dp(12), 0, 0) })
        choice(
            card,
            getString(R.string.vehicle_data_mode),
            listOf(
                getString(R.string.vehicle_data_mode_default),
                getString(R.string.vehicle_data_mode_legacy),
            ),
            if (legacyMode) 1 else 0,
            reconnects = false,
            announcesReconnect = vehicleDataSwitchesOn(),
            // A switch during ADB work would be dropped by the running step.
            enabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress(),
        ) { selectVehicleDataMode(it == 1) }
        if (!legacyMode) {
            defaultVehicleData(card)
            return
        }
        val capabilities = displayedVehicleCapabilities()
        val adbText = when {
            vehicleProbeAuthorizationInProgress -> getString(R.string.adb_checking_may_ask)
            adbCheckInProgress -> getString(if (adbCheckMayAsk)
                R.string.adb_checking_may_ask else R.string.adb_checking)
            vehicleProbeInProgress || automaticVehicleValidationInProgress ->
                getString(R.string.probing_vehicle_data)
            adbCheckFailed -> getString(R.string.adb_check_failed)
            adbAccessState == null && capabilities != null -> getString(R.string.vehicle_probe_saved_automatic)
            adbAccessState == null -> getString(R.string.adb_not_checked)
            else -> adbLinkStatusText(requireNotNull(adbAccessState))
        }
        val healthy = capabilities != null && !adbCheckFailed && vehicleProbeOutcome?.error == null &&
            pendingVehicleLostFields.isEmpty() &&
            (adbAccessState == null || adbAccessState == BydAdbAccess.State.READY)
        card.addView(label(adbText, 14, if (healthy) MUTED else WARNING)
            .apply { setPadding(0, dp(12), 0, 0) })
        if (capabilities != null) {
            showVehicleProbeResults(card, capabilities)
            showVehicleDataSettings(card, capabilities)
        }
        if (vehicleProbeAuthorizationInProgress || adbCheckInProgress ||
            vehicleProbeInProgress || automaticVehicleValidationInProgress) return
        vehicleProbeOutcome?.error?.let {
            card.addView(label(getString(R.string.vehicle_probe_failed, it), 14, WARNING)
                .apply { setPadding(0, dp(10), 0, 0) })
        }
        if (pendingVehicleLostFields.isNotEmpty()) {
            card.addView(label(
                getString(
                    R.string.vehicle_probe_lost_saved_fields,
                    localizedVehicleFields(pendingVehicleLostFields),
                ),
                14,
                WARNING,
            ).apply { setPadding(0, dp(10), 0, 0) })
            card.addView(button(getString(R.string.replace_saved_vehicle_data_anyway), false) {
                replaceSavedVehicleDataAnyway()
            }, matchButton(10, 56))
        }
        val needsUserAction = capabilities == null || adbCheckFailed || vehicleProbeOutcome?.error != null ||
            pendingVehicleLostFields.isNotEmpty() ||
            adbAccessState in setOf(BydAdbAccess.State.NOT_APPROVED, BydAdbAccess.State.ADB_OFF, BydAdbAccess.State.PAIRING_ONLY)
        if (needsUserAction) {
            val title = if (adbAccessState == BydAdbAccess.State.NOT_APPROVED) {
                R.string.request_adb_authorization
            } else if (capabilities == null) {
                R.string.probe_adb_and_vehicle_data
            } else {
                R.string.probe_vehicle_data_again
            }
            card.addView(button(getString(title), false) {
                probeVehicleData(mayAsk = true)
            }.apply { isEnabled = !adbSwitchChangePending && !adbCheckInProgress }, matchButton(10, 56))
        }
    }

    private fun defaultVehicleData(card: LinearLayout) {
        val adbText = when {
            vehicleProbeAuthorizationInProgress -> getString(R.string.adb_checking_may_ask)
            adbCheckInProgress -> getString(if (adbCheckMayAsk)
                R.string.adb_checking_may_ask else R.string.adb_checking)
            vehicleProbeInProgress -> getString(R.string.probing_vehicle_data)
            adbCheckFailed -> getString(R.string.adb_check_failed)
            adbAccessState == null -> getString(R.string.adb_not_checked)
            else -> adbLinkStatusText(requireNotNull(adbAccessState))
        }
        val healthy = !adbCheckFailed && vehicleProbeOutcome?.error == null &&
            (adbAccessState == null || adbAccessState == BydAdbAccess.State.READY)
        card.addView(label(adbText, 14, if (healthy) MUTED else WARNING)
            .apply { setPadding(0, dp(12), 0, 0) })
        val busy = vehicleProbeAuthorizationInProgress || adbCheckInProgress || vehicleProbeInProgress
        if (!busy && adbAccessState == BydAdbAccess.State.READY) {
            defaultVehicleStatus?.let { showDefaultVehicleReadings(card, it) }
        }
        vehicleProbeOutcome?.error?.let {
            card.addView(label(getString(R.string.vehicle_probe_failed, it), 14, WARNING)
                .apply { setPadding(0, dp(10), 0, 0) })
        }
        showVehicleDataSettings(card, capabilities = null)
        if (!busy) {
            card.addView(button(getString(R.string.check_adb_access), false) {
                checkAdbState(mayAsk = true)
            }.apply { isEnabled = !adbSwitchChangePending }, matchButton(10, 56))
        }
    }

    /** The last default-mode reads; a value an enabled switch needs is a warning when missing. */
    private fun showDefaultVehicleReadings(card: LinearLayout, status: BydAdbAccess.Status) {
        fun reading(value: String?, unreadable: Int, needed: Boolean) {
            val text = value ?: getString(unreadable).takeIf { needed } ?: return
            card.addView(label(text, 14, if (value != null) MUTED else WARNING))
        }
        val battery = status.batteryPercent?.let { percent ->
            status.rangeKm?.let { getString(R.string.adb_battery_reading, percent.roundToInt(), it) }
        }
        reading(battery, R.string.adb_battery_unreadable, BydOutputSettings.batteryToIphone(this))
        reading(status.speedKmh?.let { getString(R.string.adb_vehicle_speed_reading, it.roundToInt()) },
            R.string.adb_vehicle_speed_unreadable, BydOutputSettings.wheelSpeedToIphone(this))
        reading(status.gear?.let { getString(R.string.adb_vehicle_gear_reading, it.letter.toString()) },
            R.string.adb_vehicle_gear_unreadable,
            BydOutputSettings.wheelSpeedToIphone(this) || BydOutputSettings.videoWhileParked(this))
    }

    private fun selectVehicleDataMode(legacyMode: Boolean) {
        if (adbSwitchChangePending) return
        // A stale mode dialog must also invalidate a probe when the Boolean stays the same.
        synchronized(vehicleOperationLock) { vehicleProbeGeneration++ }
        vehicleProbeAuthorizationInProgress = false
        vehicleProbeInProgress = false
        if (!legacyMode) {
            cancelAutomaticVehicleValidationForUserOperation(resumeAfter = false)
            synchronized(vehicleOperationLock) {
                BydOutputSettings.setLegacyVehicleProbe(this, false)
            }
            vehicleProbeOutcome = null
            pendingVehicleReplacement = null
            pendingVehicleLostFields = emptySet()
            defaultVehicleStatus = null
            render()
            // The session was built from the legacy probe; apply once the default reads succeed.
            vehicleDataReconnectPending = vehicleDataSwitchesOn()
            if (vehicleDataReconnectPending) checkAdbState(mayAsk = true)
            return
        }
        if (BydVehicleFieldStore.load(this) == null) {
            probeVehicleData(mayAsk = true, activateLegacyModeOnSuccess = true)
            return
        }
        synchronized(vehicleOperationLock) {
            BydOutputSettings.setLegacyVehicleProbe(this, true)
        }
        vehicleProbeOutcome = null
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        scheduleAutomaticVehicleValidation()
        render()
        // With every vehicle-data switch off, the mode changes nothing CarPlay was told.
        if (vehicleDataSwitchesOn()) reconnectForVehicleSetting()
    }

    // Probe results are saved before they are shown, so the saved snapshot is the newest without
    // trusting the clock.
    private fun displayedVehicleCapabilities(): BydVehicleCapabilities? =
        BydVehicleFieldStore.load(this) ?: vehicleProbeOutcome?.capabilities

    private fun localizedVehicleFields(fields: Set<BydVehicleField>): String = fields.map { field ->
        getString(when (field) {
            BydVehicleField.SPEED -> R.string.vehicle_field_speed
            BydVehicleField.GEAR -> R.string.vehicle_field_gear
            BydVehicleField.SOC, BydVehicleField.RANGE, BydVehicleField.REMAINING_KWH ->
                R.string.vehicle_field_battery
            BydVehicleField.BMS_STATE -> R.string.vehicle_field_charging
        })
    }.distinct().joinToString()

    private fun replaceSavedVehicleDataAnyway() {
        val candidate = pendingVehicleReplacement ?: return
        // Never over a snapshot saved since the comparison; a failed write is shown, not thrown.
        val error = runCatching {
            BydVehicleFieldStore.replaceAnyway(applicationContext, pendingVehicleReplacementExpected, candidate)
        }.fold(
            onSuccess = { replaced -> if (replaced) null else getString(R.string.vehicle_probe_snapshot_changed) },
            onFailure = { it.message ?: it.javaClass.simpleName },
        )
        Log.i(BYD_VEHICLE_TAG, "user replaced saved vehicle data error=${error ?: "none"}")
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        vehicleProbeOutcome = error?.let { BydVehicleProbeOutcome(BydAdbAccess.State.READY, error = it) }
        render()
    }

    private fun showVehicleProbeResults(card: LinearLayout, capabilities: BydVehicleCapabilities) {
        val catalogFields = capabilities.fields.values.count { it.address?.source == BydFieldSource.FIRMWARE }
        card.addView(label(getString(if (capabilities.catalogAvailable)
            R.string.vehicle_probe_catalog_ready else R.string.vehicle_probe_catalog_fallback, catalogFields),
            14, if (capabilities.catalogAvailable) MUTED else WARNING).apply { setPadding(0, dp(14), 0, 0) })

        val speed = capabilities.result(BydVehicleField.SPEED)
        val speedValue = speed.value
        val speedText = when {
            speedValue != null -> getString(R.string.adb_vehicle_speed_reading, speedValue.roundToInt())
            speed.supported -> getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_speed))
            else -> getString(R.string.adb_vehicle_speed_unreadable)
        }
        card.addView(label(speedText, 14, if (speed.supported) MUTED else WARNING))
        val gear = capabilities.result(BydVehicleField.GEAR)
        val gearValue = gear.value
        val gearText = when {
            gearValue != null -> getString(R.string.adb_vehicle_gear_reading, gearLetter(gearValue.toInt()))
            gear.supported -> getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_gear))
            else -> getString(R.string.adb_vehicle_gear_unreadable)
        }
        card.addView(label(gearText, 14, if (gear.supported) MUTED else WARNING))

        if (capabilities.batterySupported) {
            val percentValue = capabilities.result(BydVehicleField.SOC).value
            val rangeValue = capabilities.result(BydVehicleField.RANGE).value
            val energy = capabilities.result(BydVehicleField.REMAINING_KWH).value
            card.addView(label(if (percentValue == null || rangeValue == null) {
                getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_battery))
            } else if (energy != null) {
                getString(R.string.vehicle_probe_battery_reading,
                    percentValue.roundToInt(), rangeValue.roundToInt(), energy)
            } else {
                getString(R.string.vehicle_probe_battery_without_energy,
                    percentValue.roundToInt(), rangeValue.roundToInt())
            }, 14, if (energy != null || percentValue == null) MUTED else WARNING))
        } else {
            card.addView(label(getString(R.string.adb_battery_unreadable), 14, WARNING))
        }
        val charging = capabilities.result(BydVehicleField.BMS_STATE)
        val chargingValue = charging.value
        val chargingText = when {
            chargingValue != null -> getString(R.string.vehicle_probe_charging_state, chargingValue.toInt())
            charging.supported -> getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_charging))
            else -> getString(R.string.vehicle_probe_charging_unreadable)
        }
        card.addView(label(chargingText, 14, if (charging.supported) MUTED else WARNING))
    }

    private fun showVehicleDataSettings(card: LinearLayout, capabilities: BydVehicleCapabilities?) {
        if (capabilities == null || capabilities.batterySupported) {
            toggle(card, getString(R.string.car_battery_for_the_iphone),
                getString(R.string.car_battery_for_the_iphone_description),
                BydOutputSettings.batteryToIphone(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setBatteryToIphone(this, it)
                onVehicleDataSettingChanged(it)
            }
            val connectors = EvChargingConnectors.entries
            choice(card, getString(R.string.charging_connectors), connectors.map { it.localizedLabel(this) },
                connectors.indexOf(BydOutputSettings.chargingConnectors(this))) {
                BydOutputSettings.setChargingConnectors(this, connectors[it])
            }
            val lowCharge = BydOutputSettings.lowChargePresets
            choice(card, getString(R.string.low_charge_warning), lowCharge.map {
                    getString(if (it == BydOutputSettings.DEFAULT_LOW_CHARGE_PERCENT)
                        R.string.percent_default else R.string.percent_value, it)
                }, lowCharge.indexOf(BydOutputSettings.lowChargePercent(this)).coerceAtLeast(0), reconnects = false) {
                BydOutputSettings.setLowChargePercent(this, lowCharge[it])
            }
        }
        if (capabilities == null || capabilities.motionSupported) {
            toggle(card, getString(R.string.wheel_speed_for_tunnels),
                getString(R.string.wheel_speed_for_tunnels_description),
                BydOutputSettings.wheelSpeedToIphone(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setWheelSpeedToIphone(this, it)
                onVehicleDataSettingChanged(it)
            }
        }
        if (capabilities == null || capabilities.gearSupported) {
            toggle(card, getString(R.string.video_while_parked),
                getString(R.string.video_while_parked_description),
                BydOutputSettings.videoWhileParked(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setVideoWhileParked(this, it)
                onVehicleDataSettingChanged(it)
            }
        }
    }

    private fun onVehicleDataSettingChanged(enabled: Boolean) {
        if (!enabled) {
            reconnectForVehicleSetting()
        } else if (BydOutputSettings.legacyVehicleProbe(this)) {
            scheduleAutomaticVehicleValidation()
            reconnectForVehicleSetting()
        } else {
            // Default mode applies the switch once a check reads what the enabled switches need.
            vehicleDataReconnectPending = true
            checkAdbState(mayAsk = true)
        }
    }

    // The approval dialog can open only after an explicit user action.
    private fun checkAdbState(mayAsk: Boolean) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) return
        val legacy = BydOutputSettings.legacyVehicleProbe(this)
        // Only a validation this check interrupts runs again; a Dashboard switch needs no vehicle check.
        cancelAutomaticVehicleValidationForUserOperation(
            resumeAfter = automaticVehicleValidationInProgress || automaticVehicleValidationPending,
        )
        val generation = ++adbCheckGeneration
        adbCheckInProgress = true
        adbCheckMayAsk = mayAsk
        adbCheckFailed = false
        render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("diplay-adb-state") {
            val result = runCatching {
                if (legacy) {
                    BydAdbAccess.Status(backend.checkState(applicationContext, mayAsk))
                } else {
                    // The default battery path must publish its first sample before CarPlay reconnects.
                    backend.check(applicationContext, mayAsk)
                }
            }
            runOnUiThread {
                val current = generation == adbCheckGeneration
                if (current) {
                    adbCheckInProgress = false
                    adbCheckMayAsk = false
                }
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                adbCheckFailed = result.isFailure
                val status = result.getOrNull()
                adbAccessState = status?.state
                if (!legacy) defaultVehicleStatus = status
                if (!legacy && adbAccessState == BydAdbAccess.State.READY) {
                    vehicleProbeOutcome = null
                }
                render()
                // Unreadable data keeps the current connection; the page shows what is missing.
                if (!legacy && vehicleDataReconnectPending && status?.state == BydAdbAccess.State.READY &&
                    enabledVehicleDataReadable(null, status)) {
                    vehicleDataReconnectPending = false
                    reconnectForVehicleSetting()
                }
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    /** First probe or user retry: one click handles ADB authorization, probing and persistence. */
    private fun probeVehicleData(mayAsk: Boolean) =
        probeVehicleData(mayAsk, activateLegacyModeOnSuccess = false)

    private fun probeVehicleData(mayAsk: Boolean, activateLegacyModeOnSuccess: Boolean) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) return
        val expectedLegacyMode = BydOutputSettings.legacyVehicleProbe(this)
        val expectedSnapshot = cancelAutomaticVehicleValidationForUserOperation(resumeAfter = false)
        val generation = ++vehicleProbeGeneration
        vehicleProbeAuthorizationInProgress = mayAsk
        vehicleProbeInProgress = !mayAsk
        adbCheckFailed = false
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        Log.i(BYD_VEHICLE_TAG, "user vehicle probe starting mayAsk=$mayAsk")
        render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("diplay-byd13-probe") {
            val app = applicationContext
            val attempt = runCatching {
                val access = backend.checkState(app, mayAsk)
                if (access != BydAdbAccess.State.READY) {
                    VehicleProbeAttempt(BydVehicleProbeOutcome(access))
                } else {
                    runOnUiThread {
                        if (generation == vehicleProbeGeneration && !isFinishing && !isDestroyed) {
                            vehicleProbeAuthorizationInProgress = false
                            vehicleProbeInProgress = true
                            render()
                        }
                    }
                    var candidate = backend.probe(app, persist = false)
                    if (candidate.access == BydAdbAccess.State.NOT_APPROVED) {
                        // adbd confirms "Always allow" before it saves the key, so this new connection can be early.
                        Log.i(BYD_VEHICLE_TAG, "probe connection not approved yet; retrying once")
                        Thread.sleep(ADB_KEY_SAVE_WAIT_MILLIS)
                        candidate = backend.probe(app, persist = false)
                    }
                    val candidateCapabilities = candidate.capabilities
                    when {
                        candidate.access == BydAdbAccess.State.NOT_APPROVED -> VehicleProbeAttempt(
                            outcome = candidate,
                            allowedOnlyOnce = true,
                        )
                        candidateCapabilities == null -> VehicleProbeAttempt(candidate)
                        else -> {
                            // Invalidation must guard persistence/publication, not only the UI callback.
                            val replacement = synchronized(vehicleOperationLock) {
                                if (generation != vehicleProbeGeneration ||
                                    BydOutputSettings.legacyVehicleProbe(app) != expectedLegacyMode) null
                                else BydVehicleFieldStore.replaceAutomatically(
                                    app,
                                    expectedSnapshot,
                                    candidateCapabilities,
                                )
                            } ?: return@runCatching VehicleProbeAttempt(
                                outcome = BydVehicleProbeOutcome(candidate.access),
                                snapshotChanged = true,
                            )
                            when {
                                replacement.saved -> VehicleProbeAttempt(candidate)
                                replacement.snapshotChanged -> VehicleProbeAttempt(
                                    outcome = candidate,
                                    snapshotChanged = true,
                                )
                                else -> VehicleProbeAttempt(
                                    outcome = BydVehicleProbeOutcome(candidate.access),
                                    heldCandidate = candidateCapabilities,
                                    lostFields = replacement.lostFields,
                                )
                            }
                        }
                    }
                }
            }.getOrElse { error ->
                VehicleProbeAttempt(
                    BydVehicleProbeOutcome(
                        BydAdbAccess.State.READY,
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
            runOnUiThread {
                val current = generation == vehicleProbeGeneration
                if (current) {
                    vehicleProbeAuthorizationInProgress = false
                    vehicleProbeInProgress = false
                }
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                pendingVehicleReplacement = attempt.heldCandidate
                pendingVehicleReplacementExpected = expectedSnapshot
                pendingVehicleLostFields = attempt.lostFields
                vehicleProbeOutcome = when {
                    attempt.allowedOnlyOnce -> attempt.outcome.copy(
                        error = getString(R.string.vehicle_probe_allowed_once),
                    )
                    attempt.snapshotChanged -> attempt.outcome.copy(
                        capabilities = null,
                        error = getString(R.string.vehicle_probe_snapshot_changed),
                    )
                    else -> attempt.outcome
                }
                adbAccessState = attempt.outcome.access
                val activatedLegacyMode = activateLegacyModeOnSuccess &&
                    vehicleProbeOutcome?.capabilities != null
                if (activatedLegacyMode) {
                    BydOutputSettings.setLegacyVehicleProbe(this, true)
                }
                if (vehicleProbeOutcome?.capabilities != null) automaticVehicleValidationStarted = true
                Log.i(
                    BYD_VEHICLE_TAG,
                    "user vehicle probe access=${attempt.outcome.access} " +
                        "saved=${vehicleProbeOutcome?.capabilities != null} " +
                        "lost=${attempt.lostFields.joinToString()} " +
                        "error=${vehicleProbeOutcome?.error ?: "none"}",
                )
                render()
                if (activatedLegacyMode && vehicleDataSwitchesOn()) reconnectForVehicleSetting()
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    /**
     * After the first saved probe, validation is automatic and never asks for authorization. ADB
     * transport failure keeps the saved snapshot; only two complete READY-but-unreadable checks
     * trigger one automatic field re-probe.
     */
    private fun validateSavedVehicleConfigurationAutomatically() {
        if (!BydOutputSettings.legacyVehicleProbe(this)) return
        val saved = BydVehicleFieldStore.load(applicationContext) ?: return
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) {
            automaticVehicleValidationPending = true
            return
        }
        if (automaticVehicleValidationInProgress) {
            // A request during a run, such as a switch just turned on, runs once this one ends.
            automaticVehicleValidationPending = true
            return
        }
        if (automaticVehicleValidationStarted) return
        automaticVehicleValidationStarted = true
        automaticVehicleValidationInProgress = true
        val generation = ++vehicleValidationGeneration
        Log.i(BYD_VEHICLE_TAG, "automatic vehicle validation starting savedFirmware=${saved.firmwareKey}")
        if (page == "settings" && settingsSection == SteamSettingsSection.LOCATION && bydVehicleAdvancedExpanded) render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("diplay-byd13-auto-validate") {
            val app = applicationContext
            val validation = runCatching {
                var status = backend.check(app, mayAsk = false)
                var outcome: BydVehicleProbeOutcome? = null
                var heldCandidate: BydVehicleCapabilities? = null
                var lostFields: Set<BydVehicleField> = emptySet()
                var snapshotChanged = false
                var readable = status.state == BydAdbAccess.State.READY && enabledVehicleDataReadable(saved, status)
                if (status.state == BydAdbAccess.State.READY && !enabledVehicleDataReadable(saved, status)) {
                    Log.w(BYD_VEHICLE_TAG, "saved vehicle fields unreadable; validating once more")
                    try {
                        Thread.sleep(VEHICLE_VALIDATION_RETRY_MILLIS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    // A cancelled validation reads nothing more alongside the operation that replaced it.
                    if (generation != vehicleValidationGeneration) {
                        return@runCatching VehicleValidationAttempt(
                            status = status,
                            snapshotChanged = true,
                        )
                    }
                    status = backend.check(app, mayAsk = false)
                    readable = status.state == BydAdbAccess.State.READY && enabledVehicleDataReadable(saved, status)
                    if (status.state == BydAdbAccess.State.READY && !readable) {
                        if (generation != vehicleValidationGeneration) {
                            return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                        }
                        Log.w(BYD_VEHICLE_TAG, "saved vehicle fields still unreadable; automatic re-probe starting")
                        val candidate = backend.probe(app, persist = false)
                        if (generation != vehicleValidationGeneration) {
                            return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                        }
                        outcome = candidate
                        candidate.capabilities?.let { next ->
                            val replacement = synchronized(vehicleOperationLock) {
                                if (generation != vehicleValidationGeneration) null
                                else BydVehicleFieldStore.replaceAutomatically(app, saved, next)
                            } ?: return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                            when {
                                replacement.saved -> Unit
                                replacement.snapshotChanged -> {
                                    snapshotChanged = true
                                    outcome = null
                                }
                                else -> {
                                    heldCandidate = next
                                    lostFields = replacement.lostFields
                                    outcome = BydVehicleProbeOutcome(candidate.access)
                                }
                            }
                        }
                    }
                }
                VehicleValidationAttempt(
                    status = status,
                    outcome = outcome,
                    heldCandidate = heldCandidate,
                    lostFields = lostFields,
                    snapshotChanged = snapshotChanged,
                    savedFieldsReadable = readable,
                )
            }
            runOnUiThread {
                val current = generation == vehicleValidationGeneration
                if (current) automaticVehicleValidationInProgress = false
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                adbCheckFailed = validation.isFailure
                validation.exceptionOrNull()?.let { Log.w(BYD_VEHICLE_TAG, "automatic vehicle validation failed", it) }
                validation.getOrNull()?.let { result ->
                    adbAccessState = result.outcome?.access ?: result.status.state
                    when {
                        result.snapshotChanged -> Unit
                        result.savedFieldsReadable && result.outcome == null -> {
                            // An open offer to replace stays: this check may not have read its lost fields.
                            vehicleProbeOutcome = null
                        }
                        result.lostFields.isNotEmpty() -> {
                            vehicleProbeOutcome = result.outcome
                            pendingVehicleReplacement = result.heldCandidate
                            pendingVehicleReplacementExpected = saved
                            pendingVehicleLostFields = result.lostFields
                        }
                        result.outcome != null -> {
                            vehicleProbeOutcome = result.outcome
                            pendingVehicleReplacement = null
                            pendingVehicleLostFields = emptySet()
                        }
                    }
                    Log.i(
                        BYD_VEHICLE_TAG,
                        "automatic vehicle validation access=${adbAccessState} " +
                            "reprobed=${result.outcome != null} " +
                            "saved=${result.outcome?.capabilities != null && result.lostFields.isEmpty()} " +
                            "lost=${result.lostFields.joinToString()} " +
                            "snapshotChanged=${result.snapshotChanged} " +
                            "error=${result.outcome?.error ?: "none"}",
                    )
                }
                if (page == "settings" && settingsSection == SteamSettingsSection.LOCATION && bydVehicleAdvancedExpanded) {
                    render()
                }
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    private fun scheduleAutomaticVehicleValidation() {
        if (!BydOutputSettings.legacyVehicleProbe(this)) {
            automaticVehicleValidationPending = false
            handler.removeCallbacks(automaticVehicleValidation)
            return
        }
        automaticVehicleValidationStarted = false
        automaticVehicleValidationPending = true
        handler.removeCallbacks(automaticVehicleValidation)
        handler.post(automaticVehicleValidation)
    }

    private fun cancelAutomaticVehicleValidationForUserOperation(
        resumeAfter: Boolean,
    ): BydVehicleCapabilities? = synchronized(vehicleOperationLock) {
        handler.removeCallbacks(automaticVehicleValidation)
        automaticVehicleValidationPending = resumeAfter
        if (resumeAfter) automaticVehicleValidationStarted = false
        vehicleValidationGeneration++
        automaticVehicleValidationInProgress = false
        BydVehicleFieldStore.load(applicationContext)
    }

    private fun runPendingAutomaticVehicleValidation() {
        if (!automaticVehicleValidationPending || adbSwitchChangePending ||
            vehicleAdbWorkInProgress() || automaticVehicleValidationInProgress) return
        handler.removeCallbacks(automaticVehicleValidation)
        handler.post(automaticVehicleValidation)
    }

    /** Whether [status] has every reading an enabled switch needs; null [capabilities] is default mode. */
    private fun enabledVehicleDataReadable(
        capabilities: BydVehicleCapabilities?,
        status: BydAdbAccess.Status,
    ): Boolean {
        if (BydOutputSettings.batteryToIphone(this) && capabilities?.batterySupported != false &&
            (status.batteryPercent == null || status.rangeKm == null)) return false
        if (BydOutputSettings.wheelSpeedToIphone(this) && capabilities?.motionSupported != false &&
            (status.speedKmh == null || status.gear == null)) return false
        if (BydOutputSettings.videoWhileParked(this) && capabilities?.gearSupported != false && status.gear == null) return false
        return true
    }

    private fun adbLinkStatusText(state: BydAdbAccess.State): String = when (state) {
        BydAdbAccess.State.READY -> getString(R.string.adb_access_ready)
        BydAdbAccess.State.NOT_APPROVED -> getString(R.string.adb_enabled_not_approved)
        BydAdbAccess.State.ADB_OFF -> getString(R.string.adb_off)
        BydAdbAccess.State.PAIRING_ONLY -> getString(R.string.adb_pairing_only)
    }

    private fun gearLetter(value: Int): String = when (value) {
        1 -> "P"
        2 -> "R"
        3 -> "N"
        else -> "D"
    }

    private fun reconnectForVehicleSetting() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun vehicleDataSwitchesOn() = BydOutputSettings.batteryToIphone(this) ||
        BydOutputSettings.wheelSpeedToIphone(this) || BydOutputSettings.videoWhileParked(this)

    private fun hasPreciseLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // The cluster screen is described at connection time, so a running session reconnects over
    // its current link. The position choices need no call: getString(R.string.apply_and_reconnect) already does it.
    private fun reconnectForClusterMap() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun onLocationReportingChanged(enabled: Boolean) {
        if (enabled && !hasPreciseLocation()) {
            // Keep the switch off until precise location is actually granted.
            render()
            locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
            return
        }
        applyLocationReporting(enabled)
    }

    private fun applyLocationReporting(enabled: Boolean) {
        if (AirPlayPersistence.loadLocationReportingEnabled(this) == enabled) return
        AirPlayPersistence.saveLocationReportingEnabled(this, enabled)
        render()
        // Location support is advertised during iAP2 identification, so both enabling and
        // disabling it require a new session. The host also refreshes its location service type.
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
        else toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        startupHotspotCancelled = true
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton(getString(R.string.cancel), null).show().also { styleSettingsDialog(it) }
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, getString(R.string.carplay_size), sizes.map { it.localizedLabel(this) }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label(getString(R.string.changes_the_size_of_carplay_icons_and_text_applying_a_size), 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun connect(wireless: Boolean) {
        if (isFinishing || isDestroyed) return
        startupHotspotCancelled = true
        if (wireless && pendingCarHotspotSetup) { toast(getString(R.string.save_your_hotspot_details_in_connection_setup_first)); page = "connection"; render(); return }
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            hotspotError(storedSsid(), storedPassword()) != null) {
            pendingCarHotspotSetup = true
            page = "connection"
            render()
            toast(getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings))
            return
        }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            AlertDialog.Builder(this).setTitle(getString(R.string.turn_on_bluetooth))
                .setMessage(getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.later), null).show().also { styleSettingsDialog(it) }; return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setTitle(getString(R.string.pair_your_iphone))
                .setMessage(getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.got_it), null).show().also { styleSettingsDialog(it) }; return
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.choose_your_iphone))
            .setItems(devices.map { device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton(getString(R.string.pair_another)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }.show().also { styleSettingsDialog(it) }
    }

    private fun wirelessHelp() {
        AlertDialog.Builder(this).setTitle(getString(R.string.wireless_connection_help))
            .setMessage(getString(R.string.pair_your_iphone_with_the_car_s_bluetooth_keep_wi_fi_on_an))
            .setPositiveButton(getString(R.string.got_it), null)
            .setNeutralButton(getString(R.string.reset_carplay_wi_fi)) { _, _ ->
                confirmWirelessReset()
            }.show().also { styleSettingsDialog(it) }
    }

    private fun handleWirelessRecovery() {
        when (page) {
            "wireless-recovery" -> { page = "home"; render(); confirmWirelessReset() }
            "automatic-channel-recovery" -> {
                AirPlayPersistence.saveWifiP2pPreferredChannel(this, WifiP2pChannels.AUTO)
                page = "home"; render(); connect(true)
            }
            "retry-connection" -> { page = "home"; render(); connect(AirPlayPersistence.loadWirelessEnabled(this)) }
        }
    }

    private fun confirmWirelessReset() {
        AlertDialog.Builder(this).setTitle(getString(R.string.reset_carplay_wi_fi_2))
            .setMessage(getString(R.string.this_ends_the_existing_wi_fi_direct_connection_including_o))
            .setPositiveButton(getString(R.string.reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(getString(R.string.cancel), null).show().also { styleSettingsDialog(it) }
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast(getString(R.string.this_head_unit_does_not_support_wi_fi_direct)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.close(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.close(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.close(); toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.close(); toast(getString(R.string.could_not_reset_wi_fi_direct_close_the_other_projection_ap)) }
                })
            }
        } catch (_: SecurityException) {
            channel.close(); permissionHelp(getString(R.string.wireless_permissions), getString(R.string.allow_nearby_devices_and_on_older_android_versions_locatio))
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        val failure = CarPlayBackgroundSession.connectionFeedback?.takeIf { running && it.hint != null }
        val hotspotOff = !running && carHotspotOff()
        val nextStatus = when {
            setupError != null -> getString(R.string.setup_needs_attention)
            failure != null -> getString(failure.title)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            hotspotOff -> getString(R.string.steam_issue_hotspot_off)
            running -> getString(R.string.connecting_to_your_iphone)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        }
        status?.let { if (it.text.toString() != nextStatus) it.text = nextStatus }
        val warning = if (failure != null) getString(failure.hint!!) else if (running) null else if (hotspotOff) getString(R.string.steam_home_hotspot_needed)
            else hotspotStartupResult?.takeIf {
                it != CarHotspotTethering.Result.READY && it != CarHotspotTethering.Result.CANCELLED &&
                    CarHotspotSettings.shouldEnable(this, true, AirPlayPersistence.loadWirelessHotspotMode(this)) &&
                    com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) != true
            }?.let(::hotspotResultText)
        homeWarning?.let {
            if (it.text.toString() != (warning ?: "")) it.text = warning ?: ""
            it.visibility = if (warning != null) View.VISIBLE else View.GONE
        }
        homeWifiAction?.visibility = if (!running && warning != null) View.VISIBLE else View.GONE
        val buttonText = getString(when {
            failure != null -> R.string.steam_retry_connection
            running -> R.string.open_carplay
            else -> R.string.connect_phone
        })
        connectButton?.let { if (it.text.toString() != buttonText) it.text = buttonText }
        if (lastRunning != running) {
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
    }
    private fun reportFileName() = "CarPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        runCatching { export.launch(reportFileName()) }.onFailure {
            toast(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                getString(R.string.this_head_unit_could_not_open_a_save_location_please_try_s)
                else getString(R.string.this_head_unit_has_no_available_file_picker_to_save_the_re))
        }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = buildString {
                    appendLine("CarPlay ${version()} · Steam diagnostic report")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("Authentication: local experimental beta identity; no remote fallback")
                    appendLine("CarPlay setup: ${if (setupError == null) "ready" else "authentication unavailable"}")
                    appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScaleTenths(appContext) * 10}%")
                    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- BYD vehicle-data probe ---")
                    appendLine(
                        "mode=${if (BydOutputSettings.legacyVehicleProbe(appContext)) "legacy-probe" else "default"} " +
                            "switches location=${AirPlayPersistence.loadLocationReportingEnabled(appContext)} " +
                            "battery=${BydOutputSettings.batteryToIphone(appContext)} " +
                            "wheelSpeed=${BydOutputSettings.wheelSpeedToIphone(appContext)} " +
                            "parkedVideo=${BydOutputSettings.videoWhileParked(appContext)}",
                    )
                    val bydCapabilities = BydVehicleFieldStore.load(appContext)
                    if (bydCapabilities == null) {
                        appendLine("no saved successful probe")
                    } else {
                        appendLine(
                            "catalog=${bydCapabilities.catalogAvailable} detectedAt=${bydCapabilities.detectedAtMillis} " +
                                "savedFirmware=${bydCapabilities.firmwareKey} " +
                                "currentFirmware=${BydVehicleFieldStore.firmwareKey()}",
                        )
                        for (field in BydVehicleField.entries) {
                            val probe = bydCapabilities.result(field)
                            appendLine("${field.name}: supported=${probe.supported} " +
                                (probe.address?.let { "tx=${it.transaction} dev=${it.device} fid=${it.fid} source=${it.source}" }
                                    ?: "address=none"))
                        }
                    }
                    appendLine()
                    appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    appendLine("--- Last received boot and app-launch result ---")
                    appendLine(StartupDiagnosticSnapshot.report(appContext))
                    appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(appContext)} " +
                        "connectWhenOpened=${DiPlayPreferences.autoConnect(appContext)}")
                    appendLine()
                    appendLine("--- Recent own-app process exits (Android 11+) ---")
                    appendLine(ProcessExitDiagnostics.report(appContext))
                    appendLine()
                    append("\n--- 接收设备实时定位 ---\n")
                    append(GnssTelemetry.describe())
                    append(VideoOutput.describe())
                    HourDiagnosticLog.appendReport(appContext, this)

                }
                if (uri != null) { DiagnosticExportStore.write(appContext.contentResolver, uri, report); uri }
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DiagnosticExportStore.saveToDownloads(appContext.contentResolver, fileName, report)
                } else error("A save location is required")
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
                if (result.isSuccess) {
                    val savedUri = result.getOrThrow()
                    AlertDialog.Builder(this).setTitle(getString(R.string.diagnostic_report_saved))
                        .setMessage(if (uri == null) "${DiagnosticExportStore.downloadDirectory}/$fileName" else getString(R.string.your_report_was_saved_to_the_selected_location))
                        .setPositiveButton(getString(R.string.done), null)
                        .setNeutralButton(getString(R.string.share)) { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedUri)
                                    clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.share_diagnostic_report)))
                            }.onFailure { toast(getString(R.string.report_saved_open_it_from_your_file_manager_to_share_it)) }
                        }.show().also { styleSettingsDialog(it) }
                } else {
                    AlertDialog.Builder(this).setTitle(getString(R.string.could_not_save_the_report))
                        .setMessage(getString(R.string.check_that_storage_is_available_or_choose_another_save_loc))
                        .setPositiveButton(getString(R.string.choose_location)) { _, _ -> chooseReportDestination() }
                        .setNegativeButton(getString(R.string.close), null).show().also { styleSettingsDialog(it) }
                }
            }
        }, "diplay-export").start()
    }
    private fun permissionHelp(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(getString(R.string.app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(R.string.later), null).show().also { styleSettingsDialog(it) }
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(R.string.open_this_setting_from_your_car_s_settings_app)) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun playTestTone(streamType: Int) {
        toneStop?.let { handler.removeCallbacks(it) }
        toneStop = null
        testToneTrack?.let { runCatching { it.stop(); it.release() } }
        testToneTrack = null
        var candidate: AudioTrack? = null
        val track = try {
            val pcm = assets.open("navigation_test.pcm").use { it.readBytes() }
            AudioTrack(streamType, 44100, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.size, AudioTrack.MODE_STREAM).also {
                candidate = it
                check(it.state == AudioTrack.STATE_INITIALIZED)
                check(it.write(pcm, 0, pcm.size) == pcm.size)
                it.play()
            }
        } catch (error: Exception) {
            val state = candidate?.state ?: AudioTrack.STATE_UNINITIALIZED
            candidate?.let { runCatching { it.release() } }
            Log.w("DiPlay", "playTestTone streamType=$streamType unavailable", error)
            toast(getString(R.string.audio_stream_unavailable, streamType, state))
            return
        }
        Log.i("DiPlay", "playTestTone streamType=$streamType state=${track.state} playState=${track.playState}")
        testToneTrack = track
        val stop = Runnable {
            track.stop()
            track.release()
            if (testToneTrack === track) testToneTrack = null
            toneStop = null
        }
        toneStop = stop
        handler.postDelayed(stop, 4500)
    }

    private val channelButtons = mutableListOf<Button>()

    private fun paintChannel(index: Int, selected: Boolean) {
        val target = channelButtons.getOrNull(index) ?: return
        target.isSelected = selected
        target.setTextColor(TEXT)
        target.background = SteamGlass.action(this, selected, radius = 16)
    }

    private fun channelSelector(): ViewGroup {
        channelButtons.clear()
        val grid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 3
            setPadding(0, dp(8), 0, dp(8))
        }
        for (i in 0..20) {
            val btn = Button(this).apply {
                text = i.toString()
                isAllCaps = false
                ResponsiveUi.text(this, 16f)
                minHeight = dp(48)
                stateListAnimator = null
                setOnClickListener {
                    val previous = navigationStreamType
                    navigationStreamType = i
                    if (previous != i) {
                        paintChannel(previous, false)
                        paintChannel(i, true)
                    }
                    playTestTone(i)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(48)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(btn, params)
            channelButtons.add(btn)
            paintChannel(i, i == navigationStreamType)
        }
        return grid
    }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun languageSettings(content: LinearLayout) {
        section(content, getString(R.string.language_section_title)) { card ->
            card.addView(label(getString(R.string.language_hint), 14, MUTED))
            val current = AppLocale.preference(this)
            val languageButton = button("${getString(R.string.language_app_language)} · ${AppLocale.displayName(this, current)}", false) { }
            languageButton.setOnClickListener { AppLocale.showPicker(this) }
            card.addView(languageButton, matchButton(12, 60))
        }
    }

    private fun section(parent: LinearLayout, title: String, icon: Int? = null, build: (LinearLayout) -> Unit) {
        val card = card()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(16)) }
        if (icon != null && !usesSteamGlass()) heading.addView(ImageView(this).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        heading.addView(label(title, if (usesSteamGlass()) 18 else 22, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)
        build(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }
    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, enabled: Boolean = true, save: (Boolean) -> Unit): Switch {
        val line = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            val inset = if (usesSteamGlass()) dp(16) else 0
            setPadding(inset, dp(12), inset, dp(12))
        }
        val text = column(); text.addView(label(title, 18, TEXT, true)); text.addView(label(description, 14, MUTED).apply { setPadding(0, dp(6), dp(16), 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val control = Switch(this).apply { contentDescription = title; isChecked = value; isEnabled = enabled; minHeight = dp(56); buttonTintList = ColorStateList.valueOf(ACCENT); setOnCheckedChangeListener { _, checked -> save(checked) } }
        if (usesSteamGlass()) {
            control.thumbTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFF66788F.toInt(), 0xFFF4FAFF.toInt(), 0xFFB9C7D9.toInt()))
            control.trackTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0x333F526B, 0xFF658DB9.toInt(), 0xFF3B4C66.toInt()))
            control.minimumWidth = dp(52)
            control.showText = false
        }
        line.addView(control)
        parent.addView(line)
        return control
    }
    // [announcesReconnect] labels a choice whose [save] reconnects by itself.
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, reconnects: Boolean = true,
        announcesReconnect: Boolean = reconnects, enabled: Boolean = true, save: (Int) -> Unit) {
        var selection = current.coerceIn(options.indices)
        val button = button("$title · ${options[selection]}", false) {}.apply {
            isEnabled = enabled
            if (usesSteamGlass()) {
                text = settingsChoiceText(title, options[selection])
                minHeight = dp(76)
                textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_steam_chevron, 0)
                compoundDrawablePadding = dp(12)
            }
        }
        button.setOnClickListener {
            var pendingSelection = selection
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(getString(if (announcesReconnect && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        button.text = if (usesSteamGlass()) settingsChoiceText(title, options[selection]) else "$title · ${options[selection]}"
                        settingsSummary?.text = settingsSummaryText()
                        if (reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton(getString(R.string.cancel), null).show().also { styleSettingsDialog(it) }
        }
        parent.addView(button, matchButton(0, if (usesSteamGlass()) 76 else 60)); parent.addView(space(12))
    }
    private fun settingsChoiceText(title: String, value: String): CharSequence {
        val text = android.text.SpannableString("$title\n$value")
        text.setSpan(android.text.style.RelativeSizeSpan(.82f), 0, title.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(android.text.style.ForegroundColorSpan(MUTED), 0, title.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }
    private fun styleSettingsDialog(dialog: AlertDialog) {
        SteamGlass.styleDialog(this, dialog)
    }
    private fun card() = column().apply {
        background = if (usesSteamGlass()) SteamGlass.surface(this@DiPlayActivity) else rounded(SURFACE, BORDER)
        val padding = dp(if (usesSteamGlass()) 20 else 24)
        setPadding(padding, padding, padding, padding)
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        if (usesSteamGlass()) textSize = size.toFloat() else ResponsiveUi.text(this, size.toFloat())
        setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false
        if (usesSteamGlass()) textSize = 16f else ResponsiveUi.text(this, 18f)
        setTextColor(if (primary && !usesSteamGlass()) BG else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = if (usesSteamGlass()) SteamGlass.action(this@DiPlayActivity, selected = primary)
            else android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x336F9FD9), rounded(if (primary) ACCENT else SURFACE, if (primary) ACCENT else BORDER), null)
        minHeight = dp(56); stateListAnimator = null
        if (usesSteamGlass()) {
            setPadding(dp(16), dp(8), dp(16), dp(8)); minimumWidth = 0
            setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(0xFF7F91AA.toInt(), TEXT)))
        } else setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat(); setStroke(dp(1), stroke) }
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, if (usesSteamGlass()) -2 else dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = if (usesSteamGlass()) (value * resources.displayMetrics.density).roundToInt() else ResponsiveUi.dp(this, value)
    private var learntSiriInWindow: ((WheelKey) -> Unit)? = null
    private var siriLearningCancelled: (() -> Unit)? = null
    private val learningPresses = WheelKeyPresses()
    private val endSiriLearning = Runnable { cancelSiriLearning() }
    private fun cancelSiriLearning() {
        learntSiriInWindow = null
        val cancelled = siriLearningCancelled
        siriLearningCancelled = null
        cancelled?.invoke()
        handler.removeCallbacks(endSiriLearning)
        WheelKeyService.cancelLearning()
    }
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action != android.view.KeyEvent.ACTION_DOWN && event.action != android.view.KeyEvent.ACTION_UP) return super.dispatchKeyEvent(event)
        val action = learningPresses.filter(Triple(event.deviceId, event.keyCode, event.scanCode),
            event.action == android.view.KeyEvent.ACTION_DOWN, event.repeatCount == 0) {
            val done = learntSiriInWindow ?: return@filter WheelKeyDisposition.PASS
            if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) return@filter WheelKeyDisposition.PASS
            if (inCall(this) || !WheelSiriSettings.enabled(this)) {
                cancelSiriLearning(); return@filter WheelKeyDisposition.PASS
            }
            val key = WheelKey.of(event)
            cancelSiriLearning()
            WheelSiriSettings.assign(this, key)
            done(key)
            WheelKeyDisposition.CONSUME
        }
        return action != WheelKeyDisposition.PASS || super.dispatchKeyEvent(event)
    }
    private fun wheelSiriControls(card: LinearLayout) {
        toggle(card, getString(R.string.wheel_siri_key), getString(R.string.wheel_siri_key_description), WheelSiriSettings.enabled(this)) {
            WheelSiriSettings.setEnabled(this, it); render()
        }
        if (!WheelSiriSettings.enabled(this)) return
        lateinit var assign: android.widget.Button
        fun title() = getString(R.string.wheel_key_assign, getString(R.string.wheel_key_role_siri),
            WheelSiriSettings.key(this)?.toString() ?: getString(R.string.wheel_key_none))
        assign = button(title(), false) {
            cancelSiriLearning()
            val done = { _: WheelKey -> runOnUiThread { assign.text = title() } }
            val started = WheelKeyService.learn(cancelled = { runOnUiThread { assign.text = title() } }, done = done)
            if (!started && !inCall(this)) {
                learntSiriInWindow = done
                siriLearningCancelled = { assign.text = title() }
                handler.postDelayed(endSiriLearning, WheelKeyService.LEARNING_TIMEOUT_MILLIS)
            }
            assign.text = getString(R.string.wheel_key_press, getString(R.string.wheel_key_role_siri))
        }
        card.addView(assign, matchButton(10, 56))
        wheelKeyServiceControls(card)
    }
    private fun wheelKeyServiceControls(card: LinearLayout) {
        val connected = WheelKeyService.connected()
        card.addView(label(getString(if (connected) R.string.wheel_keys_service_on else R.string.wheel_keys_service_off), 14, if (connected) MUTED else WARNING))
        if (!connected) {
            card.addView(button(getString(R.string.wheel_keys_enable_adb), false) {
                Thread({
                    val access = runCatching { WheelKeyService.enableOverAdb(this) }.getOrNull()
                    runOnUiThread {
                        if (access != com.shilapi.xcertplay.adb.LocalAdb.Access.READY) toast(getString(R.string.wheel_keys_adb_failed, access?.name ?: "unavailable"))
                        render()
                    }
                }, "carplay-wheel-enable").start()
            }, matchButton(10, 56))
            card.addView(button(getString(R.string.wheel_keys_open_settings), false) {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.onFailure { toast(getString(R.string.wheel_keys_no_settings)) }
            }, matchButton(10, 56))
        }
    }

    companion object {
        internal fun isLauncherIntent(intent: Intent): Boolean =
            intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && !intent.hasExtra("page")
        private const val BYD_VEHICLE_TAG = "DiPlay-BYD13"
        private const val VEHICLE_VALIDATION_RETRY_MILLIS = 500L
        private const val ADB_KEY_SAVE_WAIT_MILLIS = 500L
        private val BG = Color.rgb(12, 17, 27)
        private val SURFACE = Color.rgb(21, 30, 44)
        private val BORDER = Color.rgb(42, 56, 75)
        private val ACCENT = Color.rgb(166, 200, 255)
        private val TEXT = Color.rgb(241, 245, 252)
        private val MUTED = Color.rgb(168, 182, 202)
        private val WARNING = Color.rgb(255, 196, 128)
    }
}
