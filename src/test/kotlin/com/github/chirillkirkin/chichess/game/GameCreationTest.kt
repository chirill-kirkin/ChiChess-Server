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
                    "SELECT ${Games.whiteSessionId.name}, ${Games.blackSessionId.name}, " +
                        "${Games.inviteCode.name}, ${Games.status.name}, ${Games.revision.name} " +
                        "FROM ${Games.tableName} WHERE ${Games.id.name} = ?"
                connection.prepareStatement(selectCreatedGame).use { query ->
                    query.setString(1, firstGame.gameId)
                    query.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        val white = rows.getString(Games.whiteSessionId.name)
                        val black = rows.getString(Games.blackSessionId.name)
                        // The creator occupies exactly one randomly assigned color slot.
                        assertEquals(session.sessionId, white ?: black)
                        assertTrue(white == null || black == null)
                        assertEquals(firstGame.inviteCode, rows.getString(Games.inviteCode.name))
                        assertEquals(GameStatus.WAITING_FOR_OPPONENT.name, rows.getString(Games.status.name))
                        assertEquals(INITIAL_REVISION, rows.getLong(Games.revision.name))
                    }
                }
            }
        }
    }

}
