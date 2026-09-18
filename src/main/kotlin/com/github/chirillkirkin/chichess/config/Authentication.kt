package com.github.chirillkirkin.chichess.config

import com.github.chirillkirkin.chichess.session.GuestSessionService
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import org.koin.ktor.ext.getKoin

const val GUEST_AUTHENTICATION = "guest"

fun Application.configureGuestAuthentication() {
    val guestSessions = getKoin().get<GuestSessionService>()
    install(Authentication) {
        bearer(GUEST_AUTHENTICATION) {
            realm = "ChiChess"
            authenticate { credentials ->
                guestSessions.findSessionIdByToken(credentials.token)?.let { UserIdPrincipal(it.toString()) }
            }
        }
    }
}
