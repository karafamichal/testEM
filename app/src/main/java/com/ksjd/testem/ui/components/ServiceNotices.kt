package com.ksjd.testem.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ksjd.testem.R
import com.ksjd.testem.hub.HubClient
import com.ksjd.testem.hub.ServiceNotice
import com.ksjd.testem.ui.theme.LocalTransitColors
import kotlinx.coroutines.delay

/**
 * Service notices from emHUB (detours, strikes) on Home, in the app's language (the other one if only that was written),
 * looked up every 5 minutes. One the rider hid stays hidden; hidden ones that ended are forgotten.
 */
@Composable
fun ServiceNotices() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("notices", android.content.Context.MODE_PRIVATE) }
    var notices by remember { mutableStateOf<List<ServiceNotice>>(emptyList()) }
    var hidden by remember { mutableStateOf(prefs.getStringSet("hidden", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { HubClient.notices() }.onSuccess { list ->
                notices = list
                hidden = hidden.filter { id -> list.any { it.id == id } }.toSet()
                prefs.edit().putStringSet("hidden", hidden.map { it.toString() }.toSet()).apply()
            }
            delay(5 * 60_000L)
        }
    }
    val english = java.util.Locale.getDefault().language == "en"
    val colors = LocalTransitColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        notices.filter { it.id !in hidden }.forEach { n ->
            val title = if (english) n.titleEn.ifBlank { n.titleSk } else n.titleSk.ifBlank { n.titleEn }
            val body = if (english) n.bodyEn.ifBlank { n.bodySk } else n.bodySk.ifBlank { n.bodyEn }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (n.warning) colors.late.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyMedium)
                        val where = listOfNotNull(n.lines.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.notice_lines, it.joinToString(", ")) },
                            n.stops.takeIf { it.isNotEmpty() }?.joinToString("; "))
                        if (where.isNotEmpty()) Text(where.joinToString(". "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        hidden = hidden + n.id
                        prefs.edit().putStringSet("hidden", hidden.map { it.toString() }.toSet()).apply()
                    }) { Icon(Icons.Outlined.Close, stringResource(R.string.notice_hide), Modifier.size(20.dp)) }
                }
            }
        }
    }
}
