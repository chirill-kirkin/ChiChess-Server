package com.github.chirillkirkin.chichess.game

import java.util.UUID
import java.util.random.RandomGenerator
import kotlinx.serialization.Serializable

internal const val INVITE_CODE_LENGTH = 10
internal const val INVITE_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
internal const val INITIAL_REVISION = 0L

// Shortest cycle that returns to the same position (both sides move a piece out and back).
private const val SHORTEST_REPETITION_CYCLE_PLIES = 4

// A fivefold repetition needs four such cycles, so a lower half-move clock rules it out outright.
internal const val MIN_PLIES_FOR_FIVEFOLD =
    (FIVEFOLD_REPETITION_OCCURRENCES - 1) * SHORTEST_REPETITION_CYCLE_PLIES

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
    val lastMove: String? = null,
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
    data class RevisionConflict(val snapshot: GameSnapshot) : MoveResult
    data class DuplicateCommand(val snapshot: GameSnapshot?) : MoveResult
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
    suspend fun movesOf(gameId: UUID): List<String>
    suspend fun isCommandProcessed(gameId: UUID, commandId: String): Boolean
    suspend fun recordMove(
        gameId: UUID,
        movedBySessionId: UUID,
        commandId: String,
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
        commandId: String,
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
    lastMove = lastMove,
    result = result,
    terminationReason = terminationReason,
)

class GameService(
    private val repository: GameRepository,
    private val secureRandom: RandomGenerator,
    private val engine: ChessEngine,
    private val locks: GameLocks,
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

    suspend fun submitMove(
        gameId: UUID,
        sessionId: UUID,
        commandId: String,
        expectedRevision: Long,
        uci: String,
    ): MoveResult = locks.withGameLock(gameId) {
        if (repository.isCommandProcessed(gameId, commandId)) {
            return@withGameLock MoveResult.DuplicateCommand(snapshotFor(gameId, sessionId))
        }
        val game = repository.findById(gameId) ?: return@withGameLock MoveResult.NotFound
        val color = game.colorOf(sessionId) ?: return@withGameLock MoveResult.NotParticipant
        when (game.status) {
            GameStatus.FINISHED -> return@withGameLock MoveResult.GameFinished
            GameStatus.WAITING_FOR_OPPONENT -> return@withGameLock MoveResult.NotReady
            GameStatus.IN_PROGRESS -> Unit
        }
        if (game.revision != expectedRevision) {
            return@withGameLock MoveResult.RevisionConflict(game.snapshotFor(color))
        }
        if (engine.sideToMove(game.fen) != color) return@withGameLock MoveResult.NotYourTurn
        val outcome = engine.applyMove(game.fen, uci)
        if (outcome !is MoveOutcome.Applied) return@withGameLock MoveResult.IllegalMove

        var result = outcome.result
        var terminationReason = outcome.terminationReason
        // Position-based rules can't see repetition; the half-move clock cheaply rules out a
        // fivefold before paying for the history replay.
        if (result == null &&
            engine.halfMoveClock(outcome.fenAfter) >= MIN_PLIES_FOR_FIVEFOLD &&
            engine.isRepetition(repository.movesOf(gameId) + uci, FIVEFOLD_REPETITION_OCCURRENCES)
        ) {
            result = GameResult.DRAW
            terminationReason = TerminationReason.FIVEFOLD_REPETITION
        }

        val newRevision = game.revision + 1
        val newStatus = if (result != null) GameStatus.FINISHED else GameStatus.IN_PROGRESS
        repository.recordMove(
            gameId = gameId,
            movedBySessionId = sessionId,
            commandId = commandId,
            ply = engine.plyNumber(outcome.fenAfter),
            uci = uci,
            fenAfter = outcome.fenAfter,
            newRevision = newRevision,
            newStatus = newStatus,
            result = result,
            terminationReason = terminationReason,
        )
        val updated = game.copy(
            status = newStatus,
            revision = newRevision,
            fen = outcome.fenAfter,
            lastMove = uci,
            result = result,
            terminationReason = terminationReason,
        )
        MoveResult.Applied(updated.snapshotFor(color))
    }

    // Resignation is unconditional, so it does not check expectedRevision — only that the game is in progress.
    suspend fun submitResign(gameId: UUID, sessionId: UUID, commandId: String): MoveResult =
        locks.withGameLock(gameId) {
            if (repository.isCommandProcessed(gameId, commandId)) {
                return@withGameLock MoveResult.DuplicateCommand(snapshotFor(gameId, sessionId))
            }
            val game = repository.findById(gameId) ?: return@withGameLock MoveResult.NotFound
            val color = game.colorOf(sessionId) ?: return@withGameLock MoveResult.NotParticipant
            when (game.status) {
                GameStatus.FINISHED -> return@withGameLock MoveResult.GameFinished
                GameStatus.WAITING_FOR_OPPONENT -> return@withGameLock MoveResult.NotReady
                GameStatus.IN_PROGRESS -> Unit
            }
            val result = if (color == PieceColor.WHITE) GameResult.BLACK_WON else GameResult.WHITE_WON
            val newRevision = game.revision + 1
            repository.finishGame(gameId, commandId, newRevision, result, TerminationReason.RESIGNATION)
            val updated = game.copy(
                status = GameStatus.FINISHED,
                revision = newRevision,
                result = result,
                terminationReason = TerminationReason.RESIGNATION,
            )
            MoveResult.Applied(updated.snapshotFor(color))
        }

    private suspend fun snapshotFor(gameId: UUID, sessionId: UUID): GameSnapshot? {
        val game = repository.findById(gameId) ?: return null
        return game.colorOf(sessionId)?.let(game::snapshotFor)
    }
}
