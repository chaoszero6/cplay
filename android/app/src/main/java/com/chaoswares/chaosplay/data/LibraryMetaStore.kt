package com.chaoswares.chaosplay.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.metaDataStore by preferencesDataStore(name = "chaosplay_library_meta")

/**
 * Favourites, hidden videos and playlists (spec sections 4.1 and 4.7).
 *
 * Ids are stored as comma separated strings so the format stays simple and
 * testable; on-device this is a few hundred longs at most.
 */
class LibraryMetaStore(private val context: Context) {

    private object Keys {
        val FAVOURITES = stringPreferencesKey("favourite_ids")
        val HIDDEN = stringPreferencesKey("hidden_ids")
        val PLAYLISTS = stringPreferencesKey("playlists")
    }

    val favouriteIds: Flow<Set<Long>> = context.metaDataStore.data
        .map { parseIds(it[Keys.FAVOURITES] ?: "") }

    val hiddenIds: Flow<Set<Long>> = context.metaDataStore.data
        .map { parseIds(it[Keys.HIDDEN] ?: "") }

    val playlistsRaw: Flow<String> = context.metaDataStore.data
        .map { it[Keys.PLAYLISTS] ?: "" }

    suspend fun toggleFavourite(id: Long) {
        context.metaDataStore.edit { prefs ->
            val current = parseIds(prefs[Keys.FAVOURITES] ?: "")
            prefs[Keys.FAVOURITES] = encodeIds(
                if (id in current) current - id else current + id
            )
        }
    }

    suspend fun hide(id: Long) {
        context.metaDataStore.edit { prefs ->
            val current = parseIds(prefs[Keys.HIDDEN] ?: "")
            prefs[Keys.HIDDEN] = encodeIds(current + id)
        }
    }

    suspend fun unhideAll() {
        context.metaDataStore.edit { prefs -> prefs[Keys.HIDDEN] = "" }
    }

    suspend fun unhide(id: Long) {
        context.metaDataStore.edit { prefs ->
            val current = parseIds(prefs[Keys.HIDDEN] ?: "")
            prefs[Keys.HIDDEN] = encodeIds(current - id)
        }
    }

    val playlists: Flow<List<Playlist>> = playlistsRaw.map { PlaylistCodec.decode(it) }

    /** Applies a playlist edit atomically (read, transform, write in one transaction). */
    suspend fun updatePlaylists(transform: (List<Playlist>) -> List<Playlist>) {
        context.metaDataStore.edit { prefs ->
            val current = PlaylistCodec.decode(prefs[Keys.PLAYLISTS] ?: "")
            prefs[Keys.PLAYLISTS] = PlaylistCodec.encode(transform(current))
        }
    }

    companion object {
        /** Parses "1,2,3" into ids, ignoring anything unparseable. */
        fun parseIds(raw: String): Set<Long> {
            if (raw.isBlank()) return emptySet()
            return raw.split(',')
                .mapNotNull { it.trim().toLongOrNull() }
                .toSet()
        }

        /** Encodes ids as a stable, sorted comma separated string. */
        fun encodeIds(ids: Set<Long>): String = ids.sorted().joinToString(",")
    }
}
