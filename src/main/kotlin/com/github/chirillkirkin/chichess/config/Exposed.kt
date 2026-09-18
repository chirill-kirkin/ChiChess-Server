package com.github.chirillkirkin.chichess.config

import com.github.chirillkirkin.chichess.session.GuestSessions
import io.ktor.server.application.Application
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

fun Application.configureExposed(): Database {
    val databaseUrl = environment.config.property("database.url").getString()
    val database = Database.connect(url = databaseUrl, driver = "org.sqlite.JDBC")
    transaction(database) {
        SchemaUtils.create(GuestSessions)
    }
    return database
}
