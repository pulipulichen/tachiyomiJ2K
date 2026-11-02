package eu.kanade.tachiyomi.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncStatusTest {
    @Test
    fun noWorkIsIdle() {
        assertEquals(CloudSyncStatus.State.IDLE, CloudSyncStatus.from(emptyList()).state)
    }

    @Test
    fun periodicIntervalDoesNotKeepSyncButtonDisabled() {
        val status = CloudSyncStatus.from(listOf(CloudSyncStatus.Work(enqueued = true, periodic = true)))
        assertEquals(CloudSyncStatus.State.IDLE, status.state)
        assertFalse(status.busy)
    }

    @Test
    fun immediateRequestWaitingForNetworkShowsQueued() {
        val status = CloudSyncStatus.from(listOf(CloudSyncStatus.Work(enqueued = true)))
        assertEquals(CloudSyncStatus.State.QUEUED, status.state)
        assertTrue(status.busy)
    }

    @Test
    fun runningWorkTakesPrecedenceOverQueuedRequests() {
        val status = CloudSyncStatus.from(
            listOf(
                CloudSyncStatus.Work(enqueued = true),
                CloudSyncStatus.Work(running = true, periodic = true, stage = CloudSyncStage.UPLOADING),
            ),
        )
        assertEquals(CloudSyncStatus.State.RUNNING, status.state)
        assertEquals(CloudSyncStage.UPLOADING, status.stage)
        assertTrue(status.busy)
    }

    @Test
    fun activeProgressTakesPrecedenceOverWorkerWaitingForMutex() {
        val status = CloudSyncStatus.from(
            listOf(
                CloudSyncStatus.Work(running = true),
                CloudSyncStatus.Work(running = true, periodic = true, stage = CloudSyncStage.DOWNLOADING, completed = 2, total = 4),
            ),
        )
        assertEquals(CloudSyncStage.DOWNLOADING, status.stage)
        assertEquals(2, status.completed)
        assertEquals(4, status.total)
    }

    @Test
    fun retryAllowsUserToRetryImmediately() {
        val status = CloudSyncStatus.from(listOf(CloudSyncStatus.Work(enqueued = true, attempts = 1)))
        assertEquals(CloudSyncStatus.State.RETRYING, status.state)
        assertFalse(status.busy)
    }

    @Test
    fun periodicFailureAlsoShowsRetry() {
        val status = CloudSyncStatus.from(listOf(CloudSyncStatus.Work(enqueued = true, periodic = true, attempts = 2)))
        assertEquals(CloudSyncStatus.State.RETRYING, status.state)
    }

    @Test
    fun finishedOrCancelledWorkDoesNotLeaveControlsBusy() {
        val status = CloudSyncStatus.from(listOf(CloudSyncStatus.Work(attempts = 3, stage = CloudSyncStage.MERGING)))
        assertEquals(CloudSyncStatus.State.IDLE, status.state)
        assertFalse(status.busy)
    }
}
