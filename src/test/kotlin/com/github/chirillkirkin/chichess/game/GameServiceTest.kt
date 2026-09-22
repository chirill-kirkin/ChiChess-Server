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

// Position after 1. f3 e5 2. g4, black to move: d8h4 is Qh4#.
private const val FOOLS_MATE_FEN = "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2"

class GameServiceTest {
    private fun service(repository: GameRepository): GameService =
        GameService(repository, Random(GAME_TEST_RANDOM_SEED), ChesslibEngine())

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
    fun `applyMove applies a legal move and advances state`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())

        val result = service(repository).applyMove(game.id, white, "e2e4")

        val applied = assertIs<MoveResult.Applied>(result)
        assertEquals(GameStatus.IN_PROGRESS, applied.snapshot.status)
        assertEquals(INITIAL_REVISION + 1, applied.snapshot.revision)
        assertNotEquals(START_FEN, applied.snapshot.fen)
        assertEquals(PieceColor.WHITE, applied.snapshot.yourColor)
        assertEquals(1, repository.lastRecordedPly)
    }

    @Test
    fun `applyMove rejects moving out of turn`() = runBlocking {
        val repository = FakeGameRepository()
        val black = UUID.randomUUID()
        val game = repository.seedInProgress(white = UUID.randomUUID(), black = black)

        assertTrue(service(repository).applyMove(game.id, black, "e7e5") is MoveResult.NotYourTurn)
    }

    @Test
    fun `applyMove rejects an illegal move`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())

        assertTrue(service(repository).applyMove(game.id, white, "e2e5") is MoveResult.IllegalMove)
    }

    @Test
    fun `applyMove rejects non-participant, unknown and finished games`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())

        assertTrue(service(repository).applyMove(game.id, UUID.randomUUID(), "e2e4") is MoveResult.NotParticipant)
        assertTrue(service(repository).applyMove(UUID.randomUUID(), white, "e2e4") is MoveResult.NotFound)

        repository.markFinished(game.id)
        assertTrue(service(repository).applyMove(game.id, white, "e2e4") is MoveResult.GameFinished)
    }

    @Test
    fun `applyMove finalizes the game on checkmate`() = runBlocking {
        val repository = FakeGameRepository()
        val black = UUID.randomUUID()
        val game = repository.seedInProgress(white = UUID.randomUUID(), black = black, fen = FOOLS_MATE_FEN)

        val applied = assertIs<MoveResult.Applied>(service(repository).applyMove(game.id, black, "d8h4"))

        assertEquals(GameStatus.FINISHED, applied.snapshot.status)
        assertEquals(GameResult.BLACK_WON, applied.snapshot.result)
        assertEquals(TerminationReason.CHECKMATE, applied.snapshot.terminationReason)
    }

    @Test
    fun `resign finishes the game in favor of the opponent`() = runBlocking {
        val repository = FakeGameRepository()
        val white = UUID.randomUUID()
        val game = repository.seedInProgress(white = white, black = UUID.randomUUID())

        val applied = assertIs<MoveResult.Applied>(service(repository).resign(game.id, white))

        assertEquals(GameStatus.FINISHED, applied.snapshot.status)
        assertEquals(GameResult.BLACK_WON, applied.snapshot.result)
        assertEquals(TerminationReason.RESIGNATION, applied.snapshot.terminationReason)
    }

    @Test
    fun `resign rejects a game still waiting for an opponent`() = runBlocking {
        val repository = FakeGameRepository()
        val service = service(repository)
        val creator = UUID.randomUUID()
        val gameId = UUID.fromString(service.create(creator).gameId)

        assertTrue(service.resign(gameId, creator) is MoveResult.NotReady)
    }
}

private class FakeGameRepository : GameRepository {
    private val games = mutableMapOf<UUID, Game>()

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

    override suspend fun recordMove(
        gameId: UUID,
        movedBySessionId: UUID,
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
    }

    override suspend fun finishGame(
        gameId: UUID,
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
    }
}
