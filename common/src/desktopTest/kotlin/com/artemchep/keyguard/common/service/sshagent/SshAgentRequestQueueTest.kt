package com.artemchep.keyguard.common.service.sshagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SshAgentRequestQueueTest {
    @Test
    fun `single request resolves and clears active state`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val request = createApprovalRequest(notificationTag = "session-1")

        val result = async {
            queue.enqueueAndAwait(request)
        }
        runCurrent()

        assertSame(request, queue.state.value?.request)

        request.deferred.complete(true)

        assertTrue(result.await())
        advanceUntilIdle()
        assertNull(queue.state.value)
    }

    @Test
    fun `multiple requests are served in fifo order`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val first = createApprovalRequest(
            keyName = "first",
            notificationTag = "session-1",
        )
        val second = createApprovalRequest(
            keyName = "second",
            notificationTag = "session-2",
        )

        val firstResult = async {
            queue.enqueueAndAwait(first)
        }
        runCurrent()
        val secondResult = async {
            queue.enqueueAndAwait(second)
        }
        runCurrent()

        assertSame(first, queue.state.value?.request)

        first.deferred.complete(true)
        assertTrue(firstResult.await())
        runCurrent()

        assertSame(second, queue.state.value?.request)

        second.deferred.complete(false)
        assertFalse(secondResult.await())
        advanceUntilIdle()
        assertNull(queue.state.value)
    }

    @Test
    fun `dismissCurrentRequest denies the active request`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val request = createApprovalRequest(notificationTag = "session-1")

        val result = async {
            queue.enqueueAndAwait(request)
        }
        runCurrent()

        queue.dismissCurrentRequest()

        assertFalse(result.await())
        advanceUntilIdle()
        assertNull(queue.state.value)
    }

    @Test
    fun `request timeout denies the active request`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val request = createApprovalRequest(
            notificationTag = "session-1",
            timeout = 5.seconds,
        )

        val result = async {
            queue.enqueueAndAwait(request)
        }
        runCurrent()

        advanceTimeBy(5_000L)
        advanceUntilIdle()

        assertFalse(result.await())
        assertNull(queue.state.value)
    }

    @Test
    fun `queued request activation timestamp starts when it becomes active`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val first = createApprovalRequest(
            keyName = "first",
            notificationTag = "session-1",
            timeout = 5.seconds,
        )
        val second = createApprovalRequest(
            keyName = "second",
            notificationTag = "session-2",
            timeout = 5.seconds,
        )

        val firstResult = async {
            queue.enqueueAndAwait(first)
        }
        runCurrent()
        val firstActivatedAtMonotonicMillis = assertNotNull(queue.state.value)
            .activatedAtMonotonicMillis

        val secondResult = async {
            queue.enqueueAndAwait(second)
        }
        runCurrent()

        Thread.sleep(80L)

        first.deferred.complete(true)
        assertTrue(firstResult.await())
        runCurrent()

        val secondState = assertNotNull(queue.state.value)
        assertSame(second, secondState.request)
        assertTrue(
            secondState.activatedAtMonotonicMillis >= firstActivatedAtMonotonicMillis + 50L,
        )

        second.deferred.complete(false)
        assertFalse(secondResult.await())
        advanceUntilIdle()
        assertNull(queue.state.value)
    }

    @Test
    fun `queued request timeout starts when it becomes active`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val first = createApprovalRequest(
            keyName = "first",
            notificationTag = "session-1",
            timeout = 5.seconds,
        )
        val second = createApprovalRequest(
            keyName = "second",
            notificationTag = "session-2",
            timeout = 5.seconds,
        )

        val firstResult = async {
            queue.enqueueAndAwait(first)
        }
        runCurrent()
        val secondResult = async {
            queue.enqueueAndAwait(second)
        }
        runCurrent()

        advanceTimeBy(4_000L)
        runCurrent()
        assertSame(first, queue.state.value?.request)
        assertFalse(second.deferred.isCompleted)

        first.deferred.complete(true)
        assertTrue(firstResult.await())
        runCurrent()

        assertSame(second, queue.state.value?.request)
        advanceTimeBy(4_999L)
        runCurrent()
        assertFalse(second.deferred.isCompleted)

        advanceTimeBy(1L)
        advanceUntilIdle()
        assertFalse(secondResult.await())
        assertNull(queue.state.value)
    }

    @Test
    fun `dismissRequest denies the matching active request`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val request = createApprovalRequest(notificationTag = "session-1")

        val result = async {
            queue.enqueueAndAwait(request)
        }
        runCurrent()

        queue.dismissRequest("session-1")
        runCurrent()

        assertFalse(result.await())
        advanceUntilIdle()
        assertNull(queue.state.value)
    }

    @Test
    fun `dismissRequest removes a queued request without affecting other requests`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val first = createApprovalRequest(
            keyName = "first",
            notificationTag = "session-1",
        )
        val second = createApprovalRequest(
            keyName = "second",
            notificationTag = "session-2",
        )

        val firstResult = async {
            queue.enqueueAndAwait(first)
        }
        runCurrent()
        val secondResult = async {
            queue.enqueueAndAwait(second)
        }
        runCurrent()

        queue.dismissRequest("session-2")
        runCurrent()

        assertTrue(second.deferred.isCompleted)
        assertSame(first, queue.state.value?.request)
        assertFalse(secondResult.await())

        first.deferred.complete(true)
        assertTrue(firstResult.await())
        advanceUntilIdle()

        assertNull(queue.state.value)
    }

    @Test
    fun `dismissRequest ignores a non-matching tag`() = runTest {
        val queue = SshAgentRequestQueue(this)
        val first = createApprovalRequest(
            keyName = "first",
            notificationTag = "session-1",
        )
        val second = createApprovalRequest(
            keyName = "second",
            notificationTag = "session-2",
        )

        val firstResult = async {
            queue.enqueueAndAwait(first)
        }
        runCurrent()
        val secondResult = async {
            queue.enqueueAndAwait(second)
        }
        runCurrent()

        queue.dismissRequest("session-3")
        runCurrent()

        assertFalse(first.deferred.isCompleted)
        assertFalse(second.deferred.isCompleted)
        assertSame(first, queue.state.value?.request)

        first.deferred.complete(true)
        assertTrue(firstResult.await())
        runCurrent()

        assertSame(second, queue.state.value?.request)
        second.deferred.complete(false)
        assertFalse(secondResult.await())
        advanceUntilIdle()
        assertNull(queue.state.value)
    }

    private fun createApprovalRequest(
        keyName: String = "key",
        notificationTag: String,
        timeout: Duration = 60.seconds,
    ) = SshAgentApprovalRequest(
        keyName = keyName,
        keyFingerprint = "SHA256:test",
        caller = null,
        notificationTag = notificationTag,
        timeout = timeout,
        deferred = CompletableDeferred(),
    )
}
