package com.github.chirillkirkin.chichess.game

import com.github.chirillkirkin.chichess.config.DATABASE_UUID_STRING_LENGTH
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

private const val GAME_ENUM_COLUMN_LENGTH = 32
private const val FEN_COLUMN_LENGTH = 100
private const val MOVE_UCI_COLUMN_LENGTH = 5

internal object Games : Table("games") {
    val id = varchar("id", DATABASE_UUID_STRING_LENGTH)
    val inviteCode = varchar("invite_code", INVITE_CODE_LENGTH).uniqueIndex()
    val whiteSessionId = varchar("white_session_id", DATABASE_UUID_STRING_LENGTH).nullable()
    val blackSessionId = varchar("black_session_id", DATABASE_UUID_STRING_LENGTH).nullable()
    val status = varchar("status", GAME_ENUM_COLUMN_LENGTH)
    val revision = long("revision")
    val fen = varchar("fen", FEN_COLUMN_LENGTH)
    val result = varchar("result", GAME_ENUM_COLUMN_LENGTH).nullable()
    val terminationReason = varchar("termination_reason", GAME_ENUM_COLUMN_LENGTH).nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object Moves : Table("moves") {
    val gameId = varchar("game_id", DATABASE_UUID_STRING_LENGTH)
    val ply = integer("ply")
    val uci = varchar("uci", MOVE_UCI_COLUMN_LENGTH)
    val fenAfter = varchar("fen_after", FEN_COLUMN_LENGTH)
    val movedBySessionId = varchar("moved_by_session_id", DATABASE_UUID_STRING_LENGTH)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(gameId, ply)
}

class ExposedGameRepository(private val database: Database) : GameRepository {
    override suspend fun create(
        gameId: UUID,
        inviteCode: String,
        creatorSessionId: UUID,
        creatorColor: PieceColor,
    ): Unit = withContext(Dispatchers.IO) {
        val creatorId = creatorSessionId.toString()
        val now = System.currentTimeMillis()
        transaction(database) {
            Games.insert {
                it[id] = gameId.toString()
                it[Games.inviteCode] = inviteCode
                it[whiteSessionId] = if (creatorColor == PieceColor.WHITE) creatorId else null
                it[blackSessionId] = if (creatorColor == PieceColor.BLACK) creatorId else null
                it[status] = GameStatus.WAITING_FOR_OPPONENT.name
                it[revision] = INITIAL_REVISION
                it[fen] = START_FEN
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    override suspend fun join(inviteCode: String, joiningSessionId: UUID): JoinGameResult =
        withContext(Dispatchers.IO) {
            transaction(database) {
                val game = Games.selectAll()
                    .where { Games.inviteCode eq inviteCode }
                    .singleOrNull()
                    ?: return@transaction JoinGameResult.NotFound

                val gameId = UUID.fromString(game[Games.id])
                val white = game[Games.whiteSessionId]
                val black = game[Games.blackSessionId]
                val joinerId = joiningSessionId.toString()

                // Exactly one color slot is filled while the game waits for an opponent: the creator.
                if ((white ?: black) == joinerId) {
                    return@transaction JoinGameResult.OwnGame
                }
                if (white != null && black != null) {
                    return@transaction JoinGameResult.AlreadyJoined
                }

                val nextRevision = game[Games.revision] + 1
                val now = System.currentTimeMillis()
                val emptyColor = if (white == null) Games.whiteSessionId else Games.blackSessionId
                val updatedRows = Games.update({
                    (Games.id eq gameId.toString()) and emptyColor.isNull()
                }) {
                    it[emptyColor] = joinerId
                    it[status] = GameStatus.IN_PROGRESS.name
                    it[revision] = nextRevision
                    it[updatedAt] = now
                }
                if (updatedRows == 1) JoinGameResult.Joined(gameId) else JoinGameResult.AlreadyJoined
            }
        }

    override suspend fun findById(gameId: UUID): Game? = withContext(Dispatchers.IO) {
        transaction(database) {
            Games.selectAll()
                .where { Games.id eq gameId.toString() }
                .singleOrNull()
                ?.toGame()
        }
    }

    override suspend fun findByParticipant(sessionId: UUID): List<Game> = withContext(Dispatchers.IO) {
        val participant = sessionId.toString()
        transaction(database) {
            Games.selectAll()
                .where { (Games.whiteSessionId eq participant) or (Games.blackSessionId eq participant) }
                .orderBy(Games.createdAt, SortOrder.DESC)
                .map { it.toGame() }
        }
    }

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
    ): Unit = withContext(Dispatchers.IO) {
        val gameKey = gameId.toString()
        val now = System.currentTimeMillis()
        transaction(database) {
            Moves.insert {
                it[Moves.gameId] = gameKey
                it[Moves.ply] = ply
                it[Moves.uci] = uci
                it[Moves.fenAfter] = fenAfter
                it[Moves.movedBySessionId] = movedBySessionId.toString()
                it[createdAt] = now
            }
            Games.update({ Games.id eq gameKey }) {
                it[fen] = fenAfter
                it[revision] = newRevision
                it[status] = newStatus.name
                it[Games.result] = result?.name
                it[Games.terminationReason] = terminationReason?.name
                it[updatedAt] = now
            }
        }
    }

    override suspend fun finishGame(
        gameId: UUID,
        newRevision: Long,
        result: GameResult,
        terminationReason: TerminationReason,
    ): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        transaction(database) {
            Games.update({ Games.id eq gameId.toString() }) {
                it[status] = GameStatus.FINISHED.name
                it[revision] = newRevision
                it[Games.result] = result.name
                it[Games.terminationReason] = terminationReason.name
                it[updatedAt] = now
            }
        }
    }
}

private fun ResultRow.toGame(): Game = Game(
    id = UUID.fromString(this[Games.id]),
    inviteCode = this[Games.inviteCode],
    whiteSessionId = this[Games.whiteSessionId]?.let(UUID::fromString),
    blackSessionId = this[Games.blackSessionId]?.let(UUID::fromString),
    status = GameStatus.valueOf(this[Games.status]),
    revision = this[Games.revision],
    fen = this[Games.fen],
    result = this[Games.result]?.let(GameResult::valueOf),
    terminationReason = this[Games.terminationReason]?.let(TerminationReason::valueOf),
)
