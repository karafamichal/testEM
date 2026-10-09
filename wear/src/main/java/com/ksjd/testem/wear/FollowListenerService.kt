package com.ksjd.testem.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** "Leave now" for a starred journey's bus, sent by the phone (payload: title, a newline, body). */
const val LEAVE_PATH = "/plan-ride/leave"

/** The phone's followed bus arrives here (Wearable Data Layer), also while the watch app is closed. */
class FollowListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            val map = if (event.type == DataEvent.TYPE_DELETED) null else DataMapItem.fromDataItem(event.dataItem).dataMap
            when (event.dataItem.uri.path) {
                FOLLOW_PATH -> FollowStore.update(this, map)
                HOME_PATH -> map?.let { HomeStore.update(this, it) }
                THEME_PATH -> map?.let { ThemeStore.update(this, it) }
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != LEAVE_PATH) return
        val (title, body) = String(event.data ?: ByteArray(0), Charsets.UTF_8).split('\n', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        OngoingFollow.alert(this, OngoingFollow.Buzz.Leave, title, body)
    }
}
