package com.github.chirillkirkin.chichess.game

import com.github.chirillkirkin.chichess.config.DATABASE_UUID_STRING_LENGTH
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

internal object Games : Table("games") {
    val id = varchar("id", DATABASE_UUID_STRING_LENGTH)
    val inviteCode = varchar("invite_code", INVITE_CODE_LENGTH).uniqueIndex()
    val creatorSessionId = varchar("creator_session_id", DATABASE_UUID_STRING_LENGTH)
    val joinedSessionId = varchar("joined_session_id", DATABASE_UUID_STRING_LENGTH).nullable()

    override val primaryKey = PrimaryKey(id)
}

class ExposedGameRepository(private val database: Database) : GameRepository {
    override suspend fun create(gameId: UUID, inviteCode: String, creatorSessionId: UUID): Unit =
        withContext(Dispatchers.IO) {
            transaction(database) {
                Games.insert {
                    it[id] = gameId.toString()
                    it[Games.inviteCode] = inviteCode
                    it[Games.creatorSessionId] = creatorSessionId.toString()
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
                if (game[Games.creatorSessionId] == joiningSessionId.toString()) {
                    return@transaction JoinGameResult.OwnGame
                }
                if (game[Games.joinedSessionId] != null) {
                    return@transaction JoinGameResult.AlreadyJoined
                }

                val updatedRows = Games.update({
                    (Games.id eq gameId.toString()) and Games.joinedSessionId.isNull()
                }) {
                    it[Games.joinedSessionId] = joiningSessionId.toString()
                }
                if (updatedRows == 1) JoinGameResult.Joined(gameId) else JoinGameResult.AlreadyJoined
            }
        }
}
