package app.drip

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object Channels {
    const val RAIN = "rain"      // high priority, sound, breaks quiet hours
    const val NUDGE = "nudge"    // morning, dusk, laundry day: silent

    fun ensure(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(RAIN, "Rain alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rain is about to reach your building and clothes are out"
        })
        nm.createNotificationChannel(NotificationChannel(NUDGE, "Daily nudges", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Morning summary, laundry-day prompt and dusk reminder"
        })
    }
}

object Scheduler {
    fun start(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("drip-poll", ExistingPeriodicWorkPolicy.KEEP, req)
    }
    fun runNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork("drip-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<AlertWorker>().build())
    }
}

class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Prefs(applicationContext)
        val snap = try { Weather.fetch(prefs) } catch (e: Exception) { return@withContext Result.retry() }
        prefs.lastSnapshotJson = snap.toJson()
        Decide.run(applicationContext, prefs, snap)
        Result.success()
    }
}

/** The alert rules, in one place. */
object Decide {
    fun rainingNow(prefs: Prefs, s: Snapshot): Boolean =
        prefs.reportedRainingNow || (!prefs.reportedDryNow && (s.rainNowMmH ?: 0.0) >= prefs.activeIntensityFloor)

    fun coming(prefs: Prefs, s: Snapshot): Boolean =
        !rainingNow(prefs, s) && !prefs.reportedDryNow &&
            (s.upwindRaining || (s.maxProbNext45 >= prefs.probabilityThreshold && (s.minutesToRain ?: 999) <= 45))

    fun run(ctx: Context, prefs: Prefs, s: Snapshot) {
        val now = LocalTime.now()
        val today = LocalDate.now().toString()
        val quiet = prefs.inQuietHours(now.hour)

        // 1. Rain alert: the only one that ignores quiet hours
        val raining = rainingNow(prefs, s)
        val rainSoon = raining || coming(prefs, s)
        val gate = !prefs.onlyWhenOut || prefs.anyOut
        val cooldownOver = System.currentTimeMillis() - prefs.lastRainAlertAt > 40 * 60 * 1000
        if (rainSoon && gate && cooldownOver && !prefs.reportedRainingNow) {
            val eta = s.minutesToRain
            val title = when {
                raining -> "Raining at your building now"
                eta != null -> "Rain in about $eta min"
                else -> "Rain is reaching your area"
            }
            val outNames = prefs.outSpots.joinToString(" and ") { it.name }
            val body = buildString {
                if (prefs.anyOut) append("$outNames clothes are out. ") else append("Nothing marked out. ")
                if (s.upwindRaining && s.upwindNote != null) append(s.upwindNote).append(".")
                else append("${s.maxProbNext45}% in the next 45 min.")
            }
            Notify.rain(ctx, title, body.trim(), prefs.anyOut)
            prefs.lastRainAlertAt = System.currentTimeMillis()
        }

        if (quiet) return

        // 2. Morning summary (7 to 9), with the work umbrella note and the laundry-day prompt folded in
        if (prefs.morningSummary && now.hour in 7..8 && prefs.lastMorningDay != today) {
            val home = when {
                s.todayRainWindow != null -> "Rain likely ${s.todayRainWindow} (${s.todayPeakProb}%)."
                s.todayPeakProb >= 30 -> "Some chance of rain later (${s.todayPeakProb}%)."
                else -> "Dry all day."
            }
            val work = if (prefs.hasWork && s.workRainWindow != null) " Carry a raincoat: rain at ${prefs.workName.ifBlank { "work" }} ${s.workRainWindow}." else ""
            val laundry = prefs.laundryPrompt && prefs.isLikelyLaundryDay()
            val title = if (laundry) "Hanging clothes today?" else "Today over ${prefs.placeName}"
            val body = if (laundry) "It's usually a ${LocalDate.now().dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} for you. $home$work" else "$home$work"
            Notify.nudge(ctx, 2, title, body.trim(), withYesOut = laundry)
            prefs.lastMorningDay = today
            if (laundry) prefs.lastLaundryPromptDay = today
        }

        // 3. Dusk reminder, once, at or after sunset while clothes are out
        val sunset = s.sunset ?: LocalTime.of(18, 10)
        if (prefs.duskReminder && prefs.anyOut && now >= sunset && now.hour < 22 && prefs.lastDuskDay != today) {
            val oldest = prefs.outSpots.minOf { it.outSince }
            val hours = (System.currentTimeMillis() - oldest) / 3600000
            Notify.nudge(ctx, 3, "Sun's down. Clothes still out?", "Out for ${hours} h. Dew sets in after 8 pm.", withTakenIn = true)
            prefs.lastDuskDay = today
        }
    }
}

object Notify {
    const val ID_RAIN = 1

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun action(ctx: Context, notifId: Int, act: String) = PendingIntent.getBroadcast(ctx, notifId * 10 + (act.hashCode() and 7),
        Intent(ctx, ActionReceiver::class.java).setAction(act).putExtra("id", notifId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun rain(ctx: Context, title: String, body: String, clothesOut: Boolean) {
        Channels.ensure(ctx)
        val b = NotificationCompat.Builder(ctx, Channels.RAIN)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openApp(ctx)).setAutoCancel(true)
        if (clothesOut) b.addAction(0, "Taken in", action(ctx, ID_RAIN, ActionReceiver.TAKEN_IN))
        b.addAction(0, "Not raining", action(ctx, ID_RAIN, ActionReceiver.WRONG))
        post(ctx, ID_RAIN, b)
    }

    fun nudge(ctx: Context, id: Int, title: String, body: String, withTakenIn: Boolean = false, withYesOut: Boolean = false) {
        Channels.ensure(ctx)
        val b = NotificationCompat.Builder(ctx, Channels.NUDGE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(ctx)).setAutoCancel(true)
        if (withTakenIn) b.addAction(0, "Taken in", action(ctx, id, ActionReceiver.TAKEN_IN))
        if (withYesOut) { b.addAction(0, "Yes, out now", action(ctx, id, ActionReceiver.OUT_NOW)); b.addAction(0, "Not today", action(ctx, id, ActionReceiver.DISMISS)) }
        post(ctx, id, b)
    }

    private fun post(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        try { NotificationManagerCompat.from(ctx).notify(id, b.build()) } catch (e: SecurityException) { }
    }
}

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val TAKEN_IN = "app.drip.TAKEN_IN"
        const val WRONG = "app.drip.WRONG"
        const val OUT_NOW = "app.drip.OUT_NOW"
        const val DISMISS = "app.drip.DISMISS"
    }
    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs = Prefs(ctx)
        when (intent.action) {
            TAKEN_IN -> prefs.setAllIn()
            WRONG -> { prefs.addReport(false); prefs.thresholdAdjust = prefs.thresholdAdjust + 3 }
            OUT_NOW -> prefs.spots.firstOrNull()?.let { prefs.setOut(it.id, true) }
        }
        NotificationManagerCompat.from(ctx).cancel(intent.getIntExtra("id", 1))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { Scheduler.start(ctx) }
}
