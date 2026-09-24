package com.ksjd.testem.live

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Will the user reach their stop before the bus? Pure logic, no Android types. */
data class CatchEstimate(val walkSeconds: Int, val marginSeconds: Int) {
    enum class Verdict { AtStop, Relaxed, LeaveSoon, LeaveNow, Miss }

    val verdict: Verdict
        get() = when {
            walkSeconds <= AT_STOP_WALK_S -> Verdict.AtStop
            marginSeconds < 0 -> Verdict.Miss
            marginSeconds <= 60 -> Verdict.LeaveNow
            marginSeconds <= 5 * 60 -> Verdict.LeaveSoon
            else -> Verdict.Relaxed
        }

    companion object {
        /** Typical walking pace; the knob to tune if estimates run early or late. */
        const val WALK_METERS_PER_SECOND = 1.3
        /** Streets are not straight lines. */
        const val DETOUR_FACTOR = 1.3
        private const val AT_STOP_WALK_S = 45

        fun from(distanceMeters: Int, secondsToBoarding: Int): CatchEstimate {
            val walk = (distanceMeters * DETOUR_FACTOR / WALK_METERS_PER_SECOND).toInt()
            return CatchEstimate(walk, secondsToBoarding - walk)
        }
    }
}

/** Location on the phone only; it is never sent anywhere. */
object Locations {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Precise (fine) location: approximate can be kilometres off, useless for walking time. */
    fun hasPrecise(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * A fix no older than [maxAgeMs]: a recent cached one, else a fresh one (fused provider
     * first; it works with approximate permission too). [fallbackToLastKnown] returns an older
     * cached fix when no fresh one arrives, which is fine for "stops near me" but not for timing.
     */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, maxAgeMs: Long = 10 * 60 * 1000L, fallbackToLastKnown: Boolean = false): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val enabled = { p: String -> runCatching { manager.isProviderEnabled(p) }.getOrDefault(false) }
        val lastKnown = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter(enabled)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < maxAgeMs) return lastKnown
        val fresh = buildList {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (hasPrecise(context)) add(LocationManager.GPS_PROVIDER)
        }.filter(enabled)
        for (provider in fresh) {
            val fix = withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = android.os.CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    runCatching {
                        LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                            if (cont.isActive) cont.resume(location)
                        }
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
            if (fix != null) return fix
        }
        return if (fallbackToLastKnown) lastKnown else null
    }
}
