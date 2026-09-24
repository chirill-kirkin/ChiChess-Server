package com.github.chirillkirkin.chichess.game

import com.github.chirillkirkin.chichess.api.ApiErrorResponse
import com.github.chirillkirkin.chichess.configureTestApplication
import com.github.chirillkirkin.chichess.decodeJsonBody
import com.github.chirillkirkin.chichess.session.GuestSessionResponse
import com.github.chirillkirkin.chichess.session.createGuestSession
import com.github.chirillkirkin.chichess.withTestDatabase
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GameSnapshotTest {
    @Test
    fun `unauthenticated guest cannot read a game`() = testGame { game ->
        assertEquals(HttpStatusCode.Unauthorized, client.getGame(game.gameId).status)
    }

    @Test
    fun `creator reads a waiting game snapshot`() = testGame { game ->
        val response = client.getGame(game.gameId, game.creator.token)

        assertEquals(HttpStatusCode.OK, response.status)
        val snapshot = response.decodeJsonBody<GameSnapshot>()
        assertEquals(game.gameId, snapshot.gameId)
        assertEquals(game.inviteCode, snapshot.inviteCode)
        assertEquals(GameStatus.WAITING_FOR_OPPONENT, snapshot.status)
        assertEquals(INITIAL_REVISION, snapshot.revision)
    }

    @Test
    fun `unknown game id returns not found`() = testGame {
        val reader = client.createGuestSession()

        val response = client.getGame(UUID.randomUUID().toString(), reader.token)

        assertError(response, HttpStatusCode.NotFound, GAME_NOT_FOUND_CODE)
    }

    @Test
    fun `malformed game id returns not found`() = testGame {
        val reader = client.createGuestSession()

        val response = client.getGame(MALFORMED_GAME_ID, reader.token)

        assertError(response, HttpStatusCode.NotFound, GAME_NOT_FOUND_CODE)
    }

    @Test
    fun `non-participant cannot read a game`() = testGame { game ->
        val outsider = client.createGuestSession()

        val response = client.getGame(game.gameId, outsider.token)

        assertError(response, HttpStatusCode.Forbidden, NOT_A_GAME_PARTICIPANT_CODE)
    }

    @Test
    fun `both players read an in-progress game with opposite colors`() = testGame { game ->
        val joiner = client.createGuestSession()
        client.postJoinGame(game.inviteCode, joiner.token)

        val creatorSnapshot = client.getGame(game.gameId, game.creator.token).decodeJsonBody<GameSnapshot>()
        val joinerSnapshot = client.getGame(game.gameId, joiner.token).decodeJsonBody<GameSnapshot>()

        assertEquals(GameStatus.IN_PROGRESS, creatorSnapshot.status)
        assertEquals(GameStatus.IN_PROGRESS, joinerSnapshot.status)
        assertNotEquals(creatorSnapshot.yourColor, joinerSnapshot.yourColor)
    }

    @Test
    fun `unauthenticated guest cannot read history`() = testGame {
        assertEquals(HttpStatusCode.Unauthorized, client.getGamesHistory().status)
    }

    @Test
    fun `history returns only the sessions own games`() = testGame { game ->
        val joiner = client.createGuestSession()
        client.postJoinGame(game.inviteCode, joiner.token)
        val outsider = client.createGuestSession()

        val creatorHistory = client.getGamesHistory(game.creator.token).decodeJsonBody<List<GameSnapshot>>()
        val joinerHistory = client.getGamesHistory(joiner.token).decodeJsonBody<List<GameSnapshot>>()
        val outsiderHistory = client.getGamesHistory(outsider.token).decodeJsonBody<List<GameSnapshot>>()

        assertEquals(listOf(game.gameId), creatorHistory.map { it.gameId })
        assertEquals(listOf(game.gameId), joinerHistory.map { it.gameId })
        assertTrue(outsiderHistory.isEmpty())
    }

    private fun testGame(test: suspend ApplicationTestBuilder.(GameFixture) -> Unit) {
        withTestDatabase { databaseUrl ->
            testApplication {
                configureTestApplication(databaseUrl)
                val creator = client.createGuestSession()
                val game = client.postCreateGame(creator.token).decodeJsonBody<CreateGameResponse>()
                test(GameFixture(game.gameId, game.inviteCode, creator))
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
    )
}
