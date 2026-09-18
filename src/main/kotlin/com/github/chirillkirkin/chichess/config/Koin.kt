package com.github.chirillkirkin.chichess.config

import com.github.chirillkirkin.chichess.di.appModule
import io.ktor.server.application.Application
import io.ktor.server.application.install
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.configureKoin(database: Database) {
    install(Koin) {
        slf4jLogger()
        modules(appModule(database))
    }
}
