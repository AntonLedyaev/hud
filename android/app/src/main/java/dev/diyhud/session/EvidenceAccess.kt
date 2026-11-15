package dev.diyhud.session

/** Process-wide exclusion survives Activity recreation and a finishing export worker. */
class EvidenceAccess {
    private var recognition: Any? = null
    private val reviewers = mutableSetOf<Any>()
    private var operations = 0
    @Synchronized fun start(owner: Any): Boolean {
        if (recognition != null || reviewers.isNotEmpty() || operations > 0) return false
        recognition = owner; return true
    }
    @Synchronized fun stop(owner: Any) { if (recognition === owner) recognition = null }
    @Synchronized fun open(owner: Any): Boolean {
        if (recognition != null) return false
        reviewers += owner; return true
    }
    @Synchronized fun close(owner: Any) { reviewers -= owner }
    @Synchronized fun allowed(owner: Any) = recognition == null && owner in reviewers
    @Synchronized fun operation(owner: Any): AutoCloseable {
        check(allowed(owner)) { "Stop recognition before reviewing evidence" }
        operations++
        return object : AutoCloseable {
            private var released = false
            override fun close() = synchronized(this@EvidenceAccess) {
                if (!released) { operations--; released = true }
            }
        }
    }
    companion object { val shared = EvidenceAccess() }
}
