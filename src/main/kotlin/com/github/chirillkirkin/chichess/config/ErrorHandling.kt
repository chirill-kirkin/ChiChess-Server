package com.github.chirillkirkin.chichess.config

import com.github.chirillkirkin.chichess.api.ApiErrorResponse
import com.github.chirillkirkin.chichess.api.INTERNAL_ERROR_CODE
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException

fun Application.configureErrorHandling() {
    val logger = log
    install(StatusPages) {
        exception<Exception> { call, cause ->
            if (cause is CancellationException) throw cause
            logger.error("Request failed", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiErrorResponse(INTERNAL_ERROR_CODE))
        }
    }
}
