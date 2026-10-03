package com.github.chirillkirkin.chichess.game

import com.github.chirillkirkin.chichess.api.ApiErrorResponse
import com.github.chirillkirkin.chichess.configureTestApplication
import com.github.chirillkirkin.chichess.decodeJsonBody
import com.github.chirillkirkin.chichess.session.GuestSessionResponse
import com.github.chirillkirkin.chichess.session.createGuestSession
import com.github.chirillkirkin.chichess.withDatabaseConnection
import com.github.chirillkirkin.chichess.withTestDatabase
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val UNKNOWN_INVITE_CODE = "AAAAAAAAAA"

class GameJoiningTest {
    @Test
    fun `unauthenticated guest cannot join game`() = testGameJoining { game ->
        val response = client.postJoinGame(game.inviteCode)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `unknown invite code returns not found`() = testGameJoining {
        val joiningPlayer = client.createGuestSession()

        val response = client.postJoinGame(UNKNOWN_INVITE_CODE, joiningPlayer.token)

        assertError(response, HttpStatusCode.NotFound, GAME_NOT_FOUND_CODE)
    }

    @Test
    fun `creator cannot join own game`() = testGameJoining { game ->
        val response = client.postJoinGame(game.inviteCode, game.creator.token)

        assertError(response, HttpStatusCode.Conflict, CANNOT_JOIN_OWN_GAME_CODE)
    }

    @Test
    fun `second guest joins game by invite code`() = testGameJoining { game ->
        val joiningPlayer = client.createGuestSession()

        val response = client.postJoinGame(game.inviteCode, joiningPlayer.token)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(JoinGameResponse(game.gameId), response.decodeJsonBody<JoinGameResponse>())
        withDatabaseConnection(game.databaseUrl) { connection ->
            val selectJoinedGame =
                "SELECT ${Games.whiteSessionId.name}, ${Games.blackSessionId.name}, " +
                    "${Games.status.name}, ${Games.revision.name} " +
                    "FROM ${Games.tableName} WHERE ${Games.id.name} = ?"
            connection.prepareStatement(selectJoinedGame).use { query ->
                query.setString(1, game.gameId)
                query.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    val white = rows.getString(Games.whiteSessionId.name)
                    val black = rows.getString(Games.blackSessionId.name)
                    // Both color slots are filled: creator and joiner, one per color.
                    assertEquals(setOf(game.creator.sessionId, joiningPlayer.sessionId), setOf(white, black))
                    assertEquals(GameStatus.IN_PROGRESS.name, rows.getString(Games.status.name))
                    assertEquals(INITIAL_REVISION + 1, rows.getLong(Games.revision.name))
                }
            }
        }
    }

    @Test
    fun `participant reusing invite code of full game rejoins it`() = testGameJoining { game ->
        val joiningPlayer = client.createGuestSession()
        client.postJoinGame(game.inviteCode, joiningPlayer.token)
        val beforeRejoin = client.getGame(game.gameId, joiningPlayer.token).decodeJsonBody<GameSnapshot>()

        // Colors are random, so one of these two holds the white slot. Neither may be treated as
        // "your own game": once both slots are filled a participant always gets their game back.
        val joinerRejoin = client.postJoinGame(game.inviteCode, joiningPlayer.token)
        val creatorRejoin = client.postJoinGame(game.inviteCode, game.creator.token)

        val expected = JoinGameResponse(game.gameId)
        assertEquals(HttpStatusCode.OK, joinerRejoin.status)
        assertEquals(expected, joinerRejoin.decodeJsonBody<JoinGameResponse>())
        assertEquals(HttpStatusCode.OK, creatorRejoin.status)
        assertEquals(expected, creatorRejoin.decodeJsonBody<JoinGameResponse>())
        assertEquals(beforeRejoin, client.getGame(game.gameId, joiningPlayer.token).decodeJsonBody<GameSnapshot>())
    }

    @Test
    fun `participant reusing invite code of finished game rejoins it`() = testGameJoining { game ->
        val joiningPlayer = client.createGuestSession()
        client.postJoinGame(game.inviteCode, joiningPlayer.token)
        finishGame(game)

        val response = client.postJoinGame(game.inviteCode, joiningPlayer.token)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(JoinGameResponse(game.gameId), response.decodeJsonBody<JoinGameResponse>())
    }

    @Test
    fun `non-participant joining finished game reports game finished`() = testGameJoining { game ->
        val joiningPlayer = client.createGuestSession()
        val thirdPlayer = client.createGuestSession()
        client.postJoinGame(game.inviteCode, joiningPlayer.token)
        finishGame(game)

        val response = client.postJoinGame(game.inviteCode, thirdPlayer.token)

        assertError(response, HttpStatusCode.Conflict, GAME_FINISHED_CODE)
    }

    @Test
    fun `third guest cannot join occupied game`() = testGameJoining { game ->
        val joiningPlayer = client.createGuestSession()
        val thirdPlayer = client.createGuestSession()
        client.postJoinGame(game.inviteCode, joiningPlayer.token)

        val response = client.postJoinGame(game.inviteCode, thirdPlayer.token)

        assertError(response, HttpStatusCode.Conflict, GAME_ALREADY_JOINED_CODE)
    }

    private fun testGameJoining(test: suspend ApplicationTestBuilder.(GameFixture) -> Unit) {
        withTestDatabase { databaseUrl ->
            testApplication {
                configureTestApplication(databaseUrl)
                val creator = client.createGuestSession()
                val game = client.postCreateGame(creator.token).decodeJsonBody<CreateGameResponse>()
                test(GameFixture(game.gameId, game.inviteCode, creator, databaseUrl))
            }
        }
    }

    private fun finishGame(game: GameFixture) {
        withDatabaseConnection(game.databaseUrl) { connection ->
            val finishGame = "UPDATE ${Games.tableName} SET ${Games.status.name} = ? WHERE ${Games.id.name} = ?"
            connection.prepareStatement(finishGame).use { statement ->
                statement.setString(1, GameStatus.FINISHED.name)
                statement.setString(2, game.gameId)
                statement.executeUpdate()
            }
        }
    }

    private suspend fun assertError(
        response: HttpResponse,
        expectedStatus: HttpStatusCode,
        expectedCode: String,
    ) {
        assertEquals(expectedStatus, response.status)
        assertEquals(expectedCode, response.decodeJsonBody<ApiErrorResponse>().code)
    }

    private data class GameFixture(
        val gameId: String,
        val inviteCode: String,
        val creator: GuestSessionResponse,
        val databaseUrl: String,
    )
}
