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
            description = "Morning summary and dusk reminder"
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

/** The alert rules. Kept in one place so the Mac app can mirror them. */
object Decide {
    fun run(ctx: Context, prefs: Prefs, s: Snapshot) {
        val now = LocalTime.now()
        val today = LocalDate.now().toString()

        // 1. Rain alert
        val rainSoon = s.upwindRaining ||
            (s.maxProbNext45 >= prefs.probabilityThreshold && (s.minutesToRain ?: 999) <= 45) ||
            ((s.rainNowMmH ?: 0.0) >= prefs.intensityFloor)
        val gate = !prefs.onlyWhenOut || prefs.clothesOut
        val cooldownOver = System.currentTimeMillis() - prefs.lastRainAlertAt > 40 * 60 * 1000
        if (rainSoon && gate && cooldownOver) {
            val eta = s.minutesToRain
            val title = when {
                (s.rainNowMmH ?: 0.0) >= prefs.intensityFloor -> "Raining at your building now"
                eta != null -> "Rain in about $eta min"
                else -> "Rain is reaching your area"
            }
            val body = buildString {
                if (prefs.clothesOut) append("Clothes are out. ") else append("Nothing marked out. ")
                if (s.upwindRaining && s.upwindNote != null) append(s.upwindNote).append(". ")
                else append("${s.maxProbNext45}% in the next 45 min.")
            }
            Notify.rain(ctx, title, body.trim(), prefs.clothesOut)
            prefs.lastRainAlertAt = System.currentTimeMillis()
        }

        // 2. Morning summary, once, between 7 and 9
        if (prefs.morningSummary && now.hour in 7..8 && prefs.lastMorningDay != today) {
            val body = when {
                s.todayRainWindow != null -> "Rain likely ${s.todayRainWindow} (${s.todayPeakProb}%). Dry before that."
                s.todayPeakProb >= 30 -> "Some chance of rain later (${s.todayPeakProb}%). We'll watch."
                else -> "Dry all day. Good day for a wash."
            }
            Notify.nudge(ctx, 2, "Today over ${prefs.placeName}", body)
            prefs.lastMorningDay = today
        }

        // 3. Dusk reminder, once, at or after sunset while clothes are out
        val sunset = s.sunset ?: LocalTime.of(18, 10)
        if (prefs.duskReminder && prefs.clothesOut && now >= sunset && now.hour < 22 && prefs.lastDuskDay != today) {
            val hours = (System.currentTimeMillis() - prefs.outSince) / 3600000
            Notify.nudge(ctx, 3, "Sun's down. Clothes still out?", "Out for ${hours}h. Dew sets in after 8 pm.", withTakenIn = true)
            prefs.lastDuskDay = today
        }
    }
}

object Notify {
    private const val ID_RAIN = 1

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun takenIn(ctx: Context, notifId: Int) = PendingIntent.getBroadcast(ctx, notifId,
        Intent(ctx, ActionReceiver::class.java).setAction("app.drip.TAKEN_IN").putExtra("id", notifId),
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
        if (clothesOut) b.addAction(0, "Taken in", takenIn(ctx, ID_RAIN))
        post(ctx, ID_RAIN, b)
    }

    fun nudge(ctx: Context, id: Int, title: String, body: String, withTakenIn: Boolean = false) {
        Channels.ensure(ctx)
        val b = NotificationCompat.Builder(ctx, Channels.NUDGE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(ctx)).setAutoCancel(true)
        if (withTakenIn) b.addAction(0, "Taken in", takenIn(ctx, id))
        post(ctx, id, b)
    }

    private fun post(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        try { NotificationManagerCompat.from(ctx).notify(id, b.build()) } catch (e: SecurityException) { /* permission not granted yet */ }
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == "app.drip.TAKEN_IN") {
            Prefs(ctx).clothesOut = false
            NotificationManagerCompat.from(ctx).cancel(intent.getIntExtra("id", 1))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { Scheduler.start(ctx) }
}
