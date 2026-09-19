package com.github.chirillkirkin.chichess.session

import com.github.chirillkirkin.chichess.configureTestApplication
import com.github.chirillkirkin.chichess.config.guestSessionId
import com.github.chirillkirkin.chichess.config.GUEST_AUTHENTICATION
import com.github.chirillkirkin.chichess.decodeJsonBody
import com.github.chirillkirkin.chichess.withDatabaseConnection
import com.github.chirillkirkin.chichess.withTestDatabase
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val TEST_GUEST_ROUTE = "/test/guest"

class GuestSessionTest {
    @Test
    fun `guest token persists and authenticates after restart`() {
        withTestDatabase { databaseUrl ->
            lateinit var session: GuestSessionResponse
            testApplication {
                configureTestApplication(databaseUrl)

                val firstResponse = client.post(GUEST_SESSION_ROUTE)
                assertEquals(HttpStatusCode.Created, firstResponse.status)
                session = firstResponse.decodeJsonBody()

                val secondResponse = client.post(GUEST_SESSION_ROUTE)
                val secondSession = secondResponse.decodeJsonBody<GuestSessionResponse>()
                assertNotEquals(session.sessionId, secondSession.sessionId)
                assertNotEquals(session.token, secondSession.token)
            }

            withDatabaseConnection(databaseUrl) { connection ->
                connection.createStatement().use { statement ->
                    val selectTokenHash = "SELECT ${GuestSessions.tokenHash.name} FROM ${GuestSessions.tableName}"
                    statement.executeQuery(selectTokenHash).use { rows ->
                        assertTrue(rows.next())
                        assertNotEquals(rows.getString(GuestSessions.tokenHash.name), session.token)
                    }
                }
            }

            testApplication {
                configureTestApplication(databaseUrl) {
                    routing {
                        authenticate(GUEST_AUTHENTICATION) {
                            get(TEST_GUEST_ROUTE) {
                                call.respondText(call.guestSessionId().toString())
                            }
                        }
                    }
                }

                val authorized = client.get(TEST_GUEST_ROUTE) {
                    bearerAuth(session.token)
                }
                assertEquals(HttpStatusCode.OK, authorized.status)
                assertEquals(session.sessionId, authorized.bodyAsText())

                val unauthorized = client.get(TEST_GUEST_ROUTE) {
                    bearerAuth(INVALID_GUEST_TOKEN)
                }
                assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
            }
        }
    }
}
