package com.github.chirillkirkin.chichess.game

import com.github.chirillkirkin.chichess.config.GUEST_AUTHENTICATION
import com.github.chirillkirkin.chichess.config.guestSessionId
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.UUID
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

const val GAME_SOCKET_ROUTE = "$GAME_ROUTE/{$GAME_ID_PARAMETER}"

private const val CLOSE_FORBIDDEN: Short = 4403
private const val CLOSE_NOT_FOUND: Short = 4404

private val logger = LoggerFactory.getLogger("com.github.chirillkirkin.chichess.game.GameSocket")

fun Route.gameWebSocket(games: GameService, connections: GameConnections) {
    authenticate(GUEST_AUTHENTICATION) {
        webSocket(GAME_SOCKET_ROUTE) {
            val sessionId = call.guestSessionId()
            val gameId = call.parameters[GAME_ID_PARAMETER]?.let(::parseUuidOrNull)
            if (gameId == null) {
                logger.info("WebSocket handshake rejected: unparseable gameId, session={}", sessionId)
                close(CloseReason(CLOSE_NOT_FOUND, GAME_NOT_FOUND_CODE))
                return@webSocket
            }
            val snapshot = when (val result = games.snapshot(gameId, sessionId)) {
                is GameSnapshotResult.Success -> result.snapshot
                GameSnapshotResult.NotParticipant -> {
                    logger.info("WebSocket handshake rejected: session={} not a participant of game={}", sessionId, gameId)
                    close(CloseReason(CLOSE_FORBIDDEN, NOT_A_GAME_PARTICIPANT_CODE))
                    return@webSocket
                }
                GameSnapshotResult.NotFound -> {
                    logger.info("WebSocket handshake rejected: game={} not found (session={})", gameId, sessionId)
                    close(CloseReason(CLOSE_NOT_FOUND, GAME_NOT_FOUND_CODE))
                    return@webSocket
                }
            }

            val connection = GameConnection(sessionId, snapshot.yourColor, this)
            connections.register(gameId, connection)
            logger.info("WebSocket connected: game={} session={} color={}", gameId, sessionId, snapshot.yourColor)
            try {
                connections.sendSnapshot(gameId, connection, snapshot)
                connections.broadcast(gameId, PlayerJoinedEvent(snapshot.yourColor), except = connection)
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        handleCommand(frame.readText(), games, connections, gameId, sessionId, connection)
                    }
                }
            } finally {
                val left = connections.unregister(gameId, connection)
                logger.info("WebSocket disconnected: game={} session={} left={}", gameId, sessionId, left)
                if (left) connections.broadcast(gameId, PlayerLeftEvent(snapshot.yourColor))
            }
        }
    }
}

private suspend fun handleCommand(
    text: String,
    games: GameService,
    connections: GameConnections,
    gameId: UUID,
    sessionId: UUID,
    connection: GameConnection,
) {
    val command = try {
        gameProtocolJson.decodeFromString<GameCommand>(text)
    } catch (_: SerializationException) {
        logger.debug("Malformed command from session={} game={}", sessionId, gameId)
        connection.send(CommandRejectedEvent(commandId = null, code = MALFORMED_COMMAND_CODE))
        return
    }
    logger.debug("Command {} received: game={} session={} id={}", command::class.simpleName, gameId, sessionId, command.commandId)

    suspend fun reject(code: String) {
        logger.debug("Command {} rejected: game={} session={} id={} code={}", command::class.simpleName, gameId, sessionId, command.commandId, code)
        connection.send(CommandRejectedEvent(command.commandId, code))
    }

    if (command.protocolVersion != GAME_PROTOCOL_VERSION) {
        reject(UNSUPPORTED_PROTOCOL_VERSION_CODE)
        return
    }
    when (command) {
        is RequestSync -> {
            val result = games.snapshot(gameId, sessionId)
            if (result is GameSnapshotResult.Success) {
                connections.sendSnapshot(gameId, connection, result.snapshot)
            }
        }
        is MakeMove -> {
            val result = games.submitMove(gameId, sessionId, command.commandId, command.expectedRevision, command.uci)
            when (result) {
                is MoveResult.Applied ->
                    connections.broadcast(gameId, result.snapshot.toMoveApplied(command.uci))
                is MoveResult.RevisionConflict -> {
                    reject(REVISION_CONFLICT_CODE)
                    connections.sendSnapshot(gameId, connection, result.snapshot)
                }
                is MoveResult.DuplicateCommand ->
                    result.snapshot?.let { connections.sendSnapshot(gameId, connection, it) }
                else ->
                    reject(result.rejectionCode())
            }
        }
        is Resign -> {
            val result = games.submitResign(gameId, sessionId, command.commandId)
            when (result) {
                is MoveResult.Applied ->
                    connections.broadcast(gameId, result.snapshot.toGameFinished())
                is MoveResult.DuplicateCommand ->
                    result.snapshot?.let { connections.sendSnapshot(gameId, connection, it) }
                else ->
                    reject(result.rejectionCode())
            }
        }
        is OfferDraw -> {
            val result = games.offerDraw(gameId, sessionId, command.commandId)
            when (result) {
                is MoveResult.Applied ->
                    connections.broadcast(gameId, DrawOfferedEvent(checkNotNull(result.snapshot.pendingDrawOfferBy)))
                is MoveResult.DuplicateCommand ->
                    result.snapshot?.let { connections.sendSnapshot(gameId, connection, it) }
                else ->
                    reject(result.rejectionCode())
            }
        }
        is AcceptDraw -> {
            val result = games.acceptDraw(gameId, sessionId, command.commandId)
            when (result) {
                is MoveResult.Applied ->
                    connections.broadcast(gameId, result.snapshot.toGameFinished())
                is MoveResult.DuplicateCommand ->
                    result.snapshot?.let { connections.sendSnapshot(gameId, connection, it) }
                else ->
                    reject(result.rejectionCode())
            }
        }
        is DeclineDraw -> {
            val result = games.declineDraw(gameId, sessionId, command.commandId)
            when (result) {
                is MoveResult.Applied ->
                    connections.broadcast(gameId, DrawDeclinedEvent)
                is MoveResult.DuplicateCommand ->
                    result.snapshot?.let { connections.sendSnapshot(gameId, connection, it) }
                else ->
                    reject(result.rejectionCode())
            }
        }
        is ClaimDraw -> {
            val result = games.claimDraw(gameId, sessionId, command.commandId, command.expectedRevision)
            when (result) {
                is MoveResult.Applied ->
                    connections.broadcast(gameId, result.snapshot.toGameFinished())
                is MoveResult.RevisionConflict -> {
                    reject(REVISION_CONFLICT_CODE)
                    connections.sendSnapshot(gameId, connection, result.snapshot)
                }
                is MoveResult.DuplicateCommand ->
                    result.snapshot?.let { connections.sendSnapshot(gameId, connection, it) }
                else ->
                    reject(result.rejectionCode())
            }
        }
    }
}

private suspend fun GameConnections.sendSnapshot(gameId: UUID, connection: GameConnection, snapshot: GameSnapshot) {
    connection.send(SnapshotEvent(snapshot, opponentConnected = isConnected(gameId, snapshot.yourColor.opponent)))
}

private fun GameSnapshot.toMoveApplied(lastMove: String) =
    MoveAppliedEvent(revision, fen, status, lastMove, result, terminationReason)

private fun GameSnapshot.toGameFinished() =
    GameFinishedEvent(revision, status, checkNotNull(result), checkNotNull(terminationReason))

private fun MoveResult.rejectionCode(): String = when (this) {
    MoveResult.NotFound -> GAME_NOT_FOUND_CODE
    MoveResult.NotParticipant -> NOT_A_GAME_PARTICIPANT_CODE
    MoveResult.NotReady -> GAME_NOT_READY_CODE
    MoveResult.NotYourTurn -> NOT_YOUR_TURN_CODE
    MoveResult.IllegalMove -> ILLEGAL_MOVE_CODE
    MoveResult.GameFinished -> GAME_FINISHED_CODE
    MoveResult.NoDrawOffer -> NO_DRAW_OFFER_CODE
    MoveResult.DrawAlreadyOffered -> DRAW_ALREADY_OFFERED_CODE
    MoveResult.DrawNotClaimable -> DRAW_NOT_CLAIMABLE_CODE
    is MoveResult.Applied,
    is MoveResult.RevisionConflict,
    is MoveResult.DuplicateCommand -> error("Not a rejection: $this")
}
