package dev.usenetstream.engine.stream

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask

/**
 * Decoded segments by index, with read-ahead.
 *
 * Every [get]/[warm] makes [from, from + readAhead] the wanted window: missing segments in it are queued on
 * [readAheadLane], queued segments outside it are cancelled, and finished ones outside it are evicted oldest-first
 * past [maxCached]. The segment a reader is actually blocked on goes to [urgentLane] instead, so after a seek it
 * never waits behind read-ahead that is already running.
 */
internal class SegmentCache(
    private val count: Int,
    private val loader: (Int) -> ByteArray,
    private val readAheadLane: Executor,
    private val urgentLane: Executor,
    private val readAhead: Int,
    /** Read-ahead window plus a few segments behind the playhead for small back-seeks. */
    private val maxCached: Int = readAhead + 4,
) {
    private inner class Job(index: Int) : FutureTask<ByteArray>(Callable { loader(index) }) {
        @Volatile var started = false

        override fun run() {
            started = true
            super.run()
        }
    }

    /** Off while we only probe headers; on once this file is actually being played. */
    @Volatile var readAheadEnabled = false

    // Access order = LRU iteration order.
    private val entries = LinkedHashMap<Int, FutureTask<ByteArray>>(32, 0.75f, true)

    fun seed(index: Int, data: ByteArray) = synchronized(this) {
        entries[index] = FutureTask { data }.also { it.run() }
    }

    /** Drop finished segments and cancel queued fetches: this file isn't being read any more. */
    fun trim() = synchronized(this) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val f = it.next().value
            if (f.isDone || (f is Job && !f.started && f.cancel(false))) it.remove()
        }
    }

    fun warm(index: Int) = synchronized(this) {
        if (entries[index] == null) entries[index] = Job(index).also(readAheadLane::execute)
        schedule(index)
    }

    fun get(index: Int): ByteArray {
        while (true) {
            val future = synchronized(this) {
                // Not there, or only queued behind read-ahead: fetch it now on the urgent lane.
                val job = promote(index)
                schedule(index)
                job
            }
            try {
                return future.get()
            } catch (e: CancellationException) {
                // A concurrent seek cancelled it; loop and queue it again.
                synchronized(this) { if (entries[index] === future) entries.remove(index) }
            } catch (e: ExecutionException) {
                synchronized(this) { if (entries[index] === future) entries.remove(index) }
                val cause = e.cause ?: e
                throw cause as? IOException ?: IoFailure(cause)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("interrupted waiting for segment $index")
            }
        }
    }

    private fun promote(index: Int): FutureTask<ByteArray> {
        val job = entries[index]
        if (job != null && !(job is Job && !job.started && !job.isDone)) return job
        job?.cancel(false)
        return Job(index).also {
            entries[index] = it
            urgentLane.execute(it)
        }
    }

    private fun schedule(from: Int) {
        val window = from..minOf(count - 1, from + if (readAheadEnabled) readAhead else 0)
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val (i, f) = it.next()
            if (i !in window && f is Job && !f.started && f.cancel(false)) it.remove()
        }
        for (i in window) {
            if (entries[i] == null && i != from) entries[i] = Job(i).also(readAheadLane::execute)
        }
        if (entries.size > maxCached) {
            val evict = entries.entries.iterator()
            while (entries.size > maxCached && evict.hasNext()) {
                val (i, f) = evict.next()
                if (i !in window && f.isDone) evict.remove()
            }
        }
    }
}
