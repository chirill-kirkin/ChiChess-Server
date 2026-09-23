package com.github.chirillkirkin.chichess.game

import com.github.chirillkirkin.chichess.session.GuestSessionService
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.UUID
import kotlinx.serialization.SerializationException

const val GAME_SOCKET_ROUTE = "$GAME_ROUTE/{$GAME_ID_PARAMETER}"
const val GAME_SOCKET_TOKEN_PARAMETER = "token"
const val UNAUTHORIZED_CODE = "UNAUTHORIZED"

private const val CLOSE_UNAUTHORIZED: Short = 4401
private const val CLOSE_FORBIDDEN: Short = 4403
private const val CLOSE_NOT_FOUND: Short = 4404

fun Route.gameWebSocket(
    games: GameService,
    connections: GameConnections,
    guestSessions: GuestSessionService,
) {
    webSocket(GAME_SOCKET_ROUTE) {
        val token = call.request.queryParameters[GAME_SOCKET_TOKEN_PARAMETER]
        val sessionId = token?.let { guestSessions.findSessionIdByToken(it) }
        if (sessionId == null) {
            close(CloseReason(CLOSE_UNAUTHORIZED, UNAUTHORIZED_CODE))
            return@webSocket
        }
        val gameId = call.parameters[GAME_ID_PARAMETER]?.let(::parseUuidOrNull)
        if (gameId == null) {
            close(CloseReason(CLOSE_NOT_FOUND, GAME_NOT_FOUND_CODE))
            return@webSocket
        }
        val snapshot = when (val result = games.snapshot(gameId, sessionId)) {
            is GameSnapshotResult.Success -> result.snapshot
            GameSnapshotResult.NotParticipant -> {
                close(CloseReason(CLOSE_FORBIDDEN, NOT_A_GAME_PARTICIPANT_CODE))
                return@webSocket
            }
            GameSnapshotResult.NotFound -> {
                close(CloseReason(CLOSE_NOT_FOUND, GAME_NOT_FOUND_CODE))
                return@webSocket
            }
        }

        val connection = GameConnection(sessionId, this)
        connections.register(gameId, connection)
        try {
            connection.send(SnapshotEvent(snapshot))
            connections.broadcast(gameId, PlayerJoinedEvent(snapshot.yourColor), except = connection)
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    handleCommand(frame.readText(), games, connections, gameId, sessionId, connection)
                }
            }
        } finally {
            connections.unregister(gameId, connection)
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
        connection.send(CommandRejectedEvent(commandId = null, code = MALFORMED_COMMAND_CODE))
        return
    }
    if (command.protocolVersion != GAME_PROTOCOL_VERSION) {
        connection.send(CommandRejectedEvent(command.commandId, UNSUPPORTED_PROTOCOL_VERSION_CODE))
        return
    }
    when (command) {
        is RequestSync -> {
            val result = games.snapshot(gameId, sessionId)
            if (result is GameSnapshotResult.Success) {
                connection.send(SnapshotEvent(result.snapshot))
            }
        }
        is MakeMove -> {
            val result = games.submitMove(gameId, sessionId, command.commandId, command.expectedRevision, command.uci)
            when (result) {
                is MoveResult.Applied ->
                    connections.broadcast(gameId, result.snapshot.toMoveApplied(command.uci))
                is MoveResult.RevisionConflict -> {
                    connection.send(CommandRejectedEvent(command.commandId, REVISION_CONFLICT_CODE))
                    connection.send(SnapshotEvent(result.snapshot))
                }
                is MoveResult.DuplicateCommand ->
                    result.snapshot?.let { connection.send(SnapshotEvent(it)) }
                else ->
                    connection.send(CommandRejectedEvent(command.commandId, result.rejectionCode()))
            }
        }
        is Resign -> {
            val result = games.submitResign(gameId, sessionId, command.commandId)
            when (result) {
                is MoveResult.Applied ->
                    connections.broadcast(gameId, result.snapshot.toGameFinished())
                is MoveResult.DuplicateCommand ->
                    result.snapshot?.let { connection.send(SnapshotEvent(it)) }
                else ->
                    connection.send(CommandRejectedEvent(command.commandId, result.rejectionCode()))
            }
        }
    }
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
    is MoveResult.Applied,
    is MoveResult.RevisionConflict,
    is MoveResult.DuplicateCommand -> error("Not a rejection: $this")
}
