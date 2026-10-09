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
            column.addContent(text(getString(R.string.line_to, f.line, f.destination), Typography.TYPOGRAPHY_CAPTION1, dim, 2))
            column.addContent(text(clock(f.targetMs), Typography.TYPOGRAPHY_DISPLAY2, white, 1))
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

/** Shared by the tiles: a column of texts, tapping it opens the watch app. */
internal fun TileService.tile(lines: List<Triple<String, Int, Int>>): ListenableFuture<TileBuilders.Tile> {
    val column = LayoutElementBuilders.Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
    lines.forEach { (text, typography, maxLines) ->
        val color = if (typography == Typography.TYPOGRAPHY_CAPTION2 || typography == Typography.TYPOGRAPHY_CAPTION1) 0xFFB8BEC6.toInt() else 0xFFFFFFFF.toInt()
        column.addContent(Text.Builder(this, text).setTypography(typography).setColor(argb(color)).setMaxLines(maxLines).build())
    }
    val open = ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(
        ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(MainActivity::class.java.name).build())
            .build()
    ).build()
    val root = LayoutElementBuilders.Box.Builder().setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
        .addContent(column.build()).build()
    val tile = TileBuilders.Tile.Builder().setResourcesVersion("1").setFreshnessIntervalMillis(60_000)
        .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root)).build()
    return ResolvableFuture.create<TileBuilders.Tile>().apply { set(tile) }
}

internal fun emptyResources(): ListenableFuture<ResourceBuilders.Resources> =
    ResolvableFuture.create<ResourceBuilders.Resources>().apply { set(ResourceBuilders.Resources.Builder().setVersion("1").build()) }

/** The phone's home data is older than a minute: ask for fresh (it updates the tile when it comes). */
private fun refreshIfOld(context: android.content.Context, home: WatchHome?) {
    if (home == null || System.currentTimeMillis() - home.updatedMs > 60_000) requestHome(context)
}

/** Next departures at the first favourite stop (and the second's next one), from the phone. */
class FavouritesTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val home = HomeStore.current(this)
        refreshIfOld(this, home)
        val boards = home?.favourites.orEmpty()
        if (boards.isEmpty()) return tile(listOf(Triple(getString(R.string.no_favourites), Typography.TYPOGRAPHY_CAPTION1, 3)))
        val now = System.currentTimeMillis()
        val lines = buildList {
            boards.take(2).forEachIndexed { i, b ->
                add(Triple(b.name.substringAfter(", "), Typography.TYPOGRAPHY_CAPTION1, 1))
                b.departures.take(if (i == 0) 3 else 1).forEach { d ->
                    add(Triple("${d.line} ${d.destination.substringAfter(", ")}  ${countdown(this@FavouritesTile, d.atMs, now)}", Typography.TYPOGRAPHY_BODY2, 1))
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
            add(Triple(getString(R.string.credit), Typography.TYPOGRAPHY_CAPTION1, 1))
            home?.creditSadzv?.let { add(Triple("SAD Zvolen", Typography.TYPOGRAPHY_CAPTION2, 1)); add(Triple(money(it), Typography.TYPOGRAPHY_TITLE1, 1)) }
            home?.creditIds?.let { add(Triple("IDS BBSK", Typography.TYPOGRAPHY_CAPTION2, 1)); add(Triple(money(it), Typography.TYPOGRAPHY_TITLE1, 1)) }
            if (home?.creditSadzv == null && home?.creditIds == null) add(Triple(getString(R.string.no_credit), Typography.TYPOGRAPHY_CAPTION2, 3))
        }
        return tile(lines)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) = emptyResources()
}
