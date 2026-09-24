package com.github.chirillkirkin.chichess.config

import io.ktor.server.application.*
import io.ktor.server.websocket.*
import kotlin.time.Duration.Companion.seconds

private val WEBSOCKET_PING_PERIOD = 15.seconds
private val WEBSOCKET_TIMEOUT = 15.seconds

fun Application.configureWebsockets() {
    install(WebSockets) {
        pingPeriod = WEBSOCKET_PING_PERIOD
        timeout = WEBSOCKET_TIMEOUT
    }
}
