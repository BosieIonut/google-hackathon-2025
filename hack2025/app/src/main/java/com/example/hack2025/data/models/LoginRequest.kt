package com.example.hack2025.data.models

import com.google.gson.annotations.SerializedName

/**
 * File Path: app/src/main/java/com/example/hack2025/data/model/LoginRequest.kt
 *
 * Data class representing the request body for the login API call.
 * Use @SerializedName if your API expects different field names than your variable names.
 */
data class LoginRequest(
    @SerializedName("email") // Matches the API's expected field name
    val email: String,

    @SerializedName("password") // Matches the API's expected field name
    val password: String
)
