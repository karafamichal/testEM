package com.ksjd.testem.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import android.content.Intent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** The followed bus on the wrist (EM-0041): countdown and ride as a ring, next stops, all stops, transfers, alerts. */
class MainActivity : ComponentActivity() {
    /** Always-on display: the app stays on screen, dimmed, showing only the countdown (updated once a minute). */
    private val ambient = MutableStateFlow(false)
    private val ambientTick = MutableStateFlow(System.currentTimeMillis())
    private val ambientObserver = AmbientLifecycleObserver(this, object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            ambientTick.value = System.currentTimeMillis()
            ambient.value = true
        }
        override fun onExitAmbient() { ambient.value = false }
        override fun onUpdateAmbient() { ambientTick.value = System.currentTimeMillis() }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        FollowStore.current(this)
        HomeStore.current(this)
        ThemeStore.current(this)
        startFromTile(intent)
        if (!(BuildConfig.DEBUG && intent.getBooleanExtra("demo", false))) requestHome(this)
        // Debug builds only: `adb shell am start -n com.ksjd.testem/.wear.MainActivity --ez demo true` shows a sample bus
        // (no phone needed), to check the screens, the watch-face countdown and the tile.
        val demo = BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)
        if (demo) {
            FollowStore.update(this, demoFollow())
            HomeStore.update(this, demoHome())
        }
        // Whatever the phone has now, in case an update came while the watch was off.
        if (!demo) Wearable.getDataClient(this).dataItems.addOnSuccessListener { items ->
            val item = items.firstOrNull { it.uri.path == FOLLOW_PATH }
            FollowStore.update(this, item?.let { DataMapItem.fromDataItem(it).dataMap })
            items.release()
        }
        // Wear OS 4+: the ongoing countdown on the watch face is a notification, which needs this permission.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            val theme by ThemeStore.state.collectAsState()
            val dimmed by ambient.collectAsState()
            val tick by ambientTick.collectAsState()
            val nav = rememberSwipeDismissableNavController()
            MaterialTheme(colors = colorsOf(theme)) {
                if (dimmed) AmbientScreen(tick) else WatchApp(nav)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startFromTile(intent)
    }

    /** "Next bus home" tile tapped: follow it on the phone. */
    private fun startFromTile(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_START) ?: return
        intent.removeExtra(EXTRA_START)
        startOnPhone(this, id)
    }
}

/** The phone app's theme on the watch's black screen (EM-0042): buttons and highlights in its colours. */
private fun colorsOf(t: WatchTheme): Colors {
    val primary = Color(t.primary)
    val accent = Color(t.tertiary)
    val on = { c: Color -> if (c.luminance() > 0.45f) Color.Black else Color.White }
    return Colors(
        primary = primary, primaryVariant = Color(t.secondary), onPrimary = on(primary),
        secondary = accent, secondaryVariant = accent, onSecondary = on(accent)
    )
}

/** Start a trip on the phone ("h:", "j:", "r:"), saying whether the phone got it. */
private fun startOnPhone(context: Context, id: String) = sendToPhone(context, START_PATH, id) { ok ->
    Toast.makeText(context, if (ok) R.string.starting else R.string.report_no_phone, Toast.LENGTH_LONG).show()
}

/**
 * The always-on view: only the line and the countdown, in grey on black (little lit, little burn-in), refreshed
 * once a minute by the system.
 */
@Composable
private fun AmbientScreen(now: Long) {
    val context = LocalContext.current
    val follow by FollowStore.state.collectAsState()
    Column(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val f = follow
        if (f == null) {
            Text(stringResource(R.string.app_name), color = AmbientGrey, style = MaterialTheme.typography.caption1)
        } else {
            Text(f.line, color = AmbientGrey, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(countdown(context, f.targetMs, now), color = AmbientGrey, fontSize = 40.sp)
            Text(f.destination, color = AmbientGrey.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.caption2, modifier = Modifier.padding(horizontal = 32.dp))
        }
    }
}

private val AmbientGrey = Color(0xFFB0B0B0)

@Composable
private fun WatchApp(nav: androidx.navigation.NavHostController) {
    val context = LocalContext.current
    val follow by FollowStore.state.collectAsState()
    val home by HomeStore.state.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    // Swipe right to go back, as everywhere on Wear OS.
    SwipeDismissableNavHost(navController = nav, startDestination = "main") {
        composable("main") { MainScreen(follow, home, now, onOpen = { nav.navigate(it) }) }
        composable("trips") { TripsScreen(home) }
        composable("favourites") { BoardsScreen(stringResource(R.string.favourites), home?.favourites.orEmpty(), now, emptyText = stringResource(R.string.no_favourites)) }
        composable("nearby") {
            // Departures near the phone: it locates itself, so the watch's GPS stays off.
            LaunchedEffect(Unit) { requestHome(context, nearby = true) }
            BoardsScreen(
                stringResource(R.string.nearby), home?.nearby.orEmpty(), now,
                emptyText = stringResource(if (home?.nearbyLocated == false) R.string.nearby_no_location else R.string.updating)
            )
        }
        composable("stops") { follow?.let { StopsScreen(it, now) } }
        composable("transfers") { follow?.let { TransfersScreen(it) } }
        composable("alerts") { follow?.let { AlertsScreen(it) } }
    }
}

@Composable
private fun MainScreen(f: WatchFollow?, home: WatchHome?, now: Long, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        // Your part of the line, as on the phone's card: the ring fills as the bus goes from your stop to where you get off.
        // Green, orange or red by how late the bus is; the theme's colour while only the timetable is known.
        if (f != null && f.hops > 0) {
            CircularProgressIndicator(
                progress = (f.position / f.hops).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxSize().padding(3.dp),
                strokeWidth = 5.dp,
                indicatorColor = delayColor(f.delaySeconds)?.color() ?: MaterialTheme.colors.primary,
                trackColor = MaterialTheme.colors.onBackground.copy(alpha = 0.15f)
            )
        }
        // Opens at the top (the header), not at the list's default second item.
        ScalingLazyColumn(Modifier.fillMaxSize(), state = rememberScalingLazyListState(initialCenterItemIndex = 0),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (f == null) {
                item {
                    Text(stringResource(R.string.nothing_followed), textAlign = TextAlign.Center, style = MaterialTheme.typography.body2,
                        modifier = Modifier.padding(horizontal = edge(), vertical = 16.dp))
                }
            } else {
                item { Header(f, now) }
                // The phone notification's buttons ("Bus is here", "At <stop>"…), pressed on the phone's behalf.
                f.buttons.forEachIndexed { index, label ->
                    item {
                        Chip(
                            onClick = { sendToPhone(context, ACTION_PATH, index.toString()) },
                            label = { Text(label, maxLines = 2) },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        )
                    }
                }
                // The next stops right here; all of them one tap away.
                val upcoming = f.stops.indices.filter { it >= f.nextIndex }.take(f.nextStops)
                if (upcoming.isNotEmpty()) {
                    item { ListHeader { Text(stringResource(R.string.next_stops)) } }
                    upcoming.forEach { i -> item { StopRow(f, i) } }
                }
                if (f.stops.isNotEmpty()) item { NavChip(stringResource(R.string.all_stops)) { onOpen("stops") } }
                if (f.legs.isNotEmpty()) item { NavChip(stringResource(R.string.transfers_count, f.legs.size)) { onOpen("transfers") } }
                if (f.alerts.isNotEmpty()) item { NavChip(stringResource(R.string.alerts_count, f.alerts.size)) { onOpen("alerts") } }
                item {
                    CompactChip(
                        onClick = { stopOnPhone(context) },
                        label = { Text(stringResource(R.string.stop_following)) },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            // Always there: trips to start, departures, credit (from the phone).
            item { ListHeader { Text(stringResource(R.string.more)) } }
            // "Next bus home" (the route set on the phone): one tap follows it.
            home?.homeTrip?.takeIf { it.atMs > now - 60_000 }?.let { trip ->
                item {
                    Chip(
                        onClick = { startOnPhone(context, trip.id) },
                        label = { Text(stringResource(R.string.home_chip, trip.lines, clock(trip.atMs)), maxLines = 1) },
                        secondaryLabel = { Text(countdown(context, trip.atMs, now) + " · " + trip.sub, maxLines = 1) },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    )
                }
            }
            item { NavChip(stringResource(R.string.my_trips)) { onOpen("trips") } }
            item { NavChip(stringResource(R.string.favourites)) { onOpen("favourites") } }
            item { NavChip(stringResource(R.string.nearby)) { onOpen("nearby") } }
            if (home?.creditSadzv != null || home?.creditIds != null) {
                item {
                    InfoCard(
                        stringResource(R.string.credit),
                        listOfNotNull(home.creditSadzv?.let { "SAD Zvolen  ${money(it)}" }, home.creditIds?.let { "IDS BBSK  ${money(it)}" }).joinToString("\n")
                    )
                }
            }
            // The watch's logs go to the phone, which sends the report (with the phone's own logs too).
            item {
                CompactChip(
                    onClick = { sendLogsToPhone(context) },
                    label = { Text(stringResource(R.string.report_problem)) },
                    colors = ChipDefaults.childChipColors(),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun Header(f: WatchFollow, now: Long) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = edge()), horizontalAlignment = Alignment.CenterHorizontally) {
        // City buses blue, regional orange: which bus it is, at a glance.
        Text(
            f.line,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.background(lineColor(f.line).color(), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 1.dp)
        )
        // Two lines: on a round screen a long destination ("Banská Bystrica, Roosevelt.nem.") doesn't fit one (EM-0044).
        Text(f.destination, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.caption1, modifier = Modifier.padding(top = 4.dp))
        Text(countdown(context, f.targetMs, now), fontSize = 34.sp, fontWeight = FontWeight.Bold,
            color = delayColor(f.delaySeconds)?.color() ?: MaterialTheme.colors.onBackground)
        Text(f.caption, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant)
        if (f.delay.isNotBlank()) {
            Text(f.delay, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.secondary, modifier = Modifier.padding(top = 2.dp))
        }
        if (f.catchLine.isNotBlank()) {
            Text(f.catchLine, maxLines = 2, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption3, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** A stop: time, name, and whether you get on or off there; passed stops dimmed, the next one bold. */
@Composable
private fun StopRow(f: WatchFollow, i: Int) {
    val stop = f.stops[i]
    val next = i == f.nextIndex
    val mark = when (i) {
        f.boardIndex -> stringResource(R.string.you_board)
        f.alightIndex -> stringResource(R.string.you_alight)
        else -> ""
    }
    val color = if (stop.passed) MaterialTheme.colors.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colors.onSurface
    Row(Modifier.fillMaxWidth().padding(horizontal = edge(), vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            if (stop.atMs > 0) clock(stop.atMs) else "",
            color = if (next) MaterialTheme.colors.primary else color,
            fontWeight = if (next) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.caption1,
            modifier = Modifier.width(44.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(stop.name, color = color, fontWeight = if (next || mark.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.caption1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (mark.isNotEmpty()) Text(mark, color = MaterialTheme.colors.secondary, style = MaterialTheme.typography.caption3)
        }
    }
}

/**
 * Side margin for text on the round screen (about 12 % of its width, as Wear OS recommends): a full-width line near
 * the top or bottom of the list is wider than the circle there and gets cut (EM-0044).
 */
@Composable
private fun edge() = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.12f).dp

@Composable
private fun NavChip(label: String, onClick: () -> Unit) {
    Chip(onClick = onClick, label = { Text(label) }, colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
}

/** Every stop of the bus, opened at the next one. */
@Composable
private fun StopsScreen(f: WatchFollow, now: Long) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = (f.nextIndex + 1).coerceAtMost(f.stops.size))
    ScalingLazyColumn(Modifier.fillMaxSize().background(Color.Black), state = state) {
        item { ListHeader { Text(stringResource(R.string.line_to, f.line, f.destination), textAlign = TextAlign.Center) } }
        f.stops.indices.forEach { i -> item { StopRow(f, i) } }
    }
}

/** The journey's changes: where to get off this bus, then each next bus and when it leaves. */
@Composable
private fun TransfersScreen(f: WatchFollow) {
    ScalingLazyColumn(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = Alignment.CenterHorizontally) {
        item { ListHeader { Text(stringResource(R.string.transfers)) } }
        f.stops.getOrNull(f.alightIndex)?.let { stop ->
            item { InfoCard(stringResource(R.string.line_to, f.line, f.destination), stringResource(R.string.get_off_at, stop.name, clock(stop.atMs))) }
        }
        f.legs.forEach { leg ->
            item {
                InfoCard(
                    stringResource(R.string.line_to, leg.line, leg.destination),
                    listOfNotNull(
                        leg.from.ifBlank { null }?.let { stringResource(R.string.change_at, it) },
                        leg.atMs.takeIf { it > 0 }?.let { stringResource(R.string.departs_at, clock(it)) }
                    ).joinToString("\n")
                )
            }
        }
    }
}

/** What the phone announced on this trip (the notifications themselves came to the watch once, from the phone). */
@Composable
private fun AlertsScreen(f: WatchFollow) {
    ScalingLazyColumn(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = Alignment.CenterHorizontally) {
        item { ListHeader { Text(stringResource(R.string.alerts)) } }
        f.alerts.asReversed().forEach { a -> item { InfoCard(a.title, "${clock(a.atMs)}  ${a.body}") } }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)
            .background(MaterialTheme.colors.surface, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.caption1)
        if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
    }
}

/** Starred journeys and regular rides: a tap starts following on the phone (and so here). */
@Composable
private fun TripsScreen(home: WatchHome?) {
    val context = LocalContext.current
    ScalingLazyColumn(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = Alignment.CenterHorizontally) {
        item { ListHeader { Text(stringResource(R.string.my_trips)) } }
        val trips = home?.trips.orEmpty()
        if (trips.isEmpty()) item { Text(stringResource(R.string.no_trips), textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2, modifier = Modifier.padding(horizontal = edge(), vertical = 16.dp)) }
        trips.forEach { t ->
            item {
                Chip(
                    onClick = { startOnPhone(context, t.id) },
                    label = { Text(if (t.id.startsWith("r:")) clock(t.atMs) + "  " + t.title else t.title, maxLines = 2) },
                    secondaryLabel = { Text(listOf(t.sub, t.lines).filter { it.isNotBlank() }.joinToString("\n"), maxLines = 2) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                )
            }
        }
    }
}

/** Departure boards (favourite or nearby stops): line, destination and how soon, live ones marked. */
@Composable
private fun BoardsScreen(title: String, boards: List<WatchHome.Board>, now: Long, emptyText: String) {
    val context = LocalContext.current
    ScalingLazyColumn(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = Alignment.CenterHorizontally) {
        item { ListHeader { Text(title) } }
        if (boards.isEmpty()) item { Text(emptyText, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2, modifier = Modifier.padding(horizontal = edge(), vertical = 16.dp)) }
        boards.forEach { b ->
            item {
                Text(
                    b.name + (if (b.meters >= 0) "  ${b.meters} m" else ""),
                    style = MaterialTheme.typography.caption1, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = edge(), vertical = 4.dp)
                )
            }
            if (b.departures.isEmpty()) item { Text("—", style = MaterialTheme.typography.caption2) }
            b.departures.forEach { d ->
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = edge(), vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.line, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.caption1, modifier = Modifier.width(40.dp))
                        Text(d.destination.substringAfter(", "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.caption2, modifier = Modifier.weight(1f))
                        Text(
                            countdown(context, d.atMs, now),
                            color = if (d.live) MaterialTheme.colors.secondary else MaterialTheme.colors.onSurface,
                            style = MaterialTheme.typography.caption1
                        )
                    }
                }
            }
        }
    }
}

/** Following runs on the phone: ask it to stop (the watch hears back through the data item going away). */
private fun stopOnPhone(context: Context) {
    Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
        nodes.forEach { Wearable.getMessageClient(context).sendMessage(it.id, STOP_PATH, null) }
    }
}

/** "Report a problem": the watch's logs to the phone, which then offers the bug report with them attached. */
private fun sendLogsToPhone(context: Context) {
    val bytes = WatchLogs.collect(context).toByteArray(Charsets.UTF_8)
    Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
        if (nodes.isEmpty()) {
            Toast.makeText(context, R.string.report_no_phone, Toast.LENGTH_LONG).show()
            return@addOnSuccessListener
        }
        nodes.forEach { Wearable.getMessageClient(context).sendMessage(it.id, LOG_PATH, bytes) }
        Toast.makeText(context, R.string.report_sent, Toast.LENGTH_LONG).show()
    }
}

private fun demoFollow() = DataMap().apply {
    val now = System.currentTimeMillis()
    putString("line", "60101"); putString("dest", "Zvolen, AS")
    putLong("target", now + 7 * 60_000L + 20_000L)
    putString("caption", "do odchodu zo zastávky Tajovského školy"); putString("delay", "o 2 min neskôr")
    putString("from", "Tajovského školy"); putString("to", "Zvolen, EUROPA SC")
    putInt("hops", 5); putFloat("position", 0f); putBoolean("live", true)
    val names = listOf("Banská Bystrica, Roosevelt.nem.", "Banská Bystrica, Tajovského školy", "Banská Bystrica, Úrad PV SR",
        "Banská Bystrica, Sládkovičova", "Banská Bystrica, Zvol.cesta TESCO", "Zvolen, Rákoš", "Zvolen, EUROPA SC", "Zvolen, AS")
    putDataMapArrayList("stops", ArrayList(names.mapIndexed { i, n ->
        DataMap().apply { putString("name", n); putLong("at", now + (i * 3 + 4) * 60_000L); putBoolean("passed", i == 0) }
    }))
    putInt("next", 1); putInt("board", 1); putInt("alight", 6)
    putString("lang", "sk")
    putStringArray("buttons", arrayOf("Autobus je tu"))
    putDataMapArrayList("legs", arrayListOf(DataMap().apply {
        putString("line", "5"); putString("dest", "Zvolen, Lipovec"); putString("from", "Zvolen, Margočinka"); putLong("at", now + 31 * 60_000L)
    }))
    putDataMapArrayList("alerts", arrayListOf(DataMap().apply {
        putLong("at", now - 60_000L); putString("title", "Linka 60101 príde o 5 min"); putString("body", "Pripravte sa na zastávke Tajovského školy.")
    }))
}

private fun demoHome() = DataMap().apply {
    val now = System.currentTimeMillis()
    putLong("updated", now); putString("lang", "sk"); putBoolean("nearbyLocated", true)
    putDataMapArrayList("trips", arrayListOf(
        DataMap().apply { putString("id", "j:demo"); putString("title", "17:47 – 18:41"); putString("sub", "Tajovského školy → Lipovec Potraviny"); putString("lines", "34, 60101, 5"); putLong("at", now + 20 * 60_000L) },
        DataMap().apply { putString("id", "r:demo"); putString("title", "Tajovského školy → Zvolen, AS"); putString("sub", ""); putString("lines", "60101"); putLong("at", now + 14 * 3600_000L) }
    ))
    putDouble("creditSadzv", 0.83); putDouble("creditIds", 0.15)
    fun board(name: String, meters: Int, vararg deps: Pair<String, Int>) = DataMap().apply {
        putString("name", name); putInt("meters", meters)
        putDataMapArrayList("deps", ArrayList(deps.mapIndexed { i, (line, min) ->
            DataMap().apply { putString("line", line); putString("dest", "Banská Bystrica, Národná"); putLong("at", now + min * 60_000L); putBoolean("live", i == 0) }
        }))
    }
    putDataMapArrayList("favourites", arrayListOf(board("Banská Bystrica, Tajovského školy", -1, "34" to 3, "60101" to 9, "7" to 12)))
    putDataMapArrayList("nearby", arrayListOf(board("Banská Bystrica, Tajovského park", 140, "5" to 2, "34" to 6), board("Banská Bystrica, Tajovského školy", 260, "60101" to 9)))
}
