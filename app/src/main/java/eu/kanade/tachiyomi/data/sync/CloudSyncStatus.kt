package eu.kanade.tachiyomi.data.sync

/** Presentation state shared by the worker and the settings screen. No tokens are stored here. */
data class CloudSyncStatus(
    val state: State = State.IDLE,
    val stage: CloudSyncStage? = null,
    val completed: Int = 0,
    val total: Int = 0,
) {
    enum class State { IDLE, QUEUED, RUNNING, RETRYING }

    data class Work(
        val running: Boolean = false,
        val enqueued: Boolean = false,
        val periodic: Boolean = false,
        val attempts: Int = 0,
        val stage: CloudSyncStage? = null,
        val completed: Int = 0,
        val total: Int = 0,
    )

    val busy: Boolean get() = state == State.RUNNING || state == State.QUEUED

    companion object {
        fun from(work: List<Work>): CloudSyncStatus {
            (work.firstOrNull { it.running && it.stage != null } ?: work.firstOrNull { it.running })?.let {
                return CloudSyncStatus(State.RUNNING, it.stage, it.completed, it.total)
            }
            val pending = work.filter { it.enqueued }
            // A periodic job waiting for its next 15-minute interval is idle, not syncing.
            if (pending.any { !it.periodic && it.attempts == 0 }) return CloudSyncStatus(State.QUEUED)
            if (pending.any { it.attempts > 0 }) return CloudSyncStatus(State.RETRYING)
            return CloudSyncStatus()
        }
    }
}

enum class CloudSyncStage { AUTHORIZING, CHECKING_ACCOUNT, DOWNLOADING, PREPARING, UPLOADING, MERGING }
