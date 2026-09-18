package com.github.chirillkirkin.chichess.session

import com.github.chirillkirkin.chichess.api.ApiErrorResponse
import com.github.chirillkirkin.chichess.module
import com.github.chirillkirkin.chichess.config.GUEST_AUTHENTICATION
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.sql.DriverManager
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GuestSessionTest {
    @Test
    fun `guest token persists and authenticates after restart`() {
        val databaseFile = Files.createTempFile("chichess-guests-", ".sqlite")
        val databaseUrl = "jdbc:sqlite:$databaseFile"

        try {
            lateinit var session: GuestSessionResponse
            testApplication {
                environment { config = MapApplicationConfig("database.url" to databaseUrl) }
                application { module() }

                val firstResponse = client.post("/sessions/guest")
                assertEquals(HttpStatusCode.Created, firstResponse.status)
                session = Json.decodeFromString<GuestSessionResponse>(firstResponse.bodyAsText())

                val secondResponse = client.post("/sessions/guest")
                val secondSession = Json.decodeFromString<GuestSessionResponse>(secondResponse.bodyAsText())
                assertNotEquals(session.sessionId, secondSession.sessionId)
                assertNotEquals(session.token, secondSession.token)
            }

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT token_hash FROM guest_sessions").use { rows ->
                        assertTrue(rows.next())
                        assertNotEquals(rows.getString("token_hash"), session.token)
                    }
                }
            }

            testApplication {
                environment { config = MapApplicationConfig("database.url" to databaseUrl) }
                application {
                    module()
                    routing {
                        authenticate(GUEST_AUTHENTICATION) {
                            get("/test/guest") {
                                call.respondText(call.principal<UserIdPrincipal>()!!.name)
                            }
                        }
                    }
                }

                val authorized = client.get("/test/guest") {
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
                assertEquals(HttpStatusCode.OK, authorized.status)
                assertEquals(session.sessionId, authorized.bodyAsText())

                val unauthorized = client.get("/test/guest") {
                    header(HttpHeaders.Authorization, "Bearer invalid")
                }
                assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
            }
        } finally {
            Files.deleteIfExists(databaseFile)
        }
    }

    @Test
    fun `database failure returns a safe server error`() {
        val databaseFile = Files.createTempFile("chichess-guests-failure-", ".sqlite")
        val databaseUrl = "jdbc:sqlite:$databaseFile"

        try {
            testApplication {
                environment { config = MapApplicationConfig("database.url" to databaseUrl) }
                application { module() }
                startApplication()

                DriverManager.getConnection(databaseUrl).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("DROP TABLE guest_sessions")
                    }
                }

                val response = client.post("/sessions/guest")
                assertEquals(HttpStatusCode.InternalServerError, response.status)
                val body = response.bodyAsText()
                assertEquals("INTERNAL_ERROR", Json.decodeFromString<ApiErrorResponse>(body).code)
                assertFalse(body.contains("SQLITE_ERROR"))
            }
        } finally {
            Files.deleteIfExists(databaseFile)
        }
    }
}
