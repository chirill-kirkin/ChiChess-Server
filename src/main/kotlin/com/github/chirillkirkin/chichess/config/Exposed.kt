package com.github.chirillkirkin.chichess.config

import com.github.chirillkirkin.chichess.session.GuestSessions
import com.github.chirillkirkin.chichess.game.Games
import io.ktor.server.application.Application
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

internal const val DATABASE_UUID_STRING_LENGTH = 36
const val DATABASE_URL_CONFIG_PATH = "database.url"

fun Application.configureExposed(): Database {
    val databaseUrl = environment.config.property(DATABASE_URL_CONFIG_PATH).getString()
    val database = Database.connect(url = databaseUrl, driver = "org.sqlite.JDBC")
    transaction(database) {
        SchemaUtils.create(GuestSessions, Games)
    }
    return database
}
