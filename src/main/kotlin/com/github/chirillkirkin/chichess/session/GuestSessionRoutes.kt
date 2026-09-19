package com.github.chirillkirkin.chichess.session

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

const val GUEST_SESSION_ROUTE = "/sessions/guest"

fun Route.guestSessionRoutes(guestSessions: GuestSessionService) {
    post(GUEST_SESSION_ROUTE) {
        val session = guestSessions.create()
        call.respond(HttpStatusCode.Created, session)
    }
}
