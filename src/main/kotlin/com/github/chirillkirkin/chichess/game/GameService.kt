package com.github.chirillkirkin.chichess.game

import java.util.UUID
import java.util.random.RandomGenerator
import kotlinx.serialization.Serializable

internal const val INVITE_CODE_LENGTH = 10
internal const val INVITE_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
internal const val INITIAL_REVISION = 0L

@Serializable
data class CreateGameResponse(val gameId: String, val inviteCode: String)

@Serializable
data class JoinGameRequest(val inviteCode: String)

@Serializable
data class JoinGameResponse(val gameId: String)

@Serializable
data class GameSnapshot(
    val gameId: String,
    val inviteCode: String,
    val yourColor: PieceColor,
    val status: GameStatus,
    val revision: Long,
    val fen: String,
    val result: GameResult? = null,
    val terminationReason: TerminationReason? = null,
)

sealed interface JoinGameResult {
    data class Joined(val gameId: UUID) : JoinGameResult
    data object NotFound : JoinGameResult
    data object OwnGame : JoinGameResult
    data object AlreadyJoined : JoinGameResult
}

sealed interface GameSnapshotResult {
    data class Success(val snapshot: GameSnapshot) : GameSnapshotResult
    data object NotFound : GameSnapshotResult
    data object NotParticipant : GameSnapshotResult
}

sealed interface MoveResult {
    data class Applied(val snapshot: GameSnapshot) : MoveResult
    data object NotFound : MoveResult
    data object NotParticipant : MoveResult
    data object NotReady : MoveResult
    data object NotYourTurn : MoveResult
    data object IllegalMove : MoveResult
    data object GameFinished : MoveResult
}

interface GameRepository {
    suspend fun create(gameId: UUID, inviteCode: String, creatorSessionId: UUID, creatorColor: PieceColor)
    suspend fun join(inviteCode: String, joiningSessionId: UUID): JoinGameResult
    suspend fun findById(gameId: UUID): Game?
    suspend fun findByParticipant(sessionId: UUID): List<Game>
    suspend fun recordMove(
        gameId: UUID,
        movedBySessionId: UUID,
        ply: Int,
        uci: String,
        fenAfter: String,
        newRevision: Long,
        newStatus: GameStatus,
        result: GameResult?,
        terminationReason: TerminationReason?,
    )
    suspend fun finishGame(
        gameId: UUID,
        newRevision: Long,
        result: GameResult,
        terminationReason: TerminationReason,
    )
}

private fun Game.snapshotFor(color: PieceColor): GameSnapshot = GameSnapshot(
    gameId = id.toString(),
    inviteCode = inviteCode,
    yourColor = color,
    status = status,
    revision = revision,
    fen = fen,
    result = result,
    terminationReason = terminationReason,
)

class GameService(
    private val repository: GameRepository,
    private val secureRandom: RandomGenerator,
    private val engine: ChessEngine,
) {
    suspend fun create(creatorSessionId: UUID): CreateGameResponse {
        val gameId = UUID.randomUUID()
        val inviteCode = buildString(INVITE_CODE_LENGTH) {
            repeat(INVITE_CODE_LENGTH) {
                append(INVITE_CODE_ALPHABET[secureRandom.nextInt(INVITE_CODE_ALPHABET.length)])
            }
        }
        val creatorColor = if (secureRandom.nextBoolean()) PieceColor.WHITE else PieceColor.BLACK
        repository.create(gameId, inviteCode, creatorSessionId, creatorColor)
        return CreateGameResponse(gameId.toString(), inviteCode)
    }

    suspend fun join(inviteCode: String, joiningSessionId: UUID): JoinGameResult =
        repository.join(inviteCode, joiningSessionId)

    suspend fun snapshot(gameId: UUID, sessionId: UUID): GameSnapshotResult {
        val game = repository.findById(gameId) ?: return GameSnapshotResult.NotFound
        val color = game.colorOf(sessionId) ?: return GameSnapshotResult.NotParticipant
        return GameSnapshotResult.Success(game.snapshotFor(color))
    }

    suspend fun history(sessionId: UUID): List<GameSnapshot> =
        repository.findByParticipant(sessionId).mapNotNull { game ->
            game.colorOf(sessionId)?.let(game::snapshotFor)
        }

    suspend fun applyMove(gameId: UUID, sessionId: UUID, uci: String): MoveResult {
        val game = repository.findById(gameId) ?: return MoveResult.NotFound
        val color = game.colorOf(sessionId) ?: return MoveResult.NotParticipant
        when (game.status) {
            GameStatus.FINISHED -> return MoveResult.GameFinished
            GameStatus.WAITING_FOR_OPPONENT -> return MoveResult.NotReady
            GameStatus.IN_PROGRESS -> Unit
        }
        if (engine.sideToMove(game.fen) != color) return MoveResult.NotYourTurn
        val outcome = engine.applyMove(game.fen, uci)
        if (outcome !is MoveOutcome.Applied) return MoveResult.IllegalMove

        val newRevision = game.revision + 1
        val newStatus = if (outcome.result != null) GameStatus.FINISHED else GameStatus.IN_PROGRESS
        repository.recordMove(
            gameId = gameId,
            movedBySessionId = sessionId,
            ply = engine.plyNumber(outcome.fenAfter),
            uci = uci,
            fenAfter = outcome.fenAfter,
            newRevision = newRevision,
            newStatus = newStatus,
            result = outcome.result,
            terminationReason = outcome.terminationReason,
        )
        val updated = game.copy(
            status = newStatus,
            revision = newRevision,
            fen = outcome.fenAfter,
            result = outcome.result,
            terminationReason = outcome.terminationReason,
        )
        return MoveResult.Applied(updated.snapshotFor(color))
    }

    suspend fun resign(gameId: UUID, sessionId: UUID): MoveResult {
        val game = repository.findById(gameId) ?: return MoveResult.NotFound
        val color = game.colorOf(sessionId) ?: return MoveResult.NotParticipant
        when (game.status) {
            GameStatus.FINISHED -> return MoveResult.GameFinished
            GameStatus.WAITING_FOR_OPPONENT -> return MoveResult.NotReady
            GameStatus.IN_PROGRESS -> Unit
        }
        val result = if (color == PieceColor.WHITE) GameResult.BLACK_WON else GameResult.WHITE_WON
        val newRevision = game.revision + 1
        repository.finishGame(gameId, newRevision, result, TerminationReason.RESIGNATION)
        val updated = game.copy(
            status = GameStatus.FINISHED,
            revision = newRevision,
            result = result,
            terminationReason = TerminationReason.RESIGNATION,
        )
        return MoveResult.Applied(updated.snapshotFor(color))
    }
}
