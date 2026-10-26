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
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// Tokens from the design canvas
val Paper = Color(0xFFFBFAF7); val Ink = Color(0xFF1B2430); val Muted = Color(0xFF6A7280)
val Line = Color(0xFFE1DED5); val Peg = Color(0xFFE9A23B); val Safe = Color(0xFF2E8B6B)
val SkyClear = Color(0xFFD6ECF8); val SkyComing = Color(0xFF3F5A78); val SkyRaining = Color(0xFF2B3A4F); val SkyNoData = Color(0xFFDDE0DB)
val Amber = Color(0xFFFFF6E3); val AmberInk = Color(0xFF6A5A3A)

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
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var screen by remember { mutableStateOf("home") }
    var snap by remember { mutableStateOf(Snapshot.fromJson(prefs.lastSnapshotJson)) }
    var spots by remember { mutableStateOf(prefs.spots) }
    var reports by remember { mutableStateOf(prefs.reports) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }

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
        "home" -> Home(prefs, snap, spots, reports, loading, error,
            onToggle = { id, v -> prefs.setOut(id, v); spots = prefs.spots; if (v) refresh() },
            onAllIn = { prefs.setAllIn(); spots = prefs.spots },
            onReport = { r -> prefs.addReport(r); reports = prefs.reports; if (!r) prefs.thresholdAdjust = prefs.thresholdAdjust + 3 else prefs.thresholdAdjust = prefs.thresholdAdjust - 2 },
            onRefresh = { refresh() }, onSettings = { screen = "settings" })
        else -> SettingsScreen(prefs) { screen = "home"; spots = prefs.spots; refresh() }
    }
}

@Composable
fun Home(prefs: Prefs, s: Snapshot?, spots: List<Spot>, reports: List<Report>, loading: Boolean, error: String?,
         onToggle: (String, Boolean) -> Unit, onAllIn: () -> Unit, onReport: (Boolean) -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit) {
    val anyOut = spots.any { it.out }
    val rainingNow = s != null && Decide.rainingNow(prefs, s)
    val coming = s != null && Decide.coming(prefs, s)
    val sky = when { s == null -> SkyNoData; rainingNow -> SkyRaining; coming -> SkyComing; else -> SkyClear }
    val dark = rainingNow || coming
    val onSky = if (dark) Color.White else Ink
    val subSky = if (dark) Color(0xFFD6E6F5) else Color(0xFF35465A)
    val headline = when {
        s == null && error != null -> "Station went quiet"
        s == null -> "Looking at the sky…"
        rainingNow -> "Raining now"
        coming -> s.minutesToRain?.let { "Rain in about $it min" } ?: "Rain is near"
        anyOut -> "Good drying weather"
        else -> if (s.todayRainWindow != null) "Clear until ${s.todayRainWindow.substringBefore(" to")}" else "Clear today"
    }
    val sub = when {
        s == null -> error ?: "Fetching the forecast for ${prefs.placeName}"
        rainingNow && prefs.reportedRainingNow -> "You reported rain ${elapsed(reports.last().at)} ago"
        rainingNow -> "${"%.1f".format(s.rainNowMmH)} mm/h at your building"
        coming -> s.upwindNote ?: "${s.maxProbNext45}% in the next 45 min"
        s.todayRainWindow != null -> "Rain likely ${s.todayRainWindow} · ${s.todayPeakProb}%"
        else -> "Rain chance today ${s.todayPeakProb}% · we're watching"
    }
    val fmt = DateTimeFormatter.ofPattern("h:mm a")

    val skyState = when { s == null -> SkyState.NODATA; rainingNow -> SkyState.RAINING; coming -> SkyState.COMING; else -> SkyState.CLEAR }
    val skyAnim by animateColorAsState(sky, tween(600), label = "sky")
    val onSkyAnim by animateColorAsState(onSky, tween(600), label = "onSky")
    val subSkyAnim by animateColorAsState(subSky, tween(600), label = "subSky")
    val haptic = LocalHapticFeedback.current
    var thanks by remember { mutableStateOf(false) }
    LaunchedEffect(thanks) { if (thanks) { delay(2000); thanks = false } }

    Column(Modifier.fillMaxSize().background(Paper)) {
        Box(Modifier.fillMaxWidth().background(skyAnim)) {
            Column(Modifier.fillMaxWidth().padding(0.dp, 56.dp, 0.dp, 36.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(prefs.placeName.uppercase() + " · HOME", color = subSkyAnim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onSettings) { Text("Settings", color = onSkyAnim) }
                }
                SkyScene(skyState, anyOut, Modifier.height(200.dp))
                Column(Modifier.padding(horizontal = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (coming) PulseDot()
                    Text(when { rainingNow -> "AT YOUR BUILDING"; coming -> "RAIN APPROACHING"; else -> "OVER YOUR BUILDING" },
                        color = if (coming) Color(0xFFFFD38A) else subSkyAnim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                AnimatedContent(headline, transitionSpec = { (fadeIn(tween(300)) + slideInVertically { it / 4 }) togetherWith fadeOut(tween(200)) }, label = "headline") { h ->
                    Text(h, color = onSkyAnim, fontSize = if (coming || rainingNow) 38.sp else 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
                }
                Crossfade(sub, animationSpec = tween(300), label = "sub") { Text(it, color = subSkyAnim, fontSize = 15.sp) }
                }
            }
        }

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Spots
            Card(colors = CardDefaults.cardColors(containerColor = if (coming && anyOut) Amber else Color.White), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    spots.forEach { sp ->
                        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(sp.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Ink)
                                val line = when {
                                    !sp.out -> "Nothing hanging"
                                    s == null -> "Hung ${elapsed(sp.outSince)} ago"
                                    else -> {
                                        val dryAt = java.time.Instant.ofEpochMilli(sp.outSince).atZone(java.time.ZoneId.systemDefault()).toLocalTime().plusMinutes((s.dryHours() * 60).toLong())
                                        if (LocalTime.now() >= dryAt) "Hung ${elapsed(sp.outSince)} ago · should be dry" else "Hung ${elapsed(sp.outSince)} ago · dry by about ${dryAt.format(fmt).lowercase()}"
                                    }
                                }
                                Text(line, color = if (coming && sp.out) AmberInk else Muted, fontSize = 14.sp)
                            }
                            Switch(checked = sp.out, onCheckedChange = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onToggle(sp.id, it) },
                                colors = SwitchDefaults.colors(checkedTrackColor = Peg, checkedThumbColor = Color.White, uncheckedTrackColor = Line, uncheckedThumbColor = Color.White))
                        }
                        if (sp.out && s != null) {
                            val frac = ((System.currentTimeMillis() - sp.outSince) / (s.dryHours() * 3600000.0)).toFloat().coerceIn(0f, 1f)
                            val fracAnim by animateFloatAsState(frac, tween(800), label = "dry")
                            LinearProgressIndicator(progress = { fracAnim }, modifier = Modifier.fillMaxWidth().height(6.dp).padding(bottom = 6.dp), color = Peg, trackColor = Color(0xFFF0EDE4))
                        }
                    }
                    AnimatedVisibility((coming || rainingNow) && anyOut, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onAllIn() }, modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Ink), shape = RoundedCornerShape(16.dp)) { Text("I've taken them in", fontSize = 17.sp) }
                    }
                    AnimatedVisibility(rainingNow && !anyOut, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CheckBadge(); Text("Clothes are in.", color = Safe, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (s != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(Modifier.weight(1f), "${s.tempC?.toInt() ?: "–"}°", "Temp")
                Stat(Modifier.weight(1f), "${s.humidity ?: "–"}%", "Humidity")
                Stat(Modifier.weight(1f), "${s.windKmh?.toInt() ?: "–"} km/h", "From ${s.windDir ?: "–"}")
            }
            if (s != null) Text("Updated ${elapsed(s.fetchedAt)} ago · a load dries in about ${"%.1f".format(s.dryHours())} h" +
                (if (prefs.wuKey.isBlank()) " · forecast only, add a Weather Union key for station data" else ""), color = Muted, fontSize = 12.sp)

            // Report
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Raining at your place?", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Ink)
                        val last = reports.lastOrNull()
                        Text(if (last != null && System.currentTimeMillis() - last.at < 60 * 60 * 1000) "You said ${if (last.raining) "raining" else "dry"} ${elapsed(last.at)} ago" else "One tap teaches the app your block",
                            color = Muted, fontSize = 13.sp)
                    }
                    AnimatedContent(thanks, label = "thanks") { done ->
                        if (done) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { CheckBadge(); Text("Thanks", color = Safe, fontWeight = FontWeight.SemiBold) }
                        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onReport(true); thanks = true }, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(12.dp, 0.dp)) { Text("Yes", color = Ink) }
                            OutlinedButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onReport(false); thanks = true }, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(12.dp, 0.dp)) { Text("No", color = Ink) }
                        }
                    }
                }
            }

            OutlinedButton(onClick = onRefresh, enabled = !loading, shape = RoundedCornerShape(12.dp)) { Text(if (loading) "Checking…" else "Check now", color = Ink) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun PulseDot() {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(8.dp).background(Color(0xFFFFD38A).copy(alpha = a), CircleShape))
}

@Composable
fun CheckBadge() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else 0.4f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "check")
    Box(Modifier.size(24.dp).scale(scale).background(Safe, CircleShape), contentAlignment = Alignment.Center) {
        Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var place by remember { mutableStateOf(prefs.placeName) }
    var lat by remember { mutableStateOf(prefs.lat.toString()) }
    var lon by remember { mutableStateOf(prefs.lon.toString()) }
    var workName by remember { mutableStateOf(prefs.workName) }
    var workLat by remember { mutableStateOf(if (prefs.hasWork) prefs.workLat.toString() else "") }
    var workLon by remember { mutableStateOf(if (prefs.hasWork) prefs.workLon.toString() else "") }
    var key by remember { mutableStateOf(prefs.wuKey) }
    var loc by remember { mutableStateOf(prefs.wuLocalities) }
    var sens by remember { mutableStateOf(prefs.sensitivity.toFloat()) }
    var onlyOut by remember { mutableStateOf(prefs.onlyWhenOut) }
    var morning by remember { mutableStateOf(prefs.morningSummary) }
    var dusk by remember { mutableStateOf(prefs.duskReminder) }
    var laundry by remember { mutableStateOf(prefs.laundryPrompt) }
    var qs by remember { mutableStateOf(prefs.quietStart.toFloat()) }
    var qe by remember { mutableStateOf(prefs.quietEnd.toFloat()) }
    var spots by remember { mutableStateOf(prefs.spots) }
    var newSpot by remember { mutableStateOf("") }
    var locating by remember { mutableStateOf<String?>(null) }
    var locMsg by remember { mutableStateOf<String?>(null) }

    val askLoc = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    fun locate(target: String) {
        if (!Locate.granted(ctx)) { askLoc.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); locMsg = "Allow location, then tap again"; return }
        locating = target; locMsg = null
        scope.launch {
            val fix = Locate.current(ctx)
            if (fix == null) { locMsg = "Couldn't get a fix. Is Location on?"; locating = null; return@launch }
            val name = withContext(Dispatchers.IO) { Weather.placeName(fix.first, fix.second) }
            if (target == "home") { lat = "%.5f".format(fix.first); lon = "%.5f".format(fix.second); if (name != null) place = name }
            else { workLat = "%.5f".format(fix.first); workLon = "%.5f".format(fix.second); if (name != null) workName = name }
            locating = null
        }
    }
    fun lookup(target: String) {
        val q = if (target == "home") place else workName
        if (q.isBlank()) return
        locating = target; locMsg = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { Weather.geocode(q) }
            if (r == null) locMsg = "Couldn't find \"$q\""
            else if (target == "home") { lat = "%.5f".format(r.first); lon = "%.5f".format(r.second) }
            else { workLat = "%.5f".format(r.first); workLon = "%.5f".format(r.second) }
            locating = null
        }
    }

    Column(Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState()).padding(20.dp, 56.dp, 20.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)

        Section("Home") {
            OutlinedTextField(place, { place = it }, label = { Text("Area or building") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(lat, { lat = it }, label = { Text("Latitude") }, modifier = Modifier.weight(1f))
                OutlinedTextField(lon, { lon = it }, label = { Text("Longitude") }, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { locate("home") }, enabled = locating == null, shape = RoundedCornerShape(12.dp)) { Text(if (locating == "home") "Locating…" else "Use my location", color = Ink) }
                OutlinedButton(onClick = { lookup("home") }, enabled = locating == null, shape = RoundedCornerShape(12.dp)) { Text("Find by name", color = Ink) }
            }
            Text("Tap \"Use my location\" while standing at home. Rain in Bengaluru changes block to block.", color = Muted, fontSize = 12.sp)
            if (locMsg != null) Text(locMsg!!, color = AmberInk, fontSize = 12.sp)
        }

        Section("Drying spots") {
            spots.forEach { sp ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sp.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = Ink)
                        if (spots.size > 1) TextButton(onClick = { spots = spots.filter { it.id != sp.id } }) { Text("Remove", color = Muted) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Open", "Partly covered", "Covered").forEachIndexed { i, label ->
                            FilterChip(selected = sp.cover == i, onClick = { spots = spots.map { if (it.id == sp.id) it.copy(cover = i) else it } }, label = { Text(label, fontSize = 12.sp) })
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newSpot, { newSpot = it }, label = { Text("Add a spot (Terrace…)") }, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    if (newSpot.isNotBlank()) { spots = spots + Spot(newSpot.lowercase().replace(" ", "-") + System.currentTimeMillis() % 1000, newSpot.trim(), 0, false, 0L); newSpot = "" }
                }, shape = RoundedCornerShape(12.dp)) { Text("Add", color = Ink) }
            }
            Text("Open: any rain matters. Partly covered: moderate rain. Covered: only heavy rain with wind.", color = Muted, fontSize = 12.sp)
        }

        Section("Alerts") {
            Text("How early to warn: ${if (sens < 33) "early" else if (sens < 66) "balanced" else "only when sure"}", color = Ink)
            Slider(sens, { sens = it }, valueRange = 0f..100f, colors = SliderDefaults.colors(thumbColor = Peg, activeTrackColor = Peg))
            Text("Fires at ${(30 + (sens.toInt() * 55) / 100 + prefs.thresholdAdjust).coerceIn(20, 95)}% rain probability within 45 min, or when an upwind station reports rain." +
                (if (prefs.thresholdAdjust != 0) " Adjusted ${if (prefs.thresholdAdjust > 0) "+" else ""}${prefs.thresholdAdjust} from your reports." else ""), color = Muted, fontSize = 12.sp)
            ToggleRow("Only when clothes are out", onlyOut) { onlyOut = it }
            ToggleRow("Morning summary (7 to 9 am)", morning) { morning = it }
            ToggleRow("Laundry-day prompt", laundry) { laundry = it }
            Text(if (prefs.laundryLogCount < 3) "Learning your pattern, ${prefs.laundryLogCount} of 3 wash days logged" else "Pattern learned from ${prefs.laundryLogCount} wash days", color = Muted, fontSize = 12.sp)
            ToggleRow("Dusk reminder", dusk) { dusk = it }
            Text("Quiet hours: ${qs.toInt()}:00 to ${qe.toInt()}:00. Rain alerts still come through.", color = Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("From", color = Muted, fontSize = 12.sp); Slider(qs, { qs = it }, valueRange = 0f..23f, steps = 22, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("To", color = Muted, fontSize = 12.sp); Slider(qe, { qe = it }, valueRange = 0f..23f, steps = 22, modifier = Modifier.weight(1f))
            }
        }

        Section("Work (optional)") {
            OutlinedTextField(workName, { workName = it }, label = { Text("Office area") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(workLat, { workLat = it }, label = { Text("Latitude") }, modifier = Modifier.weight(1f))
                OutlinedTextField(workLon, { workLon = it }, label = { Text("Longitude") }, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { locate("work") }, enabled = locating == null, shape = RoundedCornerShape(12.dp)) { Text(if (locating == "work") "Locating…" else "Use my location", color = Ink) }
                OutlinedButton(onClick = { lookup("work") }, enabled = locating == null, shape = RoundedCornerShape(12.dp)) { Text("Find by name", color = Ink) }
                if (workLat.isNotBlank()) TextButton(onClick = { workLat = ""; workLon = ""; workName = "" }) { Text("Clear", color = Muted) }
            }
            Text("Adds a raincoat note to the morning summary when rain is due at work between 9 and 7.", color = Muted, fontSize = 12.sp)
        }

        Section("Weather Union (optional)") {
            OutlinedTextField(key, { key = it }, label = { Text("API key") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(loc, { loc = it }, label = { Text("Locality IDs, upwind first, comma separated") }, modifier = Modifier.fillMaxWidth())
            Text("Free key from weatherunion.com. Put the localities south-west of you first in monsoon season.", color = Muted, fontSize = 12.sp)
        }

        Section("Keep alerts alive") {
            Text("Xiaomi, Vivo, Oppo and Realme put apps to sleep. Set Drip to \"No restrictions\" and allow autostart.", color = Muted, fontSize = 13.sp)
            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) },
                shape = RoundedCornerShape(12.dp)) { Text("Open app settings", color = Ink) }
        }

        Button(onClick = {
            prefs.placeName = place.ifBlank { "Home" }
            lat.toDoubleOrNull()?.let { prefs.lat = it }; lon.toDoubleOrNull()?.let { prefs.lon = it }
            prefs.workName = workName; prefs.workLat = workLat.toDoubleOrNull() ?: 0.0; prefs.workLon = workLon.toDoubleOrNull() ?: 0.0
            prefs.wuKey = key; prefs.wuLocalities = loc; prefs.sensitivity = sens.toInt()
            prefs.onlyWhenOut = onlyOut; prefs.morningSummary = morning; prefs.duskReminder = dusk; prefs.laundryPrompt = laundry
            prefs.quietStart = qs.toInt(); prefs.quietEnd = qe.toInt()
            prefs.spots = spots
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
