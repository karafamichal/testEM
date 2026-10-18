package com.ksjd.testem.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.updateAll
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.unit.ColorProvider as FixedColor
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ksjd.testem.CredentialsManager
import com.ksjd.testem.MainActivity
import com.ksjd.testem.R

/**
 * 2.17.0 "Take me home": Home and Work on the home screen. A tap opens the planner from the stop nearest you, now
 * (the same as the app's launcher shortcuts). Not set yet: it opens the planner to set them.
 */
class PlacesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val colors = CardColors.of(context)
        val background = colors.gradient()
        val prefs = CredentialsManager(context)
        val places = listOf("home" to R.string.place_home, "work" to R.string.place_work).map { (k, label) ->
            Triple(k, context.getString(label), prefs.getPlace(k)?.selectedText)
        }
        provideContent { Content(context, places, colors, background) }
    }

    @Composable
    private fun Content(context: Context, places: List<Triple<String, String, String?>>, colors: CardColors, background: android.graphics.Bitmap) {
        val on = FixedColor(colors.on)
        val onDim = FixedColor(colors.on.copy(alpha = 0.8f))
        Box(GlanceModifier.fillMaxSize().cornerRadius(24.dp)) {
            Image(ImageProvider(background), null, contentScale = ContentScale.FillBounds, modifier = GlanceModifier.fillMaxSize())
            Row(GlanceModifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                places.forEachIndexed { i, (kind, label, stop) ->
                    if (i > 0) Spacer(GlanceModifier.width(8.dp))
                    val open = actionStartActivity(
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("feature", kind)
                            .setData(android.net.Uri.parse("planride://place/$kind")) // a distinct intent per button
                    )
                    Column(
                        GlanceModifier.defaultWeight().fillMaxSize().cornerRadius(18.dp)
                            .background(FixedColor(colors.on.copy(alpha = 0.16f)))
                            .padding(horizontal = 12.dp, vertical = 8.dp).clickable(open),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, maxLines = 1, style = TextStyle(color = on, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                        Text(stop ?: context.getString(R.string.place_not_set), maxLines = 2,
                            style = TextStyle(color = onDim, fontSize = 12.sp), modifier = GlanceModifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    companion object {
        suspend fun refresh(context: Context) {
            runCatching { PlacesWidget().updateAll(context) }
        }
    }
}

class PlacesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = PlacesWidget()
}
