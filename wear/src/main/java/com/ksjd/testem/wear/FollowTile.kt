package com.ksjd.testem.wear

import androidx.concurrent.futures.ResolvableFuture
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/**
 * A tile with the followed bus: line, destination and when it leaves (a time, so it stays right between
 * updates; the ticking countdown is the ongoing activity's). Tap: the watch app.
 */
class FollowTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val f = FollowStore.current(this)
        val white = argb(0xFFFFFFFF.toInt())
        val dim = argb(0xFFB8BEC6.toInt())
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        if (f == null) {
            column.addContent(text(getString(R.string.nothing_followed), Typography.TYPOGRAPHY_CAPTION1, dim, 3))
        } else {
            column.addContent(text(getString(R.string.line_to, f.line, f.destination), Typography.TYPOGRAPHY_CAPTION1, argb(ThemeStore.current(this).primary), 2))
            // The time in the delay's colour (green on time, orange, red), white when only the timetable is known.
            column.addContent(text(clock(f.targetMs), Typography.TYPOGRAPHY_DISPLAY2, delayColor(f.delaySeconds)?.let { argb(it) } ?: white, 1))
            column.addContent(text(f.caption, Typography.TYPOGRAPHY_CAPTION2, dim, 2))
            if (f.delay.isNotBlank()) column.addContent(text(f.delay, Typography.TYPOGRAPHY_CAPTION2, white, 1))
        }
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(MainActivity::class.java.name).build())
                    .build()
            )
            .build()
        val root = LayoutElementBuilders.Box.Builder()
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .addContent(column.build())
            .build()
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES)
            .setFreshnessIntervalMillis(60_000)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
        return ResolvableFuture.create<TileBuilders.Tile>().apply { set(tile) }
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        ResolvableFuture.create<ResourceBuilders.Resources>().apply { set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES).build()) }

    private fun text(value: String, typography: Int, color: androidx.wear.protolayout.ColorBuilders.ColorProp, lines: Int) =
        Text.Builder(this, value).setTypography(typography).setColor(color).setMaxLines(lines).build()

    private companion object {
        const val RESOURCES = "1"
    }
}

/** One line of a tile: its text, size, line count, and colour (null: white for big text, grey for small). */
internal data class TileLine(val text: String, val typography: Int, val maxLines: Int = 1, val color: Int? = null)

/** Shared by the tiles: a column of texts; a tap opens the watch app (with [startTrip]: starts that trip on the phone). */
internal fun TileService.tile(lines: List<TileLine>, startTrip: String? = null): ListenableFuture<TileBuilders.Tile> {
    val column = LayoutElementBuilders.Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
    lines.forEach { l ->
        val small = l.typography == Typography.TYPOGRAPHY_CAPTION2 || l.typography == Typography.TYPOGRAPHY_CAPTION1
        val color = l.color ?: if (small) 0xFFB8BEC6.toInt() else 0xFFFFFFFF.toInt()
        column.addContent(Text.Builder(this, l.text).setTypography(l.typography).setColor(argb(color)).setMaxLines(l.maxLines).build())
    }
    val activity = ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(MainActivity::class.java.name)
    if (startTrip != null) activity.addKeyToExtraMapping(EXTRA_START, ActionBuilders.AndroidStringExtra.Builder().setValue(startTrip).build())
    val open = ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(
        ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build()
    ).build()
    val root = LayoutElementBuilders.Box.Builder().setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
        .addContent(column.build()).build()
    val tile = TileBuilders.Tile.Builder().setResourcesVersion("1").setFreshnessIntervalMillis(60_000)
        .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root)).build()
    return ResolvableFuture.create<TileBuilders.Tile>().apply { set(tile) }
}

/** MainActivity extra from a tile: start this trip ("h:…", "j:…", "r:…") on the phone. */
const val EXTRA_START = "start"

internal fun emptyResources(): ListenableFuture<ResourceBuilders.Resources> =
    ResolvableFuture.create<ResourceBuilders.Resources>().apply { set(ResourceBuilders.Resources.Builder().setVersion("1").build()) }

/** The phone's home data is older than a minute: ask for fresh (it updates the tile when it comes). */
internal fun refreshIfOld(context: android.content.Context, home: WatchHome?) {
    if (home == null || System.currentTimeMillis() - home.updatedMs > 60_000) requestHome(context)
}

/** New data from the phone: every tile and complication of this app redraws. */
fun refreshTiles(context: android.content.Context) {
    TileService.getUpdater(context).apply {
        listOf(FollowTile::class.java, FavouritesTile::class.java, CreditTile::class.java, HomeTile::class.java).forEach { requestUpdate(it) }
    }
    listOf(CountdownComplication::class.java, CreditComplication::class.java, NextDepartureComplication::class.java, ReportComplication::class.java)
        .forEach { runCatching {
            androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
                .create(context, android.content.ComponentName(context, it)).requestUpdateAll()
        } }
}

/** Next departures at the first favourite stop (and the second's next one), from the phone. */
class FavouritesTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val home = HomeStore.current(this)
        refreshIfOld(this, home)
        val boards = home?.favourites.orEmpty()
        if (boards.isEmpty()) return tile(listOf(TileLine(getString(R.string.no_favourites), Typography.TYPOGRAPHY_CAPTION1, 3)))
        val now = System.currentTimeMillis()
        val accent = ThemeStore.current(this).primary
        val lines = buildList {
            boards.take(2).forEachIndexed { i, b ->
                add(TileLine(b.name.substringAfter(", "), Typography.TYPOGRAPHY_CAPTION1, color = accent))
                b.departures.filter { it.atMs > now - 30_000 }.take(if (i == 0) 3 else 1).forEach { d ->
                    add(TileLine("${d.line} ${d.destination.substringAfter(", ")}  ${countdown(this@FavouritesTile, d.atMs, now)}", Typography.TYPOGRAPHY_BODY2))
                }
            }
        }
        return tile(lines)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) = emptyResources()
}

/** SAD Zvolen and IDS BBSK credit, from the phone (Account → Watch can hide it). */
class CreditTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val home = HomeStore.current(this)
        refreshIfOld(this, home)
        val lines = buildList {
            add(TileLine(getString(R.string.credit), Typography.TYPOGRAPHY_CAPTION1, color = ThemeStore.current(this@CreditTile).primary))
            home?.creditSadzv?.let { add(TileLine("SAD Zvolen", Typography.TYPOGRAPHY_CAPTION2)); add(TileLine(money(it), Typography.TYPOGRAPHY_TITLE1)) }
            home?.creditIds?.let { add(TileLine("IDS BBSK", Typography.TYPOGRAPHY_CAPTION2)); add(TileLine(money(it), Typography.TYPOGRAPHY_TITLE1)) }
            if (home?.creditSadzv == null && home?.creditIds == null) add(TileLine(getString(R.string.no_credit), Typography.TYPOGRAPHY_CAPTION2, 3))
        }
        return tile(lines)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) = emptyResources()
}

/** "Next bus home": the next connection on the route home set on the phone; a tap starts following it there. */
class HomeTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val home = HomeStore.current(this)
        refreshIfOld(this, home)
        val trip = home?.homeTrip?.takeIf { it.atMs == 0L || it.atMs > System.currentTimeMillis() - 60_000 }
            ?: return tile(listOf(
                TileLine(getString(R.string.home_title), Typography.TYPOGRAPHY_CAPTION1, color = ThemeStore.current(this).primary),
                TileLine(getString(if (home?.homeTrip == null) R.string.home_not_set else R.string.updating), Typography.TYPOGRAPHY_CAPTION2, 3)
            ))
        val now = System.currentTimeMillis()
        return tile(listOf(
            TileLine(getString(R.string.home_title), Typography.TYPOGRAPHY_CAPTION1, color = ThemeStore.current(this).primary),
            TileLine(trip.lines, Typography.TYPOGRAPHY_TITLE3),
            TileLine(clock(trip.atMs), Typography.TYPOGRAPHY_DISPLAY2),
            TileLine(countdown(this, trip.atMs, now) + " · " + trip.sub, Typography.TYPOGRAPHY_CAPTION2, 2),
            TileLine(getString(R.string.tap_to_follow), Typography.TYPOGRAPHY_CAPTION2)
        ), startTrip = trip.id)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) = emptyResources()
}
