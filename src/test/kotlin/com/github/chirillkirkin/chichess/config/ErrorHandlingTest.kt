package com.github.chirillkirkin.chichess.config

import com.github.chirillkirkin.chichess.api.ApiErrorResponse
import com.github.chirillkirkin.chichess.api.INTERNAL_ERROR_CODE
import com.github.chirillkirkin.chichess.configureTestApplication
import com.github.chirillkirkin.chichess.decodeJsonBody
import com.github.chirillkirkin.chichess.withTestDatabase
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val TEST_ERROR_ROUTE = "/test/error"
private const val PRIVATE_ERROR_DETAILS = "private error details"

class ErrorHandlingTest {
    @Test
    fun `unexpected exception returns safe server error`() {
        withTestDatabase { databaseUrl ->
            testApplication {
                configureTestApplication(databaseUrl) {
                    routing {
                        get(TEST_ERROR_ROUTE) {
                            error(PRIVATE_ERROR_DETAILS)
                        }
                    }
                }

                val response = client.get(TEST_ERROR_ROUTE)

                assertEquals(HttpStatusCode.InternalServerError, response.status)
                assertEquals(INTERNAL_ERROR_CODE, response.decodeJsonBody<ApiErrorResponse>().code)
                assertFalse(response.bodyAsText().contains(PRIVATE_ERROR_DETAILS))
            }
        }
    }
}
