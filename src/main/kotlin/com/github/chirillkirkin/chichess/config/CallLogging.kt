package com.github.chirillkirkin.chichess.config

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.event.Level

fun Application.configureCallLogging() {
    install(CallLogging) {
        level = Level.INFO
        // Status/method/path only — never headers, so the bearer token stays out of the logs.
        format { call -> "${call.response.status()}: ${call.request.httpMethod.value} ${call.request.path()}" }
    }
}
