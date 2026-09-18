package com.github.chirillkirkin.chichess.di

import com.github.chirillkirkin.chichess.session.ExposedGuestSessionRepository
import com.github.chirillkirkin.chichess.session.GuestSessionRepository
import com.github.chirillkirkin.chichess.session.GuestSessionService
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module

fun appModule(database: Database) = module {
    single { database }
    single<GuestSessionRepository> { ExposedGuestSessionRepository(get()) }
    single { GuestSessionService(get()) }
}
