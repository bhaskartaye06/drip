package app.drip

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/** A place where clothes dry. cover: 0 open, 1 partly covered, 2 covered. */
data class Spot(val id: String, var name: String, var cover: Int, var out: Boolean, var outSince: Long) {
    val intensityFloor: Double get() = when (cover) { 0 -> 0.2; 1 -> 1.0; else -> 4.0 }
}

/** A user report about rain at home. raining=true means "raining here now". */
data class Report(val at: Long, val raining: Boolean)

/** All user state lives here. */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("drip", Context.MODE_PRIVATE)

    // Home. Defaults to Koramangala; editable in Settings or set from the phone's location.
    var lat: Double
        get() = p.getString("lat", "12.9352")!!.toDoubleOrNull() ?: 12.9352
        set(v) = p.edit().putString("lat", v.toString()).apply()
    var lon: Double
        get() = p.getString("lon", "77.6245")!!.toDoubleOrNull() ?: 77.6245
        set(v) = p.edit().putString("lon", v.toString()).apply()
    var placeName: String
        get() = p.getString("place", "Koramangala") ?: "Koramangala"
        set(v) = p.edit().putString("place", v).apply()

    // Work (optional). lat 0.0 means unset.
    var workLat: Double
        get() = p.getString("wlat", "0")!!.toDoubleOrNull() ?: 0.0
        set(v) = p.edit().putString("wlat", v.toString()).apply()
    var workLon: Double
        get() = p.getString("wlon", "0")!!.toDoubleOrNull() ?: 0.0
        set(v) = p.edit().putString("wlon", v.toString()).apply()
    var workName: String
        get() = p.getString("wname", "") ?: ""
        set(v) = p.edit().putString("wname", v).apply()
    val hasWork: Boolean get() = workLat != 0.0 && workLon != 0.0

    // Weather Union: optional key and locality ids, upwind first.
    var wuKey: String
        get() = p.getString("wuKey", "") ?: ""
        set(v) = p.edit().putString("wuKey", v.trim()).apply()
    var wuLocalities: String
        get() = p.getString("wuLoc", "") ?: ""
        set(v) = p.edit().putString("wuLoc", v.trim()).apply()

    // Drying spots
    var spots: List<Spot>
        get() {
            val s = p.getString("spots", null) ?: return listOf(Spot("balcony", "Balcony", 1, false, 0L))
            return try {
                val a = JSONArray(s)
                (0 until a.length()).map { i -> val o = a.getJSONObject(i)
                    Spot(o.getString("id"), o.getString("name"), o.getInt("cover"), o.getBoolean("out"), o.getLong("since")) }
            } catch (e: Exception) { listOf(Spot("balcony", "Balcony", 1, false, 0L)) }
        }
        set(v) {
            val a = JSONArray(); v.forEach { s -> a.put(JSONObject().put("id", s.id).put("name", s.name).put("cover", s.cover).put("out", s.out).put("since", s.outSince)) }
            p.edit().putString("spots", a.toString()).apply()
        }
    val anyOut: Boolean get() = spots.any { it.out }
    val outSpots: List<Spot> get() = spots.filter { it.out }
    /** The most exposed spot that's out decides the rain threshold. */
    val activeIntensityFloor: Double get() = (outSpots.ifEmpty { spots }).minOf { it.intensityFloor }

    fun setOut(spotId: String, out: Boolean) {
        spots = spots.map { if (it.id == spotId) it.copy(out = out, outSince = if (out) System.currentTimeMillis() else it.outSince) else it }
        if (out) logLaundryDay()
    }
    fun setAllIn() { spots = spots.map { it.copy(out = false) } }

    // Alerts
    var sensitivity: Int
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
    var laundryPrompt: Boolean
        get() = p.getBoolean("laundry", true)
        set(v) = p.edit().putBoolean("laundry", v).apply()
    var quietStart: Int
        get() = p.getInt("qs", 23)
        set(v) = p.edit().putInt("qs", v).apply()
    var quietEnd: Int
        get() = p.getInt("qe", 6)
        set(v) = p.edit().putInt("qe", v).apply()
    fun inQuietHours(hour: Int): Boolean =
        if (quietStart <= quietEnd) hour in quietStart until quietEnd else (hour >= quietStart || hour < quietEnd)

    // False-alarm correction: each "it didn't rain" raises the threshold a little, each "it rained" lowers it.
    var thresholdAdjust: Int
        get() = p.getInt("adj", 0)
        set(v) = p.edit().putInt("adj", v.coerceIn(-15, 20)).apply()
    val probabilityThreshold: Int get() = (30 + (sensitivity * 55) / 100 + thresholdAdjust).coerceIn(20, 95)

    // Reports from this phone
    var reports: List<Report>
        get() = try {
            val a = JSONArray(p.getString("reports", "[]"))
            (0 until a.length()).map { i -> val o = a.getJSONObject(i); Report(o.getLong("at"), o.getBoolean("r")) }
        } catch (e: Exception) { emptyList() }
        set(v) {
            val a = JSONArray(); v.takeLast(200).forEach { a.put(JSONObject().put("at", it.at).put("r", it.raining)) }
            p.edit().putString("reports", a.toString()).apply()
        }
    fun addReport(raining: Boolean) { reports = reports + Report(System.currentTimeMillis(), raining) }
    /** A fresh "raining here" report, within the last 20 min. */
    val reportedRainingNow: Boolean get() = reports.lastOrNull()?.let { it.raining && System.currentTimeMillis() - it.at < 20 * 60 * 1000 } ?: false
    val reportedDryNow: Boolean get() = reports.lastOrNull()?.let { !it.raining && System.currentTimeMillis() - it.at < 20 * 60 * 1000 } ?: false

    // Laundry-day learning: dates on which clothes were hung
    private var laundryDays: List<String>
        get() = (p.getString("ldays", "") ?: "").split(",").filter { it.isNotBlank() }
        set(v) = p.edit().putString("ldays", v.takeLast(60).joinToString(",")).apply()
    private fun logLaundryDay() {
        val today = LocalDate.now().toString()
        if (laundryDays.lastOrNull() != today) laundryDays = laundryDays + today
    }
    /** True if clothes were hung on this weekday at least twice in the last 4 weeks and nothing's out yet. */
    fun isLikelyLaundryDay(day: DayOfWeek = LocalDate.now().dayOfWeek): Boolean {
        val cutoff = LocalDate.now().minusWeeks(4)
        val hits = laundryDays.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .count { it.isAfter(cutoff) && it.dayOfWeek == day }
        return hits >= 2 && !anyOut
    }
    val laundryLogCount: Int get() = laundryDays.size

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
    var lastLaundryPromptDay: String
        get() = p.getString("lastLaundry", "") ?: ""
        set(v) = p.edit().putString("lastLaundry", v).apply()

    // Last fetched snapshot
    var lastSnapshotJson: String
        get() = p.getString("snap", "") ?: ""
        set(v) = p.edit().putString("snap", v).putLong("snapAt", System.currentTimeMillis()).apply()
    val lastSnapshotAt: Long get() = p.getLong("snapAt", 0L)
}
