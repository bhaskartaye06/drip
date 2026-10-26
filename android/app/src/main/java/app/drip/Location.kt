package app.drip

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** One-shot current location with no Play Services dependency. Returns null if denied, off, or slow. */
object Locate {
    fun granted(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Pair<Double, Double>? {
        if (!granted(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { lm.isProviderEnabled(it) } ?: return null
        val fresh = withTimeoutOrNull(15000) {
            suspendCancellableCoroutine<Pair<Double, Double>?> { cont ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val sig = CancellationSignal()
                    cont.invokeOnCancellation { sig.cancel() }
                    lm.getCurrentLocation(provider, sig, Executors.newSingleThreadExecutor()) { loc ->
                        if (cont.isActive) cont.resume(loc?.let { it.latitude to it.longitude })
                    }
                } else {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, { loc -> if (cont.isActive) cont.resume(loc.latitude to loc.longitude) }, ctx.mainLooper)
                }
            }
        }
        if (fresh != null) return fresh
        // Fall back to the last known fix from any provider
        return lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.let { it.latitude to it.longitude }
    }
}
