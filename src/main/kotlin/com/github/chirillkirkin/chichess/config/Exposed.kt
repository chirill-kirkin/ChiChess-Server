package com.github.chirillkirkin.chichess.config

import io.ktor.server.application.Application
import org.jetbrains.exposed.v1.jdbc.Database

fun Application.configureExposed() {
    val databaseUrl = environment.config.property("database.url").getString()
    Database.connect(url = databaseUrl, driver = "org.sqlite.JDBC")
}
