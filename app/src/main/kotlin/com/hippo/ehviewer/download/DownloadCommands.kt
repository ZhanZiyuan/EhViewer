package com.hippo.ehviewer.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** A single owner for queued commands and callbacks from the active gallery. */
internal class DownloadCommands(scope: CoroutineScope, private val reportFailure: (Throwable) -> Unit) {
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (command in commands) runCatching { command() }.onFailure(reportFailure)
        }
    }

    fun post(command: suspend () -> Unit) {
        commands.trySend(command).getOrThrow()
    }

    fun <T> enqueue(command: suspend () -> T): Deferred<Result<T>> {
        val result = CompletableDeferred<Result<T>>()
        post { result.complete(runCatching { command() }) }
        return result
    }

    suspend fun <T> execute(command: suspend () -> T): T = enqueue(command).await().getOrThrow()
}
