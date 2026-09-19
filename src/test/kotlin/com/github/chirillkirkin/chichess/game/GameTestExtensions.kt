package com.github.chirillkirkin.chichess.game

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

internal suspend fun HttpClient.postCreateGame(token: String): HttpResponse = post(GAME_ROUTE) {
    bearerAuth(token)
}

internal suspend fun HttpClient.postJoinGame(inviteCode: String, token: String? = null): HttpResponse =
    post(GAME_JOIN_ROUTE) {
        token?.let(::bearerAuth)
        contentType(ContentType.Application.Json)
        setBody(Json.encodeToString(JoinGameRequest(inviteCode)))
    }
