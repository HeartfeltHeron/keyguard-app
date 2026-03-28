package com.artemchep.keyguard.common.service.sshagent

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SshAgentRequestQueue(
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val queue = ArrayDeque<PendingRequest>()
    private val _state = MutableStateFlow<ActiveRequestState?>(null)

    private var activeRequest: PendingRequest? = null

    val state: StateFlow<ActiveRequestState?> = _state.asStateFlow()

    @Immutable
    data class ActiveRequestState(
        val request: SshAgentRequest,
        val activatedAtMonotonicMillis: Long,
        val queueSize: Int,
    )

    private data class PendingRequest(
        val request: SshAgentRequest,
        var timeoutJob: Job? = null,
    )

    suspend fun enqueue(
        request: SshAgentRequest,
    ) {
        val pending = PendingRequest(
            request = request,
        )
        request.deferred.invokeOnCompletion {
            scope.launch {
                mutex.withLock {
                    removeRequestLocked(request)
                }
            }
        }
        mutex.withLock {
            queue.addLast(pending)
            if (activeRequest == null) {
                activateNextLocked()
            }
        }
    }

    suspend fun enqueueAndAwait(
        request: SshAgentRequest,
    ): Boolean {
        enqueue(request)
        return request.deferred.await()
    }

    fun dismissCurrentRequest() {
        val request = _state.value?.request ?: return
        request.completeWithLog(
            value = false,
            reason = "dismiss_current_request",
        )
    }

    fun dismissRequest(
        notificationTag: String,
    ) {
        scope.launch {
            val request = mutex.withLock {
                activeRequest
                    ?.request
                    ?.takeIf { it.notificationTag == notificationTag }
                    ?: queue.indexOfFirst { it.request.notificationTag == notificationTag }
                        .takeIf { it >= 0 }
                        ?.let { index -> queue.removeAt(index).request }
            } ?: return@launch

            request.completeWithLog(
                value = false,
                reason = "dismiss_request_by_notification_tag",
            )
        }
    }

    fun hasPendingRequest(): Boolean = _state.value != null

    private fun activateNextLocked() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            activeRequest = null
            _state.value = null
            return
        }

        activeRequest = next
        val activatedAtMonotonicMillis = System.nanoTime() / 1_000_000L
        _state.value = ActiveRequestState(
            request = next.request,
            activatedAtMonotonicMillis = activatedAtMonotonicMillis,
            queueSize = queue.size,
        )
        next.timeoutJob = scope.launch {
            delay(next.request.timeout)
            next.request.completeWithLog(
                value = false,
                reason = "request_timeout",
            )
        }
    }

    private fun removeRequestLocked(
        request: SshAgentRequest,
    ) {
        val current = activeRequest
        if (current?.request === request) {
            current.timeoutJob?.cancel()
            activeRequest = null
            _state.value = null
            activateNextLocked()
            return
        }

        val index = queue.indexOfFirst { it.request === request }
        if (index >= 0) {
            queue.removeAt(index)
        }
    }
}
