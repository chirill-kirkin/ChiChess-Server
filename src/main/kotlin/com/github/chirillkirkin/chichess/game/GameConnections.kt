package com.github.chirillkirkin.chichess.game

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.github.chirillkirkin.chichess.game.GameConnections")

class GameConnection(
    val sessionId: UUID,
    val color: PieceColor,
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
        val connections = byGame.getOrPut(gameId) { mutableSetOf() }
        connections.add(connection)
        logger.debug("Connection registered: game={} session={} live={}", gameId, connection.sessionId, connections.size)
    }

    suspend fun unregister(gameId: UUID, connection: GameConnection): Boolean = lock.withLock {
        val connections = byGame[gameId] ?: return@withLock false
        connections.remove(connection)
        logger.debug("Connection unregistered: game={} session={} live={}", gameId, connection.sessionId, connections.size)
        if (connections.isEmpty()) byGame.remove(gameId)
        connections.none { it.sessionId == connection.sessionId }
    }

    suspend fun isConnected(gameId: UUID, color: PieceColor): Boolean = lock.withLock {
        byGame[gameId]?.any { it.color == color } == true
    }

    suspend fun broadcast(gameId: UUID, event: GameEvent, except: GameConnection? = null) {
        val targets = lock.withLock { byGame[gameId]?.toList().orEmpty() }.filter { it != except }
        logger.debug("Broadcasting {}: game={} targets={}", event::class.simpleName, gameId, targets.size)
        targets.forEach { it.send(event) }
    }
}
