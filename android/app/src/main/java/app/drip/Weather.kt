package app.drip

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** One combined picture of the sky over home, built from Open-Meteo and (optionally) Weather Union. */
data class Snapshot(
    val tempC: Double?,
    val humidity: Int?,
    val windKmh: Double?,
    val windDir: String?,
    val rainNowMmH: Double?,
    val maxProbNext45: Int,
    val maxProbNext3h: Int,
    val minutesToRain: Int?,
    val todayPeakProb: Int,
    val todayRainWindow: String?,
    val sunset: LocalTime?,
    val upwindRaining: Boolean,
    val upwindNote: String?,
    val workRainWindow: String?,      // rain window at the work location during 9 to 19, if work is set
    val workPeakProb: Int,
    val fetchedAt: Long = System.currentTimeMillis()
) {
    /** Hours for a cotton load to dry in this air. Rough but useful. */
    fun dryHours(): Double {
        val h = humidity ?: 60; val t = tempC ?: 27.0; val w = windKmh ?: 8.0
        return (2.0 + (h - 40) / 15.0 - w / 20.0 - (t - 25) / 10.0).coerceIn(1.5, 9.0)
    }

    fun toJson(): String = JSONObject().apply {
        put("t", tempC ?: JSONObject.NULL); put("h", humidity ?: JSONObject.NULL)
        put("w", windKmh ?: JSONObject.NULL); put("wd", windDir ?: JSONObject.NULL)
        put("rn", rainNowMmH ?: JSONObject.NULL); put("p45", maxProbNext45); put("p3", maxProbNext3h)
        put("mtr", minutesToRain ?: JSONObject.NULL); put("pk", todayPeakProb)
        put("win", todayRainWindow ?: JSONObject.NULL); put("ss", sunset?.toString() ?: JSONObject.NULL)
        put("up", upwindRaining); put("upn", upwindNote ?: JSONObject.NULL)
        put("wwin", workRainWindow ?: JSONObject.NULL); put("wpk", workPeakProb); put("at", fetchedAt)
    }.toString()

    companion object {
        fun fromJson(s: String): Snapshot? = try {
            val j = JSONObject(s)
            fun d(k: String) = if (j.isNull(k)) null else j.getDouble(k)
            fun i(k: String) = if (j.isNull(k)) null else j.getInt(k)
            fun str(k: String) = if (j.isNull(k)) null else j.getString(k)
            Snapshot(d("t"), i("h"), d("w"), str("wd"), d("rn"), j.getInt("p45"), j.getInt("p3"), i("mtr"),
                j.getInt("pk"), str("win"), str("ss")?.let { LocalTime.parse(it) }, j.getBoolean("up"), str("upn"),
                str("wwin"), j.optInt("wpk", 0), j.getLong("at"))
        } catch (e: Exception) { null }
    }
}

object Weather {

    fun fetch(prefs: Prefs): Snapshot {
        val om = openMeteo(prefs.lat, prefs.lon, prefs.probabilityThreshold)
        val wu = if (prefs.wuKey.isNotBlank() && prefs.wuLocalities.isNotBlank()) weatherUnion(prefs) else null
        val work = if (prefs.hasWork) runCatching { workWindow(prefs.workLat, prefs.workLon) }.getOrNull() else null
        return om.copy(
            rainNowMmH = wu?.first ?: om.rainNowMmH,
            upwindRaining = wu?.second ?: false,
            upwindNote = wu?.third,
            workRainWindow = work?.first, workPeakProb = work?.second ?: 0
        )
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 15000
            setRequestProperty("User-Agent", "Drip/0.2 (personal rain alert app)")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private val hourFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("h a")

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
        val (peak, window) = hourlyWindow(j.getJSONObject("hourly"), now, 0, 24)
        val sunset = try { LocalDateTime.parse(j.getJSONObject("daily").getJSONArray("sunset").getString(0)).toLocalTime() } catch (e: Exception) { null }
        return Snapshot(
            tempC = cur.optDouble("temperature_2m"), humidity = cur.optInt("relative_humidity_2m"),
            windKmh = cur.optDouble("wind_speed_10m"), windDir = compass(cur.optDouble("wind_direction_10m")),
            rainNowMmH = cur.optDouble("precipitation"), maxProbNext45 = max45, maxProbNext3h = max3h,
            minutesToRain = minutesToRain, todayPeakProb = peak, todayRainWindow = window, sunset = sunset,
            upwindRaining = false, upwindNote = null, workRainWindow = null, workPeakProb = 0
        )
    }

    /** Rain window at work between 9 and 19 today. */
    private fun workWindow(lat: Double, lon: Double): Pair<String?, Int> {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&hourly=precipitation_probability&forecast_days=1&timezone=Asia%2FKolkata"
        val j = JSONObject(get(url))
        val (peak, window) = hourlyWindow(j.getJSONObject("hourly"), LocalDateTime.now(), 9, 19)
        return window to peak
    }

    /** Peak probability and a "4 pm to 7 pm" style window of hours >= 50%, within [fromHour, toHour). */
    private fun hourlyWindow(hourly: JSONObject, now: LocalDateTime, fromHour: Int, toHour: Int): Pair<Int, String?> {
        val hp = hourly.getJSONArray("precipitation_probability"); val ht = hourly.getJSONArray("time")
        var peak = 0; var winStart: LocalDateTime? = null; var winEnd: LocalDateTime? = null
        for (k in 0 until hp.length()) {
            if (hp.isNull(k)) continue
            val pr = hp.getInt(k); val t = LocalDateTime.parse(ht.getString(k))
            if (t.isBefore(now.minusHours(1)) || t.hour < fromHour || t.hour >= toHour) continue
            peak = maxOf(peak, pr)
            if (pr >= 50) { if (winStart == null) winStart = t; winEnd = t.plusHours(1) }
        }
        val window = if (winStart != null && winEnd != null) "${winStart.format(hourFmt).lowercase()} to ${winEnd.format(hourFmt).lowercase()}" else null
        return peak to window
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

    /** Free reverse geocoding for the area name, via Open-Meteo's geocoder is forward-only, so use Nominatim politely. */
    fun placeName(lat: Double, lon: Double): String? = try {
        val j = JSONObject(get("https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lon&zoom=16"))
        val a = j.optJSONObject("address")
        a?.optString("suburb")?.takeIf { it.isNotBlank() } ?: a?.optString("neighbourhood")?.takeIf { it.isNotBlank() }
            ?: a?.optString("city_district")?.takeIf { it.isNotBlank() } ?: a?.optString("city")?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }

    /** Free forward geocoding: type an area, get coordinates. */
    fun geocode(query: String): Triple<Double, Double, String>? = try {
        val q = URLEncoder.encode("$query, Bengaluru", "UTF-8")
        val j = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en"))
        val r = j.optJSONArray("results")?.optJSONObject(0) ?: null
        if (r == null) null else Triple(r.getDouble("latitude"), r.getDouble("longitude"), r.getString("name"))
    } catch (e: Exception) { null }

    private fun compass(deg: Double): String {
        val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[((deg + 22.5) / 45).toInt() % 8]
    }
}
