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
private const val COMMAND_ID = "cmd-1"

// Position after 1. f3 e5 2. g4, black to move: d8h4 is Qh4#.
private const val FOOLS_MATE_FEN = "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2"

class GameServiceTest {
    private fun service(repository: GameRepository): GameService =
        GameService(repository, Random(GAME_TEST_RANDOM_SEED), ChesslibEngine(), GameLocks())

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

        val result = service(repository).submitMove(game.id, white, COMMAND_ID, INITIAL_REVISION, "e2e4")

        val applied = assertIs<MoveResult.Applied>(result)
        assertEquals(GameStatus.IN_PROGRESS, applied.snapshot.status)
        assertEquals(INITIAL_REVISION + 1, applied.snapshot.revision)
        assertNotEquals(START_FEN, applied.snapshot.fen)
        assertEquals(PieceColor.WHITE, applied.snapshot.yourColor)
        assertEquals(1, repository.lastRecordedPly)
    }

    @Test
    fun `submitMove rejects a stale expected revision`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())

        val result = service(repository).submitMove(game.id, white, COMMAND_ID, INITIAL_REVISION + 5, "e2e4")

        val conflict = assertIs<MoveResult.RevisionConflict>(result)
        assertEquals(INITIAL_REVISION, conflict.snapshot.revision)
    }

    @Test
    fun `submitMove ignores a duplicate command`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())
        val service = service(repository)
        service.submitMove(game.id, white, COMMAND_ID, INITIAL_REVISION, "e2e4")

        val duplicate = service.submitMove(game.id, white, COMMAND_ID, INITIAL_REVISION + 1, "e2e4")

        assertIs<MoveResult.DuplicateCommand>(duplicate)
        assertEquals(INITIAL_REVISION + 1, repository.findById(game.id)?.revision)
    }

    @Test
    fun `submitMove rejects moving out of turn`() = runBlocking {
        val repository = FakeGameRepository()
        val black = UUID.randomUUID()
        val game = repository.seedInProgress(white = UUID.randomUUID(), black = black)

        val result = service(repository).submitMove(game.id, black, COMMAND_ID, INITIAL_REVISION, "e7e5")

        assertTrue(result is MoveResult.NotYourTurn)
    }

    @Test
    fun `submitMove rejects an illegal move`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())

        val result = service(repository).submitMove(game.id, white, COMMAND_ID, INITIAL_REVISION, "e2e5")

        assertTrue(result is MoveResult.IllegalMove)
    }

    @Test
    fun `submitMove rejects non-participant, unknown and finished games`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())
        val service = service(repository)

        assertTrue(service.submitMove(game.id, UUID.randomUUID(), COMMAND_ID, INITIAL_REVISION, "e2e4") is MoveResult.NotParticipant)
        assertTrue(service.submitMove(UUID.randomUUID(), white, COMMAND_ID, INITIAL_REVISION, "e2e4") is MoveResult.NotFound)

        repository.markFinished(game.id)
        assertTrue(service.submitMove(game.id, white, COMMAND_ID, INITIAL_REVISION, "e2e4") is MoveResult.GameFinished)
    }

    @Test
    fun `submitMove finalizes the game on checkmate`() = runBlocking {
        val repository = FakeGameRepository()
        val black = UUID.randomUUID()
        val game = repository.seedInProgress(white = UUID.randomUUID(), black = black, fen = FOOLS_MATE_FEN)

        val applied = assertIs<MoveResult.Applied>(
            service(repository).submitMove(game.id, black, COMMAND_ID, INITIAL_REVISION, "d8h4"),
        )

        assertEquals(GameStatus.FINISHED, applied.snapshot.status)
        assertEquals(GameResult.BLACK_WON, applied.snapshot.result)
        assertEquals(TerminationReason.CHECKMATE, applied.snapshot.terminationReason)
    }

    @Test
    fun `submitResign finishes the game in favor of the opponent`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())

        val applied = assertIs<MoveResult.Applied>(service(repository).submitResign(game.id, white, COMMAND_ID))

        assertEquals(GameStatus.FINISHED, applied.snapshot.status)
        assertEquals(GameResult.BLACK_WON, applied.snapshot.result)
        assertEquals(TerminationReason.RESIGNATION, applied.snapshot.terminationReason)
    }

    @Test
    fun `submitResign rejects a game still waiting for an opponent`() = runBlocking {
        val repository = FakeGameRepository()
        val service = service(repository)
        val creator = UUID.randomUUID()
        val gameId = UUID.fromString(service.create(creator).gameId)

        assertTrue(service.submitResign(gameId, creator, COMMAND_ID) is MoveResult.NotReady)
    }
}

private class FakeGameRepository : GameRepository {
    private val games = mutableMapOf<UUID, Game>()
    private val processedCommands = mutableSetOf<Pair<UUID, String>>()

    var createdGameId: UUID? = null
    var createdInviteCode: String? = null
    var creatorSessionId: UUID? = null
    var creatorColor: PieceColor? = null
    var lastRecordedPly: Int? = null

    fun seedInProgress(white: UUID, black: UUID, fen: String = START_FEN): Game {
        val game = Game(
            id = UUID.randomUUID(),
            inviteCode = "INPROGRESS",
            whiteSessionId = white,
            blackSessionId = black,
            status = GameStatus.IN_PROGRESS,
            revision = INITIAL_REVISION,
            fen = fen,
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
            result = null,
            terminationReason = null,
        )
    }

    override suspend fun join(inviteCode: String, joiningSessionId: UUID): JoinGameResult =
        JoinGameResult.NotFound

    override suspend fun findById(gameId: UUID): Game? = games[gameId]

    override suspend fun findByParticipant(sessionId: UUID): List<Game> =
        games.values.filter { it.colorOf(sessionId) != null }

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
        games[gameId] = games.getValue(gameId).copy(
            fen = fenAfter,
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
            result = result,
            terminationReason = terminationReason,
        )
        processedCommands += gameId to commandId
    }
}
