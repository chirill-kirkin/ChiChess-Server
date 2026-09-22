package com.github.chirillkirkin.chichess.game

import com.github.chirillkirkin.chichess.api.ApiErrorResponse
import com.github.chirillkirkin.chichess.config.GUEST_AUTHENTICATION
import com.github.chirillkirkin.chichess.config.guestSessionId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.UUID

const val GAME_ROUTE = "/game"
const val GAME_JOIN_ROUTE = "$GAME_ROUTE/join"
const val GAME_ID_PARAMETER = "id"
const val GAME_BY_ID_ROUTE = "$GAME_ROUTE/{$GAME_ID_PARAMETER}"
const val GAMES_HISTORY_ROUTE = "/games/history"
const val GAME_NOT_FOUND_CODE = "GAME_NOT_FOUND"
const val CANNOT_JOIN_OWN_GAME_CODE = "CANNOT_JOIN_OWN_GAME"
const val GAME_ALREADY_JOINED_CODE = "GAME_ALREADY_JOINED"
const val NOT_A_GAME_PARTICIPANT_CODE = "NOT_A_GAME_PARTICIPANT"

fun Route.gameRoutes(games: GameService) {
    authenticate(GUEST_AUTHENTICATION) {
        post(GAME_ROUTE) {
            val sessionId = call.guestSessionId()
            call.respond(HttpStatusCode.Created, games.create(sessionId))
        }
        post(GAME_JOIN_ROUTE) {
            val sessionId = call.guestSessionId()
            val request = call.receive<JoinGameRequest>()
            when (val result = games.join(request.inviteCode, sessionId)) {
                is JoinGameResult.Joined -> call.respond(
                    HttpStatusCode.OK,
                    JoinGameResponse(result.gameId.toString()),
                )
                JoinGameResult.NotFound -> call.respond(
                    HttpStatusCode.NotFound,
                    ApiErrorResponse(GAME_NOT_FOUND_CODE),
                )
                JoinGameResult.OwnGame -> call.respond(
                    HttpStatusCode.Conflict,
                    ApiErrorResponse(CANNOT_JOIN_OWN_GAME_CODE),
                )
                JoinGameResult.AlreadyJoined -> call.respond(
                    HttpStatusCode.Conflict,
                    ApiErrorResponse(GAME_ALREADY_JOINED_CODE),
                )
            }
        }
        get(GAME_BY_ID_ROUTE) {
            val sessionId = call.guestSessionId()
            val gameId = call.parameters[GAME_ID_PARAMETER]?.let(::parseUuidOrNull)
            if (gameId == null) {
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse(GAME_NOT_FOUND_CODE))
                return@get
            }
            when (val result = games.snapshot(gameId, sessionId)) {
                is GameSnapshotResult.Success -> call.respond(HttpStatusCode.OK, result.snapshot)
                GameSnapshotResult.NotFound -> call.respond(
                    HttpStatusCode.NotFound,
                    ApiErrorResponse(GAME_NOT_FOUND_CODE),
                )
                GameSnapshotResult.NotParticipant -> call.respond(
                    HttpStatusCode.Forbidden,
                    ApiErrorResponse(NOT_A_GAME_PARTICIPANT_CODE),
                )
            }
        }
        get(GAMES_HISTORY_ROUTE) {
            val sessionId = call.guestSessionId()
            call.respond(HttpStatusCode.OK, games.history(sessionId))
        }
    }
}

private fun parseUuidOrNull(value: String): UUID? =
    try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        null
    }
