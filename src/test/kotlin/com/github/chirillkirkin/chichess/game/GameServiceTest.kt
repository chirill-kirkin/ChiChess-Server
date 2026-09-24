package com.github.chirillkirkin.chichess.game

import java.util.Random
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val GAME_TEST_RANDOM_SEED = 73L

class GameServiceTest {
    private fun service(repository: GameRepository, engine: ChessEngine = ChesslibEngine()): GameService =
        GameService(repository, Random(GAME_TEST_RANDOM_SEED), engine, GameLocks())

    @Test
    fun `create generates valid identifiers and persists game`() = runBlocking {
        val repository = FakeGameRepository()
        val creatorSessionId = UUID.randomUUID()

        val response = service(repository).create(creatorSessionId)

        assertEquals(INVITE_CODE_LENGTH, response.inviteCode.length)
        assertTrue(response.inviteCode.all(INVITE_CODE_ALPHABET::contains))
        assertEquals(UUID.fromString(response.gameId), repository.createdGameId)
        assertEquals(response.inviteCode, repository.createdInviteCode)
        assertNotNull(repository.creatorColor)
        assertEquals(creatorSessionId, repository.creatorSessionId)
    }

    @Test
    fun `snapshot returns participant color`() = runBlocking {
        val repository = FakeGameRepository()
        val service = service(repository)
        val creator = UUID.randomUUID()
        val gameId = UUID.fromString(service.create(creator).gameId)

        val result = service.snapshot(gameId, creator)

        val success = assertIs<GameSnapshotResult.Success>(result)
        assertEquals(gameId.toString(), success.snapshot.gameId)
        assertEquals(repository.creatorColor, success.snapshot.yourColor)
        assertEquals(START_FEN, success.snapshot.fen)
    }

    @Test
    fun `snapshot rejects non-participant and unknown game`() = runBlocking {
        val repository = FakeGameRepository()
        val service = service(repository)
        val creator = UUID.randomUUID()
        val gameId = UUID.fromString(service.create(creator).gameId)

        assertTrue(service.snapshot(gameId, UUID.randomUUID()) is GameSnapshotResult.NotParticipant)
        assertTrue(service.snapshot(UUID.randomUUID(), creator) is GameSnapshotResult.NotFound)
    }

    @Test
    fun `history returns only games the session participates in`() = runBlocking {
        val repository = FakeGameRepository()
        val service = service(repository)
        val creator = UUID.randomUUID()
        val ownGameId = service.create(creator).gameId
        service.create(UUID.randomUUID())

        val history = service.history(creator)

        assertEquals(listOf(ownGameId), history.map { it.gameId })
        assertEquals(repository.creatorColor, history.single().yourColor)
    }

    @Test
    fun `submitMove applies a legal move and advances state`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())

        val result = service(repository).submitMove(game.id, white, MOVE_COMMAND_ID, INITIAL_REVISION, OPENING_MOVE)

        val applied = assertIs<MoveResult.Applied>(result)
        assertEquals(GameStatus.IN_PROGRESS, applied.snapshot.status)
        assertEquals(INITIAL_REVISION + 1, applied.snapshot.revision)
        assertNotEquals(START_FEN, applied.snapshot.fen)
        assertEquals(OPENING_MOVE, applied.snapshot.lastMove)
        assertEquals(PieceColor.WHITE, applied.snapshot.yourColor)
        assertEquals(1, repository.lastRecordedPly)
    }

    @Test
    fun `submitMove rejects a stale expected revision`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())

        val result = service(repository)
            .submitMove(game.id, white, MOVE_COMMAND_ID, INITIAL_REVISION + STALE_REVISION_OFFSET, OPENING_MOVE)

        val conflict = assertIs<MoveResult.RevisionConflict>(result)
        assertEquals(INITIAL_REVISION, conflict.snapshot.revision)
    }

    @Test
    fun `submitMove ignores a duplicate command`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())
        val service = service(repository)
        service.submitMove(game.id, white, DUPLICATE_COMMAND_ID, INITIAL_REVISION, OPENING_MOVE)

        val duplicate = service.submitMove(game.id, white, DUPLICATE_COMMAND_ID, INITIAL_REVISION + 1, OPENING_MOVE)

        assertIs<MoveResult.DuplicateCommand>(duplicate)
        assertEquals(INITIAL_REVISION + 1, repository.findById(game.id)?.revision)
    }

    @Test
    fun `submitMove rejects moving out of turn`() = runBlocking {
        val repository = FakeGameRepository()
        val black = UUID.randomUUID()
        val game = repository.seedInProgress(white = UUID.randomUUID(), black = black)

        val result = service(repository).submitMove(game.id, black, MOVE_COMMAND_ID, INITIAL_REVISION, OPENING_REPLY)

        assertTrue(result is MoveResult.NotYourTurn)
    }

    @Test
    fun `submitMove rejects an illegal move`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())

        val result = service(repository).submitMove(game.id, white, MOVE_COMMAND_ID, INITIAL_REVISION, ILLEGAL_MOVE_UCI)

        assertTrue(result is MoveResult.IllegalMove)
    }

    @Test
    fun `submitMove rejects non-participant, unknown and finished games`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())
        val service = service(repository)

        assertTrue(service.submitMove(game.id, UUID.randomUUID(), MOVE_COMMAND_ID, INITIAL_REVISION, OPENING_MOVE) is MoveResult.NotParticipant)
        assertTrue(service.submitMove(UUID.randomUUID(), white, MOVE_COMMAND_ID, INITIAL_REVISION, OPENING_MOVE) is MoveResult.NotFound)

        repository.markFinished(game.id)
        assertTrue(service.submitMove(game.id, white, MOVE_COMMAND_ID, INITIAL_REVISION, OPENING_MOVE) is MoveResult.GameFinished)
    }

    @Test
    fun `submitMove finalizes the game on checkmate`() = runBlocking {
        val repository = FakeGameRepository()
        val black = UUID.randomUUID()
        val game = repository.seedInProgress(white = UUID.randomUUID(), black = black, fen = CHECKMATE_IN_ONE_FEN)

        val applied = assertIs<MoveResult.Applied>(
            service(repository).submitMove(game.id, black, MOVE_COMMAND_ID, INITIAL_REVISION, CHECKMATE_MOVE),
        )

        assertEquals(GameStatus.FINISHED, applied.snapshot.status)
        assertEquals(GameResult.BLACK_WON, applied.snapshot.result)
        assertEquals(TerminationReason.CHECKMATE, applied.snapshot.terminationReason)
    }

    @Test
    fun `submitMove finalizes a game on fivefold repetition`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())
        val engine = StubEngine(halfMoveClock = MIN_PLIES_FOR_FIVEFOLD)

        val applied = assertIs<MoveResult.Applied>(
            service(repository, engine).submitMove(game.id, white, MOVE_COMMAND_ID, INITIAL_REVISION, OPENING_MOVE),
        )

        assertEquals(GameStatus.FINISHED, applied.snapshot.status)
        assertEquals(GameResult.DRAW, applied.snapshot.result)
        assertEquals(TerminationReason.FIVEFOLD_REPETITION, applied.snapshot.terminationReason)
    }

    @Test
    fun `submitMove skips the repetition check below the half-move threshold`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())
        val engine = StubEngine(halfMoveClock = MIN_PLIES_FOR_FIVEFOLD - 1)

        val applied = assertIs<MoveResult.Applied>(
            service(repository, engine).submitMove(game.id, white, MOVE_COMMAND_ID, INITIAL_REVISION, OPENING_MOVE),
        )

        // The gate is closed, so the (always-true) repetition check never runs; the game continues.
        assertEquals(GameStatus.IN_PROGRESS, applied.snapshot.status)
        assertEquals(null, applied.snapshot.result)
    }

    @Test
    fun `submitResign finishes the game in favor of the opponent`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())

        val applied = assertIs<MoveResult.Applied>(service(repository).submitResign(game.id, white, RESIGN_COMMAND_ID))

        assertEquals(GameStatus.FINISHED, applied.snapshot.status)
        assertEquals(GameResult.BLACK_WON, applied.snapshot.result)
        assertEquals(TerminationReason.RESIGNATION, applied.snapshot.terminationReason)
    }

    @Test
    fun `draw offer accepted by the opponent ends the game by agreement`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val black = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = black)
        val service = service(repository)

        val offered = assertIs<MoveResult.Applied>(service.offerDraw(game.id, white, OFFER_COMMAND_ID))
        assertEquals(PieceColor.WHITE, offered.snapshot.pendingDrawOfferBy)

        val accepted = assertIs<MoveResult.Applied>(service.acceptDraw(game.id, black, ACCEPT_COMMAND_ID))
        assertEquals(GameStatus.FINISHED, accepted.snapshot.status)
        assertEquals(GameResult.DRAW, accepted.snapshot.result)
        assertEquals(TerminationReason.AGREEMENT, accepted.snapshot.terminationReason)
    }

    @Test
    fun `declining a draw clears the pending offer`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val black = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = black)
        val service = service(repository)
        service.offerDraw(game.id, white, OFFER_COMMAND_ID)

        val declined = assertIs<MoveResult.Applied>(service.declineDraw(game.id, black, DECLINE_COMMAND_ID))

        assertEquals(GameStatus.IN_PROGRESS, declined.snapshot.status)
        assertEquals(null, declined.snapshot.pendingDrawOfferBy)
    }

    @Test
    fun `accepting your own draw offer is rejected`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())
        val service = service(repository)
        service.offerDraw(game.id, white, OFFER_COMMAND_ID)

        assertTrue(service.acceptDraw(game.id, white, ACCEPT_COMMAND_ID) is MoveResult.NoDrawOffer)
    }

    @Test
    fun `offering a draw while one is pending is rejected`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val black = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = black)
        val service = service(repository)
        service.offerDraw(game.id, white, OFFER_COMMAND_ID)

        assertTrue(service.offerDraw(game.id, black, SECOND_COMMAND_ID) is MoveResult.DrawAlreadyOffered)
    }

    @Test
    fun `accepting with no pending offer is rejected`() = runBlocking {
        val repository = FakeGameRepository()
        val black = UUID.randomUUID()
        val game = repository.seedInProgress(white = UUID.randomUUID(), black = black)

        assertTrue(service(repository).acceptDraw(game.id, black, ACCEPT_COMMAND_ID) is MoveResult.NoDrawOffer)
    }

    @Test
    fun `a move withdraws a pending draw offer`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())
        val service = service(repository)
        service.offerDraw(game.id, white, OFFER_COMMAND_ID)

        val moved = assertIs<MoveResult.Applied>(
            service.submitMove(game.id, white, MOVE_COMMAND_ID, INITIAL_REVISION, OPENING_MOVE),
        )

        assertEquals(null, moved.snapshot.pendingDrawOfferBy)
    }

    @Test
    fun `claiming a draw by threefold repetition finishes the game`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())
        val engine = StubEngine(halfMoveClock = MIN_PLIES_FOR_THREEFOLD, repeats = true)

        val applied = assertIs<MoveResult.Applied>(
            service(repository, engine).claimDraw(game.id, white, CLAIM_COMMAND_ID, INITIAL_REVISION),
        )

        assertEquals(GameStatus.FINISHED, applied.snapshot.status)
        assertEquals(GameResult.DRAW, applied.snapshot.result)
        assertEquals(TerminationReason.THREEFOLD_REPETITION, applied.snapshot.terminationReason)
    }

    @Test
    fun `claiming a draw by the fifty-move rule finishes the game`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())
        val engine = StubEngine(halfMoveClock = FIFTY_MOVE_RULE_PLIES, repeats = false)

        val applied = assertIs<MoveResult.Applied>(
            service(repository, engine).claimDraw(game.id, white, CLAIM_COMMAND_ID, INITIAL_REVISION),
        )

        assertEquals(GameResult.DRAW, applied.snapshot.result)
        assertEquals(TerminationReason.FIFTY_MOVE_RULE, applied.snapshot.terminationReason)
    }

    @Test
    fun `claiming a draw with no grounds is rejected`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())
        val engine = StubEngine(halfMoveClock = 0, repeats = false)

        val result = service(repository, engine).claimDraw(game.id, white, CLAIM_COMMAND_ID, INITIAL_REVISION)

        assertTrue(result is MoveResult.DrawNotClaimable)
    }

    @Test
    fun `claiming a draw with a stale revision is rejected`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())
        val engine = StubEngine(halfMoveClock = FIFTY_MOVE_RULE_PLIES)

        val result = service(repository, engine)
            .claimDraw(game.id, white, CLAIM_COMMAND_ID, INITIAL_REVISION + STALE_REVISION_OFFSET)

        assertTrue(result is MoveResult.RevisionConflict)
    }

    @Test
    fun `submitResign rejects a game still waiting for an opponent`() = runBlocking {
        val repository = FakeGameRepository()
        val service = service(repository)
        val creator = UUID.randomUUID()
        val gameId = UUID.fromString(service.create(creator).gameId)

        assertTrue(service.submitResign(gameId, creator, RESIGN_COMMAND_ID) is MoveResult.NotReady)
    }
}

// Configurable half-move clock and repetition, isolating the service's draw-detection wiring.
private class StubEngine(
    private val halfMoveClock: Int,
    private val repeats: Boolean = true,
) : ChessEngine {
    override fun sideToMove(fen: String): PieceColor = PieceColor.WHITE
    override fun plyNumber(fen: String): Int = 1
    override fun applyMove(fen: String, uci: String): MoveOutcome =
        MoveOutcome.Applied(fenAfter = START_FEN, result = null, terminationReason = null)
    override fun halfMoveClock(fen: String): Int = halfMoveClock
    override fun isRepetition(moves: List<String>, occurrences: Int): Boolean = repeats
}

private class FakeGameRepository : GameRepository {
    private val games = mutableMapOf<UUID, Game>()
    private val moves = mutableMapOf<UUID, MutableList<String>>()
    private val processedCommands = mutableSetOf<Pair<UUID, String>>()

    var createdGameId: UUID? = null
    var createdInviteCode: String? = null
    var creatorSessionId: UUID? = null
    var creatorColor: PieceColor? = null
    var lastRecordedPly: Int? = null

    fun seedInProgress(white: UUID, black: UUID, fen: String = START_FEN): Game {
        val game = Game(
            id = UUID.randomUUID(),
            inviteCode = IN_PROGRESS_INVITE_CODE,
            whiteSessionId = white,
            blackSessionId = black,
            status = GameStatus.IN_PROGRESS,
            revision = INITIAL_REVISION,
            fen = fen,
            lastMove = null,
            drawOfferedBy = null,
            result = null,
            terminationReason = null,
        )
        games[game.id] = game
        return game
    }

    fun markFinished(gameId: UUID) {
        games[gameId] = games.getValue(gameId).copy(status = GameStatus.FINISHED)
    }

    override suspend fun create(
        gameId: UUID,
        inviteCode: String,
        creatorSessionId: UUID,
        creatorColor: PieceColor,
    ) {
        createdGameId = gameId
        createdInviteCode = inviteCode
        this.creatorSessionId = creatorSessionId
        this.creatorColor = creatorColor
        games[gameId] = Game(
            id = gameId,
            inviteCode = inviteCode,
            whiteSessionId = creatorSessionId.takeIf { creatorColor == PieceColor.WHITE },
            blackSessionId = creatorSessionId.takeIf { creatorColor == PieceColor.BLACK },
            status = GameStatus.WAITING_FOR_OPPONENT,
            revision = INITIAL_REVISION,
            fen = START_FEN,
            lastMove = null,
            drawOfferedBy = null,
            result = null,
            terminationReason = null,
        )
    }

    override suspend fun join(inviteCode: String, joiningSessionId: UUID): JoinGameResult =
        JoinGameResult.NotFound

    override suspend fun findById(gameId: UUID): Game? = games[gameId]

    override suspend fun findByParticipant(sessionId: UUID): List<Game> =
        games.values.filter { it.colorOf(sessionId) != null }

    override suspend fun movesOf(gameId: UUID): List<String> = moves[gameId].orEmpty()

    override suspend fun isCommandProcessed(gameId: UUID, commandId: String): Boolean =
        (gameId to commandId) in processedCommands

    override suspend fun recordMove(
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
    ) {
        lastRecordedPly = ply
        moves.getOrPut(gameId) { mutableListOf() }.add(uci)
        games[gameId] = games.getValue(gameId).copy(
            fen = fenAfter,
            lastMove = uci,
            drawOfferedBy = null,
            revision = newRevision,
            status = newStatus,
            result = result,
            terminationReason = terminationReason,
        )
        processedCommands += gameId to commandId
    }

    override suspend fun finishGame(
        gameId: UUID,
        commandId: String,
        newRevision: Long,
        result: GameResult,
        terminationReason: TerminationReason,
    ) {
        games[gameId] = games.getValue(gameId).copy(
            status = GameStatus.FINISHED,
            revision = newRevision,
            drawOfferedBy = null,
            result = result,
            terminationReason = terminationReason,
        )
        processedCommands += gameId to commandId
    }

    override suspend fun setDrawOffer(gameId: UUID, commandId: String, offeredBy: PieceColor) {
        games[gameId] = games.getValue(gameId).copy(drawOfferedBy = offeredBy)
        processedCommands += gameId to commandId
    }

    override suspend fun clearDrawOffer(gameId: UUID, commandId: String) {
        games[gameId] = games.getValue(gameId).copy(drawOfferedBy = null)
        processedCommands += gameId to commandId
    }
}
