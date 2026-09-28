package com.fersaiyan.cyanbridge.media

import android.content.Context
import org.json.JSONArray

internal class HeyCyanMediaManifestSnapshotStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(profileMacAddress: String): Set<HeyCyanMediaManifestItem>? = runCatching {
        val serializedItems = preferences.getString(snapshotKey(profileMacAddress), null) ?: return null
        JSONArray(serializedItems)
            .let { items ->
                buildSet {
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        add(
                            HeyCyanMediaManifestItem(
                                remoteFileName = item.getString("name"),
                                type = HeyCyanMediaType.valueOf(item.getString("type")),
                            ),
                        )
                    }
                }
            }
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun save(profileMacAddress: String, items: Set<HeyCyanMediaManifestItem>) {
        val serializedItems = JSONArray().apply {
            items.forEach { item ->
                put(
                    org.json.JSONObject().apply {
                        put("name", item.remoteFileName)
                        put("type", item.type.name)
                    },
                )
            }
        }
        preferences.edit().putString(snapshotKey(profileMacAddress), serializedItems.toString()).apply()
    }

    private fun snapshotKey(profileMacAddress: String) = "snapshot_$profileMacAddress"

    private companion object {
        const val PREFERENCES = "heycyan_media_manifest_snapshots"
    }
}