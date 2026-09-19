package com.github.chirillkirkin.chichess

import com.github.chirillkirkin.chichess.config.DATABASE_URL_CONFIG_PATH
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

private const val TEST_DATABASE_FILE_PREFIX = "chichess-test-"
private const val SQLITE_FILE_SUFFIX = ".sqlite"
private const val SQLITE_JDBC_URL_PREFIX = "jdbc:sqlite:"

internal fun withTestDatabase(block: (databaseUrl: String) -> Unit) {
    val databaseFile = Files.createTempFile(TEST_DATABASE_FILE_PREFIX, SQLITE_FILE_SUFFIX)
    try {
        block("$SQLITE_JDBC_URL_PREFIX$databaseFile")
    } finally {
        Files.deleteIfExists(databaseFile)
    }
}

internal fun ApplicationTestBuilder.configureTestApplication(
    databaseUrl: String,
    additionalConfiguration: Application.() -> Unit = {},
) {
    environment {
        config = MapApplicationConfig(DATABASE_URL_CONFIG_PATH to databaseUrl)
    }
    application {
        module()
        additionalConfiguration()
    }
}

internal fun <T> withDatabaseConnection(databaseUrl: String, block: (Connection) -> T): T =
    DriverManager.getConnection(databaseUrl).use(block)
