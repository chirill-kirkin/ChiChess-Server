package com.github.chirillkirkin.chichess.game

import com.github.chirillkirkin.chichess.api.ApiErrorResponse
import com.github.chirillkirkin.chichess.config.GUEST_AUTHENTICATION
import com.github.chirillkirkin.chichess.config.guestSessionId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

const val GAME_ROUTE = "/game"
const val GAME_JOIN_ROUTE = "$GAME_ROUTE/join"
const val GAME_NOT_FOUND_CODE = "GAME_NOT_FOUND"
const val CANNOT_JOIN_OWN_GAME_CODE = "CANNOT_JOIN_OWN_GAME"
const val GAME_ALREADY_JOINED_CODE = "GAME_ALREADY_JOINED"

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
    }
}
