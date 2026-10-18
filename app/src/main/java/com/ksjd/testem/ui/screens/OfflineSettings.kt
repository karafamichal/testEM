package com.ksjd.testem.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ksjd.testem.BuildConfig
import com.ksjd.testem.R
import com.ksjd.testem.offline.OfflineTimetables
import com.ksjd.testem.ui.components.GroupDivider
import com.ksjd.testem.ui.components.ListGroup
import com.ksjd.testem.ui.components.ListRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polygon

/**
 * 2.17.0: timetables kept on the phone, by region (kraj): tap a region on the map (or its row) to keep it or let it go.
 * Kept regions update by themselves once a day; with no connection the planner searches them.
 */
@Composable
fun OfflineSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kept by OfflineTimetables.kept.collectAsState()
    var regions by remember { mutableStateOf<List<OfflineTimetables.Region>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { OfflineTimetables.regions().second } }
            .onSuccess { regions = it }.onFailure { failed = true }
    }
    val toggle: (String) -> Unit = { id ->
        if (id !in busy) {
            busy = busy + id
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { if (id in OfflineTimetables.kept.value) OfflineTimetables.remove(id) else OfflineTimetables.download(id) }.isSuccess
                }
                busy = busy - id
                OfflineTimetables.schedule(context)
                if (!ok) android.widget.Toast.makeText(context, R.string.offline_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ListGroup brings its own side margin; the rest gets the same 16 dp.
    val side = Modifier.padding(horizontal = 16.dp)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.offline_intro), modifier = side, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val list = regions
        when {
            failed -> Text(stringResource(R.string.offline_unavailable), style = MaterialTheme.typography.bodyMedium, modifier = side)
            list == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            else -> {
                Box(side) { RegionMap(list, kept.keys, busy, toggle) }
                ListGroup {
                    list.forEachIndexed { i, r ->
                        if (i > 0) GroupDivider()
                        val on = r.id in kept
                        ListRow(
                            r.name,
                            subtitle = when {
                                r.id in busy -> stringResource(if (on) R.string.offline_removing else R.string.offline_downloading)
                                on -> stringResource(R.string.offline_kept, OfflineTimetables.sizeOf(r.id) / 1024 / 1024.0)
                                else -> stringResource(R.string.offline_not_kept)
                            },
                            onClick = { toggle(r.id) },
                            trailing = {
                                if (r.id in busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else if (on) androidx.compose.material3.OutlinedButton(onClick = { toggle(r.id) }) { Text(stringResource(R.string.offline_remove)) }
                                else androidx.compose.material3.FilledTonalButton(onClick = { toggle(r.id) }) { Text(stringResource(R.string.offline_download)) }
                            }
                        )
                    }
                }
                Text(stringResource(R.string.offline_auto), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = side)
            }
        }
    }
}

/** Slovakia with its regions: kept ones filled, a tap keeps or removes the one tapped (the smallest that holds the spot). */
@Composable
private fun RegionMap(regions: List<OfflineTimetables.Region>, kept: Set<String>, busy: Set<String>, onTap: (String) -> Unit) {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val outline = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val map = remember {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
            userAgentValue = BuildConfig.APPLICATION_ID
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            isFlingEnabled = false
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            // Only taps: the page scrolls, the map stays where it is.
            setOnTouchListener { _, e -> e.action == android.view.MotionEvent.ACTION_MOVE }
        }
    }
    DisposableEffect(Unit) { onDispose { map.onDetach() } }
    AndroidView({ map }, Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp))) { view ->
        view.overlays.clear()
        regions.forEach { r ->
            val on = r.id in kept
            view.overlays.add(Polygon(view).apply {
                points = listOf(GeoPoint(r.south, r.west), GeoPoint(r.south, r.east), GeoPoint(r.north, r.east), GeoPoint(r.north, r.west), GeoPoint(r.south, r.west))
                fillPaint.color = if (on) (primary and 0x00FFFFFF) or 0x55000000 else 0x00000000
                outlinePaint.color = if (on || r.id in busy) primary else outline
                outlinePaint.strokeWidth = if (on) 6f else 3f
                setOnClickListener { _, _, _ -> false } // taps go to the map's own handler below
            })
        }
        view.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                regions.filter { p.latitude in it.south..it.north && p.longitude in it.west..it.east }
                    .minByOrNull { (it.north - it.south) * (it.east - it.west) }?.let { onTap(it.id) }
                return true
            }
            override fun longPressHelper(p: GeoPoint) = false
        }))
        view.overlays.add(CopyrightOverlay(context))
        view.invalidate()
        view.post { view.zoomToBoundingBox(BoundingBox(49.65, 22.6, 47.7, 16.8), false, 12) }
    }
}
