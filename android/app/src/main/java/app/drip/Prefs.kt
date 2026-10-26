package app.drip

import android.content.Context
import android.content.SharedPreferences

/** All user state lives here. Small enough that SharedPreferences is the right tool. */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("drip", Context.MODE_PRIVATE)

    // Home. Defaults to Koramangala; editable in Settings.
    var lat: Double
        get() = p.getString("lat", "12.9352")!!.toDoubleOrNull() ?: 12.9352
        set(v) = p.edit().putString("lat", v.toString()).apply()
    var lon: Double
        get() = p.getString("lon", "77.6245")!!.toDoubleOrNull() ?: 77.6245
        set(v) = p.edit().putString("lon", v.toString()).apply()
    var placeName: String
        get() = p.getString("place", "Koramangala") ?: "Koramangala"
        set(v) = p.edit().putString("place", v).apply()

    // Weather Union: optional key and up to a few locality ids, upwind first.
    var wuKey: String
        get() = p.getString("wuKey", "") ?: ""
        set(v) = p.edit().putString("wuKey", v.trim()).apply()
    var wuLocalities: String
        get() = p.getString("wuLoc", "") ?: ""
        set(v) = p.edit().putString("wuLoc", v.trim()).apply()

    // Clothes
    var clothesOut: Boolean
        get() = p.getBoolean("out", false)
        set(v) = p.edit().putBoolean("out", v).putLong("outSince", if (v) System.currentTimeMillis() else 0L).apply()
    val outSince: Long get() = p.getLong("outSince", 0L)

    var spotCover: Int   // 0 open, 1 partly covered, 2 covered
        get() = p.getInt("cover", 1)
        set(v) = p.edit().putInt("cover", v).apply()

    // Alerts
    var sensitivity: Int  // 0 early ... 100 only when sure
        get() = p.getInt("sens", 35)
        set(v) = p.edit().putInt("sens", v).apply()
    var onlyWhenOut: Boolean
        get() = p.getBoolean("onlyOut", true)
        set(v) = p.edit().putBoolean("onlyOut", v).apply()
    var morningSummary: Boolean
        get() = p.getBoolean("morning", true)
        set(v) = p.edit().putBoolean("morning", v).apply()
    var duskReminder: Boolean
        get() = p.getBoolean("dusk", true)
        set(v) = p.edit().putBoolean("dusk", v).apply()
    var quietStart: Int  // hour 0-23
        get() = p.getInt("qs", 23)
        set(v) = p.edit().putInt("qs", v).apply()
    var quietEnd: Int
        get() = p.getInt("qe", 6)
        set(v) = p.edit().putInt("qe", v).apply()

    // Bookkeeping so we don't repeat alerts
    var lastRainAlertAt: Long
        get() = p.getLong("lastRain", 0L)
        set(v) = p.edit().putLong("lastRain", v).apply()
    var lastMorningDay: String
        get() = p.getString("lastMorning", "") ?: ""
        set(v) = p.edit().putString("lastMorning", v).apply()
    var lastDuskDay: String
        get() = p.getString("lastDusk", "") ?: ""
        set(v) = p.edit().putString("lastDusk", v).apply()

    // Last fetched snapshot, for the home screen
    var lastSnapshotJson: String
        get() = p.getString("snap", "") ?: ""
        set(v) = p.edit().putString("snap", v).putLong("snapAt", System.currentTimeMillis()).apply()
    val lastSnapshotAt: Long get() = p.getLong("snapAt", 0L)

    /** Probability threshold derived from the sensitivity slider. 35 -> 50%, 0 -> 30%, 100 -> 85%. */
    val probabilityThreshold: Int get() = 30 + (sensitivity * 55) / 100

    /** Minimum rain intensity (mm/h) that matters for this drying spot. */
    val intensityFloor: Double get() = when (spotCover) { 0 -> 0.2; 1 -> 1.0; else -> 4.0 }
}
