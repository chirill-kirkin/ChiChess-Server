package com.github.chirillkirkin.chichess.game

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes mutating operations of a single game so their read-modify-write stays atomic in one process. */
class GameLocks {
    private val locks = ConcurrentHashMap<UUID, Mutex>()

    suspend fun <T> withGameLock(gameId: UUID, block: suspend () -> T): T =
        locks.getOrPut(gameId) { Mutex() }.withLock { block() }
}
