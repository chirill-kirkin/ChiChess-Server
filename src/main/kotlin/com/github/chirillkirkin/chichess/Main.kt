package com.github.chirillkirkin.chichess

import com.github.chirillkirkin.chichess.config.configureExposed
import com.github.chirillkirkin.chichess.config.configureKoin
import com.github.chirillkirkin.chichess.config.configureSerialization
import com.github.chirillkirkin.chichess.config.configureWebsockets
import io.ktor.server.application.Application
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.configure
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

private const val DEFAULT_PORT = 8080

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val environment = applicationEnvironment {
        configure("application.yaml")
    }

    embeddedServer(Netty, environment, configure = {
        connector {
            this.port = port
        }
    }, module = Application::module).start(wait = true)
}

fun Application.module() {
    configureSerialization()
    configureKoin()
    configureExposed()
    configureWebsockets()
}
