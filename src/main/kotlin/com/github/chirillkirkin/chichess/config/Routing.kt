package com.github.chirillkirkin.chichess.config

import com.github.chirillkirkin.chichess.session.GuestSessionService
import com.github.chirillkirkin.chichess.session.guestSessionRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import org.koin.ktor.ext.getKoin

fun Application.configureRouting() {
    val guestSessions = getKoin().get<GuestSessionService>()

    routing {
        guestSessionRoutes(guestSessions)
    }
}
