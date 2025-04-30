package com.example.hack2025.data.models

import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * File Path: app/src/main/java/com/example/hack2025/data/model/UserInfo.kt
 *
 * Data class representing the user information received from the API upon successful login.
 * Implement Serializable to easily pass this object between Activities via Intents.
 * Adjust fields based on what your API actually returns.
 */
data class UserInfo(
    @SerializedName("userId") // Example field, adjust to your API
    val userId: String,

    @SerializedName("email") // Example field, adjust to your API
    val email: String,

    @SerializedName("user_type") // Example field, adjust to your API
    val userType: String,

    @SerializedName("friend_email") // Example field, adjust to your API
    val friend: String,

    @SerializedName("name") // Example field, adjust to your API
    val name: String?, // Make fields nullable if they might be missing

    @SerializedName("authToken") // Example: API might return a token
    val authToken: String?
) : Serializable // Implement Serializable to pass via Intent extras
