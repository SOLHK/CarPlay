package com.shilapi.xcertplay

import android.content.Context

/** Restore the owner's profile once without overwriting later choices. */
object CarPlayProfileDefaults {
    fun apply(context: Context) {
        val prefs = context.getSharedPreferences("steam_profile", Context.MODE_PRIVATE)
        if (prefs.getBoolean("restored_v27", false)) return
        if (AirPlayPersistence.loadManufacturer(context) == "DiPlay") AirPlayPersistence.saveManufacturer(context, "Steam")
        if (AirPlayPersistence.loadModel(context) == "DiPlay") AirPlayPersistence.saveModel(context, "CarPlay")
        AirPlayPersistence.saveRightHandDrive(context, false)
        // The owner had disabled the original automatic vehicle-navigation linkage.
        com.shilapi.xcertplay.hud.BydOutputSettings.setEnabled(context, false)
        AirPlayPersistence.saveClusterMapEnabled(context, false)
        DiLink51ClusterLayout.saveAutomatic(context, false)
        if (AirPlayPersistence.loadDisplayScaleTenths(context) == 10) AirPlayPersistence.saveDisplayScaleTenths(context, 8)
        prefs.edit().putBoolean("restored_v27", true).apply()
    }
}
