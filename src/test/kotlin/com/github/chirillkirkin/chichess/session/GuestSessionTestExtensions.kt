package com.github.chirillkirkin.chichess.session

import com.github.chirillkirkin.chichess.decodeJsonBody
import io.ktor.client.HttpClient
import io.ktor.client.request.post

internal const val INVALID_GUEST_TOKEN = "invalid"

internal suspend fun HttpClient.createGuestSession(): GuestSessionResponse =
    post(GUEST_SESSION_ROUTE).decodeJsonBody()
