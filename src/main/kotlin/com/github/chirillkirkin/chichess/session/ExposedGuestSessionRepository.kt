package com.github.chirillkirkin.chichess.session

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private const val UUID_STRING_LENGTH = 36
private const val SHA_256_HEX_LENGTH = 64

internal object GuestSessions : Table("guest_sessions") {
    val id = varchar("id", UUID_STRING_LENGTH)
    val tokenHash = varchar("token_hash", SHA_256_HEX_LENGTH).uniqueIndex()

    override val primaryKey = PrimaryKey(id)
}

class ExposedGuestSessionRepository(private val database: Database) : GuestSessionRepository {
    override suspend fun create(sessionId: UUID, tokenHash: String): Unit = withContext(Dispatchers.IO) {
        transaction(database) {
            GuestSessions.insert {
                it[id] = sessionId.toString()
                it[GuestSessions.tokenHash] = tokenHash
            }
        }
    }

    override suspend fun findSessionIdByTokenHash(tokenHash: String): UUID? = withContext(Dispatchers.IO) {
        transaction(database) {
            GuestSessions.selectAll()
                .where { GuestSessions.tokenHash eq tokenHash }
                .singleOrNull()
                ?.let { UUID.fromString(it[GuestSessions.id]) }
        }
    }
}
