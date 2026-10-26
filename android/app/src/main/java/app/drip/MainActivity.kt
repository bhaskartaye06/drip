package app.drip

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

// Tokens from the design canvas
val Paper = Color(0xFFFBFAF7); val Ink = Color(0xFF1B2430); val Muted = Color(0xFF6A7280)
val Line = Color(0xFFE1DED5); val Peg = Color(0xFFE9A23B); val Rain = Color(0xFF2F5D8A); val Safe = Color(0xFF2E8B6B)
val SkyClear = Color(0xFFD6ECF8); val SkyComing = Color(0xFF3F5A78); val SkyRaining = Color(0xFF2B3A4F); val SkyNoData = Color(0xFFDDE0DB)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Channels.ensure(this)
        Scheduler.start(this)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Ink, background = Paper, surface = Color.White)) { App() } }
    }
}

@Composable
fun App() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var screen by remember { mutableStateOf("home") }
    var snap by remember { mutableStateOf(Snapshot.fromJson(prefs.lastSnapshotJson)) }
    var out by remember { mutableStateOf(prefs.clothesOut) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun refresh() {
        loading = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Weather.fetch(prefs) } }
            r.onSuccess { s -> prefs.lastSnapshotJson = s.toJson(); snap = s; Decide.run(ctx, prefs, s) }
             .onFailure { error = "Couldn't reach the weather service" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (snap == null || System.currentTimeMillis() - prefs.lastSnapshotAt > 5 * 60 * 1000) refresh() }

    when (screen) {
        "home" -> Home(prefs, snap, out, loading, error,
            onToggle = { v -> prefs.clothesOut = v; out = v; if (v) refresh() },
            onRefresh = { refresh() }, onSettings = { screen = "settings" })
        else -> SettingsScreen(prefs) { screen = "home"; refresh() }
    }
}

@Composable
fun Home(prefs: Prefs, s: Snapshot?, out: Boolean, loading: Boolean, error: String?,
         onToggle: (Boolean) -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit) {
    val rainingNow = (s?.rainNowMmH ?: 0.0) >= prefs.intensityFloor
    val coming = s != null && !rainingNow && (s.upwindRaining || (s.maxProbNext45 >= prefs.probabilityThreshold && (s.minutesToRain ?: 999) <= 45))
    val sky = when { s == null -> SkyNoData; rainingNow -> SkyRaining; coming -> SkyComing; else -> SkyClear }
    val onSky = if (rainingNow || coming) Color.White else Ink
    val subSky = if (rainingNow || coming) Color(0xFFD6E6F5) else Color(0xFF35465A)
    val headline = when {
        s == null && error != null -> "Station went quiet"
        s == null -> "Looking at the sky…"
        rainingNow -> "Raining now"
        coming -> s.minutesToRain?.let { "Rain in about $it min" } ?: "Rain is near"
        out -> "Good drying weather"
        else -> if (s.todayRainWindow != null) "Clear until ${s.todayRainWindow.substringBefore(" to")}" else "Clear today"
    }
    val sub = when {
        s == null -> error ?: "Fetching the forecast for ${prefs.placeName}"
        rainingNow -> "${"%.1f".format(s.rainNowMmH)} mm/h at your building"
        coming -> s.upwindNote ?: "${s.maxProbNext45}% in the next 45 min"
        s.todayRainWindow != null -> "Rain likely ${s.todayRainWindow} · ${s.todayPeakProb}%"
        else -> "Rain chance today ${s.todayPeakProb}% · we're watching"
    }

    Column(Modifier.fillMaxSize().background(Paper)) {
        // Sky band
        Column(Modifier.fillMaxWidth().background(sky).padding(24.dp, 56.dp, 24.dp, 56.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(prefs.placeName.uppercase() + " · HOME", color = subSky, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onSettings) { Text("Settings", color = onSky) }
            }
            Spacer(Modifier.height(64.dp))
            Text(if (rainingNow) "AT YOUR BUILDING" else "OVER YOUR BUILDING", color = subSky, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(headline, color = onSky, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
            Text(sub, color = subSky, fontSize = 15.sp)
        }

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Clothes card
            Card(colors = CardDefaults.cardColors(containerColor = if (coming && out) Color(0xFFFFF6E3) else Color.White), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Balcony", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Ink)
                            Text(if (out) "Hung ${elapsed(prefs.outSince)} ago" else "Nothing hanging", color = Muted, fontSize = 14.sp)
                        }
                        Switch(checked = out, onCheckedChange = onToggle,
                            colors = SwitchDefaults.colors(checkedTrackColor = Peg, checkedThumbColor = Color.White, uncheckedTrackColor = Line, uncheckedThumbColor = Color.White))
                    }
                    if (coming && out) Button(onClick = { onToggle(false) }, modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ink), shape = RoundedCornerShape(16.dp)) { Text("I've taken them in", fontSize = 17.sp) }
                    if (rainingNow && !out) Text("Clothes are in.", color = Safe, fontWeight = FontWeight.SemiBold)
                }
            }
            // Readings
            if (s != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(Modifier.weight(1f), "${s.tempC?.toInt() ?: "–"}°", "Temp")
                Stat(Modifier.weight(1f), "${s.humidity ?: "–"}%", "Humidity")
                Stat(Modifier.weight(1f), "${s.windKmh?.toInt() ?: "–"} km/h", "Wind from ${s.windDir ?: "–"}")
            }
            Text(if (s != null) "Updated ${elapsed(s.fetchedAt)} ago" + (if (prefs.wuKey.isBlank()) " · forecast only, add a Weather Union key for station data" else "") else "",
                color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh, enabled = !loading, shape = RoundedCornerShape(12.dp)) { Text(if (loading) "Checking…" else "Check now", color = Ink) }
            }
        }
    }
}

@Composable
fun Stat(m: Modifier, big: String, small: String) {
    Card(m, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(big, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(small, fontSize = 12.sp, color = Muted)
        }
    }
}

fun elapsed(since: Long): String {
    val m = (System.currentTimeMillis() - since) / 60000
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

@Composable
fun SettingsScreen(prefs: Prefs, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var place by remember { mutableStateOf(prefs.placeName) }
    var lat by remember { mutableStateOf(prefs.lat.toString()) }
    var lon by remember { mutableStateOf(prefs.lon.toString()) }
    var key by remember { mutableStateOf(prefs.wuKey) }
    var loc by remember { mutableStateOf(prefs.wuLocalities) }
    var sens by remember { mutableStateOf(prefs.sensitivity.toFloat()) }
    var onlyOut by remember { mutableStateOf(prefs.onlyWhenOut) }
    var morning by remember { mutableStateOf(prefs.morningSummary) }
    var dusk by remember { mutableStateOf(prefs.duskReminder) }
    var cover by remember { mutableStateOf(prefs.spotCover) }

    Column(Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState()).padding(20.dp, 56.dp, 20.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)

        Section("Home") {
            OutlinedTextField(place, { place = it }, label = { Text("Area name") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(lat, { lat = it }, label = { Text("Latitude") }, modifier = Modifier.weight(1f))
                OutlinedTextField(lon, { lon = it }, label = { Text("Longitude") }, modifier = Modifier.weight(1f))
            }
            Text("Long-press your building in Google Maps to copy its coordinates.", color = Muted, fontSize = 12.sp)
        }

        Section("Drying spot") {
            listOf("Open", "Partly covered", "Covered").forEachIndexed { i, label ->
                Row(Modifier.fillMaxWidth().clickable { cover = i }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = cover == i, onClick = { cover = i }, colors = RadioButtonDefaults.colors(selectedColor = Ink))
                    Text(label, color = Ink)
                }
            }
        }

        Section("Alerts") {
            Text("How early to warn: ${if (sens < 33) "early" else if (sens < 66) "balanced" else "only when sure"}", color = Ink)
            Slider(sens, { sens = it }, valueRange = 0f..100f, colors = SliderDefaults.colors(thumbColor = Peg, activeTrackColor = Peg))
            Text("Alerts fire at ${30 + (sens.toInt() * 55) / 100}% rain probability within 45 min, or when an upwind station reports rain.", color = Muted, fontSize = 12.sp)
            ToggleRow("Only when clothes are out", onlyOut) { onlyOut = it }
            ToggleRow("Morning summary (7 to 9 am)", morning) { morning = it }
            ToggleRow("Dusk reminder", dusk) { dusk = it }
        }

        Section("Weather Union (optional)") {
            OutlinedTextField(key, { key = it }, label = { Text("API key") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(loc, { loc = it }, label = { Text("Locality IDs, upwind first, comma separated") }, modifier = Modifier.fillMaxWidth())
            Text("Free key from weatherunion.com. Locality IDs are in their station list; put the ones south-west of you first in monsoon season.", color = Muted, fontSize = 12.sp)
        }

        Section("Keep alerts alive") {
            Text("Xiaomi, Vivo, Oppo and Realme put apps to sleep. Set Drip to \"No restrictions\" and allow autostart.", color = Muted, fontSize = 13.sp)
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            }, shape = RoundedCornerShape(12.dp)) { Text("Open app settings", color = Ink) }
        }

        Button(onClick = {
            prefs.placeName = place.ifBlank { "Home" }
            lat.toDoubleOrNull()?.let { prefs.lat = it }; lon.toDoubleOrNull()?.let { prefs.lon = it }
            prefs.wuKey = key; prefs.wuLocalities = loc; prefs.sensitivity = sens.toInt()
            prefs.onlyWhenOut = onlyOut; prefs.morningSummary = morning; prefs.duskReminder = dusk; prefs.spotCover = cover
            onBack()
        }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Ink), shape = RoundedCornerShape(16.dp)) { Text("Save", fontSize = 17.sp) }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = Muted) }
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Muted)
            content()
        }
    }
}

@Composable
fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Ink)
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Ink, checkedThumbColor = Color.White))
    }
}
