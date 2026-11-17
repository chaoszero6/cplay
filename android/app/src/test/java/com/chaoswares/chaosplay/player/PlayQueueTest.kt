package com.chaoswares.chaosplay.player

import com.chaoswares.chaosplay.util.FieldCodec
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayQueueTest {

    private val a = QueueItem("u/a", "A")
    private val b = QueueItem("u/b", "B")
    private val c = QueueItem("u/c", "C")
    private val d = QueueItem("u/d", "D")
    private val abc = PlayQueue.of(listOf(a, b, c), 0)

    @Test fun `of clamps start index and exposes current`() {
        assertThat(PlayQueue.of(listOf(a, b), 5).current).isEqualTo(b)
        assertThat(PlayQueue.of(listOf(a, b), -3).current).isEqualTo(a)
        assertThat(PlayQueue.of(emptyList()).current).isNull()
    }

    @Test fun `next walks forward and stops at the end without loop`() {
        val q1 = abc.next()!!
        assertThat(q1.current).isEqualTo(b)
        val q2 = q1.next()!!
        assertThat(q2.current).isEqualTo(c)
        assertThat(q2.next()).isNull()
        assertThat(q2.hasNext).isFalse()
    }

    @Test fun `previous walks back and stops at the start without loop`() {
        val atC = abc.jumpTo(2)
        assertThat(atC.previous()!!.current).isEqualTo(b)
        assertThat(abc.previous()).isNull()
        assertThat(abc.hasPrevious).isFalse()
    }

    @Test fun `loop all wraps both directions`() {
        val q = abc.withRepeat(RepeatMode.ALL)
        assertThat(q.previous()!!.current).isEqualTo(c)
        assertThat(q.jumpTo(2).next()!!.current).isEqualTo(a)
        assertThat(q.hasNext).isTrue()
        assertThat(q.hasPrevious).isTrue()
    }

    @Test fun `single item has no next or previous even when looping all`() {
        val q = PlayQueue.single(a).withRepeat(RepeatMode.ALL)
        assertThat(q.hasNext).isFalse()
        assertThat(q.hasPrevious).isFalse()
    }

    @Test fun `onEnded replays with loop one and advances otherwise`() {
        assertThat(abc.withRepeat(RepeatMode.ONE).onEnded()).isEqualTo(abc.withRepeat(RepeatMode.ONE))
        assertThat(abc.onEnded()!!.current).isEqualTo(b)
        assertThat(abc.jumpTo(2).onEnded()).isNull()
        assertThat(abc.onEnded(autoAdvance = false)).isNull()
    }

    @Test fun `repeat mode cycles off all one`() {
        assertThat(RepeatMode.OFF.next()).isEqualTo(RepeatMode.ALL)
        assertThat(RepeatMode.ALL.next()).isEqualTo(RepeatMode.ONE)
        assertThat(RepeatMode.ONE.next()).isEqualTo(RepeatMode.OFF)
    }

    @Test fun `shuffle keeps the current item first and visits every item once`() {
        val q = PlayQueue.of(listOf(a, b, c, d), 2).withShuffle(true, newSeed = 42L)
        assertThat(q.current).isEqualTo(c)
        assertThat(q.order.first()).isEqualTo(2)
        assertThat(q.order.sorted()).containsExactly(0, 1, 2, 3).inOrder()
        val visited = mutableListOf(q.current!!)
        var cur: PlayQueue? = q
        while (true) {
            cur = cur!!.next() ?: break
            visited += cur.current!!
        }
        assertThat(visited).containsExactly(a, b, c, d)
        assertThat(q.items).containsExactly(a, b, c, d).inOrder()
    }

    @Test fun `shuffle is deterministic for a seed and turning it off restores order`() {
        assertThat(PlayQueue.shuffledOrder(10, 0, 7L)).isEqualTo(PlayQueue.shuffledOrder(10, 0, 7L))
        val off = abc.withShuffle(true, 3L).withShuffle(false)
        assertThat(off.order).containsExactly(0, 1, 2).inOrder()
        assertThat(off.shuffle).isFalse()
    }

    @Test fun `shuffled order handles empty and single`() {
        assertThat(PlayQueue.shuffledOrder(0, 0, 1L)).isEmpty()
        assertThat(PlayQueue.shuffledOrder(1, 0, 1L)).containsExactly(0)
    }

    @Test fun `play next inserts right after current`() {
        val q = abc.playNext(d)
        assertThat(q.items).containsExactly(a, d, b, c).inOrder()
        assertThat(q.current).isEqualTo(a)
        assertThat(q.next()!!.current).isEqualTo(d)
    }

    @Test fun `play next on an empty queue starts it`() {
        val q = PlayQueue().playNext(a)
        assertThat(q.current).isEqualTo(a)
    }

    @Test fun `play next while shuffled is still the next item`() {
        val q = PlayQueue.of(listOf(a, b, c), 1).withShuffle(true, 9L).playNext(d)
        assertThat(q.current).isEqualTo(b)
        assertThat(q.next()!!.current).isEqualTo(d)
        assertThat(q.order.sorted()).containsExactly(0, 1, 2, 3).inOrder()
    }

    @Test fun `add appends and starts an empty queue`() {
        assertThat(abc.add(d).items.last()).isEqualTo(d)
        assertThat(PlayQueue().add(a).current).isEqualTo(a)
    }

    @Test fun `remove before current shifts index`() {
        val q = abc.jumpTo(2).removeAt(0)
        assertThat(q.current).isEqualTo(c)
        assertThat(q.items).containsExactly(b, c).inOrder()
    }

    @Test fun `remove current makes the following item current`() {
        val q = abc.jumpTo(1).removeAt(1)
        assertThat(q.current).isEqualTo(c)
        val last = abc.jumpTo(2).removeAt(2)
        assertThat(last.current).isEqualTo(b)
    }

    @Test fun `remove the only item empties the queue and ignores bad index`() {
        assertThat(PlayQueue.single(a).removeAt(0).isEmpty).isTrue()
        assertThat(abc.removeAt(9)).isEqualTo(abc)
    }

    @Test fun `move keeps the current item current`() {
        val q = abc.jumpTo(0).move(0, 2)
        assertThat(q.items).containsExactly(b, c, a).inOrder()
        assertThat(q.current).isEqualTo(a)
        val q2 = abc.jumpTo(1).move(2, 0)
        assertThat(q2.items).containsExactly(c, a, b).inOrder()
        assertThat(q2.current).isEqualTo(b)
        assertThat(abc.move(0, 0)).isEqualTo(abc)
        assertThat(abc.move(0, 7)).isEqualTo(abc)
    }

    @Test fun `codec round trips the whole queue`() {
        val rich = QueueItem("jellyfin://s/1", "Tab\tTitle\nNew", 1234L, listOf("https://x/a.srt", "https://x/b.srt"), 999L)
        val q = PlayQueue.of(listOf(a, rich, c), 1).withRepeat(RepeatMode.ONE).withShuffle(true, 5L)
        val back = PlayQueue.decode(PlayQueue.encode(q))
        assertThat(back).isEqualTo(q)
    }

    @Test fun `decode rejects garbage and repairs a broken order`() {
        assertThat(PlayQueue.decode("")).isNull()
        assertThat(PlayQueue.decode("nonsense")).isNull()
        val raw = FieldCodec.encode(listOf(listOf("Q1", "0", "true", "BOGUS", "x", "9,9"), listOf("u", "t", "0")))
        val q = PlayQueue.decode(raw)!!
        assertThat(q.order).containsExactly(0)
        assertThat(q.repeat).isEqualTo(RepeatMode.OFF)
    }
}
