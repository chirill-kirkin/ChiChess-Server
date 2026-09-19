package com.github.chirillkirkin.chichess.config

import com.github.chirillkirkin.chichess.game.GameService
import com.github.chirillkirkin.chichess.game.gameRoutes
import com.github.chirillkirkin.chichess.session.GuestSessionService
import com.github.chirillkirkin.chichess.session.guestSessionRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import org.koin.ktor.ext.getKoin

fun Application.configureRouting() {
    val guestSessions = getKoin().get<GuestSessionService>()
    val games = getKoin().get<GameService>()

    routing {
        guestSessionRoutes(guestSessions)
        gameRoutes(games)
    }
}
