package app.drip

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** One combined picture of the sky over home, built from Open-Meteo and (optionally) Weather Union. */
data class Snapshot(
    val tempC: Double?,
    val humidity: Int?,
    val windKmh: Double?,
    val windDir: String?,
    val rainNowMmH: Double?,          // from WU nearest locality if available, else Open-Meteo current
    val maxProbNext45: Int,           // max precipitation probability in the next 45 min
    val maxProbNext3h: Int,
    val minutesToRain: Int?,          // first 15-min slot where probability >= threshold and precip > 0
    val todayPeakProb: Int,
    val todayRainWindow: String?,     // e.g. "4 pm to 7 pm"
    val sunset: LocalTime?,
    val upwindRaining: Boolean,       // any WU locality reporting rain
    val upwindNote: String?,
    val fetchedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): String = JSONObject().apply {
        put("t", tempC ?: JSONObject.NULL); put("h", humidity ?: JSONObject.NULL)
        put("w", windKmh ?: JSONObject.NULL); put("wd", windDir ?: JSONObject.NULL)
        put("rn", rainNowMmH ?: JSONObject.NULL); put("p45", maxProbNext45); put("p3", maxProbNext3h)
        put("mtr", minutesToRain ?: JSONObject.NULL); put("pk", todayPeakProb)
        put("win", todayRainWindow ?: JSONObject.NULL); put("ss", sunset?.toString() ?: JSONObject.NULL)
        put("up", upwindRaining); put("upn", upwindNote ?: JSONObject.NULL); put("at", fetchedAt)
    }.toString()

    companion object {
        fun fromJson(s: String): Snapshot? = try {
            val j = JSONObject(s)
            fun d(k: String) = if (j.isNull(k)) null else j.getDouble(k)
            fun i(k: String) = if (j.isNull(k)) null else j.getInt(k)
            fun str(k: String) = if (j.isNull(k)) null else j.getString(k)
            Snapshot(d("t"), i("h"), d("w"), str("wd"), d("rn"), j.getInt("p45"), j.getInt("p3"), i("mtr"),
                j.getInt("pk"), str("win"), str("ss")?.let { LocalTime.parse(it) }, j.getBoolean("up"), str("upn"), j.getLong("at"))
        } catch (e: Exception) { null }
    }
}

object Weather {

    fun fetch(prefs: Prefs): Snapshot {
        val om = openMeteo(prefs.lat, prefs.lon, prefs.probabilityThreshold)
        val wu = if (prefs.wuKey.isNotBlank() && prefs.wuLocalities.isNotBlank()) weatherUnion(prefs) else null
        return om.copy(
            rainNowMmH = wu?.first ?: om.rainNowMmH,
            upwindRaining = wu?.second ?: false,
            upwindNote = wu?.third
        )
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 15000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun openMeteo(lat: Double, lon: Double, threshold: Int): Snapshot {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,precipitation,wind_speed_10m,wind_direction_10m" +
            "&minutely_15=precipitation,precipitation_probability&forecast_minutely_15=24" +
            "&hourly=precipitation_probability&forecast_days=1&daily=sunset&timezone=Asia%2FKolkata"
        val j = JSONObject(get(url))
        val cur = j.getJSONObject("current")
        val m15 = j.getJSONObject("minutely_15")
        val times = m15.getJSONArray("time"); val prob = m15.getJSONArray("precipitation_probability"); val prec = m15.getJSONArray("precipitation")
        val now = LocalDateTime.now()
        var max45 = 0; var max3h = 0; var minutesToRain: Int? = null
        for (k in 0 until times.length()) {
            if (prob.isNull(k)) continue
            val t = LocalDateTime.parse(times.getString(k))
            val mins = java.time.Duration.between(now, t).toMinutes().toInt()
            if (mins < -15) continue
            val pr = prob.getInt(k); val pc = if (prec.isNull(k)) 0.0 else prec.getDouble(k)
            if (mins <= 45) max45 = maxOf(max45, pr)
            if (mins <= 180) max3h = maxOf(max3h, pr)
            if (minutesToRain == null && mins >= 0 && pr >= threshold && pc > 0.0) minutesToRain = mins
        }
        val hourly = j.getJSONObject("hourly"); val hp = hourly.getJSONArray("precipitation_probability"); val ht = hourly.getJSONArray("time")
        var peak = 0; var winStart: LocalDateTime? = null; var winEnd: LocalDateTime? = null
        for (k in 0 until hp.length()) {
            if (hp.isNull(k)) continue
            val pr = hp.getInt(k); val t = LocalDateTime.parse(ht.getString(k))
            if (t.isBefore(now.minusHours(1))) continue
            peak = maxOf(peak, pr)
            if (pr >= 50) { if (winStart == null) winStart = t; winEnd = t.plusHours(1) }
        }
        val fmt = DateTimeFormatter.ofPattern("h a")
        val window = if (winStart != null && winEnd != null) "${winStart.format(fmt).lowercase()} to ${winEnd.format(fmt).lowercase()}" else null
        val sunset = try { LocalDateTime.parse(j.getJSONObject("daily").getJSONArray("sunset").getString(0)).toLocalTime() } catch (e: Exception) { null }
        return Snapshot(
            tempC = cur.optDouble("temperature_2m"), humidity = cur.optInt("relative_humidity_2m"),
            windKmh = cur.optDouble("wind_speed_10m"), windDir = compass(cur.optDouble("wind_direction_10m")),
            rainNowMmH = cur.optDouble("precipitation"), maxProbNext45 = max45, maxProbNext3h = max3h,
            minutesToRain = minutesToRain, todayPeakProb = peak, todayRainWindow = window, sunset = sunset,
            upwindRaining = false, upwindNote = null
        )
    }

    /** Returns (rain at first locality mm/h, any locality raining, note). */
    private fun weatherUnion(prefs: Prefs): Triple<Double?, Boolean, String?>? = try {
        val ids = prefs.wuLocalities.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        var first: Double? = null; var any = false; val notes = mutableListOf<String>()
        ids.forEachIndexed { i, id ->
            val j = JSONObject(get("https://www.weatherunion.com/gw/weather/external/v0/get_locality_weather_data?locality_id=$id",
                mapOf("x-zomato-api-key" to prefs.wuKey)))
            val d = j.optJSONObject("locality_weather_data") ?: return@forEachIndexed
            val rain = if (d.isNull("rain_intensity")) null else d.getDouble("rain_intensity")
            if (i == 0) first = rain
            if (rain != null && rain > 0.0) { any = true; notes += "$id ${"%.1f".format(rain)} mm/h" }
        }
        Triple(first, any, if (notes.isEmpty()) null else "Rain at " + notes.joinToString(", "))
    } catch (e: Exception) { null }

    private fun compass(deg: Double): String {
        val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[((deg + 22.5) / 45).toInt() % 8]
    }
}
