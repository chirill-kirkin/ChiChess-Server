package com.github.chirillkirkin.chichess.game

import com.github.chirillkirkin.chichess.configureTestApplication
import com.github.chirillkirkin.chichess.decodeJsonBody
import com.github.chirillkirkin.chichess.session.INVALID_GUEST_TOKEN
import com.github.chirillkirkin.chichess.session.GuestSessionResponse
import com.github.chirillkirkin.chichess.session.createGuestSession
import com.github.chirillkirkin.chichess.withDatabaseConnection
import com.github.chirillkirkin.chichess.withTestDatabase
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameCreationTest {
    @Test
    fun `authenticated guest creates a persisted game`() {
        withTestDatabase { databaseUrl ->
            lateinit var session: GuestSessionResponse
            lateinit var firstGame: CreateGameResponse
            testApplication {
                configureTestApplication(databaseUrl)

                val unauthorized = client.post(GAME_ROUTE)
                assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
                val invalidToken = client.post(GAME_ROUTE) {
                    bearerAuth(INVALID_GUEST_TOKEN)
                }
                assertEquals(HttpStatusCode.Unauthorized, invalidToken.status)

                session = client.createGuestSession()

                val firstResponse = client.postCreateGame(session.token)
                assertEquals(HttpStatusCode.Created, firstResponse.status)
                firstGame = firstResponse.decodeJsonBody()
            }

            withDatabaseConnection(databaseUrl) { connection ->
                val selectCreatedGame =
                    "SELECT ${Games.creatorSessionId.name}, ${Games.inviteCode.name} " +
                        "FROM ${Games.tableName} WHERE ${Games.id.name} = ?"
                connection.prepareStatement(selectCreatedGame).use { query ->
                    query.setString(1, firstGame.gameId)
                    query.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(session.sessionId, rows.getString(Games.creatorSessionId.name))
                        assertEquals(firstGame.inviteCode, rows.getString(Games.inviteCode.name))
                    }
                }
            }
        }
    }

}
