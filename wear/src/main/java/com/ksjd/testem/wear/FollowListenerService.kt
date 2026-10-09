package com.ksjd.testem.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/** The phone's followed bus arrives here (Wearable Data Layer), also while the watch app is closed. */
class FollowListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            val map = if (event.type == DataEvent.TYPE_DELETED) null else DataMapItem.fromDataItem(event.dataItem).dataMap
            when (event.dataItem.uri.path) {
                FOLLOW_PATH -> FollowStore.update(this, map)
                HOME_PATH -> map?.let { HomeStore.update(this, it) }
            }
        }
    }
}
