package com.github.chirillkirkin.chichess.game

import java.util.Random
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val GAME_TEST_RANDOM_SEED = 73L

class GameServiceTest {
    @Test
    fun `create generates valid identifiers and persists game`() = runBlocking {
        val repository = FakeGameRepository()
        val service = GameService(repository, Random(GAME_TEST_RANDOM_SEED))
        val creatorSessionId = UUID.randomUUID()

        val response = service.create(creatorSessionId)

        assertEquals(INVITE_CODE_LENGTH, response.inviteCode.length)
        assertTrue(response.inviteCode.all(INVITE_CODE_ALPHABET::contains))
        assertEquals(UUID.fromString(response.gameId), repository.createdGameId)
        assertEquals(response.inviteCode, repository.createdInviteCode)
        assertEquals(creatorSessionId, repository.creatorSessionId)
    }

}

private class FakeGameRepository : GameRepository {
    var createdGameId: UUID? = null
    var createdInviteCode: String? = null
    var creatorSessionId: UUID? = null

    override suspend fun create(gameId: UUID, inviteCode: String, creatorSessionId: UUID) {
        createdGameId = gameId
        createdInviteCode = inviteCode
        this.creatorSessionId = creatorSessionId
    }

    override suspend fun join(inviteCode: String, joiningSessionId: UUID): JoinGameResult =
        JoinGameResult.NotFound
}
