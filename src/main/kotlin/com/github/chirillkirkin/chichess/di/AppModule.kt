package com.github.chirillkirkin.chichess.di

import com.github.chirillkirkin.chichess.session.ExposedGuestSessionRepository
import com.github.chirillkirkin.chichess.session.GuestSessionRepository
import com.github.chirillkirkin.chichess.session.GuestSessionService
import java.security.SecureRandom
import java.util.random.RandomGenerator
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module

fun appModule(database: Database) = module {
    single { database }
    single<RandomGenerator> { SecureRandom() }
    single<GuestSessionRepository> { ExposedGuestSessionRepository(get()) }
    single { GuestSessionService(get(), get()) }
}
