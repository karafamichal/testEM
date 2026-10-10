package com.ksjd.testem.ui.screens

import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ksjd.testem.BuildConfig
import com.ksjd.testem.R
import com.ksjd.testem.live.LiveRepository
import com.ksjd.testem.live.Locations
import com.ksjd.testem.live.TrackedTrip
import com.ksjd.testem.live.TripDetail
import com.ksjd.testem.live.TripProgress
import com.ksjd.testem.live.TripRef
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * Where the bus is, on a map (OpenStreetMap): its stops joined in order, the stops already passed dimmed, the bus
 * between the two stops it's between by its expected times (live delay included), and you, with location allowed.
 */
@Composable
fun BusMapDialog(ref: TripRef, detail: TripDetail, alightOrder: Int?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var withCoords by remember { mutableStateOf<TripDetail?>(null) }
    var failed by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ref, detail) {
        val loaded = runCatching { LiveRepository(context).withCoordinates(ref, detail) }.getOrNull()
        withCoords = loaded
        failed = loaded == null || loaded.stops.count { it.lat != null } < 2
    }
    // The bus moves along by the clock between updates.
    LaunchedEffect(Unit) { while (true) { delay(10_000); now = System.currentTimeMillis() } }
    // Shared by a code: the other side's last position (theirs as sharer; or those this phone shares its bus with).
    val incoming = remember(ref) { com.ksjd.testem.live.Shares.incomingFor(context, ref) }
    var shareBack by remember { mutableStateOf(false) }
    var sharerGone by remember { mutableStateOf(false) }
    val positions by com.ksjd.testem.live.Shares.positions.collectAsState()
    val outgoing by com.ksjd.testem.live.Shares.outgoing.collectAsState()
    val following = com.ksjd.testem.live.TripTracking.active.collectAsState().value?.ref == ref
    // The sharer's position live over a WebSocket while the map is open; ours back only with the switch on.
    val socket = remember(incoming) { incoming?.let { com.ksjd.testem.live.Shares.watchIncoming(it) { sharerGone = true } } }
    DisposableEffect(socket) { onDispose { socket?.close() } }
    LaunchedEffect(socket, shareBack) {
        while (socket != null && shareBack) {
            runCatching { Locations.current(context, maxAgeMs = 20_000L) }.getOrNull()?.let { socket.send(it.latitude, it.longitude, it.accuracy.toInt()) }
            delay(20_000)
        }
    }
    val others = buildList {
        incoming?.let { share -> positions[share.claimId]?.let { add(share.label.substringBefore(" · ") to it) } }
        if (following) outgoing.forEach { share -> positions[share.id]?.let { add(share.name to it) } }
    }.filter { now - it.second.atMs < 5 * 60_000L }
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val muted = MaterialTheme.colorScheme.outline.toArgb()
    val surface = MaterialTheme.colorScheme.surface.toArgb()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                Text(stringResource(R.string.busmap_title, ref.line, detail.destination.ifBlank { ref.destination }),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
            }
            if (incoming != null) {
                // The one who shared sees this phone only if this is on (and only while the map is open).
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (sharerGone) R.string.share_stopped else R.string.share_back), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                    if (!sharerGone) androidx.compose.material3.Switch(shareBack, { shareBack = it })
                }
            }
            val shown = withCoords
            Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
                when {
                    failed -> Text(stringResource(R.string.busmap_unavailable), modifier = Modifier.padding(24.dp))
                    shown == null -> CircularProgressIndicator()
                    else -> {
                        val map = remember {
                            Configuration.getInstance().apply {
                                load(context, context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
                                userAgentValue = BuildConfig.APPLICATION_ID // OpenStreetMap's tile policy asks for the app's name
                            }
                            MapView(context).apply {
                                setTileSource(TileSourceFactory.MAPNIK)
                                setMultiTouchControls(true)
                                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                                overlays.add(CopyrightOverlay(context)) // "© OpenStreetMap contributors", as the licence asks
                            }
                        }
                        var framed by remember { mutableStateOf(false) }
                        DisposableEffect(Unit) { onDispose { map.onDetach() } }
                        AndroidView({ map }, Modifier.fillMaxSize()) { view ->
                            draw(view, ref, shown, alightOrder, now, primary, muted, surface, others)
                            if (!framed) {
                                framed = true
                                val points = shown.stops.mapNotNull { s -> s.lat?.let { GeoPoint(it, s.lon!!) } }
                                // Only once the map has its size: before that it can't fit the route.
                                view.addOnFirstLayoutListener { _, _, _, _, _ ->
                                    runCatching { view.zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.3f), false) }
                                }
                                if (Locations.hasPermission(context)) {
                                    view.overlays.add(MyLocationNewOverlay(GpsMyLocationProvider(context), view).apply { enableMyLocation() })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Route, stops and the bus, drawn again whenever the time (so the bus's place) changes. */
private fun draw(
    map: MapView, ref: TripRef, detail: TripDetail, alightOrder: Int?, now: Long, primary: Int, muted: Int, surface: Int,
    others: List<Pair<String, com.ksjd.testem.hub.HubClient.SharedPosition>>
) {
    map.overlays.removeAll { it is Polyline || (it is Marker && it.id?.startsWith("pr:") == true) }
    val progress = TripProgress.from(detail, TrackedTrip(ref, emptyList(), alightOrder), now)
    val stops = detail.stops
    val points = stops.map { s -> s.lat?.let { GeoPoint(it, s.lon!!) } }
    val passedUpTo = progress.position
    map.overlays.add(0, Polyline(map).apply {
        setPoints(points.filterNotNull())
        outlinePaint.color = primary
        outlinePaint.strokeWidth = 10f
    })
    stops.forEachIndexed { i, stop ->
        val at = points[i] ?: return@forEachIndexed
        val target = stop.order == alightOrder
        map.overlays.add(Marker(map).apply {
            id = "pr:stop$i"
            position = at
            title = stop.name
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = dot(if (i <= passedUpTo) muted else primary, surface, if (target) 18 else 12, map.context.resources.displayMetrics.density)
        })
    }
    // The bus: between the stop it left and the next, by how far along the clock (with the delay) says it is.
    val from = passedUpTo.toInt().coerceIn(0, stops.lastIndex)
    val to = (from + 1).coerceAtMost(stops.lastIndex)
    val a = points[from]
    val b = points[to] ?: a
    if (a != null && b != null) {
        val f = (passedUpTo - from).toDouble().coerceIn(0.0, 1.0)
        map.overlays.add(Marker(map).apply {
            id = "pr:bus"
            position = GeoPoint(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
            title = ref.line
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = androidx.core.content.ContextCompat.getDrawable(map.context, R.drawable.ic_stat_bus)?.mutate()?.let { bus ->
                bus.setTint(surface)
                android.graphics.drawable.LayerDrawable(arrayOf(dot(primary, surface, 30, map.context.resources.displayMetrics.density), bus)).apply {
                    val inset = (7 * map.context.resources.displayMetrics.density).toInt()
                    setLayerInset(1, inset, inset, inset, inset)
                }
            }
        })
    }
    // People sharing with each other by a code: a mint dot with their name.
    others.forEachIndexed { i, (name, pos) ->
        map.overlays.add(Marker(map).apply {
            id = "pr:person$i"
            position = GeoPoint(pos.lat, pos.lon)
            title = name
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = dot(0xFF6EE7A8.toInt(), surface, 22, map.context.resources.displayMetrics.density)
        })
    }
    map.invalidate()
}

private fun dot(color: Int, ring: Int, sizeDp: Int, density: Float) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
    setStroke((2 * density).toInt(), ring)
    val px = (sizeDp * density).toInt()
    setSize(px, px)
}
