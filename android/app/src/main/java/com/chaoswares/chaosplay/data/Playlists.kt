package com.chaoswares.chaosplay.data

import com.chaoswares.chaosplay.player.QueueItem
import com.chaoswares.chaosplay.util.FieldCodec

/** One playlist entry. [uri] is a content URI, a file path or a stream URL. */
data class PlaylistItem(
    val uri: String,
    val title: String,
    val durationMs: Long = 0L
) {
    fun toQueueItem(): QueueItem = QueueItem(uri, title, durationMs)
}

/** A user playlist, spec 4.7: "Playlists: create, reorder by drag, import/export M3U". */
data class Playlist(
    val id: String,
    val name: String,
    val items: List<PlaylistItem> = emptyList(),
    val createdAtSec: Long = 0L
) {
    val totalDurationMs: Long get() = items.sumOf { it.durationMs.coerceAtLeast(0L) }
}

/**
 * Playlist operations. Pure and immutable so the store, the screen and
 * the tests all agree on behaviour.
 */
object PlaylistRules {

    const val MAX_NAME_LENGTH = 60

    /** Trims and validates a name; null when unusable. */
    fun cleanName(raw: String): String? {
        val trimmed = raw.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isEmpty()) return null
        return trimmed.take(MAX_NAME_LENGTH)
    }

    /**
     * Makes a name unique among [existing] by appending " (2)", " (3)"...
     * Comparison ignores case, matching how people read the list.
     */
    fun uniqueName(name: String, existing: List<Playlist>, ignoreId: String? = null): String {
        val taken = existing.filter { it.id != ignoreId }.map { it.name.lowercase() }.toSet()
        if (name.lowercase() !in taken) return name
        var n = 2
        while ("$name ($n)".lowercase() in taken) n++
        return "$name ($n)"
    }

    fun create(
        playlists: List<Playlist>,
        rawName: String,
        id: String,
        nowSec: Long
    ): List<Playlist> {
        val name = cleanName(rawName) ?: return playlists
        return playlists + Playlist(id = id, name = uniqueName(name, playlists), createdAtSec = nowSec)
    }

    fun rename(playlists: List<Playlist>, id: String, rawName: String): List<Playlist> {
        val name = cleanName(rawName) ?: return playlists
        return playlists.map {
            if (it.id == id) it.copy(name = uniqueName(name, playlists, ignoreId = id)) else it
        }
    }

    fun delete(playlists: List<Playlist>, id: String): List<Playlist> =
        playlists.filterNot { it.id == id }

    /**
     * Adds items, skipping URIs already in the playlist so "Add to
     * playlist" twice does not create duplicates.
     */
    fun addItems(playlists: List<Playlist>, id: String, items: List<PlaylistItem>): List<Playlist> =
        playlists.map { playlist ->
            if (playlist.id != id) return@map playlist
            val present = playlist.items.map { it.uri }.toMutableSet()
            val fresh = items.filter { present.add(it.uri) }
            playlist.copy(items = playlist.items + fresh)
        }

    fun removeItem(playlists: List<Playlist>, id: String, itemIndex: Int): List<Playlist> =
        playlists.map { playlist ->
            if (playlist.id != id || itemIndex !in playlist.items.indices) playlist
            else playlist.copy(items = playlist.items.toMutableList().apply { removeAt(itemIndex) })
        }

    fun moveItem(playlists: List<Playlist>, id: String, from: Int, to: Int): List<Playlist> =
        playlists.map { playlist ->
            val items = playlist.items
            if (playlist.id != id || from !in items.indices || to !in items.indices || from == to) {
                playlist
            } else {
                val list = items.toMutableList()
                val moved = list.removeAt(from)
                list.add(to, moved)
                playlist.copy(items = list)
            }
        }

    /** True when the playlist already holds [uri]; drives the "added" tick. */
    fun contains(playlist: Playlist, uri: String): Boolean = playlist.items.any { it.uri == uri }
}

/**
 * Persistence format: one header record per playlist followed by its item
 * records. Header: "P", id, name, createdAt. Item: "I", uri, title, duration.
 */
object PlaylistCodec {

    fun encode(playlists: List<Playlist>): String {
        val records = mutableListOf<List<String>>()
        for (p in playlists) {
            records += listOf("P", p.id, p.name, p.createdAtSec.toString())
            for (item in p.items) {
                records += listOf("I", item.uri, item.title, item.durationMs.toString())
            }
        }
        return FieldCodec.encode(records)
    }

    fun decode(raw: String): List<Playlist> {
        val result = mutableListOf<Playlist>()
        var current: Playlist? = null
        val items = mutableListOf<PlaylistItem>()
        fun flush() {
            current?.let { result += it.copy(items = items.toList()) }
            items.clear()
        }
        for (record in FieldCodec.decode(raw)) {
            when (record.firstOrNull()) {
                "P" -> {
                    flush()
                    val id = record.getOrNull(1)?.takeIf { it.isNotBlank() }
                    val name = record.getOrNull(2)?.takeIf { it.isNotBlank() }
                    current = if (id != null && name != null) {
                        Playlist(id, name, createdAtSec = record.getOrNull(3)?.toLongOrNull() ?: 0L)
                    } else null
                }
                "I" -> {
                    val uri = record.getOrNull(1)?.takeIf { it.isNotBlank() } ?: continue
                    if (current != null) {
                        items += PlaylistItem(
                            uri = uri,
                            title = record.getOrNull(2).orEmpty(),
                            durationMs = record.getOrNull(3)?.toLongOrNull() ?: 0L
                        )
                    }
                }
                else -> Unit
            }
        }
        flush()
        return result
    }
}

/**
 * M3U / M3U8 playlists, spec 4.7 "import/export M3U".
 *
 * Supports the extended form (#EXTM3U, #EXTINF:seconds,title) and plain
 * one-path-per-line lists. Other directives (#EXTGRP, #PLAYLIST...) are
 * ignored rather than treated as entries.
 */
object M3u {

    data class Entry(val location: String, val title: String?, val durationSec: Long?)

    fun parse(text: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        var pendingTitle: String? = null
        var pendingDuration: Long? = null
        text.removePrefix("﻿").lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val body = line.substringAfter(':', "")
                    val durationPart = body.substringBefore(',').trim()
                    // Attributes can sit between the duration and the comma:
                    // #EXTINF:123 tvg-id="x",Title
                    pendingDuration = durationPart.substringBefore(' ').toDoubleOrNull()?.toLong()
                        ?.takeIf { it >= 0 }
                    pendingTitle = if (',' in body) body.substringAfter(',').trim().ifEmpty { null } else null
                }
                line.startsWith("#") -> Unit
                else -> {
                    entries += Entry(line, pendingTitle, pendingDuration)
                    pendingTitle = null
                    pendingDuration = null
                }
            }
        }
        return entries
    }

    fun write(items: List<PlaylistItem>): String {
        val sb = StringBuilder("#EXTM3U\n")
        for (item in items) {
            val seconds = if (item.durationMs > 0) item.durationMs / 1000L else -1L
            // Commas and newlines in a title would break the #EXTINF line
            val title = item.title.replace('\n', ' ').replace('\r', ' ')
            sb.append("#EXTINF:").append(seconds).append(',').append(title).append('\n')
            sb.append(item.uri).append('\n')
        }
        return sb.toString()
    }

    /** True when [location] is a remote URL rather than a local path. */
    fun isUrl(location: String): Boolean =
        Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(location) &&
            !location.startsWith("file://", ignoreCase = true)

    /**
     * Resolves an M3U entry against the device library: an exact path or
     * URI match wins, then a file-name match, so a list exported on another
     * device still finds "Movies/Show.mkv" here. Returns null for a local
     * entry that is not on this device.
     */
    fun <T> resolve(
        entry: Entry,
        library: List<T>,
        pathOf: (T) -> String,
        uriOf: (T) -> String
    ): T? {
        val location = entry.location.removePrefix("file://")
        library.firstOrNull { pathOf(it) == location || uriOf(it) == entry.location }?.let { return it }
        val fileName = location.substringAfterLast('/').substringAfterLast('\\')
        if (fileName.isBlank()) return null
        return library.firstOrNull { pathOf(it).substringAfterLast('/').equals(fileName, ignoreCase = true) }
    }
}
