package com.github.chirillkirkin.chichess.session

import java.util.Base64
import java.util.Random
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val GUEST_SESSION_TEST_RANDOM_SEED = 42L

class GuestSessionServiceTest {
    @Test
    fun `create persists token hash and token authenticates`() = runBlocking {
        val repository = FakeGuestSessionRepository()
        val service = GuestSessionService(repository, Random(GUEST_SESSION_TEST_RANDOM_SEED))

        val session = service.create()

        assertEquals(TOKEN_BYTE_COUNT, Base64.getUrlDecoder().decode(session.token).size)
        assertFalse(repository.sessionIdsByTokenHash.containsKey(session.token))
        assertEquals(UUID.fromString(session.sessionId), service.findSessionIdByToken(session.token))
    }
}

private class FakeGuestSessionRepository : GuestSessionRepository {
    val sessionIdsByTokenHash = mutableMapOf<String, UUID>()

    override suspend fun create(sessionId: UUID, tokenHash: String) {
        sessionIdsByTokenHash[tokenHash] = sessionId
    }

    override suspend fun findSessionIdByTokenHash(tokenHash: String): UUID? =
        sessionIdsByTokenHash[tokenHash]
}
