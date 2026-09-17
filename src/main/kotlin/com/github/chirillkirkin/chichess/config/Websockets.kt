package com.github.chirillkirkin.chichess.config

import io.ktor.server.application.*
import io.ktor.server.websocket.*

fun Application.configureWebsockets() {
    install(WebSockets)
}
