package com.github.chirillkirkin.chichess.game

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GameConnection(
    val sessionId: UUID,
    private val socket: WebSocketSession,
) {
    // One WebSocket is a single byte stream; serialize writes so concurrent sends cannot interleave frames.
    private val writeLock = Mutex()

    suspend fun send(event: GameEvent) = writeLock.withLock {
        socket.send(Frame.Text(gameProtocolJson.encodeToString(event)))
    }
}

class GameConnections {
    private val byGame = mutableMapOf<UUID, MutableSet<GameConnection>>()
    private val lock = Mutex()

    suspend fun register(gameId: UUID, connection: GameConnection): Unit = lock.withLock {
        byGame.getOrPut(gameId) { mutableSetOf() }.add(connection)
    }

    suspend fun unregister(gameId: UUID, connection: GameConnection) = lock.withLock {
        val connections = byGame[gameId] ?: return@withLock
        connections.remove(connection)
        if (connections.isEmpty()) byGame.remove(gameId)
    }

    /** Sends [event] to every connection of [gameId], optionally skipping [except]. */
    suspend fun broadcast(gameId: UUID, event: GameEvent, except: GameConnection? = null) {
        val targets = lock.withLock { byGame[gameId]?.toList().orEmpty() }
        targets.forEach { if (it != except) it.send(event) }
    }
}
