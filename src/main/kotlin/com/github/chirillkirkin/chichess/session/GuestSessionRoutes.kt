package com.github.chirillkirkin.chichess.session

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.koin.ktor.ext.getKoin

fun Application.configureGuestSessionRoutes() {
    val guestSessions = getKoin().get<GuestSessionService>()
    routing {
        post("/sessions/guest") {
            val session = guestSessions.create()
            call.respond(HttpStatusCode.Created, session)
        }
    }
}
