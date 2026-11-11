package dev.diyhud.session

import java.util.concurrent.Executor

/** One current item plus one latest pending item. All replaced items are closed. */
class LatestFrameProcessor<T : AutoCloseable>(
    private val executor: Executor,
    private val process: (T) -> Unit,
    private val failure: (Exception) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var pending: T? = null
    private var running = false
    private var closed = false
    @Volatile var dropped = 0L
        private set

    fun offer(frame: T) {
        synchronized(lock) {
            if (closed) { dropped++; frame.close(); return }
            pending?.let { dropped++; it.close() }
            pending = frame
            if (!running) {
                running = true
                try { executor.execute { drain() } } catch (error: java.util.concurrent.RejectedExecutionException) {
                    closed = true; running = false; pending = null
                    dropped++; frame.close()
                    failure(error)
                }
            }
        }
    }

    private fun drain() {
        while (true) {
            val frame = synchronized(lock) {
                val next = pending
                pending = null
                if (next == null) running = false
                next
            } ?: return
            try { frame.use { process(it) } } catch (error: Exception) {
                close()
                failure(error)
                synchronized(lock) { running = false }
                return
            }
        }
    }

    override fun close() = synchronized(lock) {
        closed = true
        pending?.let { dropped++; it.close() }
        pending = null
    }
}
