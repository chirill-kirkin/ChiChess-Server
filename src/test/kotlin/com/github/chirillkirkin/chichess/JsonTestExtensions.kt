package com.github.chirillkirkin.chichess

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json

internal suspend inline fun <reified T> HttpResponse.decodeJsonBody(): T = Json.decodeFromString(bodyAsText())
