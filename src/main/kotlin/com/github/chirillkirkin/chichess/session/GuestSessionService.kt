package com.github.chirillkirkin.chichess.session

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import kotlinx.serialization.Serializable

private const val TOKEN_BYTE_COUNT = 32

@Serializable
data class GuestSessionResponse(val sessionId: String, val token: String)

interface GuestSessionRepository {
    suspend fun create(sessionId: UUID, tokenHash: String)
    suspend fun findSessionIdByTokenHash(tokenHash: String): UUID?
}

class GuestSessionService(
    private val repository: GuestSessionRepository,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    suspend fun create(): GuestSessionResponse {
        val tokenBytes = ByteArray(TOKEN_BYTE_COUNT).also(secureRandom::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val sessionId = UUID.randomUUID()
        repository.create(sessionId, hashToken(token))
        return GuestSessionResponse(sessionId.toString(), token)
    }

    suspend fun findSessionIdByToken(token: String): UUID? =
        repository.findSessionIdByTokenHash(hashToken(token))

    private fun hashToken(token: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
}
