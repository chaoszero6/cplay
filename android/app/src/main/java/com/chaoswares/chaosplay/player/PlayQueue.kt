package com.chaoswares.chaosplay.player

import com.chaoswares.chaosplay.util.FieldCodec

/** One entry in the play queue: a local content URI, a file path or a stream URL. */
data class QueueItem(
    val uri: String,
    val title: String = "",
    val durationMs: Long = 0L,
    /** Extra subtitle files to offer (WebDAV sidecars, Jellyfin external subtitles). */
    val subtitleUris: List<String> = emptyList(),
    /** Server-side resume point (Jellyfin), used when there is no local one. */
    val resumeHintMs: Long = 0L
)

/** Loop mode, spec section 3: "Playlists, queue, shuffle and loop modes". */
enum class RepeatMode(val label: String) {
    OFF("Loop off"),
    ALL("Loop all"),
    ONE("Loop one");

    fun next(): RepeatMode = entries[(ordinal + 1) % entries.size]
}

/**
 * The play queue (spec 4.7 "Queue sheet inside the player").
 *
 * [items] is the user's order and never changes because of shuffle;
 * [order] is the navigation order (identity, or a shuffled permutation
 * that starts with the item that was current when shuffle was enabled).
 * [index] always points into [items].
 *
 * Immutable: every operation returns a new queue so it can live in a
 * StateFlow without the mutation traps of a shared list.
 */
data class PlayQueue(
    val items: List<QueueItem> = emptyList(),
    val index: Int = -1,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val order: List<Int> = items.indices.toList(),
    val seed: Long = 0L
) {
    val current: QueueItem? get() = items.getOrNull(index)
    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()

    private val orderPosition: Int get() = order.indexOf(index)

    /** True when a "next" press would move to another item. */
    val hasNext: Boolean
        get() = when {
            items.size <= 1 -> false
            repeat == RepeatMode.ALL -> true
            else -> orderPosition in 0 until order.lastIndex
        }

    /** True when a "previous" press would move to another item. */
    val hasPrevious: Boolean
        get() = when {
            items.size <= 1 -> false
            repeat == RepeatMode.ALL -> true
            else -> orderPosition > 0
        }

    /** Moves to the next item; null when at the end and not looping. */
    fun next(): PlayQueue? {
        if (items.isEmpty()) return null
        val pos = orderPosition
        val nextPos = when {
            pos < order.lastIndex -> pos + 1
            repeat == RepeatMode.ALL -> 0
            else -> return null
        }
        return copy(index = order[nextPos])
    }

    /** Moves to the previous item; null when at the start and not looping. */
    fun previous(): PlayQueue? {
        if (items.isEmpty()) return null
        val pos = orderPosition
        val prevPos = when {
            pos > 0 -> pos - 1
            repeat == RepeatMode.ALL -> order.lastIndex
            else -> return null
        }
        return copy(index = order[prevPos])
    }

    /**
     * What to do when the current item finishes. Loop-one replays the same
     * item, otherwise advance; null means "stop, the queue is done".
     */
    fun onEnded(autoAdvance: Boolean = true): PlayQueue? {
        if (items.isEmpty()) return null
        if (repeat == RepeatMode.ONE) return this
        if (!autoAdvance) return null
        return next()
    }

    fun jumpTo(itemIndex: Int): PlayQueue {
        if (itemIndex !in items.indices) return this
        return copy(index = itemIndex)
    }

    fun withRepeat(mode: RepeatMode): PlayQueue = copy(repeat = mode)

    /** Turns shuffle on or off. On: the current item stays first in the new order. */
    fun withShuffle(enabled: Boolean, newSeed: Long = seed): PlayQueue {
        if (!enabled) return copy(shuffle = false, order = items.indices.toList())
        return copy(shuffle = true, seed = newSeed, order = shuffledOrder(items.size, index, newSeed))
    }

    /** "Play next": insert right after the current item. */
    fun playNext(item: QueueItem): PlayQueue {
        if (items.isEmpty()) return PlayQueue(items = listOf(item), index = 0, repeat = repeat)
        val insertAt = (index + 1).coerceIn(0, items.size)
        val newItems = items.toMutableList().apply { add(insertAt, item) }
        val rebuilt = copy(items = newItems)
        if (!shuffle) return rebuilt.copy(order = newItems.indices.toList())
        // Keep the shuffled walk, but make the new item the very next one.
        val shifted = order.map { if (it >= insertAt) it + 1 else it }.toMutableList()
        val pos = shifted.indexOf(index)
        shifted.add(pos + 1, insertAt)
        return rebuilt.copy(order = shifted)
    }

    /** Appends to the end of the queue. */
    fun add(item: QueueItem): PlayQueue {
        val newItems = items + item
        val newIndex = if (index < 0) 0 else index
        val newOrder = order + (newItems.size - 1)
        return copy(items = newItems, index = newIndex, order = newOrder)
    }

    /**
     * Removes an item. Removing the current item makes the following one
     * current (or the new last one). Returns an empty queue when the last
     * item goes.
     */
    fun removeAt(itemIndex: Int): PlayQueue {
        if (itemIndex !in items.indices) return this
        val newItems = items.toMutableList().apply { removeAt(itemIndex) }
        if (newItems.isEmpty()) return PlayQueue(repeat = repeat, shuffle = shuffle, seed = seed)
        val newIndex = when {
            itemIndex < index -> index - 1
            itemIndex > index -> index
            else -> index.coerceAtMost(newItems.lastIndex)
        }
        val newOrder = order.filter { it != itemIndex }.map { if (it > itemIndex) it - 1 else it }
        return copy(items = newItems, index = newIndex, order = newOrder)
    }

    /** Drag to reorder (spec 4.7). The current item stays current. */
    fun move(from: Int, to: Int): PlayQueue {
        if (from !in items.indices || to !in items.indices || from == to) return this
        val newItems = items.toMutableList()
        val moved = newItems.removeAt(from)
        newItems.add(to, moved)
        fun remap(i: Int): Int = when {
            i == from -> to
            from < to && i in (from + 1)..to -> i - 1
            from > to && i in to until from -> i + 1
            else -> i
        }
        val newIndex = if (index >= 0) remap(index) else index
        val newOrder = if (shuffle) order.map { remap(it) } else newItems.indices.toList()
        return copy(items = newItems, index = newIndex, order = newOrder)
    }

    companion object {
        fun of(items: List<QueueItem>, startIndex: Int = 0): PlayQueue {
            if (items.isEmpty()) return PlayQueue()
            return PlayQueue(items = items, index = startIndex.coerceIn(0, items.lastIndex))
        }

        fun single(item: QueueItem): PlayQueue = of(listOf(item), 0)

        /**
         * Deterministic shuffle (seeded LCG / Fisher-Yates) with [first]
         * moved to the front, so the user keeps watching what they were
         * watching when they pressed shuffle.
         */
        fun shuffledOrder(size: Int, first: Int, seed: Long): List<Int> {
            if (size <= 0) return emptyList()
            val list = (0 until size).toMutableList()
            var state = seed xor 0x5DEECE66DL
            for (i in list.lastIndex downTo 1) {
                state = (state * 6364136223846793005L + 1442695040888963407L)
                val j = ((state ushr 33) % (i + 1)).toInt().let { if (it < 0) -it else it }
                val tmp = list[i]; list[i] = list[j]; list[j] = tmp
            }
            if (first in 0 until size) {
                list.remove(first)
                list.add(0, first)
            }
            return list
        }

        // ---- persistence for "rememberSaveable": survives process death ----

        fun encode(queue: PlayQueue): String {
            val header = listOf(
                "Q1",
                queue.index.toString(),
                queue.shuffle.toString(),
                queue.repeat.name,
                queue.seed.toString(),
                queue.order.joinToString(",")
            )
            val rows = queue.items.map {
                listOf(it.uri, it.title, it.durationMs.toString(), it.subtitleUris.joinToString("\u0001"), it.resumeHintMs.toString())
            }
            return FieldCodec.encode(listOf(header) + rows)
        }

        fun decode(raw: String): PlayQueue? {
            val records = FieldCodec.decode(raw)
            val header = records.firstOrNull() ?: return null
            if (header.size < 6 || header[0] != "Q1") return null
            val items = records.drop(1).mapNotNull { r ->
                val uri = r.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                QueueItem(
                    uri = uri,
                    title = r.getOrNull(1).orEmpty(),
                    durationMs = r.getOrNull(2)?.toLongOrNull() ?: 0L,
                    subtitleUris = r.getOrNull(3).orEmpty().split('\u0001').filter { it.isNotBlank() },
                    resumeHintMs = r.getOrNull(4)?.toLongOrNull() ?: 0L
                )
            }
            if (items.isEmpty()) return null
            val index = header[1].toIntOrNull()?.coerceIn(0, items.lastIndex) ?: 0
            val shuffle = header[2].toBooleanStrictOrNull() ?: false
            val repeat = runCatching { RepeatMode.valueOf(header[3]) }.getOrDefault(RepeatMode.OFF)
            val seed = header[4].toLongOrNull() ?: 0L
            val order = header[5].split(',').mapNotNull { it.toIntOrNull() }
                .takeIf { o -> o.size == items.size && o.toSet() == items.indices.toSet() }
                ?: items.indices.toList()
            return PlayQueue(items, index, shuffle, repeat, order, seed)
        }
    }
}
