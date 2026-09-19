package com.github.chirillkirkin.chichess.config

import com.github.chirillkirkin.chichess.session.GuestSessionService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import java.util.UUID
import org.koin.ktor.ext.getKoin

const val GUEST_AUTHENTICATION = "guest"
private const val AUTHENTICATION_REALM = "ChiChess"

data class GuestPrincipal(val sessionId: UUID)

fun ApplicationCall.guestSessionId(): UUID = checkNotNull(principal<GuestPrincipal>()).sessionId

fun Application.configureGuestAuthentication() {
    val guestSessions = getKoin().get<GuestSessionService>()
    install(Authentication) {
        bearer(GUEST_AUTHENTICATION) {
            realm = AUTHENTICATION_REALM
            authenticate { credentials ->
                guestSessions.findSessionIdByToken(credentials.token)?.let(::GuestPrincipal)
            }
        }
    }
}
