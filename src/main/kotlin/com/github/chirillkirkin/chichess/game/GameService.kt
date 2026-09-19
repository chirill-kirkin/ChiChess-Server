package com.github.chirillkirkin.chichess.game

import java.util.UUID
import java.util.random.RandomGenerator
import kotlinx.serialization.Serializable

internal const val INVITE_CODE_LENGTH = 10
internal const val INVITE_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

@Serializable
data class CreateGameResponse(val gameId: String, val inviteCode: String)

@Serializable
data class JoinGameRequest(val inviteCode: String)

@Serializable
data class JoinGameResponse(val gameId: String)

sealed interface JoinGameResult {
    data class Joined(val gameId: UUID) : JoinGameResult
    data object NotFound : JoinGameResult
    data object OwnGame : JoinGameResult
    data object AlreadyJoined : JoinGameResult
}

interface GameRepository {
    suspend fun create(gameId: UUID, inviteCode: String, creatorSessionId: UUID)
    suspend fun join(inviteCode: String, joiningSessionId: UUID): JoinGameResult
}

class GameService(
    private val repository: GameRepository,
    private val secureRandom: RandomGenerator,
) {
    suspend fun create(creatorSessionId: UUID): CreateGameResponse {
        val gameId = UUID.randomUUID()
        val inviteCode = buildString(INVITE_CODE_LENGTH) {
            repeat(INVITE_CODE_LENGTH) {
                append(INVITE_CODE_ALPHABET[secureRandom.nextInt(INVITE_CODE_ALPHABET.length)])
            }
        }
        repository.create(gameId, inviteCode, creatorSessionId)
        return CreateGameResponse(gameId.toString(), inviteCode)
    }

    suspend fun join(inviteCode: String, joiningSessionId: UUID): JoinGameResult =
        repository.join(inviteCode, joiningSessionId)
}
