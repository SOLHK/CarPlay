package com.shilapi.xcertplay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicBoolean
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.BydOutputSettings


/** Captures only assigned Siri keys and explicitly enabled CarPlay call controls. */
class WheelKeyService : AccessibilityService() {
    private val presses = WheelKeyPresses()
    private val siri = WheelSiriKey()
    private val handler = Handler(Looper.getMainLooper())
    private var learnt: ((WheelKey) -> Unit)? = null
    private var learningCancelled: (() -> Unit)? = null
    private val endLearning = Runnable { clearLearning() }

    override fun onServiceConnected() {
        running = this
        CarPlayCallKeys.install(this)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { clearLearning() }
    override fun onDestroy() {
        if (running === this) running = null
        clearLearning()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    override fun onUnbind(intent: Intent?): Boolean {
        if (running === this) running = null
        clearLearning()
        return super.onUnbind(intent)
    }
    private fun clearLearning(notify: Boolean = true) {
        handler.removeCallbacks(endLearning)
        val cancelled = learningCancelled
        learnt = null
        learningCancelled = null
        if (notify) cancelled?.invoke()
    }
    private fun settingsChanged() {
        if (!WheelSiriSettings.enabled(this) || inCall(this)) clearLearning()
    }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val physical = PhysicalWheelKey(event.deviceId, event.keyCode, event.scanCode)
        if (presses.hasConsumedPress(physical)) {
            presses.filter(physical, down, event.repeatCount == 0) { WheelKeyDisposition.PASS }
            return true
        }
        if (CarPlayCallKeys.onKey(this, event.keyCode, down)) return true
        val controller = CarPlayBackgroundSession.snapshot()?.controller
        val active = controller?.activeAirPlaySessionToken() != null
        val assigned = WheelSiriSettings.isSiriKey(this, WheelKey.of(event))
        val action = presses.filter(physical, down, event.repeatCount == 0) {
            if (inCall(this)) { clearLearning(); return@filter WheelKeyDisposition.PASS }
            learnt?.let { done ->
                if (event.keyCode == KeyEvent.KEYCODE_BACK) return@filter WheelKeyDisposition.PASS
                clearLearning(notify = false)
                val key = WheelKey.of(event)
                WheelSiriSettings.assign(this, key)
                done(key)
                return@filter WheelKeyDisposition.CONSUME
            }
            val legacy = BydOutputSettings.carPlayCallControls(this) &&
                (CarPlayMediaButton.opensSiri(event.keyCode) || CarPlayMediaButton.opensSiriWhileCarPlay(event.keyCode))
            if (!active || (!assigned && !legacy)) return@filter WheelKeyDisposition.PASS
            if (siri.opens(event.eventTime)) Log.i(TAG, "wheel Siri key ${event.keyCode}: sent=${controller?.requestSiri() == true}")
            WheelKeyDisposition.CONSUME
        }
        return action != WheelKeyDisposition.PASS
    }

    companion object {
        private val accessibilityLock = Any()
        internal const val TAG = "DiPlay-WheelKeys"
        private const val ZOOM_NOTE_SOURCE = 6
        private const val ELIGIBILITY_POLL_MILLIS = 250L
        private const val ROUTE_CHECK_MILLIS = 1_000L
        internal const val LEARNING_TIMEOUT_MILLIS = 10_000L
        private const val RESTORE_GRACE_MILLIS = 4_000L
        @Volatile private var running: WheelKeyService? = null
        private val restoring = AtomicBoolean(false)

        fun connected(): Boolean = running != null

        /** The next key pressed is assigned to [role]; [done] runs on the service's thread. */
        /** A key that another active role uses goes to [refused] with that role, unassigned. */
        fun learn(cancelled: () -> Unit = {}, done: (WheelKey) -> Unit): Boolean {
            val service = running ?: return false
            if (!WheelSiriSettings.enabled(service) || inCall(service)) return false
            service.clearLearning()
            service.learnt = done
            service.learningCancelled = cancelled
            service.handler.postDelayed(service.endLearning, LEARNING_TIMEOUT_MILLIS)
            return true
        }

        fun cancelLearning() { running?.onMain { clearLearning() } }

        fun settingsChanged() { running?.onMain { settingsChanged() } }

        fun enabledInSettings(context: Context): Boolean {
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return component(context).flattenToString() in list.orEmpty().split(':')
        }

        /**
         * BYD's settings have no accessibility page, so the user can turn the service on through the car's
         * own adb (allowed once on the car screen). Services already in the list stay there.
         */
        fun enableOverAdb(context: Context, mayAsk: Boolean = true): LocalAdb.Access = LocalAdb(AdbKeys.load(context)).use { adb ->
            val access = adb.connect(mayAsk)
            if (access != LocalAdb.Access.READY) return@use access
            if (!mayAsk && !needsRestore(context)) return@use if (connected()) access else LocalAdb.Access.UNREACHABLE
            val allowed = applyServiceSettings(context, adb::shell) {
                mayAsk || wanted(context)
            }
            if (allowed) Log.i(TAG, "wheel key service allowed over adb")
            if (allowed) access else LocalAdb.Access.UNREACHABLE
        }

        private const val GRANT_EXIT = "DIPLAY_WHEEL_EXIT"
        private fun checkedShell(command: String, shell: (String) -> String?): String? {
            val lines = shell("( $command ); result=\$?; printf '\\n$GRANT_EXIT:%s\\n' \"\$result\"")
                ?.trimEnd()?.lines() ?: return null
            if (lines.lastOrNull() != "$GRANT_EXIT:0") return null
            return lines.dropLast(1).joinToString("\n").trim()
        }

        /** Failed reads and shell commands must not replace the accessibility list or claim success. */
        internal fun applyServiceSettings(context: Context, shell: (String) -> String?,
            shouldContinue: () -> Boolean = { true }): Boolean = synchronized(accessibilityLock) {
            applyServiceSettingsLocked(context, shell, shouldContinue)
        }

        private fun applyServiceSettingsLocked(context: Context, shell: (String) -> String?,
            shouldContinue: () -> Boolean): Boolean {
            if (!shouldContinue()) return false
            val current = checkedShell("settings get secure enabled_accessibility_services", shell) ?: return false
            val lists = allowedServices(current, component(context).flattenToString()) ?: return false
            // Binding may finish after the read. Check again immediately before a destructive rebind,
            // after the continuation callback, without blocking the service's main-thread callback.
            lists.first?.let {
                if (!shouldContinue()) return false
                if (!connected() && checkedShell("settings put secure enabled_accessibility_services '$it'", shell) == null) return false
            }
            for (command in listOf(
                "settings put secure enabled_accessibility_services '${lists.second}'",
                "settings put secure accessibility_enabled 1",
            )) {
                if (!shouldContinue() || checkedShell(command, shell) == null) return false
            }
            return enabledInSettings(context) &&
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        }

        internal fun needsRestore(context: Context): Boolean = !connected() && wanted(context)

        /**
         * The call controls need the service too: outside the CarPlay screen the call key reaches DiPlay
         * only through it, and without it BYD's window manager opens its own phone app instead.
         */
        internal fun wanted(context: Context): Boolean = WheelSiriSettings.enabled(context) ||
            BydOutputSettings.carPlayCallControls(context)

        /**
         * Android takes the service off the allowed list when the app is force-stopped (BYD's system does
         * that), and an update or a crash can leave it unbound. With a wheel key setting or the call controls on, DiPlay
         * puts it back over the car's adb, already allowed, when it is still not running a few seconds after
         * DiPlay starts, so the keys work without a visit to the settings.
         */
        fun restoreIfNeeded(context: Context) {
            val app = context.applicationContext
            if (!needsRestore(app) || !restoring.compareAndSet(false, true)) return
            Thread({
                try {
                    Thread.sleep(RESTORE_GRACE_MILLIS)
                    if (needsRestore(app)) Log.i(TAG, "wheel key service not running; restoring over adb: ${enableOverAdb(app, mayAsk = false)}")
                } catch (error: Exception) {
                    Log.w(TAG, "wheel key service restore failed", error)
                } finally {
                    restoring.set(false)
                }
            }, "diplay-wheel-keys-restore").start()
        }

        /**
         * The allowed-services setting without and with [ours]: the first is null when [ours] is not listed,
         * the second keeps every other service in its place.
         */
        internal fun allowedServices(current: String?, ours: String): Pair<String?, String>? {
            val value = current?.trim() ?: return null
            val component = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")
            if (!component.matches(ours)) return null
            val listed = value.takeUnless { it.isEmpty() || it == "null" }?.split(':').orEmpty()
            if (listed.any { !component.matches(it) }) return null
            val others = listed.filter { it != ours }
            val without = if (ours in listed) others.joinToString(":") else null
            return without to (others + ours).joinToString(":")
        }

        private fun component(context: Context) = ComponentName(context, WheelKeyService::class.java)
    }

    private fun onMain(action: WheelKeyService.() -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }
}

// CarPlay's iAP2 call state can be active even when the head unit leaves Android's mode normal.
// Native Bluetooth calls also use a call/communication audio mode.
internal fun inCall(context: Context): Boolean =
    BydNavigationOutputs.carPlayCall() != null ||
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode.let { it != null && it != AudioManager.MODE_NORMAL }

/** Use the input-device ID for a held press; saved assignments still use the stable device name. */
private data class PhysicalWheelKey(val device: Int, val code: Int, val scan: Int)

/** Keep the system's key stream well-formed even if settings/calls/routes change mid-press. */
internal class WheelKeyPresses {
    private val consumed = mutableMapOf<Any, Boolean>()

    fun hasConsumedPress(key: Any): Boolean = consumed[key] == true

    fun filter(key: Any?, down: Boolean, firstPress: Boolean, decide: () -> WheelKeyDisposition): WheelKeyDisposition {
        key ?: return WheelKeyDisposition.PASS
        if (!down) return disposition(consumed.remove(key) ?: false)
        consumed[key]?.let { return disposition(it) }
        // A repeat whose DOWN was not seen is passed without starting a new action or capture.
        if (!firstPress) return WheelKeyDisposition.PASS
        return decide().also { consumed[key] = it != WheelKeyDisposition.PASS }
    }

    private fun disposition(consume: Boolean) = if (consume) WheelKeyDisposition.CONSUME else WheelKeyDisposition.PASS
}

/** A key as the head unit reports it; code, scan code and device name tell keys apart. */
data class WheelKey(val code: Int, val scan: Int, val device: String) {
    override fun toString(): String = "$code/$scan"

    fun encode(): String = "$code|$scan|$device"

    companion object {
        fun of(event: KeyEvent): WheelKey = WheelKey(event.keyCode, event.scanCode,
            runCatching { InputDevice.getDevice(event.deviceId)?.name }.getOrNull() ?: "?")

        fun decode(text: String?): WheelKey? = text?.split('|', limit = 3)?.takeIf { it.size == 3 }?.let {
            WheelKey(it[0].toIntOrNull() ?: return null, it[1].toIntOrNull() ?: return null, it[2])
        }
    }
}

/** When the Siri key opens Siri; no Android types, so it is unit-tested. */
class WheelSiriKey {
    private var lastPressMillis: Long? = null

    /**
     * Some head units send a held key as a new press about every 100 ms. Presses closer together than
     * [REPEAT_GAP_MILLIS] belong to the press before them, so a hold opens Siri once.
     */
    fun opens(eventTimeMillis: Long): Boolean {
        val last = lastPressMillis
        lastPressMillis = eventTimeMillis
        return last == null || eventTimeMillis - last >= REPEAT_GAP_MILLIS
    }

    companion object {
        const val REPEAT_GAP_MILLIS = 400L
    }
}

internal enum class WheelKeyDisposition { PASS, CONSUME }

/** Assignments use a stable input-device name; capture stays disabled until explicitly enabled. */
object WheelSiriSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("carplay_wheel_siri", Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        WheelKeyService.settingsChanged()
    }
    fun key(context: Context): WheelKey? = WheelKey.decode(prefs(context).getString("key", null))
    fun assign(context: Context, key: WheelKey) {
        prefs(context).edit().putString("key", key.encode()).apply()
        WheelKeyService.settingsChanged()
    }
    fun isSiriKey(context: Context, key: WheelKey): Boolean = enabled(context) && key(context) == key
}
