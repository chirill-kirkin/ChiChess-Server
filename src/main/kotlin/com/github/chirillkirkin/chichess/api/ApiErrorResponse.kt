package com.github.chirillkirkin.chichess.api

import kotlinx.serialization.Serializable

const val INTERNAL_ERROR_CODE = "INTERNAL_ERROR"

@Serializable
data class ApiErrorResponse(val code: String)
